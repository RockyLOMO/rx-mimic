package org.rx.crawler.task.order;

import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

public class OrderSyncCoordinatorTest {

    private final ZoneId zone = ZoneId.of("Asia/Shanghai");

    @Test
    public void testIsInQuietHoursDefaultRange() {
        LocalDate date = LocalDate.of(2026, 10, 24);

        // 0点到8点：0, 1, ..., 7 should be in quiet hours
        for (int h = 0; h < 8; h++) {
            Instant t = date.atTime(h, 30).atZone(zone).toInstant();
            assertTrue(OrderSyncCoordinator.isInQuietHours(t, zone, 0, 8),
                    "Hour " + h + " should be in quiet hours");
        }

        // 8点到24点：8, 9, ..., 23 should NOT be in quiet hours
        for (int h = 8; h < 24; h++) {
            Instant t = date.atTime(h, 0).atZone(zone).toInstant();
            assertFalse(OrderSyncCoordinator.isInQuietHours(t, zone, 0, 8),
                    "Hour " + h + " should not be in quiet hours");
        }
    }

    @Test
    public void testIsInQuietHoursCrossMidnight() {
        LocalDate date = LocalDate.of(2026, 10, 24);

        // Quiet period 23:00 to 07:00
        Instant t23 = date.atTime(23, 15).atZone(zone).toInstant();
        Instant t0 = date.atTime(0, 30).atZone(zone).toInstant();
        Instant t4 = date.atTime(4, 0).atZone(zone).toInstant();
        Instant t6 = date.atTime(6, 59).atZone(zone).toInstant();

        assertTrue(OrderSyncCoordinator.isInQuietHours(t23, zone, 23, 7));
        assertTrue(OrderSyncCoordinator.isInQuietHours(t0, zone, 23, 7));
        assertTrue(OrderSyncCoordinator.isInQuietHours(t4, zone, 23, 7));
        assertTrue(OrderSyncCoordinator.isInQuietHours(t6, zone, 23, 7));

        Instant t7 = date.atTime(7, 0).atZone(zone).toInstant();
        Instant t12 = date.atTime(12, 0).atZone(zone).toInstant();
        Instant t22 = date.atTime(22, 59).atZone(zone).toInstant();

        assertFalse(OrderSyncCoordinator.isInQuietHours(t7, zone, 23, 7));
        assertFalse(OrderSyncCoordinator.isInQuietHours(t12, zone, 23, 7));
        assertFalse(OrderSyncCoordinator.isInQuietHours(t22, zone, 23, 7));
    }

    @Test
    public void testIsInQuietHoursDisabledWhenSameHour() {
        LocalDate date = LocalDate.of(2026, 10, 24);
        Instant t = date.atTime(5, 0).atZone(zone).toInstant();
        assertFalse(OrderSyncCoordinator.isInQuietHours(t, zone, 0, 0));
        assertFalse(OrderSyncCoordinator.isInQuietHours(t, zone, 8, 8));
    }

    @Test
    public void testIsSyncAllowedWithDefaultConfig() {
        AppConfig.OrderSyncConfig config = new AppConfig.OrderSyncConfig();
        AppConfig.OrderSourceConfig source = new AppConfig.OrderSourceConfig();
        source.setId("jd-main");

        // Defaults: quietHoursEnabled = true, quietHoursStart = 0, quietHoursEnd = 8
        assertEquals(0, config.getQuietHoursStart());
        assertEquals(8, config.getQuietHoursEnd());
        assertTrue(config.isQuietHoursEnabled());

        LocalDate date = LocalDate.of(2026, 10, 24);

        // At 02:00 -> not allowed
        Instant night = date.atTime(2, 0).atZone(zone).toInstant();
        assertFalse(OrderSyncCoordinator.isSyncAllowed(night, zone, config, source));

        // At 07:59 -> not allowed
        Instant morningBefore8 = date.atTime(7, 59).atZone(zone).toInstant();
        assertFalse(OrderSyncCoordinator.isSyncAllowed(morningBefore8, zone, config, source));

        // At 08:00 -> allowed
        Instant morningAt8 = date.atTime(8, 0).atZone(zone).toInstant();
        assertTrue(OrderSyncCoordinator.isSyncAllowed(morningAt8, zone, config, source));

        // At 15:30 -> allowed
        Instant afternoon = date.atTime(15, 30).atZone(zone).toInstant();
        assertTrue(OrderSyncCoordinator.isSyncAllowed(afternoon, zone, config, source));

        // When quietHoursEnabled is disabled -> 02:00 is allowed
        config.setQuietHoursEnabled(false);
        assertTrue(OrderSyncCoordinator.isSyncAllowed(night, zone, config, source));
    }

    @Test
    public void testSourceQuietHoursOverride() {
        AppConfig.OrderSyncConfig config = new AppConfig.OrderSyncConfig();
        config.setQuietHoursStart(0);
        config.setQuietHoursEnd(8);

        AppConfig.OrderSourceConfig source = new AppConfig.OrderSourceConfig();
        source.setId("special-source");
        source.setQuietHoursStart(2);
        source.setQuietHoursEnd(5);

        LocalDate date = LocalDate.of(2026, 10, 24);

        // 01:00 is allowed for source (outside 2-5) even though global is 0-8
        Instant t1 = date.atTime(1, 0).atZone(zone).toInstant();
        assertTrue(OrderSyncCoordinator.isSyncAllowed(t1, zone, config, source));

        // 03:00 is quiet for source
        Instant t3 = date.atTime(3, 0).atZone(zone).toInstant();
        assertFalse(OrderSyncCoordinator.isSyncAllowed(t3, zone, config, source));
    }

    @Test
    public void testCalculateQuietHoursEndResumesAt8AmPlusJitter() {
        AppConfig.OrderSyncConfig config = new AppConfig.OrderSyncConfig();
        config.setQuietHoursStart(0);
        config.setQuietHoursEnd(8);

        LocalDate date = LocalDate.of(2026, 10, 24);
        Instant tNight = date.atTime(1, 30).atZone(zone).toInstant();

        Instant resumeAt = OrderSyncCoordinator.calculateQuietHoursEnd(tNight, zone, config, null);
        ZonedDateTime resumeZdt = resumeAt.atZone(zone);

        assertEquals(date, resumeZdt.toLocalDate());
        assertEquals(8, resumeZdt.getHour());
        // Jitter is between 60s and 300s (1 to 5 min)
        assertTrue(resumeZdt.getMinute() >= 1 && resumeZdt.getMinute() <= 5,
                "Resume minute should include jitter: " + resumeZdt.getMinute());
    }

    @Test
    public void testCalculateNextSyncDelaySeconds() {
        AppConfig.OrderSyncConfig config = new AppConfig.OrderSyncConfig();
        config.setMinIntervalMinutes(8);
        config.setMaxIntervalMinutes(15);

        AppConfig.OrderSourceConfig source = new AppConfig.OrderSourceConfig();

        for (int i = 0; i < 20; i++) {
            long delay = OrderSyncCoordinator.calculateNextSyncDelaySeconds(source, config);
            assertTrue(delay >= 8 * 60 && delay <= 15 * 60, "Delay should be within 8-15 min: " + delay);
        }
    }
}
