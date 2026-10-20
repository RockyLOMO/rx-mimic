package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserOwnerExecutorTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir;
    @Test
    void taskMaintenanceAndSessionCloseShareOwnerThread() {
        BrowserOwnerExecutor owner = new BrowserOwnerExecutor();
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        BrowserProfileManager profiles = new BrowserProfileManager(config);
        ReflectionTestUtils.setField(profiles, "ownerExecutor", owner);
        WebBrowser browser = mock(WebBrowser.class);
        java.util.List<Thread> operations = new java.util.ArrayList<>();
        profiles.setBrowserFactory((browserConfig, type) -> { operations.add(Thread.currentThread()); return browser; });
        doAnswer(invocation -> { operations.add(Thread.currentThread()); return null; }).when(browser).close();
        try {
            assertThrows(IllegalStateException.class, () -> profiles.acquire("common", new WebBrowserConfig()));
            owner.call(() -> {
                try (var lease = profiles.acquire("common", new WebBrowserConfig())) { assertSame(browser, lease.getBrowser()); }
                assertTrue(owner.call(owner::isOwnerThread));
                return null;
            });
            profiles.closeSession("common");
            assertEquals(2, operations.size());
            assertSame(operations.get(0), operations.get(1));
            verify(browser).close();
        } finally { profiles.destroy(); owner.shutdown(); }
    }

    @Test
    void queuedMaintenanceWaitsUntilTaskFinishesAndIsCountedForDrain() throws Exception {
        BrowserOwnerExecutor owner = new BrowserOwnerExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(1);
        try {
            owner.execute(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            owner.execute(completed::countDown);
            assertEquals(2, owner.getPendingCount());
            assertEquals(1, completed.getCount());
            release.countDown();
            assertTrue(completed.await(3, TimeUnit.SECONDS));
        } finally { release.countDown(); owner.shutdown(); }
    }
    @Test
    void disabledPrewarmAllowsLazyInitializationAndReinitializationAfterClose() {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        config.getCustom().getChrome().setPreWarm(false);
        BrowserOwnerExecutor owner = new BrowserOwnerExecutor();
        BrowserProfileManager profiles = new BrowserProfileManager(config);
        ReflectionTestUtils.setField(profiles, "ownerExecutor", owner);
        java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        profiles.setBrowserFactory((browserConfig, type) -> { opened.incrementAndGet(); return mock(WebBrowser.class); });
        try {
            profiles.init();
            assertFalse(profiles.isBrowserReady());
            assertEquals(0, opened.get());
            profiles.requestWarmup();
            owner.call(() -> null); // Wait behind initialization on the same owner.
            assertTrue(profiles.isBrowserReady());
            assertEquals(1, opened.get());
            profiles.closeSession("common");
            assertFalse(profiles.isBrowserReady());
            profiles.requestWarmup();
            owner.call(() -> null);
            assertTrue(profiles.isBrowserReady());
            assertEquals(2, opened.get());
        } finally { profiles.destroy(); owner.shutdown(); }
    }

    @Test
    void closedBrowserContextIsRemovedInsteadOfReusedByNextTask() {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        BrowserOwnerExecutor owner = new BrowserOwnerExecutor();
        BrowserProfileManager profiles = new BrowserProfileManager(config);
        ReflectionTestUtils.setField(profiles, "ownerExecutor", owner);
        WebBrowser first = mock(WebBrowser.class), replacement = mock(WebBrowser.class);
        java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        profiles.setBrowserFactory((browserConfig, type) -> opened.getAndIncrement() == 0 ? first : replacement);
        try {
            owner.call(() -> {
                try (var ignored = profiles.acquire("common", new WebBrowserConfig())) { }
                when(first.isSessionUnavailable()).thenReturn(true);
                try (var lease = profiles.acquire("common", new WebBrowserConfig())) {
                    assertSame(replacement, lease.getBrowser());
                    assertFalse(lease.isFromSession());
                }
                return null;
            });
            assertEquals(2, opened.get());
            verify(first).close();
        } finally { profiles.destroy(); owner.shutdown(); }
    }

}
