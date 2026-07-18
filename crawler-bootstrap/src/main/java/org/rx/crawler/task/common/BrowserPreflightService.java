package org.rx.crawler.task.common;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BrowserPreflightService {
    private final Map<String, CachedPreflight> cache = new ConcurrentHashMap<String, CachedPreflight>();

    public BrowserPreflightResult check(Browser browser, CrawlEntryOptions config, boolean force) {
        if (!config.isPreflightEnabled()) {
            return BrowserPreflightResult.pass(false);
        }

        String profileName = config.getProfileName();
        String cacheKey = Strings.isEmpty(profileName) ? "common" : profileName;
        CachedPreflight cached = cache.get(cacheKey);
        if (!force && cached != null && !cached.isExpired(config.getPreflightCacheMinutes())) {
            BrowserPreflightResult result = BrowserPreflightResult.pass(true);
            result.getDiagnostics().putAll(cached.getDiagnostics());
            return result;
        }

        try {
            String preflightUrl = Strings.isEmpty(config.getPreflightUrl())
                    ? SannysoftFingerprintEvaluator.DEFAULT_URL
                    : config.getPreflightUrl();
            browser.navigateUrl(preflightUrl, Browser.BODY_SELECTOR, config.getInitialPageTimeoutSeconds());
            Extends.sleep(Math.max(800, config.nextStepDelayMillis()));

            Map<String, Object> probe = browser.executeScript("return {" +
                    "webdriver: navigator.webdriver," +
                    "userAgent: navigator.userAgent," +
                    "plugins: navigator.plugins ? navigator.plugins.length : 0," +
                    "language: navigator.language," +
                    "languages: navigator.languages ? Array.prototype.slice.call(navigator.languages).join(',') : ''," +
                    "chrome: !!window.chrome" +
                    "};");
            Object rawRows = browser.executeScript(SannysoftFingerprintEvaluator.ROW_PROBE_SCRIPT);
            String report = browser.executeScript(
                    "return (document.body && document.body.innerText) ? document.body.innerText : '';");

            boolean webdriverOk = probe.get("webdriver") == null || Boolean.FALSE.equals(probe.get("webdriver"));
            boolean userAgentOk = !String.valueOf(probe.get("userAgent")).contains("HeadlessChrome");
            boolean pluginsOk = intValue(probe.get("plugins")) > 0;
            boolean languagesOk = !Strings.isEmpty(String.valueOf(probe.get("languages")))
                    && !"null".equalsIgnoreCase(String.valueOf(probe.get("languages")));
            boolean chromeOk = Boolean.TRUE.equals(probe.get("chrome"));

            SannysoftFingerprintEvaluator.Evaluation evaluation = SannysoftFingerprintEvaluator.evaluateRaw(rawRows);
            boolean basicOk = webdriverOk && userAgentOk && pluginsOk && languagesOk && chromeOk;
            boolean reportOk;
            if (config.isPreflightStrict()) {
                // strict：关键表项必须通过；若表格未渲染则退回文本关键特征 + basic
                if (!evaluation.rows.isEmpty()) {
                    reportOk = evaluation.passed;
                } else {
                    reportOk = basicOk && !SannysoftFingerprintEvaluator.textLooksBroken(report);
                }
            } else {
                // 非 strict：basic 探针通过即可；表格结果仅作诊断
                reportOk = basicOk;
            }
            boolean passed = basicOk && reportOk;

            BrowserPreflightResult result = passed ? BrowserPreflightResult.pass(false)
                    : BrowserPreflightResult.fail("Sannysoft fingerprint check failed");
            result.getDiagnostics().put("webdriverOk", webdriverOk);
            result.getDiagnostics().put("userAgentOk", userAgentOk);
            result.getDiagnostics().put("pluginsOk", pluginsOk);
            result.getDiagnostics().put("languagesOk", languagesOk);
            result.getDiagnostics().put("chromeOk", chromeOk);
            result.getDiagnostics().put("reportOk", reportOk);
            result.getDiagnostics().put("basicOk", basicOk);
            result.getDiagnostics().put("probe", probe);
            result.getDiagnostics().put("reportSnippet", snippet(report, 2000));
            result.getDiagnostics().putAll(evaluation.toDiagnostics());
            if (passed) {
                cache.put(cacheKey, new CachedPreflight(result.getDiagnostics()));
            } else {
                log.warn("Sannysoft preflight failed profile={}, basicOk={}, reportOk={}, failedCritical={}",
                        profileName, basicOk, reportOk, evaluation.failedCritical);
            }
            return result;
        } catch (Exception e) {
            log.warn("Browser preflight fail profile={}, url={}, error={}", profileName, config.getPreflightUrl(), e.getMessage());
            BrowserPreflightResult result = BrowserPreflightResult.fail(e.getMessage());
            result.getDiagnostics().put("exception", e.getClass().getName());
            return result;
        }
    }

    private int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private String snippet(String value, int maxLength) {
        if (Strings.isEmpty(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static class CachedPreflight {
        private final long createdAtMillis = System.currentTimeMillis();
        private final Map<String, Object> diagnostics;

        CachedPreflight(Map<String, Object> diagnostics) {
            this.diagnostics = diagnostics;
        }

        Map<String, Object> getDiagnostics() {
            return diagnostics;
        }

        boolean isExpired(int cacheMinutes) {
            long ttl = TimeUnit.MINUTES.toMillis(cacheMinutes <= 0 ? 30 : cacheMinutes);
            return System.currentTimeMillis() - createdAtMillis > ttl;
        }
    }
}
