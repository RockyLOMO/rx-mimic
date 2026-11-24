package org.rx.crawler.service.impl;

import org.junit.jupiter.api.Test;
import org.rx.crawler.service.impl.HumanSlideTrack.Sample;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanSlideTrackTest {
    private static final double START_X = 1061.2;
    private static final double START_Y = 348;

    @Test
    void aimedDragStartsFromRest() {
        Random random = new Random(7);
        for (int trial = 0; trial < 30; trial++) {
            double endX = START_X + 50 + random.nextInt(170);
            List<Sample> track = HumanSlideTrack.planAimed(START_X, START_Y, endX, START_Y + 1, random);
            double distance = endX - START_X;

            // 头 70 毫秒走的路程：人从静止起步，这一段只占很小一部分。
            double early = 0;
            long clock = 0;
            long totalTime = 0;
            long peakAt = 0;
            double peakSpeed = 0;
            double lastX = START_X;
            for (Sample sample : track) {
                double speed = Math.abs(sample.x - lastX) / sample.delayMillis;
                if (speed > peakSpeed) {
                    peakSpeed = speed;
                    peakAt = clock;
                }
                clock += sample.delayMillis;
                if (clock <= 70) {
                    early = sample.x - START_X;
                }
                lastX = sample.x;
                totalTime += sample.delayMillis;
            }
            String trialName = "drag of " + (int) distance + " px, trial " + trial;
            assertTrue(early < distance * 0.08, trialName + ": covered " + early + " px within 70 ms");
            // 最快的一段在全程的前 15% 到 60% 之间，不在刚按下的时候。
            assertTrue(peakAt > totalTime * 0.15 && peakAt < totalTime * 0.6,
                    trialName + ": peak speed at " + peakAt + " of " + totalTime + " ms");
            // 停在目标跟前：多半差一点没到，最多冲过一点。剩下的交给松开前的修正。
            double stop = track.get(track.size() - 1).x - endX;
            assertTrue(stop > -4.6 && stop < 2.6, trialName + ": stopped " + stop + " px from the target");
            // 最后一帧带着「停下来看一眼」的停顿。
            assertTrue(track.get(track.size() - 1).delayMillis >= 150, trialName + ": no pause at the end of the drag");
            // 全程时长随距离变长；人拖一段 60 到 220 像素的拼图，加上停下看一眼，大约 0.6 到 1.6 秒。
            long limit = Math.round((420 + distance * 1.6 + 200 + 330) * 1.2);
            assertTrue(totalTime >= 450 && totalTime <= limit, trialName + ": whole drag took " + totalTime + " ms");
        }
    }

    @Test
    void aimedDragStaysCloseToTheTrackLine() {
        Random random = new Random(11);
        for (int trial = 0; trial < 30; trial++) {
            List<Sample> track = HumanSlideTrack.planAimed(START_X, START_Y, START_X + 120, START_Y + 1, random);
            for (Sample sample : track) {
                assertTrue(Math.abs(sample.y - START_Y) <= 7, "vertical strays " + (sample.y - START_Y) + " px");
                assertTrue(sample.x > START_X - 1 && sample.x < START_X + 126, "horizontal strays: " + sample.x);
            }
        }
    }

    @Test
    void aimedDragOfNoDistanceIsASingleSample() {
        List<Sample> track = HumanSlideTrack.planAimed(100, 100, 102, 101, new Random(3));
        assertEquals(1, track.size());
        assertEquals(102, track.get(0).x, 0.0001);
    }

    /**
     * WindowsMouse 在按下之前先用 approach 把系统光标连续移开、再连续移回来。
     * 这里锁住「没有一步跳点」：任何一次采样跨过 60px 都会被京东页面当成瞬移。
     */
    @Test
    void approachHasNoTeleport() {
        Random random = new Random(23);
        for (int trial = 0; trial < 30; trial++) {
            double toX = START_X - 200 - random.nextInt(320);
            double toY = START_Y - 90 - random.nextInt(220);
            List<Sample> track = HumanSlideTrack.approach(START_X, START_Y, toX, toY, random);
            assertTrue(track.size() >= 20, "trial " + trial + ": only " + track.size() + " samples");
            Sample last = track.get(track.size() - 1);
            assertEquals(toX, last.x, 0.0001, "trial " + trial + ": did not land on the approach origin");
            assertEquals(toY, last.y, 0.0001, "trial " + trial + ": did not land on the approach origin");
            double prevX = START_X;
            double prevY = START_Y;
            long total = 0;
            for (Sample sample : track) {
                double jump = Math.hypot(sample.x - prevX, sample.y - prevY);
                assertTrue(jump < 60, "trial " + trial + ": teleport of " + jump + " px");
                assertTrue(sample.delayMillis >= 8, "trial " + trial + ": impossible sample delay");
                prevX = sample.x;
                prevY = sample.y;
                total += sample.delayMillis;
            }
            assertTrue(total >= 400 && total <= 1600,
                    "trial " + trial + ": approach took " + total + " ms");
        }
    }

    @Test
    void approachOfNoDistanceIsASingleSample() {
        List<Sample> track = HumanSlideTrack.approach(100, 100, 100.4, 100.2, new Random(5));
        assertEquals(1, track.size());
        assertEquals(100.4, track.get(0).x, 0.0001);
        assertEquals(100.2, track.get(0).y, 0.0001);
    }
}
