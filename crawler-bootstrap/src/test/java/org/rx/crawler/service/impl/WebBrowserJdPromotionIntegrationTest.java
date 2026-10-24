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
                    <button role="tab" id="short-tab" onclick="document.querySelector('#links').style.display='block';document.querySelector('#commands').style.display='none';this.dataset.clicked='yes'">短链接</button>
                    <button role="tab" id="command-tab" onclick="document.querySelector('#commands').style.display='block';document.querySelector('#links').style.display='none';this.dataset.clicked='yes'">京口令</button>
                    <section id="links"><label>推广短链接</label><textarea readonly>https://u.jd.com/local-short</textarea></section>
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
            assertEquals("https://u.jd.com/local-short", output.get("shortUrl"));
            assertEquals("! Z7V8shortCommand! ZH9112", output.get("promotionToken"));
            assertFalse(output.get("promotionToken").contains("LONGcommand"));
            assertFalse(output.get("promotionToken").contains("复制"));
            assertEquals("yes", page.locator("#short-tab").getAttribute("data-clicked"));
            assertEquals("yes", page.locator("#command-tab").getAttribute("data-clicked"));
            assertEquals(1, context.pages().size());
        }
    }
}
