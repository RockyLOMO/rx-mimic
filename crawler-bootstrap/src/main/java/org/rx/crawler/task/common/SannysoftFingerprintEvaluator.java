package org.rx.crawler.task.common;

import org.rx.core.Strings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 解析 bot.sannysoft.com 检测表，并按业务关键项判定是否通过。
 * <p>
 * 注意：页面正文几乎总会出现 "failed" 字样（失败行/图例），不能用整页 contains("failed") 粗判。
 */
public final class SannysoftFingerprintEvaluator {
    public static final String DEFAULT_URL = "https://bot.sannysoft.com/";

    /**
     * 在页面上下文执行，抽取检测表行状态。
     */
    public static final String ROW_PROBE_SCRIPT = "return (function () {"
            + "function isRed(bg){if(!bg)return false;var m=bg.match(/rgba?\\((\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)/i);"
            + "if(!m)return false;var r=+m[1],g=+m[2],b=+m[3];return r>150&&g<120&&b<120;}"
            + "function isGreen(bg){if(!bg)return false;var m=bg.match(/rgba?\\((\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)/i);"
            + "if(!m)return false;var r=+m[1],g=+m[2],b=+m[3];return g>140&&r<140;}"
            + "var rows=[];"
            + "var trs=document.querySelectorAll('table tr');"
            + "for(var i=0;i<trs.length;i++){"
            + "var tds=trs[i].querySelectorAll('td');"
            + "if(tds.length<2){continue;}"
            + "var name=(tds[0].innerText||'').replace(/\\s+/g,' ').trim();"
            + "if(!name){continue;}"
            + "var valueCell=tds[tds.length-1];"
            + "var value=(valueCell.innerText||'').replace(/\\s+/g,' ').trim();"
            + "var cls=((valueCell.className||'')+' '+(trs[i].className||'')).toLowerCase();"
            + "var bg='';try{bg=window.getComputedStyle(valueCell).backgroundColor||'';}catch(e){}"
            + "var failed=/failed/.test(cls)||/\\(failed\\)/i.test(value)||isRed(bg);"
            + "var passed=/passed|\\bok\\b/.test(cls)||/\\(passed\\)/i.test(value)||isGreen(bg);"
            + "if(!failed&&!passed){"
            + "if(/present\\s*\\(failed\\)|missing\\s*\\(failed\\)/i.test(value)){failed=true;}"
            + "else if(/missing\\s*\\(passed\\)|present\\s*\\(passed\\)/i.test(value)){passed=true;}"
            + "}"
            + "rows.push({name:name,value:value,failed:!!failed,passed:!!passed});"
            + "}"
            + "return rows;"
            + "})();";

    /**
     * 抓取任务要求必须通过的关键检测项（名称子串匹配，忽略大小写）。
     */
    public static final String[] CRITICAL_NAME_HINTS = new String[]{
            "User Agent (Old)",
            "WebDriver (New)",
            "WebDriver Advanced",
            "Chrome (New)",
            "Plugins Length (Old)",
            "Plugins is of type PluginArray",
            "Languages (Old)",
            "Broken Image Dimensions"
    };

    private SannysoftFingerprintEvaluator() {
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> normalizeRows(Object rawRows) {
        if (!(rawRows instanceof List)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Object item : (List<?>) rawRows) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> raw = (Map<?, ?>) item;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("name", String.valueOf(raw.get("name") == null ? "" : raw.get("name")).trim());
            row.put("value", String.valueOf(raw.get("value") == null ? "" : raw.get("value")).trim());
            row.put("failed", Boolean.TRUE.equals(raw.get("failed")) || "true".equalsIgnoreCase(String.valueOf(raw.get("failed"))));
            row.put("passed", Boolean.TRUE.equals(raw.get("passed")) || "true".equalsIgnoreCase(String.valueOf(raw.get("passed"))));
            rows.add(row);
        }
        return rows;
    }

    public static Evaluation evaluate(List<Map<String, Object>> rows) {
        Evaluation evaluation = new Evaluation();
        evaluation.rows.addAll(rows == null ? Collections.<Map<String, Object>>emptyList() : rows);

        for (String hint : CRITICAL_NAME_HINTS) {
            Map<String, Object> row = findRow(evaluation.rows, hint);
            CheckResult check = evaluateCritical(hint, row);
            evaluation.criticalResults.put(hint, check);
            if (!check.passed) {
                evaluation.failedCritical.add(hint + " => " + check.detail);
            }
        }
        evaluation.passed = evaluation.failedCritical.isEmpty() && !evaluation.rows.isEmpty();
        if (evaluation.rows.isEmpty()) {
            evaluation.failedCritical.add("sannysoft table rows empty");
        }
        return evaluation;
    }

    public static Evaluation evaluateRaw(Object rawRows) {
        return evaluate(normalizeRows(rawRows));
    }

    /**
     * 兼容旧逻辑：整页文本粗判（不推荐用于 strict）。仅当无法解析表格时兜底。
     */
    public static boolean textLooksBroken(String report) {
        if (Strings.isEmpty(report)) {
            return true;
        }
        String lower = report.toLowerCase(Locale.ROOT);
        // 关键失败特征（比任意 failed 更精确）
        return lower.contains("webdriver (new)") && lower.contains("present (failed)")
                || lower.contains("webdriver advanced") && containsLineFailed(lower, "webdriver advanced")
                || lower.contains("chrome (new)") && lower.contains("missing (failed)")
                || lower.contains("headlesschrome");
    }

    private static boolean containsLineFailed(String lowerReport, String nameHint) {
        for (String line : lowerReport.split("\\r?\\n")) {
            if (line.contains(nameHint) && line.contains("failed")) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Object> findRow(List<Map<String, Object>> rows, String nameHint) {
        if (rows == null || Strings.isEmpty(nameHint)) {
            return null;
        }
        String hint = nameHint.toLowerCase(Locale.ROOT);
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("name")).toLowerCase(Locale.ROOT);
            if (name.contains(hint) || hint.contains(name) && !name.isEmpty()) {
                return row;
            }
        }
        // 宽松：去掉括号后匹配
        String compactHint = hint.replace("(", "").replace(")", "").trim();
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("name")).toLowerCase(Locale.ROOT)
                    .replace("(", "").replace(")", "").trim();
            if (name.contains(compactHint) || compactHint.contains(name) && !name.isEmpty()) {
                return row;
            }
        }
        return null;
    }

    static CheckResult evaluateCritical(String nameHint, Map<String, Object> row) {
        if (row == null) {
            // 表格未渲染出该项：不直接判死，留给 probe 其它信号；但关键 WebDriver 缺失更危险
            if (nameHint.toLowerCase(Locale.ROOT).contains("webdriver")) {
                return CheckResult.fail("row missing");
            }
            return CheckResult.pass("row missing(skip)");
        }
        String name = String.valueOf(row.get("name"));
        String value = String.valueOf(row.get("value"));
        boolean failedFlag = Boolean.TRUE.equals(row.get("failed"));
        boolean passedFlag = Boolean.TRUE.equals(row.get("passed"));
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerValue = value.toLowerCase(Locale.ROOT);

        if (lowerName.contains("webdriver")) {
            if (lowerValue.contains("present") && lowerValue.contains("failed")) {
                return CheckResult.fail(value);
            }
            if (lowerValue.contains("true") && !lowerValue.contains("false")) {
                return CheckResult.fail(value);
            }
            if (failedFlag && !passedFlag) {
                return CheckResult.fail(value);
            }
            if (lowerValue.contains("missing") || lowerValue.contains("passed") || lowerValue.contains("false") || passedFlag) {
                return CheckResult.pass(value);
            }
            return failedFlag ? CheckResult.fail(value) : CheckResult.pass(value);
        }
        if (lowerName.contains("chrome (new)") || lowerName.contains("chrome(new)")) {
            if (lowerValue.contains("missing") && lowerValue.contains("failed")) {
                return CheckResult.fail(value);
            }
            if (failedFlag && !passedFlag) {
                return CheckResult.fail(value);
            }
            return CheckResult.pass(value);
        }
        if (lowerName.contains("user agent")) {
            if (lowerValue.contains("headlesschrome") || (failedFlag && !passedFlag)) {
                return CheckResult.fail(value);
            }
            return CheckResult.pass(value);
        }
        if (lowerName.contains("plugins length")) {
            if (failedFlag && !passedFlag) {
                return CheckResult.fail(value);
            }
            try {
                String digits = value.replaceAll("[^0-9].*$", "").trim();
                if (!digits.isEmpty() && Integer.parseInt(digits) <= 0) {
                    return CheckResult.fail(value);
                }
            } catch (Exception ignored) {
            }
            return CheckResult.pass(value);
        }
        if (lowerName.contains("pluginarray") || lowerName.contains("plugins is of type")) {
            if (failedFlag && !passedFlag) {
                return CheckResult.fail(value);
            }
            return CheckResult.pass(value);
        }
        if (lowerName.contains("languages")) {
            if (failedFlag && !passedFlag) {
                return CheckResult.fail(value);
            }
            if (Strings.isEmpty(value) || "undefined".equalsIgnoreCase(value)) {
                return CheckResult.fail(value);
            }
            return CheckResult.pass(value);
        }
        if (lowerName.contains("broken image")) {
            if (failedFlag && !passedFlag) {
                return CheckResult.fail(value);
            }
            return CheckResult.pass(value);
        }
        if (failedFlag && !passedFlag) {
            return CheckResult.fail(value);
        }
        return CheckResult.pass(value);
    }

    public static final class CheckResult {
        public final boolean passed;
        public final String detail;

        private CheckResult(boolean passed, String detail) {
            this.passed = passed;
            this.detail = detail == null ? "" : detail;
        }

        public static CheckResult pass(String detail) {
            return new CheckResult(true, detail);
        }

        public static CheckResult fail(String detail) {
            return new CheckResult(false, detail);
        }
    }

    public static final class Evaluation {
        public boolean passed;
        public final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        public final Map<String, CheckResult> criticalResults = new LinkedHashMap<String, CheckResult>();
        public final List<String> failedCritical = new ArrayList<String>();

        public Map<String, Object> toDiagnostics() {
            Map<String, Object> diagnostics = new LinkedHashMap<String, Object>();
            diagnostics.put("sannysoftPassed", passed);
            diagnostics.put("sannysoftFailedCritical", new ArrayList<String>(failedCritical));
            Map<String, String> critical = new LinkedHashMap<String, String>();
            for (Map.Entry<String, CheckResult> e : criticalResults.entrySet()) {
                critical.put(e.getKey(), (e.getValue().passed ? "PASS" : "FAIL") + " | " + e.getValue().detail);
            }
            diagnostics.put("sannysoftCritical", critical);
            diagnostics.put("sannysoftRowCount", rows.size());
            return diagnostics;
        }
    }
}
