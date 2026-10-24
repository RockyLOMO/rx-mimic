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
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.tb.SliderVerifyHandler;
import org.rx.crawler.task.tb.TbPromotionUrlTask;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local-only fixture for the visible native Taobao-command selector and its current material text. */
@EnabledIfSystemProperty(named = "browser.tb.promotion.integration", matches = "true")
class WebBrowserTbPromotionIntegrationTest {
    @TempDir Path profile;

    @Test
    void nativeCommandSelectionReadsCurrentDialogAndIgnoresHiddenOrExternalMaterials() throws Exception {
        AtomicInteger routedRequests = new AtomicInteger();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(profile,
                     new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(false))) {
            context.route("**/*", route -> {
                routedRequests.incrementAndGet();
                route.fulfill(new Route.FulfillOptions().setStatus(404).setBody("local fixture only"));
            });
            Page page = context.pages().getFirst();
            page.setContent("""
                    <style>body{font:18px sans-serif}.next-dialog{padding:30px;width:700px}textarea{display:block;width:550px;height:90px}button{padding:12px;margin:12px}</style>
                    <p id="outside">页面外旧素材 ¥OUTSIDEtoken¥ https://m.tb.cn/h.Outside</p>
                    <div id="J_promotion" class="next-dialog" role="dialog" style="position:relative;z-index:5000">
                      <h2>文案素材 · 立即推广</h2>
                      <button id="long-mode">长链接</button>
                      <button id="command-mode" onclick="document.querySelector('#copy-text').value='18¥ CZ001 fSUiTDnNz8G ¥ https://m.tb.cn/h.Example';this.dataset.clicked='yes'">淘口令</button>
                      <textarea readonly id="copy-text">商品价格 ¥18.00 https://uland.taobao.com/coupon/edetail?e=long-only</textarea>
                      <div style="display:none"><textarea>¥HIDDENoldToken¥ https://m.tb.cn/h.HiddenOld</textarea></div>
                      <button>一键复制</button>
                    </div>
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
            TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                    new CrawlEntryService(new BrowserPreflightService()), new ObjectMapper());
            SliderVerifyHandler slider = mock(SliderVerifyHandler.class);
            when(slider.isSliderVerifyPage(browser)).thenReturn(false);
            ReflectionTestUtils.setField(task, "sliderVerifyHandler", slider);
            PromotionUrlResult result = new PromotionUrlResult();
            Method collect = Arrays.stream(TbPromotionUrlTask.class.getDeclaredMethods())
                    .filter(method -> method.getName().equals("collectPromotionShareOutput")).findFirst().orElseThrow();
            collect.setAccessible(true);
            assertEquals(Boolean.TRUE, collect.invoke(task, browser, config.getCustom().getTbPromotion(), result, null));
            assertEquals("https://m.tb.cn/h.Example", result.getShortUrl());
            assertEquals("¥CZ001 fSUiTDnNz8G¥", result.getPromotionToken());
            assertEquals("yes", page.locator("#command-mode").getAttribute("data-clicked"));
            assertFalse(result.getPromotionToken().contains("OUTSIDE"));
            assertFalse(result.getPromotionToken().contains("HIDDEN"));
            assertFalse(result.getShortUrl().contains("long-only"));
            assertEquals(0, routedRequests.get(), "The share collector should never issue an active AJAX request");
            assertEquals(1, context.pages().size());
            verify(slider).isSliderVerifyPage(browser);
        }
    }
}
