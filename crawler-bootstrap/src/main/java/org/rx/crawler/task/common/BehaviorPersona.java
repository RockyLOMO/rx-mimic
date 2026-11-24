package org.rx.crawler.task.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;

/**
 * 行为人格（behavioral persona）。
 *
 * <p>解决的问题：旧实现所有延迟都用 {@code ThreadLocalRandom.nextInt(min, max)}，即
 * <b>均匀分布</b>。真人操作的事件间隔、击键间隔、鼠标步距是重尾的对数正态分布。
 * 风控不需要找任何"自动化标志"，只要对 mousemove / keydown 的间隔序列做一次
 * KS 检验或看直方图形状就能把均匀分布挑出来。另外"每次会话都完全重新随机"
 * 也是指纹：真人的打字速度、鼠标速度、微修正习惯是<b>稳定的人格特征</b>，
 * 不会每天早上换一套。</p>
 *
 * <p>因此这里用 Chrome profile 目录做稳定种子：同一个用户目录跨会话保持同一套
 * 行为参数，不同 profile 之间自然分化。所有取值仍是随机的，只是分布与人格稳定。</p>
 *
 * <p>纯 JDK、无外部依赖、可单元测试；不修改任何页面 JS 环境。</p>
 */
public final class BehaviorPersona {

    private final Random random;
    private final double paceFactor;
    private final double curvature;
    private final double jitterScale;
    private final double stepBias;
    private final double microCorrectionRate;
    private final double distractionRate;
    private final double typoRate;

    private BehaviorPersona(long seed) {
        this.random = new Random(seed);
        this.paceFactor = 0.82 + random.nextDouble() * 0.42;      // 0.82 ~ 1.24
        this.curvature = 0.55 + random.nextDouble() * 0.90;       // 0.55 ~ 1.45
        this.jitterScale = 0.75 + random.nextDouble() * 0.60;     // 0.75 ~ 1.35
        this.stepBias = 0.85 + random.nextDouble() * 0.35;        // 0.85 ~ 1.20
        this.microCorrectionRate = 0.18 + random.nextDouble() * 0.28;
        this.distractionRate = 0.010 + random.nextDouble() * 0.025;
        this.typoRate = 0.004 + random.nextDouble() * 0.016;
    }

    /**
     * 由 Chrome user-data-dir 派生稳定人格。同一目录 => 同一串行为参数。
     */
    public static BehaviorPersona derive(String profileKey) {
        String key = profileKey == null || profileKey.isBlank() ? "rx-mimic/common" : profileKey.trim();
        long seed;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            seed = 0L;
            for (int i = 0; i < 8; i++) {
                seed = (seed << 8) | (digest[i] & 0xFFL);
            }
        } catch (Exception e) {
            seed = key.hashCode();
        }
        return new BehaviorPersona(seed);
    }

    /**
     * 截断对数正态：中位数落在几何均值附近，两端重尾但严格夹在 [min, max]。
     * 均匀分布是"机器"，对数正态 + 偶发长停顿才是"人"。
     */
    public synchronized int boundedLognormal(int min, int max) {
        if (max <= min) {
            return Math.max(0, min);
        }
        double lo = min > 0 ? min : Math.max(1.0, max / 20.0);
        double hi = Math.max(lo + 1.0, max);
        double logLo = Math.log(lo);
        double logHi = Math.log(hi);
        double mu = (logLo + logHi) / 2.0;
        double sigma = (logHi - logLo) / 5.0; // ±2.5σ ≈ 98.8% 落在区间内，超出的夹到边界
        double sample = Math.exp(mu + sigma * random.nextGaussian()) * paceFactor;
        return (int) Math.max(min, Math.min(max, Math.round(sample)));
    }

    /** 高斯抖动，用于轨迹控制点与坐标微扰。 */
    public synchronized double gaussian(double center, double stdDev) {
        return center + random.nextGaussian() * stdDev * jitterScale;
    }

    public synchronized double nextDouble(double min, double max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextDouble() * (max - min);
    }

    public synchronized boolean chance(double rate) {
        return random.nextDouble() < rate;
    }

    /** 步数随距离增长，而不是每次都在 24~56 里均匀抽一个。 */
    public int movementSteps(int min, int max, double distance) {
        int base = boundedLognormal(min, max);
        int distanceDriven = (int) Math.round(distance / 9.0);
        int steps = (int) Math.round((base * 0.6 + distanceDriven * 0.4) * stepBias);
        return Math.max(6, Math.min(90, steps));
    }

    /**
     * minimum-jerk 时间重映射：均匀采样点经 s(t)=10t³-15t⁴+6t⁵ 后，
     * 速度呈钟形（起步慢、中段快、收尾慢），符合人手运动的 5 次最小加加速度模型。
     */
    public static double minJerk(double t) {
        double v = Math.max(0.0, Math.min(1.0, t));
        return v * v * v * (10.0 + v * (-15.0 + 6.0 * v));
    }

    public double curvature() {
        return curvature;
    }

    public double jitterScale() {
        return jitterScale;
    }

    public double microCorrectionRate() {
        return microCorrectionRate;
    }

    public double distractionRate() {
        return distractionRate;
    }

    public double typoRate() {
        return typoRate;
    }

    public boolean shouldMicroCorrect() {
        return chance(microCorrectionRate);
    }

    public boolean distracted() {
        return chance(distractionRate);
    }

    public boolean shouldTypo() {
        return chance(typoRate);
    }

    /** 诊断日志用，不含任何可反推种子的信息。 */
    public String summary() {
        return String.format(Locale.ROOT,
                "pace=%.2f curve=%.2f jitter=%.2f steps=%.2f micro=%.2f distraction=%.3f typo=%.3f",
                paceFactor, curvature, jitterScale, stepBias,
                microCorrectionRate, distractionRate, typoRate);
    }
}
