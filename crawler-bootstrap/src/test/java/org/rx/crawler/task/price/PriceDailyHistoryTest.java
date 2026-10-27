package org.rx.crawler.task.price;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class PriceDailyHistoryTest {
    @TempDir Path temp;
    EntityDatabase db;
    PriceWatchStore store;
    long product;
    final Instant now = Instant.parse("2026-10-27T04:00:00Z");
    final ZoneId zone = ZoneId.of("Asia/Shanghai");
    long task;
    @BeforeEach void open() {
        db = new EntityDatabaseImpl(temp.resolve("prices").toString(), null);
        store = new PriceWatchStore(db); store.init();
        product = store.upsertProduct(1, "123456789", "sample", "shop", "", Timestamp.from(now)).getProductId();
    }
    @AfterEach void close() throws Exception { db.close(); }

    @Test void calendarDatesPickLatestSuccessWithoutLosingOlderDaysBehindManyIntradayRows() {
        save("2026-09-28T00:00:00Z", "90", PriceCodes.FETCH_OK);
        save("2026-09-27T00:00:00Z", "80", PriceCodes.FETCH_OK);
        save("2026-08-29T00:00:00Z", "70", PriceCodes.FETCH_OK);
        save("2026-07-30T00:00:00Z", "60", PriceCodes.FETCH_OK);
        save("2026-07-29T00:00:00Z", "50", PriceCodes.FETCH_OK);
        save("2026-10-26T15:59:59Z", "99", PriceCodes.FETCH_OK); // Oct 26 locally.
        for (int i = 0; i < 130; i++) save(Instant.parse("2026-10-26T16:00:00Z").plusSeconds(i).toString(), "88", PriceCodes.FETCH_OK);
        save("2026-10-27T03:00:00Z", "77", PriceCodes.FETCH_OK);
        save("2026-10-27T03:30:00Z", "66", PriceCodes.FETCH_RISK);
        var rows = store.dailySnapshots(product, 30, zone, now);
        assertEquals(3, rows.size());
        assertEquals(0, new BigDecimal("77").compareTo(rows.get(2).getPriceAmount()));
        assertEquals(LocalDate.parse("2026-10-26"), rows.get(1).getObservedAt().toInstant().atZone(zone).toLocalDate());
        assertEquals(5, store.dailySnapshots(product, 60, zone, now).size());
        assertEquals(6, store.dailySnapshots(product, 90, zone, now).size());
        assertThrows(org.rx.crawler.task.common.TaskApiException.class, () -> store.dailySnapshots(product, 31, zone, now));
    }

    @Test void successfulObservationCountsForDailyMonitorButYesterdayDoesNot() throws Exception {
        var clock = PriceWatchClock.parse("Asia/Shanghai", "06:00", "01:00");
        save("2026-10-26T15:59:59Z", "99", PriceCodes.FETCH_OK);
        assertFalse(store.priceChecked(product, clock.dayStart(now), clock.nextDayStart(now)));
        save("2026-10-26T16:00:00Z", "88", PriceCodes.FETCH_OK);
        db.close();
        db = new EntityDatabaseImpl(temp.resolve("prices").toString(), null);
        store = new PriceWatchStore(db); store.init();
        assertTrue(store.priceChecked(product, clock.dayStart(now), clock.nextDayStart(now)));
        var next = clock.nextDailyCheck(now, product);
        assertTrue(clock.open(next));
        assertEquals(LocalDate.parse("2026-10-28"), next.atZone(zone).toLocalDate());
    }

    private void save(String instant, String amount, int status) {
        var s = new PriceWatchStore.SnapshotWrite();
        s.setProductId(product); s.setTaskId(++task); s.setItemIndex(0);
        s.setPriceAmount(new BigDecimal(amount)); s.setRawPrice(amount); s.setSource(PriceCodes.SOURCE_UNION);
        s.setPriceKind(1); s.setSourceField("wlPrice"); s.setComparable(true); s.setComparableKey("jd:list");
        s.setFetchStatus(status); s.setObservedAt(Timestamp.from(Instant.parse(instant))); s.setCreatedAt(s.getObservedAt());
        store.saveSnapshot(s);
    }
}
