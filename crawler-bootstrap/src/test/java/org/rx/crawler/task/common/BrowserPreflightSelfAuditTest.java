package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.rx.crawler.service.Browser;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 JDK 动态代理代替 Mockito 验证 preflight + 本地自检的联动。
 * 本机 Mockito inline mock maker 在 JDK21 上无法自附加 agent，这个测试不依赖它。
 */
public class BrowserPreflightSelfAuditTest {

    @Test
    public void strictPreflightPassesWithCleanSelfAudit() {
        BrowserPreflightResult result = new BrowserPreflightService()
                .check(fakeBrowser(cleanSelfAuditProbe(), true), strictOptions("clean"), true);
        assertTrue(result.isPassed(), "clean browser must pass strict preflight: " + result.getDiagnostics());
        assertTrue(Boolean.TRUE.equals(result.getDiagnostics().get("selfAuditPassed")));
    }

    @Test
    public void strictPreflightFailsWhenSelfAuditFindsInjectedGlobals() {
        Map<String, Object> probe = cleanSelfAuditProbe();
        probe.put("customGlobals", List.of("_rx", "__rxMm"));
        probe.put("customSleep", true);
        BrowserPreflightResult result = new BrowserPreflightService()
                .check(fakeBrowser(probe, true), strictOptions("leaking"), true);
        assertFalse(result.isPassed(), "injected globals must veto strict preflight");
        assertTrue(String.valueOf(result.getMessage()).contains("self-audit"));
        assertFalse(Boolean.TRUE.equals(result.getDiagnostics().get("selfAuditPassed")));
    }

    @Test
    public void strictPreflightSkipsSelfAuditWhenProbeUnavailable() {
        BrowserPreflightResult result = new BrowserPreflightService()
                .check(fakeBrowser(null, false), strictOptions("no-probe"), true);
        assertTrue(result.isPassed(), "unavailable probe must not veto a valid task: " + result.getMessage());
        assertTrue(String.valueOf(result.getDiagnostics().get("selfAuditReport")).contains("SKIPPED"));
    }

    private static CrawlEntryOptions strictOptions(String profile) {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setProfileName(profile);
        options.setPreflightEnabled(true);
        options.setPreflightUrl("https://bot.sannysoft.com/");
        options.setPreflightStrict(true);
        options.setPreflightCacheMinutes(0);
        options.setInitialPageTimeoutSeconds(10);
        options.setStepDelayMillis(0);
        options.setStepDelayRandomMillis(0);
        return options;
    }

    private static Map<String, Object> cleanSelfAuditProbe() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("webdriver", false);
        p.put("webdriverGetterNative", true);
        p.put("webdriverEnumerable", true);
        p.put("customGlobals", List.of());
        p.put("customSleep", false);
        p.put("replaceAllNative", true);
        p.put("addEventListenerNative", true);
        p.put("pluginArrayNative", true);
        p.put("pluginCount", 5);
        p.put("chromeShape", true);
        p.put("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0 Safari/537.36");
        p.put("headlessUa", false);
        p.put("languages", "zh-CN,zh,en-US,en");
        p.put("screenWidth", 1920);
        p.put("screenHeight", 1080);
        p.put("innerWidth", 1904);
        p.put("innerHeight", 953);
        p.put("outerWidth", 1920);
        p.put("outerHeight", 1040);
        p.put("outerMinusInnerX", 16);
        p.put("outerMinusInnerY", 87);
        p.put("visibility", "visible");
        return p;
    }

    private static Browser fakeBrowser(Map<String, Object> selfAuditProbe, boolean probeIsMap) {
        return (Browser) Proxy.newProxyInstance(
                Browser.class.getClassLoader(), new Class<?>[]{Browser.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("executeScript".equals(name)) {
                        String script = String.valueOf(args == null || args.length == 0 ? "" : args[0]);
                        if (script.contains("Object.getOwnPropertyNames(window)")) {
                            return probeIsMap ? selfAuditProbe : "navigator webdriver: false\npassed";
                        }
                        if (script.contains("webdriver:")) {
                            Map<String, Object> probe = new LinkedHashMap<String, Object>();
                            probe.put("webdriver", false);
                            probe.put("userAgent", "Mozilla/5.0 Chrome/131.0.0.0");
                            probe.put("plugins", 5);
                            probe.put("languages", "zh-CN,zh,en-US,en");
                            probe.put("chrome", true);
                            return probe;
                        }
                        if (script.contains("table tr")) {
                            return Arrays.asList(
                                    row("User Agent (Old)", "passed", true, false),
                                    row("WebDriver (New)", "missing (passed)", true, false),
                                    row("WebDriver Advanced", "passed", true, false),
                                    row("Chrome (New)", "present (passed)", true, false),
                                    row("Plugins Length (Old)", "5", true, false),
                                    row("Plugins is of type PluginArray", "passed", true, false),
                                    row("Languages (Old)", "passed", true, false),
                                    row("Broken Image Dimensions", "passed", true, false));
                        }
                        return "passed";
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    if (returnType == double.class) {
                        return 0D;
                    }
                    return null;
                });
    }

    private static Map<String, Object> row(String name, String value, boolean passed, boolean failed) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("value", value);
        row.put("passed", passed);
        row.put("failed", failed);
        return row;
    }
}
