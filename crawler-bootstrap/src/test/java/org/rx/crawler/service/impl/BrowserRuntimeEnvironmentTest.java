package org.rx.crawler.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BrowserRuntimeEnvironmentTest {

    @AfterEach
    public void tearDown() {
        BrowserRuntimeEnvironment.clearCache();
    }

    @Test
    public void detectLocaleShouldFollowJvmDefaultNotHardcodedZhCn() {
        String detected = BrowserRuntimeEnvironment.detectLocale();
        assertNotNull(detected);
        assertFalse(detected.isEmpty());
        // 应与当前 JVM 默认语言一致（换机则变），而不是固定 zh-CN
        String expected = BrowserRuntimeEnvironment.normalizeLocaleTag(Locale.getDefault().toLanguageTag());
        assertEquals(expected, detected);
    }

    @Test
    public void detectTimezoneShouldFollowSystemDefault() {
        assertEquals(ZoneId.systemDefault().getId(), BrowserRuntimeEnvironment.detectTimezone());
    }

    @Test
    public void resolveLocaleShouldPreferExplicitConfig() {
        assertEquals("ja-JP", BrowserRuntimeEnvironment.resolveLocale("ja-JP"));
        assertEquals("zh-CN", BrowserRuntimeEnvironment.resolveLocale("zh_CN"));
        assertEquals(BrowserRuntimeEnvironment.detectLocale(), BrowserRuntimeEnvironment.resolveLocale(null));
        assertEquals(BrowserRuntimeEnvironment.detectLocale(), BrowserRuntimeEnvironment.resolveLocale(""));
    }

    @Test
    public void resolveTimezoneShouldPreferExplicitConfig() {
        assertEquals("UTC", BrowserRuntimeEnvironment.resolveTimezone("UTC"));
        assertEquals(ZoneId.systemDefault().getId(), BrowserRuntimeEnvironment.resolveTimezone(null));
    }

    @Test
    public void detectInstalledChromeShouldReturnStructuredResult() {
        BrowserRuntimeEnvironment.ChromeInstall install = BrowserRuntimeEnvironment.detectInstalledChrome();
        assertNotNull(install);
        // 本机有 Chrome 则 found=true 且有路径；无 Chrome 的 CI 也允许 found=false
        if (install.isFound()) {
            assertNotNull(install.getExecutablePath());
            assertFalse(install.getExecutablePath().isEmpty());
            System.out.println("DETECTED_CHROME path=" + install.getExecutablePath()
                    + " version=" + install.getVersion());
        }
    }

    @Test
    public void ensurePlaywrightDriverTmpDirDefaultsOutsideSystemTempAndRespectsExplicitProperty(@org.junit.jupiter.api.io.TempDir Path tempDir) {
        String previous = System.getProperty("playwright.driver.tmpdir");
        try {
            System.clearProperty("playwright.driver.tmpdir");
            String resolvedDefault = WebBrowser.ensurePlaywrightDriverTmpDir();
            assertEquals(Paths.get("data", "playwright-driver").toAbsolutePath().normalize().toString(), resolvedDefault);
            assertTrue(Files.isDirectory(Paths.get(resolvedDefault)));

            Path explicit = tempDir.resolve("custom-playwright");
            System.setProperty("playwright.driver.tmpdir", explicit.toString());
            String resolvedExplicit = WebBrowser.ensurePlaywrightDriverTmpDir();
            assertEquals(explicit.toString(), resolvedExplicit);
            assertTrue(Files.isDirectory(explicit));
        } finally {
            if (previous == null) {
                System.clearProperty("playwright.driver.tmpdir");
            } else {
                System.setProperty("playwright.driver.tmpdir", previous);
            }
        }
    }
}
