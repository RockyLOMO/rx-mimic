package org.rx.test;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.core.Extends;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.crawler.service.BrowserType;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserPreflightResult;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.SannysoftFingerprintEvaluator;
import org.rx.net.http.HttpClientCookieJar;
import org.rx.util.BeanMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.rx.core.Extends.tryClose;

public class BrowserFingerprintTests {

    @Test
    public void chromeFingerprintResourcesExist() {
        assertResourceExists("/static/js/stealth.min.js");
        assertResourceExists("/bot/chrome-fingerprint.js");
    }

    @Test
    public void fingerprintScriptContentShouldBeStealthSafe() throws Exception {
        String script = readResource("/bot/chrome-fingerprint.js");
        assertTrue(script.contains("__rxChromeFingerprintReady"));
        assertTrue(script.contains("isSoftwareRenderer"));
        assertFalse(script.contains("Intel(R) Iris(R) OpenGL Engine"));
        assertFalse(script.matches("(?s).*defineGetter\\(Navigator\\.prototype,\\s*\"hardwareConcurrency\".*return 8;.*"));
    }

    /**
     * 可选：本机 Chrome 轻量探针（about:blank，不访问外网）。
     * 开启：-Dfingerprint.local=true
     */
    @SneakyThrows
    @Test
    public void localChromeStealthProbe() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("fingerprint.local", "false"))
                || Boolean.parseBoolean(System.getProperty("fingerprint.integration", "false")));
        assumeTrue(isLocalChromeLikelyPresent(), "local Google Chrome not found");

        Path profileDir = Files.createTempDirectory("rx-mercury-fp-");
        WebBrowser browser = openStealthChrome(profileDir, true);
        try {
            browser.navigateUrl("about:blank", "body", 30);
            Extends.sleep(1500);

            Map<String, Object> probe = browser.executeScript("return {" +
                    "webdriver: navigator.webdriver," +
                    "userAgent: navigator.userAgent," +
                    "plugins: navigator.plugins ? navigator.plugins.length : 0," +
                    "language: navigator.language," +
                    "languages: navigator.languages ? Array.prototype.slice.call(navigator.languages).join(',') : ''," +
                    "chrome: !!window.chrome," +
                    "vendor: navigator.vendor," +
                    "hardwareConcurrency: navigator.hardwareConcurrency," +
                    "platform: navigator.platform," +
                    "localeAligned: !!window.__rxLocaleAligned," +
                    "webglRenderer: (function(){try{var c=document.createElement('canvas').getContext('webgl');" +
                    "var e=c&&c.getExtension('WEBGL_debug_renderer_info');" +
                    "return e?c.getParameter(e.UNMASKED_RENDERER_WEBGL):'';}catch(e){return '';}})()" +
                    "};");
            System.out.println("LOCAL_CHROME_PROBE=" + probe);

            Object webdriver = probe.get("webdriver");
            assertTrue(webdriver == null || Boolean.FALSE.equals(webdriver),
                    "navigator.webdriver should be hidden, actual=" + webdriver);
            assertFalse(String.valueOf(probe.get("userAgent")).contains("HeadlessChrome"));
            assertTrue(Boolean.TRUE.equals(probe.get("chrome")), "window.chrome should exist");
            assertTrue(((Number) probe.get("plugins")).intValue() > 0, "plugins should not be empty");
            assertFalse(String.valueOf(probe.get("webglRenderer")).contains("Iris(R) OpenGL Engine"),
                    "should not force mac Iris GPU on real chrome");
            // 未强制 locale 时：语言交给本机 Chrome，只需非空（像真人浏览器）
            assertFalse(String.valueOf(probe.get("language")).isEmpty(), "navigator.language should exist");
            assertFalse(String.valueOf(probe.get("languages")).isEmpty(), "navigator.languages should exist");
            assertFalse(Boolean.TRUE.equals(probe.get("localeAligned")),
                    "must not JS-force language when locale config is empty");
        } finally {
            tryClose(browser);
        }
    }

    /**
     * 访问 https://bot.sannysoft.com/，校验关键检测项全部通过。
     * 开启：-Dfingerprint.integration=true
     * 也可：-Dfingerprint.sannysoft=true
     */
    @SneakyThrows
    @Test
    public void sannysoftCriticalChecksShouldPass() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("fingerprint.integration", "false"))
                || Boolean.parseBoolean(System.getProperty("fingerprint.sannysoft", "false")));
        assumeTrue(isLocalChromeLikelyPresent(), "local Google Chrome not found");

        Path profileDir = Files.createTempDirectory("rx-mercury-sannysoft-");
        WebBrowser browser = openStealthChrome(profileDir, true);
        try {
            String checkUrl = System.getProperty("app.browser.fingerprintCheckUrl",
                    SannysoftFingerprintEvaluator.DEFAULT_URL);
            browser.navigateUrl(checkUrl, "body", 60);
            // 等待表格渲染与异步检测完成
            Extends.sleep(Long.parseLong(System.getProperty("fingerprint.sannysoft.waitMillis", "6000")));

            Map<String, Object> probe = browser.executeScript("return {" +
                    "webdriver: navigator.webdriver," +
                    "userAgent: navigator.userAgent," +
                    "plugins: navigator.plugins ? navigator.plugins.length : 0," +
                    "language: navigator.language," +
                    "languages: navigator.languages ? Array.prototype.slice.call(navigator.languages).join(',') : ''," +
                    "chrome: !!window.chrome," +
                    "localeAligned: !!window.__rxLocaleAligned" +
                    "};");
            Object rawRows = browser.executeScript(SannysoftFingerprintEvaluator.ROW_PROBE_SCRIPT);
            List<Map<String, Object>> rows = SannysoftFingerprintEvaluator.normalizeRows(rawRows);
            SannysoftFingerprintEvaluator.Evaluation evaluation = SannysoftFingerprintEvaluator.evaluate(rows);

            System.out.println("SANNYSOFT_PROBE=" + probe);
            System.out.println("SANNYSOFT_ROW_COUNT=" + rows.size());
            for (Map<String, Object> row : rows) {
                System.out.println("SANNYSOFT_ROW=" + row.get("name") + " | " + row.get("value")
                        + " | passed=" + row.get("passed") + " failed=" + row.get("failed"));
            }
            System.out.println("SANNYSOFT_CRITICAL=" + evaluation.toDiagnostics().get("sannysoftCritical"));
            System.out.println("SANNYSOFT_FAILED=" + evaluation.failedCritical);

            assertTrue(rows.size() >= 5, "sannysoft table should render enough rows, actual=" + rows.size());

            Object webdriver = probe.get("webdriver");
            assertTrue(webdriver == null || Boolean.FALSE.equals(webdriver),
                    "navigator.webdriver should be hidden, actual=" + webdriver);
            assertFalse(String.valueOf(probe.get("userAgent")).contains("HeadlessChrome"));
            assertTrue(Boolean.TRUE.equals(probe.get("chrome")));
            assertTrue(((Number) probe.get("plugins")).intValue() > 0);
            assertFalse(String.valueOf(probe.get("language")).isEmpty());
            assertFalse(String.valueOf(probe.get("languages")).isEmpty());

            assertTrue(evaluation.passed,
                    "sannysoft critical checks failed: " + evaluation.failedCritical
                            + " diagnostics=" + evaluation.toDiagnostics().get("sannysoftCritical"));

            // 业务预检链路（strict）也应通过
            BrowserPreflightService preflightService = new BrowserPreflightService();
            CrawlEntryOptions options = new CrawlEntryOptions();
            options.setProfileName("sannysoft-test");
            options.setPreflightEnabled(true);
            options.setPreflightUrl(checkUrl);
            options.setPreflightStrict(true);
            options.setPreflightCacheMinutes(0);
            options.setInitialPageTimeoutSeconds(60);
            options.setStepDelayMillis(500);
            options.setStepDelayRandomMillis(0);
            BrowserPreflightResult preflight = preflightService.check(browser, options, true);
            System.out.println("SANNYSOFT_PREFLIGHT_PASSED=" + preflight.isPassed());
            System.out.println("SANNYSOFT_PREFLIGHT_DIAG=" + preflight.getDiagnostics());
            assertTrue(preflight.isPassed(),
                    "BrowserPreflightService strict mode should pass: " + preflight.getDiagnostics());
            assertEquals(true, preflight.getDiagnostics().get("webdriverOk"));
            assertEquals(true, preflight.getDiagnostics().get("chromeOk"));
            assertEquals(true, preflight.getDiagnostics().get("reportOk"));
        } finally {
            tryClose(browser);
        }
    }

    /**
     * 兼容旧测试名：转发到完整 Sannysoft 关键项校验。
     */
    @Test
    public void sannysoftAuthorizedDiagnostics() {
        sannysoftCriticalChecksShouldPass();
    }

    private static WebBrowser openStealthChrome(Path profileDir, boolean fingerprintEnabled) {
        AppConfig appConfig = new AppConfig();
        System.setProperty("app.browser.fingerprintEnabled", String.valueOf(fingerprintEnabled));

        WebBrowserConfig config = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
        config.setHeadless(false);
        config.setFingerprintEnabled(fingerprintEnabled);
        config.setFingerprintHeadless(false);
        config.setFingerprintDiagnostics(true);
        config.setCookieJar(HttpClientCookieJar.memory());
        config.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        config.setDownloadPath(profileDir.resolve("temp").toString());
        config.setProfileDataPath(profileDir.resolve("profile").toString());
        config.setWindowRectangle(new BrowserWindowRect(0, 0, 1100, 900));
        config.setPlaywrightChannel("chrome");
        // 空=跟随运行机器，换机自动适应
        config.setLocale(null);
        config.setTimezoneId(null);
        config.setHumanInputEnabled(true);
        return new WebBrowser(config, BrowserType.CHROME);
    }

    private static boolean isLocalChromeLikelyPresent() {
        String[] candidates = new String[]{
                System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
        };
        for (String path : candidates) {
            if (path != null && Files.isRegularFile(Path.of(path))) {
                return true;
            }
        }
        return false;
    }

    private void assertResourceExists(String path) {
        try (InputStream in = BrowserFingerprintTests.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
        } catch (Exception e) {
            throw new AssertionError(path, e);
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = BrowserFingerprintTests.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
