package org.rx.crawler.task.price;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.TaskApiException;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProductVisitBudgetTest {
    private static final Instant NOON = at(2026, 10, 22, 12, 0);
    private static final String DAY = "2026-10-22";
    @TempDir Path directory;

    @Test
    void backgroundCannotConsumeUnusedForegroundReserve() throws Exception {
        try (Fixture f = fixture(10, 10, 4, 100)) {
            for (int i = 0; i < 6; i++) f.budget.reserve(PriceCodes.JD, true, NOON);
            denied(() -> f.budget.reserve(PriceCodes.TB, true, NOON), "DAILY_VISIT_LIMIT");
            assertEquals(6, f.store.visitCount(0, DAY));
            assertEquals(6, f.store.visitCount(-1, DAY));
            assertEquals(0, f.store.visitCount(PriceCodes.TB, DAY), "denied visits must consume no dimension");
            for (int i = 0; i < 4; i++) f.budget.reserve(PriceCodes.TB, false, NOON);
            denied(() -> f.budget.reserve(PriceCodes.JD, false, NOON), "DAILY_VISIT_LIMIT");
            assertEquals(10, f.store.visitCount(0, DAY));
            assertEquals(6, f.store.visitCount(-1, DAY));
        }
    }

    @Test
    void foregroundAboveItsReserveReducesRemainingBackgroundCapacity() throws Exception {
        try (Fixture f = fixture(10, 8, 4, 100)) {
            for (int i = 0; i < 5; i++) f.budget.reserve(PriceCodes.JD, false, NOON);
            for (int i = 0; i < 5; i++) f.budget.reserve(PriceCodes.TB, true, NOON);
            denied(() -> f.budget.reserve(PriceCodes.TB, true, NOON), "DAILY_VISIT_LIMIT");
            assertEquals(10, f.store.visitCount(0, DAY));
            assertEquals(5, f.store.visitCount(-1, DAY));
        }
    }

    @Test
    void backgroundSublimitAndPlatformLimitApplyBeforeAnyCounterChanges() throws Exception {
        try (Fixture f = fixture(20, 2, 0, 2)) {
            f.budget.reserve(PriceCodes.JD, true, NOON);
            f.budget.reserve(PriceCodes.JD, true, NOON);
            denied(() -> f.budget.reserve(PriceCodes.TB, true, NOON), "DAILY_VISIT_LIMIT");
            denied(() -> f.budget.reserve(PriceCodes.JD, false, NOON), "DAILY_VISIT_LIMIT");
            assertEquals(2, f.store.visitCount(0, DAY));
            assertEquals(0, f.store.visitCount(PriceCodes.TB, DAY));
            f.budget.reserve(PriceCodes.TB, false, NOON);
            assertEquals(3, f.store.visitCount(0, DAY));
            assertEquals(2, f.store.visitCount(-1, DAY));
        }
    }

    @Test
    void businessDayResetsAtSixAndBackgroundObeysCrossMidnightWindow() throws Exception {
        try (Fixture f = fixture(1, 1, 0, 10)) {
            f.budget.reserve(PriceCodes.JD, true, at(2026, 10, 22, 0, 59));
            assertEquals(1, f.store.visitCount(0, "2026-10-21"));
            denied(() -> f.budget.reserve(PriceCodes.JD, true, at(2026, 10, 22, 1, 0)), "VISIT_WINDOW_CLOSED");
            denied(() -> f.budget.reserve(PriceCodes.JD, true, at(2026, 10, 22, 5, 59)), "VISIT_WINDOW_CLOSED");
            f.budget.reserve(PriceCodes.JD, true, at(2026, 10, 22, 6, 0));
            assertEquals(1, f.store.visitCount(0, DAY));
        }
    }

    @Test
    void foregroundOutsideWindowIsAllowedButInvalidWindowAndRiskFailClosed() throws Exception {
        try (Fixture f = fixture(10, 10, 0, 100)) {
            f.budget.reserve(PriceCodes.JD, false, at(2026, 10, 22, 2, 0));
            assertEquals(1, f.store.visitCount(0, "2026-10-21"));
            f.config.getCustom().getPriceWatch().setTimezone("Invalid/Zone");
            denied(() -> f.budget.reserve(PriceCodes.JD, false, NOON), "INVALID_VISIT_WINDOW");
            f.config.getCustom().getPriceWatch().setTimezone("Asia/Shanghai");
            for (int i = 0; i < 3; i++) f.store.incrementRisk(PriceCodes.JD, DAY);
            denied(() -> f.budget.reserve(PriceCodes.JD, false, NOON), "RISK_STOP");
            assertEquals(0, f.store.visitCount(0, DAY));
            f.budget.reserve(PriceCodes.TB, false, NOON);
            assertEquals(1, f.store.visitCount(0, DAY));
        }
    }

    @Test
    void unknownPlatformAndUnconfiguredPlatformNeverConsumeBudget() throws Exception {
        try (Fixture f = fixture(10, 10, 0, 100)) {
            denied(() -> f.budget.reserve(0, false, NOON), "INVALID_PLATFORM");
            f.config.getCustom().getPriceWatch().getDailyMaxProductVisits().remove("tb");
            denied(() -> f.budget.reserve(PriceCodes.TB, false, NOON), "DAILY_VISIT_LIMIT");
            assertEquals(0, f.store.visitCount(0, DAY));
        }
    }

    @Test
    void synchronizedAdmissionDoesNotOverbookTheSharedProfile() throws Exception {
        try (Fixture f = fixture(10, 10, 0, 100)) {
            var executor = Executors.newFixedThreadPool(4);
            try {
                List<Future<Boolean>> calls = new ArrayList<>();
                for (int i = 0; i < 24; i++) calls.add(executor.submit(() -> {
                    try { f.budget.reserve(PriceCodes.JD, true, NOON); return true; }
                    catch (TaskApiException e) { assertEquals("DAILY_VISIT_LIMIT", e.getCode()); return false; }
                }));
                int admitted = 0;
                for (Future<Boolean> call : calls) if (call.get(10, TimeUnit.SECONDS)) admitted++;
                assertEquals(10, admitted);
                assertEquals(10, f.store.visitCount(0, DAY));
                assertEquals(10, f.store.visitCount(-1, DAY));
                assertEquals(10, f.store.visitCount(PriceCodes.JD, DAY));
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void nestedBackgroundScopeRestoresStateAndDoesNotLeakToOtherThreads() throws Exception {
        assertFalse(ProductVisitBudget.isBackground());
        try (var outer = ProductVisitBudget.enterBackground()) {
            assertTrue(ProductVisitBudget.isBackground());
            try (var inner = ProductVisitBudget.enterBackground()) {
                assertTrue(ProductVisitBudget.isBackground());
            }
            assertTrue(ProductVisitBudget.isBackground());
            var other = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread thread = new Thread(() -> other.set(ProductVisitBudget.isBackground()));
            thread.start(); thread.join(1000);
            assertFalse(thread.isAlive());
            assertFalse(other.get());
        }
        assertFalse(ProductVisitBudget.isBackground());
    }

    private Fixture fixture(int total, int background, int foregroundReserve, int platformMax) {
        AppConfig config = new AppConfig();
        var settings = config.getCustom().getPriceWatch();
        settings.setDailyTotalVisits(total);
        settings.setDailyBackgroundVisits(background);
        settings.setForegroundReserveVisits(foregroundReserve);
        settings.getDailyMaxProductVisits().put("jd", platformMax);
        settings.getDailyMaxProductVisits().put("tb", platformMax);
        EntityDatabase database = new EntityDatabaseImpl(directory.resolve("budget-" + UUID.randomUUID()).toString(), null);
        PriceWatchStore store = new PriceWatchStore(database);
        store.init();
        return new Fixture(database, store, config, new ProductVisitBudget(store, config, new PriceRiskGate(store, config)));
    }

    private static void denied(Runnable action, String code) {
        TaskApiException e = assertThrows(TaskApiException.class, action::run);
        assertEquals(429, e.getStatus());
        assertEquals(code, e.getCode());
        assertEquals(code, e.getMessage());
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();
    }

    private record Fixture(EntityDatabase database, PriceWatchStore store, AppConfig config,
                           ProductVisitBudget budget) implements AutoCloseable {
        @Override public void close() throws Exception { database.close(); }
    }
}
