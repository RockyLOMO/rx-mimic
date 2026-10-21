package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.LoginNotificationContext;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 价格窗口、访问预算、登录/滑块停访和落库规则。不打开 Chrome。 */
class PriceWatchLogicTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal ONE = new BigDecimal("1.00");
    private static final BigDecimal THREE = new BigDecimal("3");

    @Test
    void windowUsesBusinessDateAndRejectsInvalidZone() {
        PriceWatchClock clock = PriceWatchClock.parse("Asia/Shanghai", "06:00", "01:00");
        Instant at0059 = at(2026, 10, 21, 0, 59);
        Instant at0100 = at(2026, 10, 21, 1, 0);
        Instant at0559 = at(2026, 10, 21, 5, 59);
        Instant at0600 = at(2026, 10, 21, 6, 0);
        assertTrue(clock.valid());
        assertTrue(clock.open(at0059));
        assertEquals(LocalDate.of(2026, 10, 20), clock.businessDate(at0059));
        assertFalse(clock.open(at0100));
        assertFalse(clock.open(at0559));
        assertEquals(LocalDate.of(2026, 10, 20), clock.businessDate(at0559));
        assertTrue(clock.open(at0600));
        assertEquals(LocalDate.of(2026, 10, 21), clock.businessDate(at0600));
        PriceWatchClock invalid = PriceWatchClock.parse("Not/AZone", "06:00", "01:00");
        assertFalse(invalid.valid());
        assertFalse(invalid.open(at0600));
    }

    @Test
    void numericIdComesOnlyFromConfirmedProductUrl() {
        assertProduct(PriceCodes.JD, "https://item.jd.com/100012345.html", "100012345");
        assertProduct(PriceCodes.JD, "https://item.m.jd.com/100012345.html", "100012345");
        assertOther(PriceCodes.JD, "https://item.jd.com/encryptedSku.html");
        assertKind(PriceCodes.JD, "https://passport.jd.com/new/login.aspx", ProductIdentityParser.PageKind.LOGIN);
        assertKind(PriceCodes.JD, "https://union.jd.com/active", ProductIdentityParser.PageKind.INTERMEDIATE);
        assertKind(PriceCodes.JD, "https://u.jd.com/abc", ProductIdentityParser.PageKind.INTERMEDIATE);
        assertProduct(PriceCodes.TB, "https://item.taobao.com/item.htm?id=123456", "123456");
        assertProduct(PriceCodes.TB, "https://item.tmall.com/item.html?id=77", "77");
        assertProduct(PriceCodes.TB, "https://detail.tmall.com/item.htm?id=88", "88");
        assertOther(PriceCodes.TB, "https://item.taobao.com/item.htm?id=abc");
        assertOther(PriceCodes.TB, "https://item.taobao.com/item.htm?skuId=123456");
        assertKind(PriceCodes.TB, "https://login.taobao.com/member/login.jhtml", ProductIdentityParser.PageKind.LOGIN);
        assertKind(PriceCodes.TB, "https://login.taobao.com/havanaone/login", ProductIdentityParser.PageKind.LOGIN);
        assertKind(PriceCodes.TB, "https://s.click.taobao.com/t", ProductIdentityParser.PageKind.INTERMEDIATE);
        assertKind(PriceCodes.TB, "https://pub.alimama.com/portal", ProductIdentityParser.PageKind.INTERMEDIATE);
        assertOther(PriceCodes.TB, "https://example.com/item.htm?id=123456");
    }

    @Test
    void dropAlertsAgainstBaselineThenLastNotifiedPrice() {
        assertNull(PriceRules.amount("2?"));
        assertNull(PriceRules.amount("10-20"));
        assertNull(PriceRules.amount("1~2"));
        assertNull(PriceRules.amount("登录后可见"));
        assertNull(PriceRules.amount("待公布"));
        assertEquals(new BigDecimal("12.50"), PriceRules.amount("¥12.5"));
        assertFalse(PriceRules.comparable(PriceCodes.KIND_UNKNOWN, new BigDecimal("10.00")));
        assertFalse(PriceRules.comparable(PriceCodes.KIND_LIST, new BigDecimal("0.40")));
        assertTrue(PriceRules.alert(money("100"), null, money("95"), null, ONE, THREE));
        assertFalse(PriceRules.alert(money("100"), money("95"), money("100"), null, ONE, THREE));
        assertFalse(PriceRules.alert(money("100"), money("95"), money("95"), null, ONE, THREE));
        assertTrue(PriceRules.alert(money("100"), money("95"), money("90"), null, ONE, THREE));
        assertTrue(PriceRules.alert(money("100"), null, money("99"), money("99"), money("5"), money("10")));
    }

    @Test
    void dailyVisitBudgetStopsAndResetsOnNextBusinessDate() throws Exception {
        try (Db db = Db.open("price-visits")) {
            assertEquals(1, db.store.reserveVisit(PriceCodes.JD, "2026-10-20", 2));
            assertEquals(2, db.store.reserveVisit(PriceCodes.JD, "2026-10-20", 2));
            assertEquals(-1, db.store.reserveVisit(PriceCodes.JD, "2026-10-20", 2));
            assertEquals(1, db.store.reserveVisit(PriceCodes.JD, "2026-10-21", 2));
            assertEquals(1, db.store.reserveVisit(PriceCodes.TB, "2026-10-20", 2));
            assertEquals(-1, db.store.reserveVisit(PriceCodes.JD, "2026-10-21", 0));
            assertEquals(-1, db.store.reserveVisit(PriceCodes.TB, "2026-10-21", -1));
        }
    }

    @Test
    void loginAndSliderFailuresStopOnlyThatPlatformForTheBusinessDay() throws Exception {
        AppConfig config = appConfig(3);
        try (Db db = Db.open("price-risk")) {
            PriceRiskGate gate = new PriceRiskGate(db.store, config);
            for (int i = 0; i < 3; i++) {
                db.store.incrementRisk(PriceCodes.JD, "2026-10-20");
            }
            assertTrue(gate.stopped(PriceCodes.JD, at(2026, 10, 21, 0, 59)));
            assertFalse(gate.stopped(PriceCodes.TB, at(2026, 10, 21, 0, 59)));
            assertFalse(gate.stopped(PriceCodes.JD, at(2026, 10, 21, 6, 0)));
            for (int i = 0; i < 3; i++) {
                db.store.incrementRisk(0, "2026-10-19");
            }
            assertTrue(gate.stopped(PriceCodes.JD, at(2026, 10, 19, 12, 0)));
            assertTrue(gate.stopped(PriceCodes.TB, at(2026, 10, 19, 12, 0)));
            assertFalse(gate.stopped(PriceCodes.TB, at(2026, 10, 20, 12, 0)));
        }
        try (Db live = Db.open("price-risk-live")) {
            PriceRiskGate gate = new PriceRiskGate(live.store, config);
            record(gate, "jdLoginKeepAlive", "desk", "login");
            record(gate, "jdLoginKeepAlive", "desk", "login");
            assertEquals(1, gate.count(PriceCodes.JD, Instant.now()));
            record(gate, "getPromotionUrl", "desk", "slider");
            record(gate, "jdProductDetail", "phone", "login");
            assertEquals(3, gate.count(PriceCodes.JD, Instant.now()));
            assertTrue(gate.stopped(PriceCodes.JD, Instant.now()));
            assertFalse(gate.stopped(PriceCodes.TB, Instant.now()));
        }
    }

    @Test
    void nonPositiveFailureLimitDoesNotStopVisits() throws Exception {
        try (Db db = Db.open("price-risk-off")) {
            PriceRiskGate gate = new PriceRiskGate(db.store, appConfig(0));
            record(gate, "tbLoginKeepAlive", "desk", "login");
            assertEquals(1, gate.count(PriceCodes.TB, Instant.now()));
            assertFalse(gate.stopped(PriceCodes.TB, Instant.now()));
            assertFalse(new PriceRiskGate(db.store, appConfig(-1)).stopped(PriceCodes.TB, Instant.now()));
        }
    }

    @Test
    void crawlEntryCountsRiskBeforeMail() throws Exception {
        try (Db db = Db.open("price-entry-risk")) {
            PriceRiskGate gate = new PriceRiskGate(db.store, appConfig(3));
            CrawlEntryService entry = new CrawlEntryService(mock(BrowserPreflightService.class));
            entry.setPriceRiskGate(gate);
            entry.notifyLoginRequired(context("tbLoginKeepAlive", "a"));
            entry.notifySliderFailed(context("tbMallSearch", "b"));
            entry.notifyLoginRequired(context("getTbPromotionUrl", "c"));
            entry.notifyLoginRequired(context("getTbPromotionUrl", "c"));
            assertEquals(3, gate.count(PriceCodes.TB, Instant.now()));
            assertTrue(gate.stopped(PriceCodes.TB, Instant.now()));
            assertFalse(gate.stopped(PriceCodes.JD, Instant.now()));
        }
    }

    @Test
    void searchKeepsSnapshotAndPromotionCreatesOneWatch() throws Exception {
        try (Db db = Db.open("price-ingest")) {
            AppConfig config = appConfig(3);
            PriceAlertService alerts = new PriceAlertService(db.store, config, body -> false);
            PriceIngestService ingest = new PriceIngestService(db.store, config, new ObjectMapper(), alerts);
            ingest.accept("jdUnionSearchProducts", "alice", 11, "{}",
                    "{\"status\":\"SUCCESS\",\"items\":[{\"skuId\":\"encSearch\",\"productName\":\"搜索品\",\"storeName\":\"店\",\"price\":\"88.00\",\"priceField\":\"wlPrice\"}]}");
            assertEquals(0, db.store.countActive());
            assertNotNull(db.store.findAliasAny(PriceCodes.JD, "encSearch"));
            String promotion = "{\"status\":\"SUCCESS\",\"keyword\":\"encSku\",\"profileName\":\"common\","
                    + "\"promotionUrl\":\"https://union-click.jd.com/jda?x=1\",\"adSiteName\":\"0\","
                    + "\"productInfo\":{\"productName\":\"纸巾\",\"storeName\":\"店\",\"price\":\"100.00\",\"priceField\":\"finalPrice\"}}";
            ingest.accept("getPromotionUrl", "alice", 21, "{\"keyword\":\"encSku\"}", promotion);
            ingest.accept("getPromotionUrl", "alice", 21, "{\"keyword\":\"encSku\"}", promotion);
            ingest.accept("getPromotionUrl", "alice", 22, "{}",
                    "{\"status\":\"NOT_FOUND\",\"keyword\":\"missing\"}");
            assertEquals(1, db.store.countActive());
            assertEquals(1, db.count("select count(*) from price_promotion_record"));
            assertEquals(2, db.count("select count(*) from price_snapshot"));
            assertEquals(0, db.count("select count(*) from price_alert_outbox"));
            PriceWatchStore.WatchRow watch = db.store.listByOwner("alice").get(0);
            assertNotNull(watch.getBaselineSnapshotId());
            assertEquals("encSku", watch.getUnionProductId());
        }
    }

    private static void record(PriceRiskGate gate, String taskType, String profile, String kind) {
        LoginNotificationContext context = context(taskType, profile);
        gate.record(context, kind);
    }

    private static LoginNotificationContext context(String taskType, String profile) {
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType(taskType);
        context.setProfileName(profile);
        return context;
    }

    private static AppConfig appConfig(int failureLimit) {
        AppConfig config = new AppConfig();
        config.getCustom().getPriceWatch().setDailyMaxLoginOrSliderFailures(failureLimit);
        config.getCustom().getPriceWatch().setNotifyEnabled(false);
        return config;
    }

    private static void assertProduct(int platform, String url, String id) {
        ProductIdentityParser.Hit hit = ProductIdentityParser.inspect(platform, url);
        assertEquals(ProductIdentityParser.PageKind.PRODUCT, hit.kind);
        assertEquals(id, hit.productId);
    }

    private static void assertOther(int platform, String url) {
        ProductIdentityParser.Hit hit = ProductIdentityParser.inspect(platform, url);
        assertEquals(ProductIdentityParser.PageKind.OTHER, hit.kind);
        assertEquals("", hit.productId);
    }

    private static void assertKind(int platform, String url, ProductIdentityParser.PageKind kind) {
        assertEquals(kind, ProductIdentityParser.inspect(platform, url).kind);
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, SHANGHAI).toInstant();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static final class Db implements AutoCloseable {
        final EntityDatabase database;
        final PriceWatchStore store;

        private Db(EntityDatabase database, PriceWatchStore store) {
            this.database = database;
            this.store = store;
        }

        static Db open(String name) {
            EntityDatabase database = new EntityDatabaseImpl(
                    "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", null, 4, true);
            PriceWatchStore store = new PriceWatchStore(database);
            store.init();
            return new Db(database, store);
        }

        int count(String sql) {
            return database.withConnection(connection -> {
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                    rows.next();
                    return rows.getInt(1);
                }
            });
        }

        @Override
        public void close() throws Exception {
            database.close();
        }
    }
}
