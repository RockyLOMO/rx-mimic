package org.rx.crawler.service.impl;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local HTML fixture only; never uses the service profile or external websites. */
@EnabledIfSystemProperty(named = "browser.input.integration", matches = "true")
class WebBrowserInputIntegrationTest {
    @TempDir Path temp;

    @Test
    void realChromeInputWheelAndNestedScrollRespectTheirTargets() {
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(temp,
                     new BrowserType.LaunchPersistentContextOptions().setChannel("chrome")
                             .setHeadless(false).setViewportSize((ViewportSize) null))) {
            Page page = context.pages().getFirst();
            page.setContent("""
                    <style>body{margin:0;height:4000px} #panel,#unrelated{height:120px;width:250px;overflow:auto}
                    #unrelated{position:fixed;right:0;top:0}</style>
                    <input id="input"><button id="button" onclick="this.dataset.clicked='yes'">Click</button>
                    <div id="panel"><div style="height:600px"></div><div id="target">Target</div></div>
                    <div id="unrelated"><div style="height:2000px">Unrelated</div></div>
                    """);
            WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
            WebBrowserConfig config = new WebBrowserConfig();
            config.setHumanActionMinDelayMillis(0); config.setHumanActionMaxDelayMillis(0);
            config.setOperationRandomMinDelayMillis(0); config.setOperationRandomMaxDelayMillis(0);
            config.setMouseMoveMinSteps(1); config.setMouseMoveMaxSteps(1);
            config.setTypingMinDelayMillis(0); config.setTypingMaxDelayMillis(0);
            ReflectionTestUtils.setField(browser, "page", page);
            ReflectionTestUtils.setField(browser, "config", config);

            assertNull(page.viewportSize());
            browser.elementPress("#input", "中文商品 title 123456789");
            assertEquals("中文商品 title 123456789", page.locator("#input").inputValue());
            browser.elementClick("#button");
            assertEquals("yes", page.locator("#button").getAttribute("data-clicked"));

            browser.scrollPage(350);
            page.waitForCondition(() -> ((Number) page.evaluate("window.scrollY")).doubleValue() > 0);
            page.evaluate("window.scrollTo(0,0)");
            ReflectionTestUtils.invokeMethod(browser, "jsScrollPage", 0D, 120D);
            assertEquals(120, ((Number) page.evaluate("window.scrollY")).intValue());

            page.evaluate("window.scrollTo(0,0);document.querySelector('#unrelated').scrollTop=90");
            Boolean visible = ReflectionTestUtils.invokeMethod(browser, "jsScrollToElement", "#target", 0.5D);
            assertEquals(Boolean.TRUE, visible);
            assertEquals(90, ((Number) page.locator("#unrelated").evaluate("el=>el.scrollTop")).intValue());
            assertTrue(((Number) page.locator("#panel").evaluate("el=>el.scrollTop")).intValue() > 0);
        }
    }
}
