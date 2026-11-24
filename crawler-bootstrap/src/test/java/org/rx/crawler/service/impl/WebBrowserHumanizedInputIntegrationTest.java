package org.rx.crawler.service.impl;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.task.common.BehaviorPersona;
import org.rx.crawler.task.common.StealthSelfAudit;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * 真机 Chrome + 本地 HTML fixture，专门覆盖本次加固后的新代码路径：
 * BehaviorPersona 驱动的全量逐字输入 / minimum-jerk 轨迹 / 对数正态延迟，
 * 以及 root.js 删除后主世界必须干净。不访问任何外部站点，不碰 common profile。
 */
@EnabledIfSystemProperty(named = "browser.humanized.integration", matches = "true")
class WebBrowserHumanizedInputIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void humanizedPathsWorkAndMainWorldStaysClean() {
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(temp,
                     new BrowserType.LaunchPersistentContextOptions()
                             .setChannel("chrome")
                             .setHeadless(false)
                             .setViewportSize((ViewportSize) null)
                             .setIgnoreDefaultArgs(List.of("--enable-automation"))
                             .setArgs(List.of("--disable-blink-features=AutomationControlled")))) {
            Page page = context.pages().getFirst();
            page.setContent("<style>body{margin:0;height:4000px}</style>"
                    + "<input id='input'>"
                    + "<button id='button' onclick=\"this.dataset.clicked='yes'\">Click</button>");

            WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
            WebBrowserConfig config = new WebBrowserConfig();
            config.setHumanActionMinDelayMillis(0);
            config.setHumanActionMaxDelayMillis(0);
            config.setOperationRandomMinDelayMillis(0);
            config.setOperationRandomMaxDelayMillis(0);
            config.setMouseMoveMinSteps(1);
            config.setMouseMoveMaxSteps(2);
            config.setTypingMinDelayMillis(2);
            config.setTypingMaxDelayMillis(8);
            ReflectionTestUtils.setField(browser, "page", page);
            ReflectionTestUtils.setField(browser, "config", config);
            ReflectionTestUtils.setField(browser, "persona", BehaviorPersona.derive(temp.toString()));

            // 1) root.js 已删除：任何 executeScript 都不得污染主世界或覆盖原生 builtin
            browser.executeScript("return 1 + 1;");
            browser.executeScript("return document.title;");
            @SuppressWarnings("unchecked")
            Map<String, Object> world = (Map<String, Object>) page.evaluate("() => ({"
                    + "rx: typeof window._rx !== 'undefined',"
                    + "sleep: typeof window.sleep !== 'undefined',"
                    + "replaceAllNative: /\\[native code\\]/.test(String.prototype.replaceAll.toString()),"
                    + "addEventListenerNative: /\\[native code\\]/.test(window.addEventListener.toString())"
                    + "})");
            assertFalse(Boolean.TRUE.equals(world.get("rx")), "window._rx must not exist");
            assertFalse(Boolean.TRUE.equals(world.get("sleep")), "window.sleep must not exist");
            assertTrue(Boolean.TRUE.equals(world.get("replaceAllNative")), "native builtin must stay intact");
            assertTrue(Boolean.TRUE.equals(world.get("addEventListenerNative")));

            // 2) 全量逐字输入（含中文），偶发错字退格后最终值仍须完全一致
            String value = "中文商品 title 123456789";
            browser.elementPress("#input", value);
            assertEquals(value, page.locator("#input").inputValue());

            // 3) minimum-jerk 轨迹 + 落点微修正后仍能正确点击
            browser.elementClick("#button");
            assertEquals("yes", page.locator("#button").getAttribute("data-clicked"));

            // 4) persona 轨迹与真实 wheel 滚动
            browser.mouseMove(180, 160);
            browser.scrollPage(0, 240);
            page.waitForCondition(() -> ((Number) page.evaluate("window.scrollY")).doubleValue() > 0);

            // 5) 真机页面上跑一次本地自检，blocking 项必须全过
            Map<String, Object> audit = StealthSelfAudit.run(browser);
            assertTrue(Boolean.TRUE.equals(audit.get("passed")),
                    "stealth self-audit must pass on real Chrome:\n" + audit.get("report"));
        }
    }
}
