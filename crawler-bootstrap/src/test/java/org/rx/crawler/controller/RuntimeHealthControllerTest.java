package org.rx.crawler.controller;

import org.junit.jupiter.api.Test;
import org.rx.crawler.dto.Result;
import org.rx.crawler.dto.RuntimeRelease;
import org.rx.crawler.task.common.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeHealthControllerTest {
    @Test
    void readinessRequiresDatabaseDesktopAndScheduler() {
        CustomCrawlQueueService queue = mock(CustomCrawlQueueService.class);
        BrowserOwnerExecutor owner = mock(BrowserOwnerExecutor.class);
        DesktopAvailability desktop = mock(DesktopAvailability.class);
        var controller = new RuntimeHealthController(queue, owner, desktop, mock(LoginKeepAliveService.class));
        BrowserProfileManager profiles = mock(BrowserProfileManager.class);
        ReflectionTestUtils.setField(controller, "profiles", profiles);
        when(profiles.isBrowserReady()).thenReturn(true);
        when(owner.isAvailable()).thenReturn(true);
        when(queue.isDatabaseAvailable()).thenReturn(true);
        assertEquals(503, controller.ready().getStatusCode().value());
        when(desktop.isAvailable()).thenReturn(true);
        assertEquals(200, controller.ready().getStatusCode().value());
        when(queue.isDraining()).thenReturn(true);
        assertEquals(503, controller.ready().getStatusCode().value());
        assertTrue(controller.live().isSuccess());
    }

    @Test
    void deploymentRequiresLoopbackAndConfiguredAdminSecret() {
        CustomCrawlQueueService queue = mock(CustomCrawlQueueService.class);
        var controller = new RuntimeHealthController(queue, mock(BrowserOwnerExecutor.class), mock(DesktopAvailability.class), mock(LoginKeepAliveService.class));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer unit-test-key");
        assertEquals(403, controller.drain(request).getStatusCode().value());
        ReflectionTestUtils.setField(controller, "adminKey", "unit-test-key");
        request.setRemoteAddr("192.0.2.1");
        assertEquals(403, controller.drain(request).getStatusCode().value());
        request.setRemoteAddr("::1");
        assertEquals(200, controller.drain(request).getStatusCode().value());
        verify(queue).setDraining(true);
        assertEquals(200, controller.resume(request).getStatusCode().value());
        verify(queue).setDraining(false);
        when(queue.isDraining()).thenReturn(true);
        when(queue.isIdle()).thenReturn(true);
        when(queue.hasWaitingUser()).thenReturn(true);
        assertEquals(409, controller.shutdown(request).getStatusCode().value());
        assertEquals(false, controller.status(request).getBody().getData().get("idle"));
    }
    @Test
    void executionGateWarmsOnlyForPendingWorkAndRecoversAfterProfileClose() {
        CustomCrawlQueueService queue = mock(CustomCrawlQueueService.class);
        BrowserOwnerExecutor owner = mock(BrowserOwnerExecutor.class);
        DesktopAvailability desktop = mock(DesktopAvailability.class);
        BrowserProfileManager profiles = mock(BrowserProfileManager.class);
        var controller = new RuntimeHealthController(queue, owner, desktop, mock(LoginKeepAliveService.class));
        ReflectionTestUtils.setField(controller, "profiles", profiles);
        when(owner.isAvailable()).thenReturn(true);
        when(desktop.isAvailable()).thenReturn(true);
        java.util.concurrent.atomic.AtomicReference<java.util.function.BooleanSupplier> gate = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> { gate.set(invocation.getArgument(0)); return null; }).when(queue).setExecutionGate(any());
        controller.configureGate();
        assertFalse(gate.get().getAsBoolean());
        controller.ready();
        verify(profiles, never()).requestWarmup();
        when(queue.hasPendingTasks()).thenReturn(true);
        assertFalse(gate.get().getAsBoolean());
        verify(profiles).requestWarmup();
        when(profiles.isBrowserReady()).thenReturn(true);
        assertTrue(gate.get().getAsBoolean());
        // Simulate explicit session close or WAITING_USER cleanup invalidating the browser.
        when(profiles.isBrowserReady()).thenReturn(false);
        assertFalse(gate.get().getAsBoolean());
        verify(profiles, times(2)).requestWarmup();
    }

    @Test
    void desktopCaptureRequiresLoopbackAdminKey() {
        CustomCrawlQueueService queue = mock(CustomCrawlQueueService.class);
        var controller = new RuntimeHealthController(queue, mock(BrowserOwnerExecutor.class), mock(DesktopAvailability.class), mock(LoginKeepAliveService.class));
        ReflectionTestUtils.setField(controller, "adminKey", "unit-test-key");
        var missing = new MockHttpServletRequest();
        missing.setRemoteAddr("127.0.0.1");
        assertEquals(403, controller.desktop(missing).getStatusCode().value());
        var caller = new MockHttpServletRequest();
        caller.setRemoteAddr("127.0.0.1");
        caller.addHeader("Authorization", "Bearer caller-key");
        assertEquals(403, controller.desktop(caller).getStatusCode().value());
        var remote = new MockHttpServletRequest();
        remote.setRemoteAddr("192.0.2.1");
        remote.addHeader("Authorization", "Bearer unit-test-key");
        assertEquals(403, controller.desktop(remote).getStatusCode().value());
    }

    @Test
    void releaseNumberIsReturnedOnHealthAndApiEnvelope() {
        RuntimeRelease.publish(0);
        try {
            CustomCrawlQueueService queue = mock(CustomCrawlQueueService.class);
            var controller = new RuntimeHealthController(queue, mock(BrowserOwnerExecutor.class), mock(DesktopAvailability.class), mock(LoginKeepAliveService.class));
            ReflectionTestUtils.setField(controller, "release", 6);
            assertEquals(6, controller.live().getData().get("release"));
            RuntimeRelease.publish(6);
            assertEquals(6, Result.success("ok").getRelease());
            assertEquals(6, Result.fail("FAILED", "missing", null).getRelease());
        } finally {
            RuntimeRelease.publish(0);
        }
    }

}
