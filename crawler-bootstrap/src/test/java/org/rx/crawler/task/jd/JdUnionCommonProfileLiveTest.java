package org.rx.crawler.task.jd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.rx.core.Extends;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.BrowserType;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.ChromeProfilePaths;
import org.rx.net.http.HttpClientCookieJar;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.core.Extends.tryClose;

/**
 * 用生产上的 common profile 打开有头 Chrome，真实访问 https://union.jd.com/。
 * 只读：不填账号、不点登录、不拖滑块、不消耗验证码次数。
 * 目的是验证「已建立设备信誉的持久化 profile」在真实页面上是登录态还是登录墙，
 * 以及上一轮的会话层改动在真浏览器里能不能正常导航。
 * 只在 JD_COMMON_PROFILE_LIVE=true 时运行。
 */
@EnabledIf("liveEnabled")
class JdUnionCommonProfileLiveTest {

    private static final String TARGET_URL = "https://union.jd.com/";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    static boolean liveEnabled() {
        return "true".equalsIgnoreCase(System.getenv("JD_COMMON_PROFILE_LIVE"))
                || "true".equalsIgnoreCase(System.getProperty("browser.jd.commonProfile.live"));
    }

    @Test
    void opensUnionInTheAgedCommonProfile() throws Exception {
        Path profile = ChromeProfilePaths.profileDir(null, ChromeProfilePaths.DEFAULT_PROFILE_NAME);
        assertTrue(Files.isDirectory(profile), "common profile is missing: " + profile);
        assertTrue(Files.isRegularFile(profile.resolve("Default/Network/Cookies")),
                "common profile has no cookie db: " + profile);

        Path dumpDir = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "ai",
                LocalDate.now().format(DATE), "jd-common-profile", LocalTime.now().format(TIME)));
        System.out.println("JD_COMMON_PROFILE=" + profile);
        System.out.println("JD_COMMON_DUMP=" + dumpDir);

        WebBrowserConfig settings = new WebBrowserConfig();
        settings.setHeadless(false);
        // 与 application.yml 的 fingerprintEnabled: true 一致。
        settings.setFingerprintEnabled(true);
        settings.setProfileDataPath(profile.toString());
        settings.setDownloadPath(profile.resolve("download").toString());
        settings.setCookieJar(HttpClientCookieJar.memory());
        settings.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        settings.setPlaywrightChannel("chrome");

        WebBrowser browser = new WebBrowser(settings, BrowserType.CHROME);
        try {
            browser.navigateUrl(TARGET_URL, "body", 40);
            // 一次自然的鼠标移动和停顿，别让页面看到「导航完立刻读状态」。
            browser.mouseMove(420, 300);
            Extends.sleep(1200);

            String url = browser.getCurrentUrl();
            String title = browser.executeScript("return document.title;");
            String body = browser.executeScript("return document.body ? document.body.innerText : '';");
            // 先把 profile 里已有的浏览器 cookie 同步进 jar，再读，避免误判「无会话 cookie」。
            String rawCookie = browser.saveCookies(false);
            Set<String> cookieNames = cookieNames(rawCookie);

            JdUnionConfig config = new JdUnionConfig();
            boolean wall = JdLoginState.unionLoginWall(url, body, config.getLoginUrlPrefix());
            // 已登录时登录页签不存在，不能拿它考连续点击路径；只有登录墙上才做这一步。
            boolean qrTab = false;
            boolean accountTab = false;
            boolean passwordFieldBack = false;
            boolean clickChecked = false;
            if (wall) {
                // 真页面上走一遍本轮改过的连续轨迹点击：只切登录页签，不提交表单、不消耗验证码次数。
                qrTab = browser.clickTextInFrames("扫码登录", "扫码登录", 20, 10, 300, 80);
                Extends.sleep(600);
                accountTab = browser.clickTextInFrames("账号登录", "账号登录", 20, 10, 300, 80);
                Extends.sleep(400);
                passwordFieldBack = browser.locateInFrames("input[type='password']") != null;
                clickChecked = true;
            }

            // 只读探测：和京东 pc-tk.js 看的是同一个 window，不点击、不提交、不消耗验证码次数。
            String envProbe = browser.executeScript("var o = {};"
                    + "o.webdriver = navigator.webdriver;"
                    + "o.pwInitScripts = typeof globalThis.__pwInitScripts;"
                    + "o.pwBinding = typeof globalThis.__playwright__binding__;"
                    + "o.cdcKeys = Object.keys(window).filter(function (k) { return /cdc_[a-z0-9]/i.test(k); }).length;"
                    + "o.selenium = !!(window.callSelenium || window._selenium || document.__driver_evaluate"
                    + " || document.__webdriver_evaluate || document.__selenium_unwrapped);"
                    + "o.phantom = !!(window.callPhantom || window._phantom);"
                    + "o.getJsToken = typeof globalThis.getJsToken;"
                    + "o.getJdEid = typeof globalThis.getJdEid;"
                    + "o.hardwareConcurrency = navigator.hardwareConcurrency;"
                    + "o.languages = navigator.languages;"
                    + "try { var t = typeof globalThis.__getTkResult === 'function' ? globalThis.__getTkResult() : null;"
                    + " o.jsTokenLength = t && t.jsToken ? String(t.jsToken).length : 0;"
                    + " o.fpLength = t && t.fp ? String(t.fp).length : 0; } catch (e) { o.tkError = String(e); }"
                    + "try { o.eidLength = globalThis.getJdEid ? String(globalThis.getJdEid().eid || '').length : 0; }"
                    + " catch (e) { o.eidError = String(e); }"
                    + "return JSON.stringify(o);");

            boolean ready = JdLoginState.unionReady(url, body, rawCookie, config.getLoginUrlPrefix());
            boolean sessionCookie = JdLoginState.hasSessionCookie(rawCookie);
            // unionReady 判的是推广工作台，营销落地页会显示「已登录账户」，单独认一下。
            boolean loggedIn = body != null
                    && (body.contains("已登录账户") || body.contains("进入我的联盟"));

            byte[] png = browser.screenshotAsBytes(Browser.BODY_SELECTOR);
            if (png != null && png.length > 0) {
                Files.write(dumpDir.resolve("union-after-navigate.png"), png);
            }
            StringBuilder evidence = new StringBuilder();
            evidence.append("profile=").append(profile).append('\n')
                    .append("target=").append(TARGET_URL).append('\n')
                    .append("finalUrl=").append(url).append('\n')
                    .append("title=").append(title).append('\n')
                    .append("loginWall=").append(wall).append('\n')
                    .append("unionReady=").append(ready).append('\n')
                    .append("loggedIn=").append(loggedIn).append('\n')
                    .append("hasSessionCookie=").append(sessionCookie).append('\n')
                    .append("cookieNames=").append(cookieNames).append('\n')
                    .append("humanClickChecked=").append(clickChecked)
                    .append(" humanClickQrTab=").append(qrTab)
                    .append(" humanClickAccountTab=").append(accountTab)
                    .append(" passwordFieldBack=").append(passwordFieldBack).append('\n')
                    .append("envProbe=").append(envProbe).append('\n')
                    .append("bodyLength=").append(body == null ? 0 : body.length()).append('\n')
                    .append("--- body head (first 1200 chars) ---\n")
                    .append(body == null ? "" : body.substring(0, Math.min(1200, body.length()))).append('\n');
            Files.writeString(dumpDir.resolve("union-state.txt"), evidence.toString(), StandardCharsets.UTF_8);

            System.out.println("JD_COMMON_RESULT finalUrl=" + url + " loginWall=" + wall
                    + " loggedIn=" + loggedIn + " unionReady=" + ready
                    + " hasSessionCookie=" + sessionCookie);
            System.out.println("JD_COMMON_TITLE=" + title);
            System.out.println("JD_COMMON_COOKIE_NAMES=" + cookieNames);
            System.out.println("JD_COMMON_ENV_PROBE=" + envProbe);
            System.out.println("JD_COMMON_HUMAN_CLICK checked=" + clickChecked + " qrTab=" + qrTab
                    + " accountTab=" + accountTab + " passwordFieldBack=" + passwordFieldBack);
            if (clickChecked) {
                assertTrue(qrTab && accountTab && passwordFieldBack,
                        "continuous click path failed on the real JD login page");
            } else {
                System.out.println("JD_COMMON_HUMAN_CLICK_SKIPPED=already logged in, no login tabs");
            }
            assertTrue(!wall || clickChecked, "login wall should have been exercised");
            System.out.println("JD_COMMON_BODY_HEAD=" + (body == null ? "" : body.substring(0, Math.min(300, body.length()))
                    .replace('\n', ' ')));
            assertTrue(url != null && !url.isBlank(), "navigation produced no URL");
            assertTrue(body != null && body.trim().length() > 20, "page body did not load");

            long holdSeconds = holdSeconds();
            if (holdSeconds > 0) {
                System.out.println("JD_COMMON_HOLD=" + holdSeconds + "s (window stays visible)");
                Extends.sleep(holdSeconds * 1000);
            }
        } finally {
            tryClose(browser);
        }
    }

    /** 只留 cookie 名，不打印、不落盘 cookie 值。 */
    private static Set<String> cookieNames(String rawCookie) {
        Set<String> names = new LinkedHashSet<String>();
        if (rawCookie == null || rawCookie.isEmpty()) {
            return names;
        }
        for (String pair : rawCookie.split(";")) {
            int equal = pair.indexOf('=');
            String name = (equal > 0 ? pair.substring(0, equal) : pair).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private static long holdSeconds() {
        String raw = System.getenv("JD_COMMON_PROFILE_HOLD_SECONDS");
        try {
            return raw == null || raw.isBlank() ? 12 : Math.max(0, Math.min(120, Long.parseLong(raw.trim())));
        } catch (NumberFormatException e) {
            return 12;
        }
    }
}
