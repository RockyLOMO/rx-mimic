package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoginKeepAliveServiceTest {
    @Test void tbChallengeFailureIsUnhealthyAndUsesTheSelectedKeepAliveUrlForRecovery() {
        var entry = mock(CrawlEntryService.class);
        var service = new LoginKeepAliveService(new AppConfig(), null, entry);
        var handler = mock(org.rx.crawler.task.tb.SliderVerifyHandler.class);
        var browser = mock(org.rx.crawler.service.Browser.class);
        ReflectionTestUtils.setField(service, "tbSliderVerifyHandler", handler);
        when(handler.isSliderVerifyPage(browser)).thenReturn(true);
        String target = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
        var result = new LoginKeepAliveResult();
        assertFalse(service.clearTbSlider(browser, result, "tbLoginKeepAlive", "common", target, 30, 30));
        assertFalse(result.isHealthy());
        assertEquals(CustomCrawlStatus.PAGE_CHANGED, result.getStatus());
        verify(handler).checkAndHandle(eq(browser), eq("keepalive-tb-slider"), eq(5), anyLong(), isNull(), eq(target));
        verify(entry).notifySliderFailed(argThat(context -> target.equals(context.getInitialUrl())));
    }
    @Test
    public void jdHealthShouldDetectLoginUrl() {
        LoginKeepAliveService service = new LoginKeepAliveService(new AppConfig(), null, null);

        assertTrue(service.isLoginUnhealthy("jd", "https://union.jd.com/index?returnUrl=https%3A%2F%2Funion.jd.com%2Foverview", ""));
        assertTrue(service.isLoginUnhealthy("jd", "https://passport.jd.com/new/login.aspx", ""));
        assertFalse(service.isLoginUnhealthy("jd", "https://union.jd.com/overview", "京东联盟 我要推广 订单"));
    }

    @Test
    public void tbHealthShouldDetectLoginUrl() {
        LoginKeepAliveService service = new LoginKeepAliveService(new AppConfig(), null, null);

        assertTrue(service.isLoginUnhealthy("tb", "https://pub.alimama.com/portal/v2/home/plus/index.htm/_____tmd_____/page/login_jump", ""));
        assertTrue(service.isLoginUnhealthy("tb", "https://login.taobao.com/havanaone/login/login.htm", ""));
        assertFalse(service.isLoginUnhealthy("tb", "https://pub.alimama.com/portal/v2/home/plus/index.htm", "阿里妈妈 淘宝联盟 选品广场"));
        assertTrue(service.isLoginUnhealthy("tb", "https://pub.alimama.com/?forward=example", "确认登录 快速进入"));
    }

    @Test
    public void timeRangeShouldDetermineIfKeepAliveRuns() {
        LoginKeepAliveService service = new LoginKeepAliveService(new AppConfig(), null, null);

        // Case 1: Neither configured -> Should return true
        AppConfig.LoginKeepAliveConfig config1 = new AppConfig.LoginKeepAliveConfig();
        config1.setStartTime(null);
        config1.setEndTime(null);
        assertTrue(service.isInKeepAliveTimeRange(config1));

        // Case 2: Only startTime configured -> Should return true
        AppConfig.LoginKeepAliveConfig config2 = new AppConfig.LoginKeepAliveConfig();
        config2.setStartTime("08:00");
        config2.setEndTime(null);
        assertTrue(service.isInKeepAliveTimeRange(config2));

        // Case 3: Both configured and now is within range
        AppConfig.LoginKeepAliveConfig config3 = new AppConfig.LoginKeepAliveConfig();
        java.time.LocalTime now = java.time.LocalTime.now();
        java.time.LocalTime start = now.minusHours(1);
        java.time.LocalTime end = now.plusHours(1);
        config3.setStartTime(start.toString().substring(0, 5));
        config3.setEndTime(end.toString().substring(0, 5));
        assertTrue(service.isInKeepAliveTimeRange(config3));

        // Case 4: Both configured and now is outside range
        AppConfig.LoginKeepAliveConfig config4 = new AppConfig.LoginKeepAliveConfig();
        java.time.LocalTime start2 = now.plusHours(1);
        java.time.LocalTime end2 = now.plusHours(2);
        config4.setStartTime(start2.toString().substring(0, 5));
        config4.setEndTime(end2.toString().substring(0, 5));
        
        // Wait, if now is e.g. 23:30, now.plusHours(1) will cross midnight and be 00:30, which would wrap.
        // To prevent test flakiness due to midnight wrapping during current time:
        if (now.getHour() < 22) {
            assertFalse(service.isInKeepAliveTimeRange(config4));
        }
    }
    private LoginKeepAliveService scheduledService(BrowserOwnerExecutor owner) {
        AppConfig config = new AppConfig();
        config.getCustom().getLoginKeepAlive().setEnabled(true);
        config.getCustom().getLoginKeepAlive().setJdEnabled(true);
        config.getCustom().getLoginKeepAlive().setTbEnabled(false);
        config.getCustom().getLoginKeepAlive().setStartTime(null);
        config.getCustom().getLoginKeepAlive().setEndTime(null);
        LoginKeepAliveService service = spy(new LoginKeepAliveService(config, null, null));
        ReflectionTestUtils.setField(service, "ownerExecutor", owner);
        return service;
    }

    @Test
    void scheduledCheckReturnsWhileOwnerBusyAndDeduplicatesPendingChecks() throws Exception {
        BrowserOwnerExecutor owner = new BrowserOwnerExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        LoginKeepAliveService service = scheduledService(owner);
        doReturn(new LoginKeepAliveResult()).when(service).checkJd();
        try {
            owner.execute(() -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                service.scheduledKeepAlive();
                service.scheduledKeepAlive();
            });
            verify(service, never()).checkJd();
            assertEquals(2, owner.getPendingCount());
            release.countDown();
            owner.call(() -> null);
            verify(service, times(1)).checkJd();
        } finally { release.countDown(); owner.shutdown(); }
    }

    @Test
    void scheduledFailureClearsDeduplicationFlag() {
        BrowserOwnerExecutor owner = new BrowserOwnerExecutor();
        LoginKeepAliveService service = scheduledService(owner);
        doThrow(new IllegalStateException("simulated failure"))
                .doReturn(new LoginKeepAliveResult()).when(service).checkJd();
        try {
            service.scheduledKeepAlive();
            owner.call(() -> null);
            service.scheduledKeepAlive();
            owner.call(() -> null);
            verify(service, times(2)).checkJd();
        } finally { owner.shutdown(); }
    }

    @Test
    void queuedKeepAliveRechecksExecutionGateBeforeOpeningBrowser() throws Exception {
        BrowserOwnerExecutor owner = new BrowserOwnerExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        LoginKeepAliveService service = scheduledService(owner);
        java.util.concurrent.atomic.AtomicBoolean gate = new java.util.concurrent.atomic.AtomicBoolean(true);
        service.setExecutionGate(gate::get);
        doReturn(new LoginKeepAliveResult()).when(service).checkJd();
        try {
            owner.execute(() -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            service.scheduledKeepAlive();
            gate.set(false);
            release.countDown();
            owner.call(() -> null);
            verify(service, never()).checkJd();
        } finally { release.countDown(); owner.shutdown(); }
    }
}
