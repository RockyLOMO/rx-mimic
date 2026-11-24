package org.rx.crawler.task.jd;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.rx.core.Extends;
import org.rx.crawler.service.BrowserType;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.ChromeProfilePaths;
import org.rx.crawler.task.common.SannysoftFingerprintEvaluator;
import org.rx.net.http.HttpClientCookieJar;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.core.Extends.tryClose;

/**
 * 生产配置下的实网指纹审计：webdriver 必须 false 且 getter 原生，其他指纹项不能被检出。
 * 用 common profile + 默认 6 项 ignoreDefaultArgs + fingerprintEnabled=true，
 * 在京东联盟页读环境，再开一个标签跑 bot.sannysoft.com 全表。
 * 只读，不提交登录、不消耗验证码次数。JD_FP_AUDIT_LIVE=true 才运行。
 */
@EnabledIf("liveEnabled")
class JdFingerprintAuditLiveTest {

    private static final String UNION_URL = "https://union.jd.com/";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    static boolean liveEnabled() {
        return "true".equalsIgnoreCase(System.getenv("JD_FP_AUDIT_LIVE"));
    }

    @Test
    void webdriverIsNativeFalseAndNoFingerprintLeaks() throws Exception {
        Path profile = ChromeProfilePaths.profileDir(null, ChromeProfilePaths.DEFAULT_PROFILE_NAME);
        assertTrue(Files.isDirectory(profile), "common profile is missing: " + profile);
        Path dumpDir = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "ai",
                LocalDate.now().format(DATE), "jd-fp-audit", LocalTime.now().format(TIME)));

        WebBrowserConfig settings = new WebBrowserConfig();
        settings.setHeadless(false);
        settings.setFingerprintEnabled(true);
        settings.setProfileDataPath(profile.toString());
        settings.setDownloadPath(profile.resolve("download").toString());
        settings.setCookieJar(HttpClientCookieJar.memory());
        settings.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        settings.setPlaywrightChannel("chrome");

        WebBrowser browser = new WebBrowser(settings, BrowserType.CHROME);
        try {
            browser.navigateUrl(UNION_URL, "body", 40);
            Extends.sleep(1500);
            String url = browser.getCurrentUrl();
            Object probeValue = browser.executeScript(ENV_PROBE);
            String probe = String.valueOf((Object) probeValue);
            System.out.println("JD_FP_AUDIT_URL=" + url);
            System.out.println("JD_FP_AUDIT_PROBE=" + probe);

            Page page = (Page) ReflectionTestUtils.getField(browser, "page");
            Page sanny = page.context().newPage();
            SannysoftFingerprintEvaluator.Evaluation evaluation;
            byte[] sannyPng;
            try {
                sanny.navigate(SannysoftFingerprintEvaluator.DEFAULT_URL);
                sanny.waitForSelector("table tr", new Page.WaitForSelectorOptions().setTimeout(25000));
                Extends.sleep(1800);
                // 检测表在新标签里，必须在这个标签的上下文求值。
                Object rawRows = sanny.evaluate("() => { "
                        + SannysoftFingerprintEvaluator.ROW_PROBE_SCRIPT + " }");
                evaluation = SannysoftFingerprintEvaluator.evaluateRaw(rawRows);
                sannyPng = sanny.screenshot();
            } finally {
                sanny.close();
            }

            StringBuilder report = new StringBuilder();
            report.append("url=").append(url).append('\n')
                    .append("envProbe=").append(probe).append('\n')
                    .append("sannysoftPassed=").append(evaluation.passed).append('\n')
                    .append("failedCritical=").append(evaluation.failedCritical).append('\n')
                    .append("--- sannysoft rows ---\n");
            for (Map<String, Object> row : evaluation.rows) {
                report.append(row.get("name")).append(" | ").append(row.get("value"))
                        .append(" | ").append(Boolean.TRUE.equals(row.get("failed")) ? "FAILED"
                                : Boolean.TRUE.equals(row.get("passed")) ? "passed" : "?")
                        .append('\n');
            }
            Files.writeString(dumpDir.resolve("fingerprint-audit.txt"), report.toString(), StandardCharsets.UTF_8);
            Files.write(dumpDir.resolve("sannysoft.png"), sannyPng);

            System.out.println("JD_FP_AUDIT_SANNYSOFT_PASSED=" + evaluation.passed);
            System.out.println("JD_FP_AUDIT_FAILED_CRITICAL=" + evaluation.failedCritical);
            System.out.println("JD_FP_AUDIT_ROWS=" + evaluation.rows.size());
            for (Map<String, Object> row : evaluation.rows) {
                if (Boolean.TRUE.equals(row.get("failed"))) {
                    System.out.println("JD_FP_AUDIT_FAILED_ROW " + row.get("name") + " | " + row.get("value"));
                }
            }
            System.out.println("JD_FP_AUDIT_DUMP=" + dumpDir);

            assertTrue(probe.contains("\"webdriver\":false"), "navigator.webdriver must be false");
            assertTrue(probe.contains("\"webdriverGetter\":\"native\""), "webdriver getter must be native");
            assertFalse(probe.contains("HeadlessChrome"), "user agent must not contain HeadlessChrome");
            assertTrue(evaluation.passed, "sannysoft critical checks failed: " + evaluation.failedCritical);

            long holdSeconds = holdSeconds();
            if (holdSeconds > 0) {
                System.out.println("JD_FP_AUDIT_HOLD=" + holdSeconds + "s (Chrome window stays visible)");
                Extends.sleep(holdSeconds * 1000);
            }
        } finally {
            tryClose(browser);
        }
    }

    private static final String ENV_PROBE = "var o = {};"
            + "o.webdriver = navigator.webdriver;"
            + "var d = Object.getOwnPropertyDescriptor(Navigator.prototype, 'webdriver');"
            + "o.webdriverGetter = (!d || typeof d.get !== 'function') ? 'no-getter'"
            + " : (Function.prototype.toString.call(d.get).indexOf('[native code]') >= 0 ? 'native' : 'patched');"
            + "o.userAgent = navigator.userAgent;"
            + "o.headlessUa = /HeadlessChrome/i.test(navigator.userAgent);"
            + "o.languages = navigator.languages;"
            + "o.hardwareConcurrency = navigator.hardwareConcurrency;"
            + "o.deviceMemory = navigator.deviceMemory;"
            + "o.pluginsLength = navigator.plugins.length;"
            + "o.chromeRuntime = !!(window.chrome && window.chrome.runtime);"
            + "o.timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;"
            + "o.outer = window.outerWidth + 'x' + window.outerHeight;"
            + "o.inner = window.innerWidth + 'x' + window.innerHeight;"
            + "o.pwInitScripts = typeof globalThis.__pwInitScripts;"
            + "o.pwBinding = typeof globalThis.__playwright__binding__;"
            + "o.cdcKeys = Object.keys(window).filter(function (k) { return /cdc_[a-z0-9]/i.test(k); }).length;"
            + "o.selenium = !!(window.callSelenium || window._selenium || document.__driver_evaluate"
            + " || document.__webdriver_evaluate || document.__selenium_unwrapped);"
            + "o.phantom = !!(window.callPhantom || window._phantom);"
            + "o.getJsToken = typeof globalThis.getJsToken;"
            + "o.getJdEid = typeof globalThis.getJdEid;"
            + "try { var t = typeof globalThis.__getTkResult === 'function' ? globalThis.__getTkResult() : null;"
            + " o.jsTokenLength = t && t.jsToken ? String(t.jsToken).length : 0;"
            + " o.fpLength = t && t.fp ? String(t.fp).length : 0; } catch (e) { o.tkError = String(e); }"
            + "try { o.eidLength = globalThis.getJdEid ? String(globalThis.getJdEid().eid || '').length : 0; }"
            + " catch (e) { o.eidError = String(e); }"
            + "return JSON.stringify(o);";

    private static long holdSeconds() {
        String raw = System.getenv("JD_FP_AUDIT_HOLD_SECONDS");
        try {
            return raw == null || raw.isBlank() ? 8 : Math.max(0, Math.min(180, Long.parseLong(raw.trim())));
        } catch (NumberFormatException e) {
            return 8;
        }
    }
}
