package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SannysoftFingerprintEvaluatorTest {

    @Test
    public void shouldPassWhenCriticalRowsLookHuman() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row("User Agent (Old)", "Mozilla/5.0 Chrome/150", true, false));
        rows.add(row("WebDriver (New)", "missing (passed)", true, false));
        rows.add(row("WebDriver Advanced", "passed", true, false));
        rows.add(row("Chrome (New)", "present (passed)", true, false));
        rows.add(row("Plugins Length (Old)", "5", true, false));
        rows.add(row("Plugins is of type PluginArray", "passed", true, false));
        rows.add(row("Languages (Old)", "zh-CN,zh,en-US,en", true, false));
        rows.add(row("Broken Image Dimensions", "passed", true, false));

        SannysoftFingerprintEvaluator.Evaluation evaluation = SannysoftFingerprintEvaluator.evaluate(rows);
        assertTrue(evaluation.passed, evaluation.failedCritical.toString());
        assertTrue(evaluation.failedCritical.isEmpty());
    }

    @Test
    public void shouldFailWhenWebdriverPresent() {
        List<Map<String, Object>> rows = humanRows();
        rows.set(1, row("WebDriver (New)", "present (failed)", false, true));

        SannysoftFingerprintEvaluator.Evaluation evaluation = SannysoftFingerprintEvaluator.evaluate(rows);
        assertFalse(evaluation.passed);
        assertFalse(evaluation.failedCritical.isEmpty());
        assertTrue(evaluation.failedCritical.stream().anyMatch(s -> s.contains("WebDriver (New)")));
    }

    @Test
    public void shouldFailWhenChromeMissing() {
        List<Map<String, Object>> rows = humanRows();
        rows.set(3, row("Chrome (New)", "missing (failed)", false, true));

        SannysoftFingerprintEvaluator.Evaluation evaluation = SannysoftFingerprintEvaluator.evaluate(rows);
        assertFalse(evaluation.passed);
        assertTrue(evaluation.failedCritical.stream().anyMatch(s -> s.contains("Chrome (New)")));
    }

    @Test
    public void shouldFailWhenHeadlessUserAgent() {
        List<Map<String, Object>> rows = humanRows();
        rows.set(0, row("User Agent (Old)", "Mozilla/5.0 HeadlessChrome/149", false, true));

        SannysoftFingerprintEvaluator.Evaluation evaluation = SannysoftFingerprintEvaluator.evaluate(rows);
        assertFalse(evaluation.passed);
        assertTrue(evaluation.failedCritical.stream().anyMatch(s -> s.contains("User Agent")));
    }

    @Test
    public void textLooksBrokenShouldDetectCriticalFailures() {
        assertTrue(SannysoftFingerprintEvaluator.textLooksBroken(
                "WebDriver (New) present (failed)\nChrome (New) present (passed)"));
        assertTrue(SannysoftFingerprintEvaluator.textLooksBroken(
                "something HeadlessChrome/100"));
        assertFalse(SannysoftFingerprintEvaluator.textLooksBroken(
                "WebDriver (New) missing (passed)\nChrome (New) present (passed)\nPlugins Length (Old) 5"));
    }

    @Test
    public void emptyRowsShouldNotPass() {
        SannysoftFingerprintEvaluator.Evaluation evaluation =
                SannysoftFingerprintEvaluator.evaluate(new ArrayList<Map<String, Object>>());
        assertFalse(evaluation.passed);
    }

    private static List<Map<String, Object>> humanRows() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row("User Agent (Old)", "Mozilla/5.0 Chrome/150", true, false));
        rows.add(row("WebDriver (New)", "missing (passed)", true, false));
        rows.add(row("WebDriver Advanced", "passed", true, false));
        rows.add(row("Chrome (New)", "present (passed)", true, false));
        rows.add(row("Plugins Length (Old)", "5", true, false));
        rows.add(row("Plugins is of type PluginArray", "passed", true, false));
        rows.add(row("Languages (Old)", "zh-CN,zh", true, false));
        rows.add(row("Broken Image Dimensions", "passed", true, false));
        return rows;
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
