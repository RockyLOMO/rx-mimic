package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceInstanceLockTest {
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void secondAcquireFailsUntilTheFirstCloses() {
        String name = "Local\\rx-mimic-service-test-" + UUID.randomUUID();
        ServiceInstanceLock first = ServiceInstanceLock.acquire(name);
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> ServiceInstanceLock.acquire(name));
            assertTrue(error.getMessage().contains("already running"));
        } finally {
            first.close();
        }
        try (ServiceInstanceLock ignored = ServiceInstanceLock.acquire(name)) {
            assertTrue(ignored != null);
        }
    }
}
