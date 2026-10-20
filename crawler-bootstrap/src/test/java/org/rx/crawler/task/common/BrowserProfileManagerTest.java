package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class BrowserProfileManagerTest {
    @TempDir
    Path tempDir;

    private List<WebBrowser> setupMockBrowserFactory(BrowserProfileManager manager) {
        List<WebBrowser> constructed = Collections.synchronizedList(new ArrayList<>());
        manager.setBrowserFactory((config, type) -> {
            WebBrowser mock = Mockito.mock(WebBrowser.class);
            constructed.add(mock);
            return mock;
        });
        return constructed;
    }

    @Test
    public void taskCompletionShouldKeepAndReuseChromeSessionUntilExplicitClose() throws Exception {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        BrowserProfileManager manager = new BrowserProfileManager(config);
        List<WebBrowser> constructed = setupMockBrowserFactory(manager);

        WebBrowser firstBrowser;
        try (BrowserProfileManager.ProfileLease lease = manager.acquire("common", new WebBrowserConfig())) {
            firstBrowser = lease.getBrowser();
            assertFalse(lease.isFromSession());
        }

        assertEquals(1, manager.activeSessionCount());
        try (BrowserProfileManager.ProfileLease lease = manager.acquire("common", new WebBrowserConfig())) {
            assertTrue(lease.isFromSession());
            assertSame(firstBrowser, lease.getBrowser());
        }

        assertEquals(1, constructed.size());
        verify(firstBrowser, times(2)).saveCookies(false);
        verify(firstBrowser, never()).close();

        assertTrue(manager.closeSession("common"));
        assertEquals(0, manager.activeSessionCount());
        verify(firstBrowser).close();
    }

    @Test
    public void configuredCloseAfterTaskShouldCloseChromeWithoutCreatingReusableSession() throws Exception {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        config.getCustom().getChrome().setCloseBrowserAfterTask(true);
        BrowserProfileManager manager = new BrowserProfileManager(config);
        List<WebBrowser> constructed = setupMockBrowserFactory(manager);

        WebBrowser browser;
        try (BrowserProfileManager.ProfileLease lease = manager.acquire("common", new WebBrowserConfig())) {
            browser = lease.getBrowser();
        }

        assertEquals(1, constructed.size());
        assertEquals(0, manager.activeSessionCount());
        verify(browser).saveCookies(false);
        verify(browser).close();
    }

    @Test
    public void poolShouldLimitConcurrencyAndBlockUntilReleased() throws Exception {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        config.getCustom().getChrome().setMaxActive(2); // Limit concurrency to 2
        config.getCustom().getChrome().setMinIdle(1);
        BrowserProfileManager manager = new BrowserProfileManager(config);
        List<WebBrowser> constructed = setupMockBrowserFactory(manager);

        // Acquire 1st instance
        BrowserProfileManager.ProfileLease lease1 = manager.acquire("common", new WebBrowserConfig());
        // A configured capacity greater than one cannot double-open a persistent profile.
        assertEquals(1, constructed.size());
        assertEquals(1, manager.activeSessionCount());

        // Try to acquire 3rd instance in another thread, which should block since maxActive=2
        java.util.concurrent.atomic.AtomicBoolean acquired3 = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try (BrowserProfileManager.ProfileLease lease3 = manager.acquire("common", new WebBrowserConfig())) {
                acquired3.set(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        t.start();

        // Wait a short time to verify it's blocked
        Thread.sleep(200);
        assertFalse(acquired3.get());

        // Release one lease
        lease1.close();

        // Wait for thread to finish and verify it successfully acquired and released
        t.join(2000);
        assertTrue(acquired3.get());

        // Cleanup
        manager.closeSession("common");
    }

    @Test
    public void configuredLargePoolStillReusesOnePersistentBrowser() throws Exception {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        config.getCustom().getChrome().setMaxActive(5);
        config.getCustom().getChrome().setMinIdle(2);
        BrowserProfileManager manager = new BrowserProfileManager(config);
        List<WebBrowser> constructed = setupMockBrowserFactory(manager);
        for (int i = 0; i < 4; i++) {
            try (var ignored = manager.acquire("common", new WebBrowserConfig())) { }
        }
        assertEquals(1, constructed.size());
        assertEquals(1, manager.activeSessionCount());
        manager.closeSession("common");
        verify(constructed.get(0)).close();
    }

    @Test
    public void managerShouldPreWarmBrowsersOnInitialization() throws Exception {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        // Production default is 1 because one Chrome profile cannot be opened twice.
        // This test uses mock browsers, so raise the cap to match minIdle.
        config.getCustom().getChrome().setMaxActive(2);
        config.getCustom().getChrome().setMinIdle(2);
        config.getCustom().getChrome().setPreWarm(true);
        BrowserProfileManager manager = new BrowserProfileManager(config);
        List<WebBrowser> constructed = setupMockBrowserFactory(manager);

        // Trigger pre-warm manually (since we instantiated manually, PostConstruct isn't auto-run)
        manager.init();

        // Wait a short bit for the pre-warm thread to finish
        long deadline = System.currentTimeMillis() + 3000;
        while (constructed.size() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertEquals(1, constructed.size());
        assertEquals(1, manager.activeSessionCount());

        // Cleanup
        manager.closeSession("common");
    }
}
