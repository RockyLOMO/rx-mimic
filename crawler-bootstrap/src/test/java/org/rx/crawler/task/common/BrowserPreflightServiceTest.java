package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.rx.crawler.service.Browser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BrowserPreflightServiceTest {

    @Test
    public void preflightDisabledShouldPassWithoutBrowserWork() {
        BrowserPreflightService service = new BrowserPreflightService();
        Browser browser = mock(Browser.class);
        CrawlEntryOptions options = baseOptions();
        options.setPreflightEnabled(false);

        BrowserPreflightResult result = service.check(browser, options, true);
        assertTrue(result.isPassed());
        assertFalse(result.isCached());
    }

    @Test
    public void preflightShouldPassWhenProbeLooksHuman() throws Exception {
        BrowserPreflightService service = new BrowserPreflightService();
        Browser browser = mockPassingBrowser(false);

        BrowserPreflightResult result = service.check(browser, baseOptions(), true);
        assertTrue(result.isPassed());
        assertFalse(result.isCached());
        assertEquals(true, result.getDiagnostics().get("webdriverOk"));
        assertEquals(true, result.getDiagnostics().get("userAgentOk"));
        assertEquals(true, result.getDiagnostics().get("pluginsOk"));
        assertEquals(true, result.getDiagnostics().get("chromeOk"));
        verify(browser, times(1)).navigateUrl(anyString(), anyString(), anyInt());
    }

    @Test
    public void preflightShouldFailWhenWebdriverExposed() throws Exception {
        BrowserPreflightService service = new BrowserPreflightService();
        Browser browser = mockPassingBrowser(true);

        BrowserPreflightResult result = service.check(browser, baseOptions(), true);
        assertFalse(result.isPassed());
        assertEquals(false, result.getDiagnostics().get("webdriverOk"));
    }

    @Test
    public void preflightShouldReuseCacheWithinTtl() throws Exception {
        BrowserPreflightService service = new BrowserPreflightService();
        AtomicInteger navigations = new AtomicInteger();
        Browser browser = mock(Browser.class);
        doAnswer(inv -> {
            navigations.incrementAndGet();
            return null;
        }).when(browser).navigateUrl(anyString(), anyString(), anyInt());
        when(browser.executeScript(anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(0);
            if (script.contains("webdriver")) {
                return probe(false);
            }
            return "ok passed";
        });

        CrawlEntryOptions options = baseOptions();
        options.setPreflightCacheMinutes(30);
        options.setForcePreflight(false);

        BrowserPreflightResult first = service.check(browser, options, false);
        BrowserPreflightResult second = service.check(browser, options, false);

        assertTrue(first.isPassed());
        assertTrue(second.isPassed());
        assertFalse(first.isCached());
        assertTrue(second.isCached());
        assertEquals(1, navigations.get(), "cached preflight should not re-navigate");
    }

    @Test
    public void forcePreflightShouldBypassCache() throws Exception {
        BrowserPreflightService service = new BrowserPreflightService();
        AtomicInteger navigations = new AtomicInteger();
        Browser browser = mock(Browser.class);
        doAnswer(inv -> {
            navigations.incrementAndGet();
            return null;
        }).when(browser).navigateUrl(anyString(), anyString(), anyInt());
        when(browser.executeScript(anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(0);
            if (script.contains("webdriver")) {
                return probe(false);
            }
            return "ok passed";
        });

        CrawlEntryOptions options = baseOptions();
        assertTrue(service.check(browser, options, false).isPassed());
        assertTrue(service.check(browser, options, true).isPassed());
        assertEquals(2, navigations.get());
    }

    @Test
    public void headlessUserAgentShouldFail() throws Exception {
        BrowserPreflightService service = new BrowserPreflightService();
        Browser browser = mock(Browser.class);
        doAnswer(inv -> null).when(browser).navigateUrl(anyString(), anyString(), anyInt());
        when(browser.executeScript(anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(0);
            if (script.contains("table tr") || script.contains("ROW") || script.contains("querySelectorAll('table")) {
                return java.util.Collections.emptyList();
            }
            if (script.contains("webdriver")) {
                Map<String, Object> probe = probe(false);
                probe.put("userAgent", "Mozilla/5.0 HeadlessChrome/149.0.0.0");
                return probe;
            }
            return "ok";
        });

        BrowserPreflightResult result = service.check(browser, baseOptions(), true);
        assertFalse(result.isPassed());
        assertEquals(false, result.getDiagnostics().get("userAgentOk"));
    }

    @Test
    public void strictModeShouldFailWhenSannysoftWebdriverRowFailed() throws Exception {
        BrowserPreflightService service = new BrowserPreflightService();
        Browser browser = mock(Browser.class);
        doAnswer(inv -> null).when(browser).navigateUrl(anyString(), anyString(), anyInt());
        when(browser.executeScript(anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(0);
            if (script.contains("querySelectorAll('table") || script.contains("table tr")) {
                return java.util.Arrays.asList(
                        mapRow("User Agent (Old)", "Mozilla/5.0 Chrome/150", true, false),
                        mapRow("WebDriver (New)", "present (failed)", false, true),
                        mapRow("WebDriver Advanced", "failed", false, true),
                        mapRow("Chrome (New)", "present (passed)", true, false),
                        mapRow("Plugins Length (Old)", "5", true, false),
                        mapRow("Plugins is of type PluginArray", "passed", true, false),
                        mapRow("Languages (Old)", "zh-CN", true, false),
                        mapRow("Broken Image Dimensions", "passed", true, false)
                );
            }
            if (script.contains("webdriver:")) {
                return probe(false);
            }
            return "WebDriver (New) present (failed)";
        });

        CrawlEntryOptions options = baseOptions();
        options.setPreflightStrict(true);
        BrowserPreflightResult result = service.check(browser, options, true);
        assertFalse(result.isPassed());
        assertEquals(false, result.getDiagnostics().get("reportOk"));
        assertEquals(false, result.getDiagnostics().get("sannysoftPassed"));
    }

    @Test
    public void strictModeShouldPassWhenSannysoftCriticalRowsPass() throws Exception {
        BrowserPreflightService service = new BrowserPreflightService();
        Browser browser = mockPassingBrowserWithSannysoftTable(false);
        CrawlEntryOptions options = baseOptions();
        options.setPreflightStrict(true);

        BrowserPreflightResult result = service.check(browser, options, true);
        assertTrue(result.isPassed(), String.valueOf(result.getDiagnostics()));
        assertEquals(true, result.getDiagnostics().get("reportOk"));
        assertEquals(true, result.getDiagnostics().get("sannysoftPassed"));
    }

    private static CrawlEntryOptions baseOptions() {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setProfileName("common");
        options.setPreflightEnabled(true);
        options.setPreflightUrl("https://bot.sannysoft.com/");
        options.setPreflightStrict(false);
        options.setPreflightCacheMinutes(30);
        options.setInitialPageTimeoutSeconds(10);
        options.setStepDelayMillis(0);
        options.setStepDelayRandomMillis(0);
        return options;
    }

    private static Browser mockPassingBrowser(boolean webdriverTrue) throws Exception {
        return mockPassingBrowserWithSannysoftTable(webdriverTrue);
    }

    private static Browser mockPassingBrowserWithSannysoftTable(boolean webdriverTrue) throws Exception {
        Browser browser = mock(Browser.class);
        doAnswer(inv -> null).when(browser).navigateUrl(anyString(), anyString(), anyInt());
        when(browser.executeScript(anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(0);
            if (script.contains("querySelectorAll('table") || script.contains("table tr")) {
                if (webdriverTrue) {
                    return java.util.Arrays.asList(
                            mapRow("WebDriver (New)", "present (failed)", false, true)
                    );
                }
                return java.util.Arrays.asList(
                        mapRow("User Agent (Old)", "Mozilla/5.0 Chrome/150", true, false),
                        mapRow("WebDriver (New)", "missing (passed)", true, false),
                        mapRow("WebDriver Advanced", "passed", true, false),
                        mapRow("Chrome (New)", "present (passed)", true, false),
                        mapRow("Plugins Length (Old)", "5", true, false),
                        mapRow("Plugins is of type PluginArray", "passed", true, false),
                        mapRow("Languages (Old)", "zh-CN,zh,en-US,en", true, false),
                        mapRow("Broken Image Dimensions", "passed", true, false)
                );
            }
            if (script.contains("webdriver:")) {
                return probe(webdriverTrue);
            }
            return "navigator webdriver: false\npassed";
        });
        return browser;
    }

    private static Map<String, Object> mapRow(String name, String value, boolean passed, boolean failed) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("value", value);
        row.put("passed", passed);
        row.put("failed", failed);
        return row;
    }

    private static Map<String, Object> probe(boolean webdriverTrue) {
        Map<String, Object> probe = new LinkedHashMap<String, Object>();
        probe.put("webdriver", webdriverTrue ? Boolean.TRUE : null);
        probe.put("userAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/149.0.0.0 Safari/537.36");
        probe.put("plugins", 3);
        probe.put("languages", "zh-CN,zh,en-US,en");
        probe.put("chrome", true);
        return probe;
    }
}
