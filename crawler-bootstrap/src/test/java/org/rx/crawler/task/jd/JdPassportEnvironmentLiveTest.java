package org.rx.crawler.task.jd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.rx.core.Extends;
import org.rx.crawler.service.BrowserType;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.net.http.HttpClientCookieJar;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.core.Extends.tryClose;

/**
 * 京东通行证页的风控 SDK 怎么看这个浏览器。只打开登录页、读页面状态，不提交登录，不消耗验证码次数。
 * 探测项照抄 passport 页上的 pc-tk.js：它把结果加密进 jsToken，随滑块校验一起提交。
 * 滑块脚本另外把 navigator.webdriver 直接当 o1 参数提交，为 true 时位置再准也过不了。
 * 只在显式打开 browser.jd.login.live 时运行。
 */
@EnabledIf("liveEnabled")
class JdPassportEnvironmentLiveTest {
    static boolean liveEnabled() {
        return "true".equalsIgnoreCase(System.getenv("JD_LOGIN_LIVE"))
                || "true".equalsIgnoreCase(System.getProperty("browser.jd.login.live"));
    }

    @TempDir
    Path profile;

    @Test
    void withoutFingerprintScript() throws Exception {
        Map<String, Object> seen = seeTheBrowser(false);

        System.out.println("JD_ENV_PROBE(fingerprintEnabled=false)=" + new ObjectMapper().writeValueAsString(seen));
        // 启动参数就该让 webdriver 原生为 false，不靠脚本去改原型。
        assertEquals(false, seen.get("webdriver"), "navigator.webdriver must be false without any script");
        assertTrue(String.valueOf(seen.get("commandLine")).contains("--disable-blink-features=AutomationControlled"),
                "the launch flag must reach Chrome's command line");
        assertNoAutomationMarkers(seen);
    }

    @Test
    void withFingerprintScript() throws Exception {
        Map<String, Object> seen = seeTheBrowser(true);

        // 生产配置是 fingerprintEnabled=true。
        System.out.println("JD_ENV_PROBE(fingerprintEnabled=true)=" + new ObjectMapper().writeValueAsString(seen));
        assertEquals(false, seen.get("webdriver"), "navigator.webdriver must be false with the fingerprint script");
        assertNoAutomationMarkers(seen);
    }

    private static void assertNoAutomationMarkers(Map<String, Object> seen) {
        assertEquals("undefined", seen.get("pwInitScripts"), "Playwright init scripts must stay out of the page's world");
        assertEquals("undefined", seen.get("pwBinding"), "Playwright binding must stay out of the page's world");
        assertEquals(0, ((Number) seen.get("consoleGetterHits")).intValue(), "console getter must not fire (CDP probe)");
        assertEquals(0, ((List<?>) seen.get("cdcKeys")).size(), "no chromedriver keys on window");
        assertEquals(false, seen.get("selenium"));
        assertEquals(false, seen.get("phantom"));
        assertTrue(((Number) seen.get("jsTokenLength")).intValue() > 0, "the risk SDK must have produced a jsToken");
    }

    private Map<String, Object> seeTheBrowser(boolean fingerprintEnabled) throws Exception {
        WebBrowserConfig settings = new WebBrowserConfig();
        settings.setHeadless(false);
        settings.setFingerprintEnabled(fingerprintEnabled);
        settings.setProfileDataPath(profile.resolve(Boolean.toString(fingerprintEnabled)).toString());
        settings.setDownloadPath(profile.resolve("download-" + fingerprintEnabled).toString());
        settings.setCookieJar(HttpClientCookieJar.memory());
        settings.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        settings.setPlaywrightChannel("chrome");
        WebBrowser browser = new WebBrowser(settings, BrowserType.CHROME);
        try {
            browser.navigateUrl("https://passport.jd.com/common/loginPage?from=media&ReturnUrl=https%3A%2F%2Funion.jd.com%2Fentire",
                    "body", 40);
            Page page = (Page) ReflectionTestUtils.getField(browser, "page");
            // 等 pc-tk 生成 jsToken。
            Extends.sleep(4000);
            Map<String, Object> seen = probe(page);
            // Chrome 自己报告的启动命令行，用来核对去自动化特征的参数到底带上了没有。
            page.navigate("chrome://version");
            Extends.sleep(600);
            seen.put("commandLine", page.textContent("#command_line").replaceAll("--user-data-dir=\\S+", "--user-data-dir=..."));
            return seen;
        } finally {
            tryClose(browser);
        }
    }

    /**
     * 只读：在页面自己的脚本环境里执行，看到的就是京东脚本看到的 window。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> probe(Page page) {
        return (Map<String, Object>) page.evaluate("() => {"
                + "  var out = {};"
                + "  out.webdriver = navigator.webdriver;"
                + "  out.pwInitScripts = typeof globalThis.__pwInitScripts;"
                + "  out.pwBinding = typeof globalThis.__playwright__binding__;"
                + "  var hits = 0;"
                + "  try {"
                + "    var bait = {};"
                + "    Object.defineProperty(bait, 'stack', {configurable: false, enumerable: false,"
                + "      get: function () { hits += 1; return ''; }});"
                + "    console.debug(bait);"
                + "  } catch (e) { out.consoleProbeError = String(e); }"
                + "  out.consoleGetterHits = hits;"
                + "  out.cdcKeys = Object.keys(window).filter(function (k) { return /cdc_[a-z0-9]/i.test(k); });"
                + "  out.selenium = !!(window.callSelenium || window._selenium || window._Selenium_IDE_Recorder"
                + "    || document.__driver_evaluate || document.__webdriver_evaluate || document.__selenium_unwrapped"
                + "    || document.__driver_unwrapped || document.__fxdriver_unwrapped || document.__fxdriver_evaluate"
                + "    || document.__webdriver_script_func || document.__selenium_evaluate);"
                + "  out.phantom = !!(window.callPhantom || window._phantom);"
                + "  out.outer = window.outerWidth + 'x' + window.outerHeight;"
                + "  out.inner = window.innerWidth + 'x' + window.innerHeight;"
                + "  out.getJsTokenType = typeof globalThis.getJsToken;"
                + "  out.getJdEidType = typeof globalThis.getJdEid;"
                + "  try {"
                + "    var result = typeof globalThis.__getTkResult === 'function' ? globalThis.__getTkResult() : null;"
                + "    out.jsTokenLength = result && result.jsToken ? String(result.jsToken).length : 0;"
                + "    out.fpLength = result && result.fp ? String(result.fp).length : 0;"
                + "  } catch (e) { out.tkError = String(e); }"
                + "  try { out.eidLength = globalThis.getJdEid ? String(globalThis.getJdEid().eid || '').length : 0; } catch (e) {}"
                + "  out.rxProbe = typeof window.__rxMmFn;"
                + "  out.hardwareConcurrency = navigator.hardwareConcurrency;"
                + "  out.languages = navigator.languages;"
                + "  out.platform = navigator.platform;"
                + "  out.plugins = navigator.plugins.length;"
                + "  return out;"
                + "}");
    }
}
