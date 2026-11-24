package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 隐蔽性自检的判定逻辑，喂合成探针结果，不启动浏览器。 */
public class StealthSelfAuditTest {

    private Map<String, Object> cleanProbe() {
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
        p.put("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36");
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

    @Test
    public void cleanEnvironmentPasses() {
        Map<String, Object> result = StealthSelfAudit.evaluate(cleanProbe());
        assertTrue(Boolean.TRUE.equals(result.get("passed")));
        assertTrue(String.valueOf(result.get("report")).contains("STEALTH SELF-AUDIT: PASS"));
    }

    @Test
    public void customGlobalFromRootJsIsBlocking() {
        Map<String, Object> p = cleanProbe();
        p.put("customGlobals", List.of("_rx", "__rxMm"));
        p.put("customSleep", true);
        assertFalse(Boolean.TRUE.equals(StealthSelfAudit.evaluate(p).get("passed")));
    }

    @Test
    public void overriddenNativeBuiltinIsBlocking() {
        Map<String, Object> p = cleanProbe();
        p.put("replaceAllNative", false);
        assertFalse(Boolean.TRUE.equals(StealthSelfAudit.evaluate(p).get("passed")));
    }

    @Test
    public void webdriverTrueOrPatchedGetterIsBlocking() {
        Map<String, Object> p = cleanProbe();
        p.put("webdriver", true);
        assertFalse(Boolean.TRUE.equals(StealthSelfAudit.evaluate(p).get("passed")));

        Map<String, Object> q = cleanProbe();
        q.put("webdriverGetterNative", false);
        assertFalse(Boolean.TRUE.equals(StealthSelfAudit.evaluate(q).get("passed")));
    }

    @Test
    public void headlessUserAgentIsBlocking() {
        Map<String, Object> p = cleanProbe();
        p.put("ua", "Mozilla/5.0 ... HeadlessChrome/131.0.0.0");
        p.put("headlessUa", true);
        assertFalse(Boolean.TRUE.equals(StealthSelfAudit.evaluate(p).get("passed")));
    }

    @Test
    public void advisoryOnlyFailuresDoNotBlockButAreReported() {
        Map<String, Object> p = cleanProbe();
        p.put("outerMinusInnerX", 0);   // viewport emulation
        p.put("visibility", "hidden");  // prefetched background tab
        Map<String, Object> result = StealthSelfAudit.evaluate(p);
        assertTrue(Boolean.TRUE.equals(result.get("passed")), "advisory checks must not veto");
        assertTrue(String.valueOf(result.get("report")).contains("[FAIL]"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void reportContainsOneLinePerCheck() {
        Map<String, Object> result = StealthSelfAudit.evaluate(cleanProbe());
        List<Map<String, Object>> checks = (List<Map<String, Object>>) result.get("checks");
        assertEquals(11, checks.size());
        String report = String.valueOf(result.get("report"));
        assertEquals(checks.size() + 1, report.split("\\R").length);
    }
}
