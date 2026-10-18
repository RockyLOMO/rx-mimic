package org.rx.crawler.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 滑块轨迹：前段快、后段慢，Y 轴有弧度和噪声，末端过冲再拉回。
 * 每次随机，不回放固定轨迹。
 */
public final class HumanSlideTrack {
    public static final class Sample {
        public final double x;
        public final double y;
        public final int delayMillis;

        public Sample(double x, double y, int delayMillis) {
            this.x = x;
            this.y = y;
            this.delayMillis = delayMillis;
        }
    }

    private HumanSlideTrack() {
    }

    public static List<Sample> plan(double startX, double startY, double endX, double endY, Random random) {
        double distance = Math.hypot(endX - startX, endY - startY);
        int points = 48 + random.nextInt(16);
        if (distance < 120) {
            points = 32 + random.nextInt(10);
        }
        double bend = 1.65 + random.nextDouble() * 0.45;
        double yAmp = 3.0 + random.nextDouble() * 3.0;
        double ySign = random.nextBoolean() ? 1.0 : -1.0;
        double dir = endX >= startX ? 1.0 : -1.0;
        List<Sample> samples = new ArrayList<Sample>(points + 10);
        double lastX = startX;
        boolean corrected = false;
        for (int i = 1; i <= points; i++) {
            double u = i / (double) points;
            double ease = 1.0 - Math.pow(1.0 - u, bend);
            double x = startX + (endX - startX) * ease;
            double wobble = (random.nextDouble() - 0.5) * 0.8;
            x += wobble;
            if (dir > 0 && x < lastX) {
                x = lastX + 0.35;
            } else if (dir < 0 && x > lastX) {
                x = lastX - 0.35;
            }
            if (!corrected && u > 0.84 && u < 0.96 && random.nextInt(4) == 0) {
                x = lastX - dir * (1.0 + random.nextDouble() * 1.5);
                corrected = true;
            }
            double baseY = startY + (endY - startY) * ease;
            // 偏移在前中段保持，最后才回到轨道，避免后半段采样过密把抖动平均掉。
            double shaped = Math.sin(Math.pow(Math.min(ease, 1.0), 0.6) * Math.PI);
            double arc = shaped * yAmp * ySign;
            double noise = (random.nextDouble() - 0.5) * 2.2;
            double y = clamp(baseY + arc + noise, baseY - 8.0, baseY + 8.0);
            samples.add(new Sample(x, y, delayFor(ease, random)));
            lastX = x;
        }

        if (samples.size() > 10) {
            int at = samples.size() * 55 / 100 + random.nextInt(Math.max(1, samples.size() / 8));
            if (at >= samples.size()) {
                at = samples.size() - 1;
            }
            Sample paused = samples.get(at);
            samples.set(at, new Sample(paused.x, paused.y, paused.delayMillis + 40 + random.nextInt(70)));
        }

        double overshoot = dir * (2.0 + random.nextDouble() * 3.0);
        int outSteps = 3;
        for (int i = 1; i <= outSteps; i++) {
            double x = endX + overshoot * i / outSteps;
            double y = endY + (random.nextDouble() - 0.5) * 1.4;
            samples.add(new Sample(x, y, 12 + random.nextInt(8)));
        }
        int backSteps = 4;
        for (int i = 1; i <= backSteps; i++) {
            double x = (endX + overshoot) + (endX - (endX + overshoot)) * i / (double) backSteps;
            double y = endY + (random.nextDouble() - 0.5) * 1.2;
            int delay = 14 + random.nextInt(12);
            if (i == backSteps) {
                samples.add(new Sample(endX, endY, delay));
            } else {
                samples.add(new Sample(x, y, delay));
            }
        }
        scaleDuration(samples, random, distance);
        return samples;
    }

    private static int delayFor(double ease, Random random) {
        if (ease < 0.35) {
            return 8 + random.nextInt(6);
        }
        if (ease < 0.75) {
            return 10 + random.nextInt(8);
        }
        return 16 + random.nextInt(12);
    }

    private static void scaleDuration(List<Sample> samples, Random random, double distance) {
        int total = 0;
        for (int i = 0; i < samples.size(); i++) {
            total += samples.get(i).delayMillis;
        }
        if (total < 1) {
            return;
        }
        int target = 680 + random.nextInt(280);
        if (distance > 280) {
            target += 100;
        }
        double factor = target / (double) total;
        if (factor < 0.55) {
            factor = 0.55;
        } else if (factor > 1.8) {
            factor = 1.8;
        }
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            int delay = (int) Math.round(sample.delayMillis * factor);
            if (delay < 6) {
                delay = 6;
            }
            samples.set(i, new Sample(sample.x, sample.y, delay));
        }
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
