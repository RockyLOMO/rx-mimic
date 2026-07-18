package org.rx.crawler.service.impl;

import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.util.BeanMapper;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Playwright 升级后隐蔽性相关逻辑的纯单元测试（不启动真实浏览器）。
 */
public class WebBrowserStealthTests {

    @Test
    public void acceptLanguageShouldAlignWithLocale() {
        // 空值跟随运行机器，不写死 zh-CN
        String machineLocale = BrowserRuntimeEnvironment.detectLocale();
        assertTrue(WebBrowser.buildAcceptLanguage(null).startsWith(machineLocale.split("-")[0])
                        || WebBrowser.buildAcceptLanguage(null).startsWith(machineLocale),
                "empty locale should follow machine: " + WebBrowser.buildAcceptLanguage(null));
        assertEquals(WebBrowser.buildAcceptLanguage(machineLocale), WebBrowser.buildAcceptLanguage(""));
        assertEquals("zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7", WebBrowser.buildAcceptLanguage("zh-CN"));
        assertEquals("zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7", WebBrowser.buildAcceptLanguage("zh_CN"));
        assertEquals("zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7", WebBrowser.buildAcceptLanguage("zh-TW"));
        assertEquals("en-US,en;q=0.9", WebBrowser.buildAcceptLanguage("en-US"));
        assertEquals("ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7", WebBrowser.buildAcceptLanguage("ja-JP"));
    }

    @Test
    public void navigatorLanguagesShouldMatchAcceptLanguageOrder() {
        List<String> zh = WebBrowser.buildNavigatorLanguages("zh-CN");
        assertEquals(List.of("zh-CN", "zh", "en-US", "en"), zh);
        assertTrue(WebBrowser.buildAcceptLanguage("zh-CN").startsWith(zh.get(0)));
        assertTrue(WebBrowser.buildAcceptLanguage("zh-CN").contains(zh.get(1)));

        List<String> en = WebBrowser.buildNavigatorLanguages("en-US");
        assertEquals(List.of("en-US", "en"), en);
        assertEquals("en-US,en;q=0.9", WebBrowser.buildAcceptLanguage("en-US"));

        List<String> ja = WebBrowser.buildNavigatorLanguages("ja-JP");
        assertEquals(List.of("ja-JP", "ja", "en-US", "en"), ja);

        // 空 locale：与 detectLocale 同源
        List<String> auto = WebBrowser.buildNavigatorLanguages(null);
        assertFalse(auto.isEmpty());
        assertEquals(BrowserRuntimeEnvironment.detectLocale(), auto.get(0));
    }

    @Test
    public void localeAlignInitScriptShouldBindConfiguredLanguages() {
        String script = WebBrowser.buildLocaleAlignInitScript("zh-CN");
        assertTrue(script.contains("__rxLocaleAligned"));
        assertTrue(script.contains("\"zh-CN\""));
        assertTrue(script.contains("\"zh\""));
        assertTrue(script.contains("\"en-US\""));
        assertTrue(script.contains("Navigator.prototype"));
        assertTrue(script.contains("languages"));
        assertTrue(script.contains("language"));
    }

    @Test
    public void stealthHttpHeadersShouldContainAcceptLanguage() {
        Map<String, String> headers = WebBrowser.buildStealthHttpHeaders("zh-CN");
        assertNotNull(headers);
        assertEquals(1, headers.size());
        assertEquals("zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7", headers.get("Accept-Language"));
    }

    @Test
    public void chromeArgsShouldDisableAutomationControlledAndKeepLocale() throws Exception {
        WebBrowserConfig config = new WebBrowserConfig();
        config.setLocale("zh-CN");
        config.setWindowRectangle(new BrowserWindowRect(0, 0, -1, -1));

        WebBrowser browser = newBrowserShell(config);
        List<String> args = browser.buildChromeArgs();

        assertTrue(args.contains("--disable-blink-features=AutomationControlled"));
        assertTrue(args.contains("--no-first-run"));
        assertTrue(args.contains("--no-default-browser-check"));
        assertTrue(args.contains("--disable-features=TranslateUI"));
        assertTrue(args.contains("--start-maximized"));
        assertTrue(args.contains("--lang=zh-CN"));
        assertFalse(args.contains("--disable-infobars"), "obsolete infobars flag should not be forced");
        assertFalse(args.stream().anyMatch(a -> a.contains("enable-automation")));
    }

    @Test
    public void chromeArgsWithoutMaximizeShouldSkipStartMaximized() throws Exception {
        WebBrowserConfig config = new WebBrowserConfig();
        config.setLocale("en-US");
        config.setWindowRectangle(new BrowserWindowRect(0, 0, 1280, 720));

        WebBrowser browser = newBrowserShell(config);
        List<String> args = browser.buildChromeArgs();

        assertFalse(args.contains("--start-maximized"));
        assertTrue(args.contains("--lang=en-US"));
        assertTrue(args.contains("--disable-blink-features=AutomationControlled"));
    }

    @Test
    public void maximizedRectDetectionShouldMatchNegativeSize() {
        assertTrue(WebBrowser.isMaximized(new BrowserWindowRect(0, 0, -1, -1)));
        assertTrue(WebBrowser.isMaximized(new BrowserWindowRect(10, 20, 0, 0)));
        assertFalse(WebBrowser.isMaximized(new BrowserWindowRect(0, 0, 1280, 720)));
        assertFalse(WebBrowser.isMaximized(null));
    }

    @Test
    public void browserConfigDefaultsShouldFavorStealthAndMachineAuto() {
        WebBrowserConfig config = new WebBrowserConfig();
        assertEquals("chrome", config.getPlaywrightChannel());
        // 空=运行时自动跟随机器，不在默认值写死 zh-CN / Asia/Shanghai
        assertTrue(config.getLocale() == null || config.getLocale().isEmpty());
        assertTrue(config.getTimezoneId() == null || config.getTimezoneId().isEmpty());
        assertTrue(config.isHumanInputEnabled());
        assertFalse(config.isBlockResourceEnabled());
        assertFalse(config.isFingerprintHeadless());
        assertEquals("/bot/chrome-fingerprint.js", config.getFingerprintScriptPath());
        assertEquals("/static/js/stealth.min.js", config.getFingerprintStealthScriptPath());
    }

    @Test
    public void appBrowserPoolConfigDefaultsShouldMatchStealthDefaults() {
        AppConfig.BrowserPoolConfig browser = new AppConfig().getBrowser();
        assertEquals("chrome", browser.getPlaywrightChannel());
        assertTrue(browser.getLocale() == null || browser.getLocale().isEmpty());
        assertTrue(browser.getTimezoneId() == null || browser.getTimezoneId().isEmpty());
        assertTrue(browser.isHumanInputEnabled());
        assertFalse(browser.isBlockResourceEnabled());
        assertFalse(browser.isHeadless());
    }

    @Test
    public void beanMapperShouldCopyStealthFieldsFromAppConfig() {
        AppConfig appConfig = new AppConfig();
        appConfig.getBrowser().setLocale("zh-CN");
        appConfig.getBrowser().setTimezoneId("Asia/Shanghai");
        appConfig.getBrowser().setHumanInputEnabled(true);
        appConfig.getBrowser().setBlockResourceEnabled(false);
        appConfig.getBrowser().setPlaywrightChannel("chrome");

        WebBrowserConfig mapped = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
        assertEquals("zh-CN", mapped.getLocale());
        assertEquals("chrome", mapped.getPlaywrightChannel());
        assertTrue(mapped.isHumanInputEnabled());
        assertFalse(mapped.isBlockResourceEnabled());
        assertEquals("Asia/Shanghai", mapped.getTimezoneId());
    }

    @Test
    public void resolveHelpersShouldMatchMachineWhenConfigBlank() {
        assertEquals(BrowserRuntimeEnvironment.detectLocale(),
                BrowserRuntimeEnvironment.resolveLocale(null));
        assertEquals(BrowserRuntimeEnvironment.detectTimezone(),
                BrowserRuntimeEnvironment.resolveTimezone(""));
    }

    @Test
    public void fingerprintScriptShouldAvoidMacGpuHardcodeAndKeepRealHardware() throws Exception {
        String script = readClasspathResource("/bot/chrome-fingerprint.js");
        assertTrue(script.contains("isSoftwareRenderer"), "should only spoof software GPU renderers");
        assertTrue(script.toLowerCase().contains("swiftshader"));
        assertTrue(script.contains("webdriver"));
        assertTrue(script.contains("Google Inc."));
        // 旧逻辑会在 Windows 真机无条件伪造 mac Iris，升级后应移除该硬编码主路径。
        assertFalse(script.contains("Intel(R) Iris(R) OpenGL Engine"),
                "must not unconditionally spoof macOS Iris GPU");
        assertFalse(script.matches("(?s).*defineGetter\\(Navigator\\.prototype,\\s*\"hardwareConcurrency\".*return 8;.*"),
                "hardwareConcurrency must not be forced to 8");
        assertFalse(script.matches("(?s).*defineGetter\\(Navigator\\.prototype,\\s*\"deviceMemory\".*return 8;.*"),
                "deviceMemory must not be forced to 8");
    }

    @Test
    public void stealthScriptResourceShouldBePresentAndNonTrivial() throws Exception {
        String stealth = readClasspathResource("/static/js/stealth.min.js");
        assertTrue(stealth.length() > 500, "stealth.min.js should not be empty/stub");
        assertTrue(stealth.toLowerCase().contains("webdriver") || stealth.contains("navigator"),
                "stealth script should touch navigator/webdriver surface");
    }

    /**
     * 构造不启动 Chrome 的 WebBrowser 壳对象，仅用于测 buildChromeArgs 等依赖 config 的方法。
     */
    private static WebBrowser newBrowserShell(WebBrowserConfig config) throws Exception {
        Class<?> unsafeClass;
        Object unsafe;
        try {
            unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
        } catch (Exception e) {
            unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
        }
        WebBrowser instance = (WebBrowser) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, WebBrowser.class);
        Field configField = WebBrowser.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(instance, config);
        return instance;
    }

    private static String readClasspathResource(String path) throws Exception {
        try (var in = WebBrowserStealthTests.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
