package org.rx.crawler.task.price;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.LoginNotificationContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失效和滑动验证失败按平台、按业务日计数。
 * 达到配置次数后，该平台停止后续商品访问。同一次操作 60 秒内不重复计数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceRiskGate {
    private static final long DEDUPE_MILLIS = 60_000L;

    private final PriceWatchStore store;
    private final AppConfig appConfig;
    private final ConcurrentHashMap<String, Long> recent = new ConcurrentHashMap<String, Long>();

    public synchronized void record(LoginNotificationContext context, String kind) {
        if (context == null) {
            return;
        }
        int platform = platformOf(context.getTaskType());
        String token = platform + "|" + (kind == null ? "login" : kind) + "|"
                + empty(context.getTaskType()) + "|" + empty(context.getProfileName());
        long now = System.currentTimeMillis();
        Long previous = recent.put(token, now);
        if (previous != null && now - previous < DEDUPE_MILLIS) {
            recent.put(token, previous);
            return;
        }
        String day = businessDate(Instant.ofEpochMilli(now));
        int count = store.incrementRisk(platform, day);
        int limit = limit();
        if (limit > 0 && count >= limit) {
            log.warn("Stop product visits after login or slider failures, platform={}, count={}, limit={}, day={}",
                    platform == 0 ? "unknown" : PriceCodes.platformKey(platform), count, limit, day);
        }
    }

    public boolean stopped(int platform, Instant now) {
        int limit = limit();
        if (limit <= 0) {
            return false;
        }
        String day = businessDate(now);
        if (store.riskCount(0, day) >= limit) {
            return true;
        }
        return platform != 0 && store.riskCount(platform, day) >= limit;
    }

    public int count(int platform, Instant now) {
        return store.riskCount(platform, businessDate(now));
    }

    /**
     * 调用者必须先验证该平台的真实登录会话，且在验证前采样 checkedAt 和风险计数。
     * 仅恢复仍属于当前业务日、且验证期间未增加风险的指定平台；返回恢复前计数。
     * 未知平台风险与访问预算始终保留，不会因平台登录恢复而放行。
     */
    public synchronized int recoverAfterVerifiedLogin(int platform, Instant checkedAt, int expectedRiskCount) {
        if (platform != PriceCodes.JD && platform != PriceCodes.TB) {
            throw new IllegalArgumentException("Supported platform required for risk recovery");
        }
        if (checkedAt == null || expectedRiskCount < 0) {
            throw new IllegalArgumentException("Verification time and non-negative risk count required");
        }
        String day = businessDate(checkedAt);
        if (!day.equals(businessDate(Instant.now()))) {
            throw new IllegalStateException("Business date changed; verify login again before risk recovery");
        }
        if (!store.clearRiskIfUnchanged(platform, day, expectedRiskCount)) {
            throw new IllegalStateException("Risk count changed; verify login again before risk recovery");
        }
        String prefix = platform + "|";
        recent.keySet().removeIf(key -> key.startsWith(prefix));
        log.warn("Product visit risk recovered after verified login, platform={}, previousCount={}, day={}",
                PriceCodes.platformKey(platform), expectedRiskCount, day);
        return expectedRiskCount;
    }

    public int limit() {
        AppConfig.PriceWatchConfig config = config();
        return Math.max(0, config.getDailyMaxLoginOrSliderFailures());
    }

    static int platformOf(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return 0;
        }
        String value = taskType.toLowerCase();
        if (value.contains("tb") || value.contains("taobao")) {
            return PriceCodes.TB;
        }
        if (value.startsWith("jd") || value.contains("promotion")) {
            return PriceCodes.JD;
        }
        return 0;
    }

    private String businessDate(Instant now) {
        AppConfig.PriceWatchConfig config = config();
        PriceWatchClock clock = PriceWatchClock.parse(config.getTimezone(), config.getWindowStart(), config.getWindowEnd());
        if (clock.valid()) {
            return clock.businessDate(now).toString();
        }
        return LocalDate.ofInstant(now, ZoneId.of("Asia/Shanghai")).toString();
    }

    private AppConfig.PriceWatchConfig config() {
        AppConfig.PriceWatchConfig config = appConfig.getCustom() == null ? null : appConfig.getCustom().getPriceWatch();
        return config == null ? new AppConfig.PriceWatchConfig() : config;
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
