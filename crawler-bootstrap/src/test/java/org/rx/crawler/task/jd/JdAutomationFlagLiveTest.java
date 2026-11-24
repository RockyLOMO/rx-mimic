package org.rx.crawler.task.jd;

import com.microsoft.playwright.Page;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.core.Extends.tryClose;

/**
 * A/B 验证 --disable-blink-features=AutomationControlled 放进 ignoreDefaultArgs 的后果。
 * 两种模式用同一个 common profile、同一套启动参数，只差 JD_IGNORE_AUTOMATION_CONTROLLED。
 * 读 chrome://version 的真实命令行和 JD 页面上的 navigator.webdriver，不提交登录、不拖滑块。
 * 只在 JD_FLAG_LIVE=true 时运行。
 */
@EnabledIf("liveEnabled")
class JdAutomationFlagLiveTest {

    private static final String TARGET_URL = "https://union.jd.com/";
    private static final String FLAG = "--disable-blink-features=AutomationControlled";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    static boolean liveEnabled() {
        return "true".equalsIgnoreCase(System.getenv("JD_FLAG_LIVE"));
    }

    @Test
    void reportsRealChromeCommandLineAndWebdriver() throws Exception {
        boolean ignoreFlag = Boolean.parseBoolean(
                System.getenv().getOrDefault("JD_IGNORE_AUTOMATION_CONTROLLED", "false"));
        String mode = ignoreFlag ? "OLD_7_ITEMS" : "CURRENT_6_ITEMS";

        Path profile = ChromeProfilePaths.profileDir(null, ChromeProfilePaths.DEFAULT_PROFILE_NAME);
        assertTrue(Files.isDirectory(profile), "common profile is missing: " + profile);
        Path dumpDir = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "ai",
                LocalDate.now().format(DATE), "jd-flag-ab", LocalTime.now().format(TIME) + "-" + mode));

        WebBrowserConfig settings = new WebBrowserConfig();
        settings.setHeadless(false);
        boolean fingerprint = !"false".equalsIgnoreCase(
                System.getenv().getOrDefault("JD_FLAG_FINGERPRINT", "true"));
        settings.setFingerprintEnabled(fingerprint);
        settings.setProfileDataPath(profile.toString());
        settings.setDownloadPath(profile.resolve("download").toString());
        settings.setCookieJar(HttpClientCookieJar.memory());
        settings.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        settings.setPlaywrightChannel("chrome");

        WebBrowser browser = new WebBrowser(settings, BrowserType.CHROME);
        try {
            browser.navigateUrl(TARGET_URL, "body", 40);
            Extends.sleep(1200);
            String url = browser.getCurrentUrl();
            Object webdriverValue = browser.executeScript("return String(navigator.webdriver);");
            String webdriver = String.valueOf((Object) webdriverValue);
            // webdriver=false 有两种来源：Chrome 启动参数原生为 false，或指纹脚本改写 getter。
            // 后者能被属性描述符 / Function.prototype.toString 侧信道识别。
            Object getterValue = browser.executeScript(
                    "var d = Object.getOwnPropertyDescriptor(Navigator.prototype, 'webdriver');"
                    + "if (!d || typeof d.get !== 'function') { return 'no-getter'; }"
                    + "return Function.prototype.toString.call(d.get).indexOf('[native code]') >= 0"
                    + " ? 'native' : 'patched';");
            String getterKind = String.valueOf((Object) getterValue);
            byte[] jdPng = browser.screenshotAsBytes(Browser.BODY_SELECTOR);
            if (jdPng != null && jdPng.length > 0) {
                Files.write(dumpDir.resolve("jd-login-wall.png"), jdPng);
            }

            // 另开一个标签读 chrome://version，不动京东那个标签。
            Page page = (Page) ReflectionTestUtils.getField(browser, "page");
            Page versionPage = page.context().newPage();
            String commandLine = "";
            try {
                versionPage.navigate("chrome://version");
                versionPage.waitForTimeout(700);
                String text = versionPage.textContent("#command_line");
                commandLine = text == null ? "" : text;
                byte[] versionPng = versionPage.screenshot();
                Files.write(dumpDir.resolve("chrome-version.png"), versionPng);
            } finally {
                versionPage.close();
            }
            // 用户目录是隐私信息，落盘时打码。
            String masked = commandLine.replaceAll("--user-data-dir=\\S+", "--user-data-dir=...");
            boolean inChromeCmdline = commandLine.contains(FLAG);
            java.lang.reflect.Method ignoreMethod = WebBrowser.class.getDeclaredMethod("defaultArgsToIgnore");
            ignoreMethod.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<String> ignoreList = (java.util.List<String>) ignoreMethod.invoke(null);
            boolean inIgnoreList = ignoreList.contains(FLAG);

            StringBuilder evidence = new StringBuilder();
            evidence.append("mode=").append(mode).append('\n')
                    .append("JD_IGNORE_AUTOMATION_CONTROLLED=").append(ignoreFlag).append('\n')
                    .append("ignoreDefaultArgs contains flag=").append(inIgnoreList).append('\n')
                    .append("chrome command line contains flag=").append(inChromeCmdline).append('\n')
                    .append("finalUrl=").append(url).append('\n')
                    .append("navigator.webdriver=").append(webdriver).append('\n')
                    .append("webdriverGetter=").append(getterKind).append('\n')
                    .append("--- chrome command line ---\n").append(masked).append('\n');
            Files.writeString(dumpDir.resolve("result.txt"), evidence.toString(), StandardCharsets.UTF_8);

            System.out.println("JD_FLAG_MODE=" + mode);
            System.out.println("JD_FLAG_FINGERPRINT_ENABLED=" + fingerprint);
            System.out.println("JD_FLAG_IGNORE_LIST=" + ignoreList);
            System.out.println("JD_FLAG_IN_IGNORE_LIST=" + inIgnoreList);
            System.out.println("JD_FLAG_IN_CHROME_CMDLINE=" + inChromeCmdline);
            System.out.println("JD_FLAG_WEBDRIVER=" + webdriver);
            System.out.println("JD_FLAG_WEBDRIVER_GETTER=" + getterKind);
            System.out.println("JD_FLAG_EXPECTED_O1=" + ("true".equals(webdriver) ? "1" : "0"));
            System.out.println("JD_FLAG_DUMP=" + dumpDir);
            System.out.println("JD_FLAG_CMDLINE=" + masked);
            assertTrue(!commandLine.isEmpty(), "chrome://version command line was empty");

            long holdSeconds = holdSeconds();
            if (holdSeconds > 0) {
                System.out.println("JD_FLAG_HOLD=" + holdSeconds + "s (Chrome window stays visible)");
                Extends.sleep(holdSeconds * 1000);
            }
        } finally {
            tryClose(browser);
        }
    }

    private static long holdSeconds() {
        String raw = System.getenv("JD_FLAG_HOLD_SECONDS");
        try {
            return raw == null || raw.isBlank() ? 20 : Math.max(0, Math.min(180, Long.parseLong(raw.trim())));
        } catch (NumberFormatException e) {
            return 20;
        }
    }
}
