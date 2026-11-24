package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 行为人格的统计特性与稳定性，纯单元测试，不启动浏览器。 */
public class BehaviorPersonaTest {

    @Test
    public void sameProfileKeepsSamePersonaAcrossSessions() {
        BehaviorPersona a = BehaviorPersona.derive("C:\\Users\\X\\chrome\\common");
        BehaviorPersona b = BehaviorPersona.derive("C:\\Users\\X\\chrome\\common");
        assertEquals(a.summary(), b.summary(), "同一个 profile 必须保持同一套行为特征");
        assertNotEquals(a.summary(), BehaviorPersona.derive("C:\\Users\\X\\chrome\\jd-main").summary());
    }

    @Test
    public void boundedLognormalNeverEscapesRange() {
        BehaviorPersona p = BehaviorPersona.derive("range-test");
        for (int i = 0; i < 5000; i++) {
            int v = p.boundedLognormal(8, 28);
            assertTrue(v >= 8 && v <= 28, "out of range: " + v);
            int zeroBased = p.boundedLognormal(0, 320);
            assertTrue(zeroBased >= 0 && zeroBased <= 320, "out of range: " + zeroBased);
        }
    }

    @Test
    public void distributionIsHeavyTailedNotUniform() {
        BehaviorPersona p = BehaviorPersona.derive("distribution-test");
        long sum = 0;
        int draws = 20000;
        for (int i = 0; i < draws; i++) {
            sum += p.boundedLognormal(10, 1000);
        }
        double mean = (double) sum / draws;
        // 均匀分布的均值应接近 505；对数正态（几何均值 100）应显著低于它。
        assertTrue(mean < 260, "inter-event timing must be heavy-tailed, mean=" + mean);
        assertTrue(mean > 60, "mean should not collapse to the lower bound, mean=" + mean);
    }

    @Test
    public void minJerkIsMonotonicWithFixedEndpoints() {
        assertEquals(0.0, BehaviorPersona.minJerk(0.0), 1e-9);
        assertEquals(1.0, BehaviorPersona.minJerk(1.0), 1e-9);
        assertEquals(0.5, BehaviorPersona.minJerk(0.5), 1e-9);
        double previous = -1;
        for (int i = 0; i <= 100; i++) {
            double value = BehaviorPersona.minJerk(i / 100.0);
            assertTrue(value >= previous, "min-jerk must be monotonic");
            previous = value;
        }
        // 起步慢、中段快：前半程位移小于 50%
        assertTrue(BehaviorPersona.minJerk(0.25) < 0.25);
    }

    @Test
    public void movementStepsScaleWithDistanceAndStayBounded() {
        BehaviorPersona p = BehaviorPersona.derive("steps-test");
        for (int i = 0; i < 2000; i++) {
            int near = p.movementSteps(24, 56, 20);
            int far = p.movementSteps(24, 56, 1200);
            assertTrue(near >= 6 && near <= 90, "near=" + near);
            assertTrue(far >= 6 && far <= 90, "far=" + far);
        }
        assertFalse(p.summary().isBlank());
    }
}
