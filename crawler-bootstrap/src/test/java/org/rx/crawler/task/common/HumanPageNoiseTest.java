package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.order.OrderSyncCoordinator;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class HumanPageNoiseTest {

    @Test
    public void testNullBrowserSafety() {
        // Applying noise or pagination on a null browser must not throw exceptions
        HumanPageNoise.NoisePattern pattern = HumanPageNoise.applyRandomNoise(null, "unit-test");
        assertNotNull(pattern);

        assertDoesNotThrow(() -> HumanPageNoise.applyPaginationNoise(null, 1));
        assertDoesNotThrow(() -> HumanPageNoise.applyPaginationNoise(null, 5));
        assertDoesNotThrow(() -> HumanPageNoise.applyPacing(10, 20));
    }

    @Test
    public void testNoisePatternsDiversity() {
        HumanPageNoise.NoisePattern[] patterns = HumanPageNoise.NoisePattern.values();
        assertEquals(4, patterns.length);

        Set<HumanPageNoise.NoisePattern> seen = new HashSet<HumanPageNoise.NoisePattern>();
        for (int i = 0; i < 100; i++) {
            seen.add(HumanPageNoise.applyRandomNoise(null, "diversity-test"));
        }
        // With 100 iterations and 4 patterns, we should hit all 4 patterns
        assertEquals(4, seen.size());
    }

    @Test
    public void testRandomizedSyncTimerRange() {
        AppConfig.OrderSyncConfig syncConfig = new AppConfig.OrderSyncConfig();
        syncConfig.setMinIntervalMinutes(8);
        syncConfig.setMaxIntervalMinutes(15);

        for (int i = 0; i < 50; i++) {
            long delaySeconds = OrderSyncCoordinator.calculateNextSyncDelaySeconds(null, syncConfig);
            assertTrue(delaySeconds >= 8 * 60L, "delay must be >= 480s, got " + delaySeconds);
            assertTrue(delaySeconds <= 15 * 60L, "delay must be <= 900s, got " + delaySeconds);
        }

        // Test source override
        AppConfig.OrderSourceConfig sourceConfig = new AppConfig.OrderSourceConfig();
        sourceConfig.setMinIntervalMinutes(5);
        sourceConfig.setMaxIntervalMinutes(9);

        for (int i = 0; i < 50; i++) {
            long delaySeconds = OrderSyncCoordinator.calculateNextSyncDelaySeconds(sourceConfig, syncConfig);
            assertTrue(delaySeconds >= 5 * 60L, "delay must be >= 300s, got " + delaySeconds);
            assertTrue(delaySeconds <= 9 * 60L, "delay must be <= 540s, got " + delaySeconds);
        }
    }
}
