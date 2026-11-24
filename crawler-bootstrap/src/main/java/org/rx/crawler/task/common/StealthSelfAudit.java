package org.rx.crawler.task.common;

import org.rx.crawler.service.Browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地隐蔽性自检（不依赖 Sannysoft）。
 *
 * <p>bot.sannysoft.com 只覆盖约 8 项静态指纹，通过它不等于通过现代风控。
 * 真正会立刻暴露自动化的是「自己往页面里加的东西」：自定义全局、原型覆盖、
 * 非可信事件、被 emulation 改写的窗口几何。这里在任意目标页上做一次<b>只读</b>探测，
 * 输出逐项 PASS/FAIL 报告，供 preflight 诊断与自有站点红队验证使用。</p>
 *
 * <p>探针本身只读，不 defineProperty、不挂全局、不改原型。</p>
 */
public final class StealthSelfAudit {

    /** 在页面主世界执行；只读取属性，不产生任何副作用。 */
    public static final String PROBE_SCRIPT = "return (function(){"
            + "function nativeCode(fn){try{return /\\[native code\\]/.test(Function.prototype.toString.call(fn));}catch(e){return false;}}"
            + "var names=Object.getOwnPropertyNames(window),custom=[];"
            + "for(var i=0;i<names.length;i++){var n=names[i];"
            + "if(/^__rx|^_rx$|^__pw|^__playwright|^cdc_|^_selenium|^callPhantom|^__nightmare|^domAutomation/i.test(n)){custom.push(n);}}"
            + "var wd=Object.getOwnPropertyDescriptor(Navigator.prototype,'webdriver');"
            + "var tz='';try{tz=Intl.DateTimeFormat().resolvedOptions().timeZone;}catch(e){}"
            + "return {"
            + "webdriver:navigator.webdriver,"
            + "webdriverGetterNative:!!wd&&nativeCode(wd.get),"
            + "webdriverEnumerable:!!wd&&wd.enumerable===true,"
            + "customGlobals:custom,"
            + "customSleep:typeof window.sleep!=='undefined',"
            + "replaceAllNative:nativeCode(String.prototype.replaceAll),"
            + "addEventListenerNative:nativeCode(window.addEventListener),"
            + "pluginArrayNative:(typeof PluginArray!=='undefined')&&(navigator.plugins instanceof PluginArray),"
            + "pluginCount:navigator.plugins?navigator.plugins.length:0,"
            + "chromeShape:!!window.chrome&&nativeCode(window.chrome.loadTimes)&&nativeCode(window.chrome.csi),"
            + "ua:navigator.userAgent,"
            + "headlessUa:/HeadlessChrome/i.test(navigator.userAgent),"
            + "languages:navigator.languages?Array.prototype.slice.call(navigator.languages).join(','):'',"
            + "timezone:tz,"
            + "screenWidth:window.screen.width,screenHeight:window.screen.height,"
            + "innerWidth:window.innerWidth,innerHeight:window.innerHeight,"
            + "outerWidth:window.outerWidth,outerHeight:window.outerHeight,"
            + "outerMinusInnerX:window.outerWidth-window.innerWidth,"
            + "outerMinusInnerY:window.outerHeight-window.innerHeight,"
            + "visibility:document.visibilityState,"
            + "hardwareConcurrency:navigator.hardwareConcurrency"
            + "};})();";

    private StealthSelfAudit() {
    }

    /** 在浏览器当前页面跑一次自检，失败时返回带 exception 的诊断结果而不是抛出。 */
    public static Map<String, Object> run(Browser browser) {
        try {
            Object raw = browser.executeScript(PROBE_SCRIPT);
            if (!(raw instanceof Map)) {
                // 页面上下文不支持探针（或被测试桩替换）时不能误判成"泄露"，
                // 否则会无端否决本来能通过的业务任务。
                return skipped("page probe unavailable (non-map result)");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> probe = (Map<String, Object>) raw;
            // 校验探针形状：必须带有本探针独有的键，避免把其它 executeScript 的返回值
            // （例如 preflight 的基础 probe）误当成自检结果而错误否决任务。
            if (!probe.containsKey("customGlobals") || !probe.containsKey("replaceAllNative")) {
                return skipped("page probe shape mismatch");
            }
            return evaluate(probe);
        } catch (Exception e) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("passed", false);
            out.put("skipped", false);
            out.put("checks", Collections.emptyList());
            out.put("exception", e.getClass().getName() + ": " + e.getMessage());
            out.put("report", "SELF-AUDIT ERROR: " + e.getMessage());
            return out;
        }
    }

    private static Map<String, Object> skipped(String reason) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("passed", true);
        out.put("skipped", true);
        out.put("checks", Collections.emptyList());
        out.put("report", "STEALTH SELF-AUDIT: SKIPPED (" + reason + ")");
        return out;
    }

    /** 纯 Java 判定，便于单元测试喂合成探针结果。blocking=false 的项只告警不否决。 */
    public static Map<String, Object> evaluate(Map<String, Object> p) {
        Map<String, Object> probe = p == null ? Collections.emptyMap() : p;
        List<Map<String, Object>> checks = new ArrayList<Map<String, Object>>();

        add(checks, "webdriver=false", !bool(probe, "webdriver", true),
                "navigator.webdriver=" + probe.get("webdriver"), true);
        add(checks, "webdriver getter native", bool(probe, "webdriverGetterNative", false)
                        && bool(probe, "webdriverEnumerable", false),
                "getterNative=" + probe.get("webdriverGetterNative")
                        + ", enumerable=" + probe.get("webdriverEnumerable"), true);
        add(checks, "no custom globals", list(probe, "customGlobals").isEmpty()
                        && !bool(probe, "customSleep", false),
                "globals=" + list(probe, "customGlobals") + ", window.sleep=" + probe.get("customSleep"), true);
        add(checks, "native builtins intact", bool(probe, "replaceAllNative", false)
                        && bool(probe, "addEventListenerNative", false),
                "replaceAll=" + probe.get("replaceAllNative")
                        + ", addEventListener=" + probe.get("addEventListenerNative"), true);
        add(checks, "no headless UA", !bool(probe, "headlessUa", false),
                "ua=" + abbreviate(String.valueOf(probe.get("ua")), 120), true);
        add(checks, "window.chrome shape", bool(probe, "chromeShape", false),
                "chromeShape=" + probe.get("chromeShape"), false);
        add(checks, "plugins = real PluginArray", bool(probe, "pluginArrayNative", false)
                        && num(probe, "pluginCount", 0) > 0,
                "count=" + probe.get("pluginCount") + ", instanceof=" + probe.get("pluginArrayNative"), false);
        add(checks, "languages present", !String.valueOf(probe.getOrDefault("languages", "")).isBlank(),
                "languages=" + probe.get("languages"), false);
        add(checks, "no viewport emulation", num(probe, "outerMinusInnerX", 0) > 0
                        && num(probe, "outerMinusInnerY", 0) > 0,
                "outer-inner=" + probe.get("outerMinusInnerX") + "x" + probe.get("outerMinusInnerY"), false);
        add(checks, "screen larger than viewport",
                num(probe, "screenHeight", 0) - num(probe, "innerHeight", 0) > 20,
                "screen=" + probe.get("screenWidth") + "x" + probe.get("screenHeight")
                        + ", inner=" + probe.get("innerWidth") + "x" + probe.get("innerHeight"), false);
        add(checks, "foreground visible tab", "visible".equals(String.valueOf(probe.get("visibility"))),
                "visibility=" + probe.get("visibility"), false);

        boolean passed = true;
        for (Map<String, Object> check : checks) {
            if (Boolean.TRUE.equals(check.get("blocking")) && !Boolean.TRUE.equals(check.get("ok"))) {
                passed = false;
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("passed", passed);
        out.put("checks", checks);
        out.put("probe", probe);
        out.put("report", format(checks, passed));
        return out;
    }

    private static void add(List<Map<String, Object>> checks, String name, boolean ok, String detail,
            boolean blocking) {
        Map<String, Object> check = new LinkedHashMap<String, Object>();
        check.put("name", name);
        check.put("ok", ok);
        check.put("detail", detail);
        check.put("blocking", blocking);
        checks.add(check);
    }

    /** 逐项 PASS/FAIL 文本，便于日志与人工核对。 */
    @SuppressWarnings("unchecked")
    public static String format(List<Map<String, Object>> checks, boolean passed) {
        StringBuilder sb = new StringBuilder();
        sb.append("STEALTH SELF-AUDIT: ").append(passed ? "PASS" : "FAIL").append('\n');
        for (Map<String, Object> raw : checks) {
            boolean ok = Boolean.TRUE.equals(raw.get("ok"));
            sb.append(String.format("  [%s] %-32s %s  %s%n",
                    ok ? "PASS" : "FAIL",
                    String.valueOf(raw.get("name")),
                    Boolean.TRUE.equals(raw.get("blocking")) ? "(blocking)" : "(advisory)",
                    String.valueOf(raw.get("detail"))));
        }
        return sb.toString();
    }

    private static boolean bool(Map<String, Object> p, String key, boolean fallback) {
        Object v = p.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        if (v == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static int num(Map<String, Object> p, String key, int fallback) {
        Object v = p.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v instanceof List ? (List<Object>) v : Collections.emptyList();
    }

    private static String abbreviate(String value, int max) {
        return value == null || value.length() <= max ? String.valueOf(value) : value.substring(0, max) + "...";
    }
}
