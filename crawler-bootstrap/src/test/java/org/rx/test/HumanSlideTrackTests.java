package org.rx.test;

import org.junit.jupiter.api.Test;
import org.rx.crawler.service.impl.HumanSlideTrack;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class HumanSlideTrackTests {
    @Test
    public void slideTrackShouldAccelerateThenSettleWithYjitterAndOvershoot() {
        for (int seed = 1; seed <= 40; seed++) {
            double startX = 420;
            double startY = 360;
            double endX = 690;
            double endY = 362;
            List<HumanSlideTrack.Sample> samples = HumanSlideTrack.plan(startX, startY, endX, endY, new Random(seed));
            assertTrue(samples.size() >= 30, "seed=" + seed);
            HumanSlideTrack.Sample first = samples.get(0);
            double distance = endX - startX;
            assertTrue(first.x - startX > 0 && first.x - startX < distance * 0.08, "first step seed=" + seed);

            int elapsed = 0;
            int halfTime = -1;
            int doneTime = -1;
            double sumY = 0;
            double sumY2 = 0;
            int uniqueDelays = 0;
            int prevDelay = -1;
            boolean reversed = false;
            double prevX = startX;
            double maxAbsY = 0;
            for (int i = 0; i < samples.size(); i++) {
                HumanSlideTrack.Sample sample = samples.get(i);
                elapsed += sample.delayMillis;
                double progress = (sample.x - startX) / distance;
                if (halfTime < 0 && progress >= 0.5) {
                    halfTime = elapsed;
                }
                if (doneTime < 0 && progress >= 0.98) {
                    doneTime = elapsed;
                }
                double lineY = startY + (endY - startY) * Math.min(1, Math.max(0, progress));
                maxAbsY = Math.max(maxAbsY, Math.abs(sample.y - lineY));
                sumY += sample.y;
                sumY2 += sample.y * sample.y;
                if (sample.delayMillis != prevDelay) {
                    uniqueDelays++;
                    prevDelay = sample.delayMillis;
                }
                if (sample.x < prevX - 0.4) {
                    reversed = true;
                }
                prevX = sample.x;
            }
            double meanY = sumY / samples.size();
            double variance = sumY2 / samples.size() - meanY * meanY;
            assertTrue(variance > 0.5, "y variance seed=" + seed + " var=" + variance);
            assertTrue(maxAbsY >= 2.0 && maxAbsY < 9.0, "y drift seed=" + seed + " max=" + maxAbsY);
            assertTrue(halfTime > 0 && doneTime > halfTime, "timing seed=" + seed);
            assertTrue(halfTime < doneTime - halfTime, "slow end seed=" + seed + " half=" + halfTime + " done=" + doneTime);
            assertTrue(reversed, "overshoot seed=" + seed);
            assertTrue(uniqueDelays >= 4, "delay entropy seed=" + seed);
            assertTrue(elapsed >= 500 && elapsed <= 1600, "duration seed=" + seed + " ms=" + elapsed);
            HumanSlideTrack.Sample last = samples.get(samples.size() - 1);
            assertTrue(Math.abs(last.x - endX) < 0.2, "release x seed=" + seed);
            assertTrue(Math.abs(last.y - endY) < 0.2, "release y seed=" + seed);
        }
    }
}
