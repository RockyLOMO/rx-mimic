package org.rx.crawler.service.impl;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.task.tb.TbUnionAnalysisPage;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated Chrome fixtures; every URL is fulfilled locally and profiles are disposable. */
@EnabledIfSystemProperty(named = "browser.currenttab.integration", matches = "true")
class WebBrowserCurrentTabIntegrationTest {
    private static final String SOURCE = "https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm";
    private static final String ANALYSIS = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic&itemId=MarketingAlias_0123456789012345";
    @TempDir Path profile;

    @Test
    void closesOnlyMatchingNewSourcePopupAndRestoresSource() {
        exercise(false);
    }

    @Test
    void actionFailureStillClosesMatchingPopupAndRestoresSource() {
        exercise(true);
    }

    private void exercise(boolean throwFromAction) {
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(profile,
                     new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(false))) {
            context.route("**/*", route -> route.fulfill(new Route.FulfillOptions()
                    .setContentType("text/html; charset=utf-8").setBody("""
                            <!doctype html><link rel="icon" href="data:,">
                            <a id="analysis" target="_blank" href="%s">分析</a>
                            <a id="unrelated" target="_blank" href="https://example.test/other">其他页面</a>
                            """.formatted(ANALYSIS))));
            Page source = context.pages().getFirst();
            source.navigate(SOURCE);
            Page preexistingAnalysis = context.newPage();
            preexistingAnalysis.navigate(ANALYSIS);
            Page prefetch = context.newPage();
            prefetch.navigate("https://example.test/prefetch");
            source.bringToFront();
            WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(browser, "page", source);
            ReflectionTestUtils.setField(browser, "config", new WebBrowserConfig());
            Map<String, Page> tabs = new LinkedHashMap<>();
            tabs.put("source", source);
            tabs.put("preexisting-analysis", preexistingAnalysis);
            tabs.put("prefetch", prefetch);
            ReflectionTestUtils.setField(browser, "tabs", tabs);
            Map<String, String> prefetchTargets = new HashMap<>();
            prefetchTargets.put("prefetch", prefetch.url());
            ReflectionTestUtils.setField(browser, "prefetchTargetByHandle", prefetchTargets);
            List<Page> opened = new ArrayList<>();
            IllegalStateException original = new IllegalStateException("action failed after opening popup");
            Runnable action = () -> {
                Page analysis = source.waitForPopup(() -> source.locator("#analysis").click());
                opened.add(analysis);
                tabs.put("new-analysis", analysis);
                Page other = source.waitForPopup(() -> source.locator("#unrelated").click());
                opened.add(other);
                tabs.put("new-unrelated", other);
                // Emulate a page listener selecting the latest popup while the action is in flight.
                ReflectionTestUtils.setField(browser, "page", other);
                ReflectionTestUtils.setField(browser, "activeFrame", other.mainFrame());
                if (throwFromAction) throw original;
            };
            if (throwFromAction) {
                assertSame(original, assertThrows(IllegalStateException.class,
                        () -> browser.runInCurrentTab(action, TbUnionAnalysisPage::isAnalysisUrl, 1000)));
            } else {
                browser.runInCurrentTab(action, TbUnionAnalysisPage::isAnalysisUrl, 1000);
            }
            assertEquals(2, opened.size());
            assertTrue(opened.get(0).isClosed());
            assertFalse(opened.get(1).isClosed());
            assertFalse(preexistingAnalysis.isClosed(), "Even a matching pre-existing analysis tab must survive");
            assertFalse(prefetch.isClosed());
            assertFalse(source.isClosed());
            assertSame(source, ReflectionTestUtils.getField(browser, "page"));
            assertNull(ReflectionTestUtils.getField(browser, "activeFrame"));
            assertEquals("source", browser.getCurrentHandle());
            assertEquals(SOURCE, browser.getCurrentUrl());
            assertFalse(tabs.containsKey("new-analysis"));
            assertSame(opened.get(1), tabs.get("new-unrelated"));
            assertTrue(prefetchTargets.containsKey("prefetch"));
            // The scoped listener must be gone after the action; later matching popups remain untouched.
            Page later = source.waitForPopup(() -> source.locator("#analysis").click());
            source.waitForTimeout(150);
            assertFalse(later.isClosed());
        }
    }
}
