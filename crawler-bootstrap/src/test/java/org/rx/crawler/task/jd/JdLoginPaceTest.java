package org.rx.crawler.task.jd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实网登录的失败冷却：京东账号当晚累计多次拼图失败后会被会话级风控拒绝，
 * 继续跑只会加深风险分。这里只测纯逻辑和落盘，不启动浏览器。
 */
class JdLoginPaceTest {

    private static final long HOUR = 3600000L;

    @Test
    void noFailureHistoryNeverBlocks() {
        assertFalse(JdUnionPasswordLoginLiveTest.withinCooldown(0, 1000L * HOUR, 6 * HOUR));
    }

    @Test
    void cooldownDisabledNeverBlocks() {
        assertTrue(JdUnionPasswordLoginLiveTest.withinCooldown(1000, 2000, 6 * HOUR));
        assertFalse(JdUnionPasswordLoginLiveTest.withinCooldown(1000, 2000, 0));
    }

    @Test
    void onlyRecentFailuresBlock() {
        long now = 1000L * HOUR;
        assertTrue(JdUnionPasswordLoginLiveTest.withinCooldown(now - HOUR, now, 6 * HOUR));
        assertFalse(JdUnionPasswordLoginLiveTest.withinCooldown(now - 7 * HOUR, now, 6 * HOUR));
        assertFalse(JdUnionPasswordLoginLiveTest.withinCooldown(now, now, 6 * HOUR),
                "exactly at the boundary the next attempt is allowed");
    }

    @Test
    void clockSkewOrCorruptMarkerIsNotTreatedAsAFailure() {
        assertFalse(JdUnionPasswordLoginLiveTest.withinCooldown(2000L * HOUR, 1000L * HOUR, 6 * HOUR));
    }

    @Test
    void failureMarkerRoundTrips(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("last-failed-login.txt");
        assertEquals(0, JdUnionPasswordLoginLiveTest.readLastFailure(marker));
        JdUnionPasswordLoginLiveTest.recordFailure(marker, 123456789L);
        assertTrue(Files.isRegularFile(marker));
        assertEquals(123456789L, JdUnionPasswordLoginLiveTest.readLastFailure(marker));
    }

    @Test
    void corruptMarkerIsIgnored(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("last-failed-login.txt");
        Files.writeString(marker, "not-a-number");
        assertEquals(0, JdUnionPasswordLoginLiveTest.readLastFailure(marker));
    }
}
