package org.rx.crawler.service.impl;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Chrome, entirely local responses, no requests reach shopping sites. */
@EnabledIfSystemProperty(named="browser.followtab.integration",matches="true")
class WebBrowserFollowTabIntegrationTest {
    @TempDir Path profile;

    @Test void followsNativePopupButNeverAdoptsPreexistingMatchingTab() {
        try(var playwright=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
            var context=playwright.chromium().launchPersistentContext(profile,
                new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(false))) {
            context.route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("""
                    <!doctype html><link rel="icon" href="data:,">
                    <a id="new" target="_blank" href="https://example.test/search?new=1">Search</a>
                    <a id="same" href="https://example.test/search?same=1">Same</a>
                    """)));
            var source=context.pages().getFirst();source.navigate("https://example.test/search?initial=1");
            var old=context.newPage();old.navigate("https://example.test/search?old=1");
            var browser=mock(WebBrowser.class,CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(browser,"page",source);
            ReflectionTestUtils.setField(browser,"context",context);
            ReflectionTestUtils.setField(browser,"config",new WebBrowserConfig());
            Map<String,Page> tabs=new LinkedHashMap<>();tabs.put("source",source);tabs.put("old",old);
            ReflectionTestUtils.setField(browser,"tabs",tabs);
            ReflectionTestUtils.setField(browser,"prefetchTargetByHandle",new HashMap<String,String>());
            assertFalse(browser.runAndFollowNewTab(()->{},u->u.contains("/search"),200));
            assertSame(source,ReflectionTestUtils.getField(browser,"page"));
            assertTrue(browser.runAndFollowNewTab(()->source.locator("#new").click(),u->u.contains("/search"),3000));
            assertTrue(browser.getCurrentUrl().endsWith("?new=1"));
            assertFalse(old.isClosed());assertTrue(source.isClosed());
            assertEquals(2,context.pages().size(),"Following search replaces its source instead of leaking tabs");
            var active=(Page)ReflectionTestUtils.getField(browser,"page");
            assertTrue(browser.runAndFollowNewTab(()->active.locator("#same").click(),u->u.contains("/search"),3000));
            assertTrue(browser.getCurrentUrl().endsWith("?same=1"));
        }
    }
}
