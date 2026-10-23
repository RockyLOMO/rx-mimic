package org.rx.crawler.task.price;

import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.TaskApiException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/** One admission boundary for foreground and background product-data visits. */
@Service
public class ProductVisitBudget {
    private static final ThreadLocal<Boolean> BACKGROUND = new ThreadLocal<>();
    private final PriceWatchStore store;
    private final AppConfig config;
    private final PriceRiskGate risk;

    public ProductVisitBudget(PriceWatchStore store, AppConfig config, PriceRiskGate risk) {
        this.store = store;
        this.config = config;
        this.risk = risk;
    }

    public synchronized void reserve(int platform, boolean background) {
        reserve(platform, background, Instant.now());
    }

    synchronized void reserve(int platform, boolean background, Instant now) {
        if (platform != PriceCodes.JD && platform != PriceCodes.TB) throw reject("INVALID_PLATFORM");
        AppConfig.PriceWatchConfig settings = config.getCustom().getPriceWatch();
        PriceWatchClock clock = PriceWatchClock.parse(settings.getTimezone(), settings.getWindowStart(), settings.getWindowEnd());
        if (!clock.valid()) throw reject("INVALID_VISIT_WINDOW");
        if (background && !clock.open(now)) throw reject("VISIT_WINDOW_CLOSED");
        if (risk.stopped(platform, now)) throw reject("RISK_STOP");

        int totalLimit = settings.getDailyTotalVisits();
        int backgroundLimit = settings.getDailyBackgroundVisits();
        int foregroundReserve = settings.getForegroundReserveVisits();
        Map<String, Integer> platformLimits = settings.getDailyMaxProductVisits();
        Integer configured = platformLimits == null ? null : platformLimits.get(PriceCodes.platformKey(platform));
        int platformLimit = configured == null ? 0 : configured;
        if (totalLimit <= 0 || platformLimit <= 0 || backgroundLimit < 0 || foregroundReserve < 0)
            throw reject("DAILY_VISIT_LIMIT");

        String day = clock.businessDate(now).toString();
        int globalUsed = store.visitCount(0, day);
        int backgroundUsed = store.visitCount(-1, day);
        int platformUsed = store.visitCount(platform, day);
        if (globalUsed >= totalLimit || platformUsed >= platformLimit) throw reject("DAILY_VISIT_LIMIT");
        if (background) {
            long foregroundUsed = Math.max(0L, (long) globalUsed - backgroundUsed);
            long reservedRemaining = Math.max(0L, (long) foregroundReserve - foregroundUsed);
            long available = Math.min((long) backgroundLimit - backgroundUsed,
                    (long) totalLimit - globalUsed - reservedRemaining);
            if (available <= 0) throw reject("DAILY_VISIT_LIMIT");
        }

        // All callers use this component's lock; check every dimension before consuming any counter.
        if (store.reserveVisit(0, day, totalLimit) < 0
                || store.reserveVisit(platform, day, platformLimit) < 0
                || (background && store.reserveVisit(-1, day, backgroundLimit) < 0))
            throw reject("DAILY_VISIT_LIMIT");
    }

    public static BackgroundScope enterBackground() {
        Boolean previous = BACKGROUND.get();
        BACKGROUND.set(Boolean.TRUE);
        return new BackgroundScope(previous);
    }

    public static boolean isBackground() {
        return Boolean.TRUE.equals(BACKGROUND.get());
    }

    public static final class BackgroundScope implements AutoCloseable {
        private final Boolean previous;
        private boolean closed;

        private BackgroundScope(Boolean previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) BACKGROUND.remove(); else BACKGROUND.set(previous);
        }
    }

    private static TaskApiException reject(String code) {
        return new TaskApiException(429, code, code);
    }
}
