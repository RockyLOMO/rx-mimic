package org.rx.crawler.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.jd.JdUnionConfig;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Installed visible Chrome; inline local modal fixture and no external requests. */
@EnabledIfSystemProperty(named = "browser.jd.promotion.integration", matches = "true")
class WebBrowserJdPromotionIntegrationTest {
    @TempDir Path profile;

    @Test
    void promotionEntryClicksTheButtonInsteadOfTheTwoButtonContainer() throws Exception {
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(profile,
                     new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(false))) {
            Page page = context.pages().getFirst();
            page.setContent("""
                    <style>#card{margin:30px;width:600px}#actions{display:flex;gap:80px;width:500px}
                    button{padding:12px}#dialog{display:none}</style>
                    <div id="card"><a href="https://item.jd.com/100256373049.html">矿泉水</a><p>佣金 1%</p>
                      <div id="actions"><button id="promote" onclick="document.querySelector('#dialog').style.display='block'">我要推广</button>
                        <button id="one-click">一键领链</button></div></div>
                    <div id="dialog" class="el-dialog">生成推广链接 所属导购媒体</div>
                    """);
            WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
            WebBrowserConfig settings = new WebBrowserConfig();
            settings.setHumanActionMinDelayMillis(0); settings.setHumanActionMaxDelayMillis(0);
            settings.setOperationRandomMinDelayMillis(0); settings.setOperationRandomMaxDelayMillis(0);
            settings.setMouseMoveMinSteps(1); settings.setMouseMoveMaxSteps(1);
            ReflectionTestUtils.setField(browser, "page", page);
            ReflectionTestUtils.setField(browser, "config", settings);
            ReflectionTestUtils.setField(browser, "injectedScript", ConcurrentHashMap.<String>newKeySet());
            AppConfig config = new AppConfig();
            JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                    new CrawlEntryService(new BrowserPreflightService()), new ObjectMapper());
            String kind = ReflectionTestUtils.invokeMethod(task, "locateAndClickPrimaryPromoteButton", browser, "100256373049");
            assertEquals("promote", page.locator("[data-rx-jd-primary-promote='1']").getAttribute("id"));
            assertEquals("我要推广", kind);
            assertTrue(page.locator("#dialog").isVisible());
            assertEquals("settings", ReflectionTestUtils.invokeMethod(task, "waitPromotionDialog", browser,
                    config.getCustom().getJdUnion()));
            page.setContent("""
                    <style>.el-dialog{padding:20px}.el-select{display:inline-block}.el-select-dropdown__item{padding:8px;width:100px}</style>
                    <div class="el-dialog">生成推广链接<div><label>所属导购媒体</label>
                    <span class="el-select"><input id="media" readonly value="请选择导购媒体" onclick="window.opens=(window.opens||0)+1;setTimeout(()=>document.querySelector('.el-select-dropdown').style.display='block',500)"></span></div></div>
                    <ul class="el-select-dropdown" style="display:none"><li class="el-select-dropdown__item" onclick="document.querySelector('#media').value='wx';this.parentElement.style.display='none'">wx</li></ul>
                    """);
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(task, "selectGuideMedia", browser, "wx",
                    config.getCustom().getJdUnion()), () -> page.content());
            assertEquals("wx", page.locator("#media").inputValue());
            assertEquals(1, ((Number) page.evaluate("window.opens")).intValue());
            page.setContent("""
                    <div class="el-dialog"><h2>生成推广链接</h2><div id="pending"></div></div>
                    <script>setTimeout(()=>document.querySelector('#pending').innerHTML='<button>获取推广链接</button>',250)</script>
                    """);
            assertEquals("settings", ReflectionTestUtils.invokeMethod(task, "waitPromotionDialog", browser,
                    config.getCustom().getJdUnion()));
            page.setContent("<div class='el-dialog'><h2>生成推广链接</h2><button role='tab'>短链接</button><button role='tab'>京口令</button></div>");
            assertEquals("output", ReflectionTestUtils.invokeMethod(task, "waitPromotionDialog", browser,
                    config.getCustom().getJdUnion()));
            page.setContent("""
                    <div id="card">佣金 1%<a href="https://item.jd.com/100256373049.html">矿泉水</a>
                    <button onclick="document.querySelector('.el-dialog').style.display='block'">一键领链</button></div>
                    <div class="el-dialog" style="display:none">生成推广链接<button>获取推广链接</button></div>
                    """);
            assertEquals("一键领链", ReflectionTestUtils.invokeMethod(task, "locateAndClickPrimaryPromoteButton", browser, "100256373049"));
            assertEquals("settings", ReflectionTestUtils.invokeMethod(task, "waitPromotionDialog", browser,
                    config.getCustom().getJdUnion()));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void nativeTabsReturnShortLinkAndTheEntireShortCommandInsteadOfTheLongCommand() throws Exception {
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(profile,
                     new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(false))) {
            context.route("**/*", route -> route.fulfill(new Route.FulfillOptions().setStatus(404).setBody("local fixture only")));
            Page page = context.pages().getFirst();
            page.setContent("""
                    <style>body{font:18px sans-serif}.el-dialog{padding:30px;width:700px}textarea{display:block;width:500px;height:70px}button{padding:12px;margin:10px}</style>
                    <div class="el-dialog" role="dialog"><h2>生成推广链接</h2>
                    <a href="https://example.invalid/help">京口令</a>
                    <button role="tab" id="short-tab" onclick="document.querySelector('#links').style.display='block';document.querySelector('#commands').style.display='none';this.dataset.clicked='yes'">短链接</button>
                    <button role="tab" id="command-tab" onclick="document.querySelector('#commands').style.display='block';document.querySelector('#links').style.display='none';this.dataset.clicked='yes'">京口令</button>
                    <section id="links">
                      <div><label>优惠券链接</label><textarea readonly>https://u.jd.com/local-coupon</textarea></div>
                      <div><label>推广链接</label><textarea readonly>https://u.jd.com/local-short</textarea></div>
                    </section>
                    <section id="commands" style="display:none">
                      <div><label>长口令</label><textarea readonly>京东好物商品名称分享长文案 ! LONGcommand! ZH1000</textarea><button>复制</button></div>
                      <div><label>短口令</label><textarea readonly>! Z7V8shortCommand! ZH9112</textarea><button>复制</button></div>
                    </section></div>
                    """);
            WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
            WebBrowserConfig browserConfig = new WebBrowserConfig();
            browserConfig.setHumanActionMinDelayMillis(0); browserConfig.setHumanActionMaxDelayMillis(0);
            browserConfig.setOperationRandomMinDelayMillis(0); browserConfig.setOperationRandomMaxDelayMillis(0);
            browserConfig.setMouseMoveMinSteps(1); browserConfig.setMouseMoveMaxSteps(1);
            ReflectionTestUtils.setField(browser, "page", page);
            ReflectionTestUtils.setField(browser, "config", browserConfig);
            ReflectionTestUtils.setField(browser, "injectedScript", ConcurrentHashMap.<String>newKeySet());
            AppConfig config = new AppConfig();
            JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                    new CrawlEntryService(new BrowserPreflightService()), new ObjectMapper());
            Method read = JdUnionPromotionTask.class.getDeclaredMethod("readPromotionOutputs", org.rx.crawler.service.Browser.class, JdUnionConfig.class);
            read.setAccessible(true);
            Map<String, String> output = (Map<String, String>) read.invoke(task, browser, config.getCustom().getJdUnion());
            assertEquals("https://u.jd.com/local-coupon", output.get("shortUrl"));
            assertEquals("https://u.jd.com/local-short", output.get("identityPromotionUrl"));
            assertEquals("! Z7V8shortCommand! ZH9112", output.get("promotionToken"));
            assertFalse(output.get("promotionToken").contains("LONGcommand"));
            assertFalse(output.get("promotionToken").contains("复制"));
            assertEquals("yes", page.locator("#short-tab").getAttribute("data-clicked"));
            assertEquals("yes", page.locator("#command-tab").getAttribute("data-clicked"));
            assertEquals(1, context.pages().size());
            assertEquals("about:blank", page.url());
            page.setContent("""
                    <style>body{font:16px sans-serif}.el-dialog{padding:24px;width:640px}label{display:inline-block;margin:8px}
                    .el-radio__input{display:inline-block;position:relative}.el-radio__inner{display:inline-block;width:16px;height:16px;border:1px solid}
                    input[type=radio]{position:absolute;opacity:0;width:0;height:0}</style>
                    <div class="el-dialog" role="dialog"><h2>生成推广链接</h2>
                    <label class="el-radio is-checked"><input type="radio" name="type" checked><span class="el-radio__label">网站推广</span></label>
                    <label class="el-radio" id="guide-radio"><span class="el-radio__input"><span class="el-radio__inner"></span><input type="radio" name="type"></span><span class="el-radio__label">导购媒体推广</span></label>
                    <div id="site">所属网站</div>
                    <div id="guide" style="display:none">所属导购媒体</div>
                    </div>
                    <script>
                    document.querySelectorAll('label.el-radio').forEach(function(label){
                      label.querySelector('input').addEventListener('change', function(event){
                        if(!event.isTrusted)throw new Error('Synthetic radio interaction');
                        document.querySelectorAll('label.el-radio').forEach(function(item){item.classList.remove('is-checked');});
                        label.classList.add('is-checked');
                        var guide=label.id==='guide-radio';
                        document.getElementById('guide').style.display=guide?'block':'none';
                        document.getElementById('site').style.display=guide?'none':'block';
                      });
                    });
                    </script>
                    """);
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(task, "selectDialogRadio", browser, "导购媒体推广"));
            assertTrue(page.locator("#guide-radio").getAttribute("class").contains("is-checked"));
            assertEquals("block", page.locator("#guide").evaluate("el => getComputedStyle(el).display"));
            assertFalse((Boolean) ReflectionTestUtils.invokeMethod(task, "selectDialogRadio", browser, "不存在的类型"));
            page.setContent("""
                    <div class="el-dialog"><div><label>推广短链接</label><input value="https://u.jd.com/only-product"></div></div>
                    <div class="el-dialog" style="display:none"><div><label>优惠券链接</label><input value="https://u.jd.com/stale"></div></div>
                    """);
            Map<String,String> onlyProduct=ReflectionTestUtils.invokeMethod(task,"readPromotionShortLinks",browser);
            assertEquals("https://u.jd.com/only-product",onlyProduct.get("productUrl"));
            assertFalse(onlyProduct.containsKey("couponUrl"));
        }
    }
}
