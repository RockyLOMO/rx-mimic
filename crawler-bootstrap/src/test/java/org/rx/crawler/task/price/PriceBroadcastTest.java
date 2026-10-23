package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.config.TaskApiAuthenticationFilter;
import org.rx.crawler.config.TaskApiProperties;
import org.rx.crawler.controller.PriceEventController;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 降价通知对外广播与事件生命周期测试：
 * 覆盖四种开关组合、多接收方独立交付与隔离、HMAC-SHA256 签名、重试退避与耗尽、
 * 挂起恢复、7 天保留期清理、游标拉取 API（分页、owner 隔离与 410 过期）。
 */
class PriceBroadcastTest {
    private HttpServer mockServer;
    private int mockPort;
    private final Map<String, RequestCapture> capturedRequests = new ConcurrentHashMap<>();
    private final Map<String, Integer> routeStatusCodes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> routeResponseHeaders = new ConcurrentHashMap<>();

    private static class RequestCapture {
        String body;
        String eventId;
        String deliveryId;
        String timestamp;
        String signature;
        String contentType;
    }

    @BeforeEach
    void setupMockServer() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockPort = mockServer.getAddress().getPort();
        mockServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            RequestCapture cap = new RequestCapture();
            cap.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            cap.eventId = exchange.getRequestHeaders().getFirst("X-Price-Event-Id");
            cap.deliveryId = exchange.getRequestHeaders().getFirst("X-Price-Delivery-Id");
            cap.timestamp = exchange.getRequestHeaders().getFirst("X-Price-Timestamp");
            cap.signature = exchange.getRequestHeaders().getFirst("X-Price-Signature");
            try (InputStream in = exchange.getRequestBody()) {
                cap.body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            capturedRequests.put(path, cap);

            int status = routeStatusCodes.getOrDefault(path, 200);
            Map<String, String> extraHeaders = routeResponseHeaders.get(path);
            if (extraHeaders != null) {
                extraHeaders.forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
            }
            byte[] resp = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, resp.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(resp);
            }
            exchange.close();
        });
        mockServer.start();
    }

    @AfterEach
    void tearDownMockServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
        capturedRequests.clear();
        routeStatusCodes.clear();
        routeResponseHeaders.clear();
    }

    @Test
    void switchCombinationsControlPublishingAndChannels() throws Exception {
        try (TestDb db = TestDb.open("switch-combinations")) {
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Timestamp nowTs = Timestamp.from(now);

            PriceWatchStore.ProductHit product = db.store.upsertProduct(
                    PriceCodes.JD, "123456789", "手机", "官方店", "http://img.jpg", nowTs);
            long baselineId = db.insertSnapshot(product.getProductId(), new BigDecimal("100.00"), nowTs, 101);
            long candidateId = db.insertSnapshot(product.getProductId(), new BigDecimal("80.00"), nowTs, 102);

            // --- 组合 1: notify=false, broadcast=false ---
            AppConfig c1 = testConfig(false, false, mockPort);
            PriceAlertService alertSvc1 = new PriceAlertService(db.store, c1, body -> true);
            long watchId1 = db.insertWatch("alice", product.getProductId(), "w-1", new BigDecimal("1.00"), new BigDecimal("5.00"));
            db.store.insertAlert(watchId1, "alert-key-1", baselineId, candidateId, "payload", nowTs, nowTs);
            db.store.updateAlert(db.store.pendingAlert(watchId1).getId(), PriceCodes.ALERT_READY, candidateId, "payload", nowTs, null, 0);

            alertSvc1.flush(now);
            assertEquals(0, db.count("select count(*) from price_event"));
            assertEquals(0, db.count("select count(*) from price_event_delivery"));
            db.update("delete from price_alert_outbox");
            db.update("delete from price_watch");

            // --- 组合 2: notify=true, broadcast=false ---
            AppConfig c2 = testConfig(true, false, mockPort);
            PriceAlertService alertSvc2 = new PriceAlertService(db.store, c2, body -> true);
            long watchId2 = db.insertWatch("alice", product.getProductId(), "w-2", new BigDecimal("1.00"), new BigDecimal("5.00"));
            db.store.insertAlert(watchId2, "alert-key-2", baselineId, candidateId, "payload", nowTs, nowTs);
            db.store.updateAlert(db.store.pendingAlert(watchId2).getId(), PriceCodes.ALERT_READY, candidateId, "payload", nowTs, null, 0);

            alertSvc2.flush(now);
            assertEquals(1, db.count("select count(*) from price_event where owner_id='alice'"));
            assertEquals(1, db.count("select count(*) from price_event_delivery where channel='mail'"));
            assertEquals(0, db.count("select count(*) from price_event_delivery where channel='webhook'"));
            db.update("delete from price_event_delivery");
            db.update("delete from price_event");
            db.update("delete from price_alert_outbox");
            db.update("delete from price_watch");

            // --- 组合 3: notify=false, broadcast=true ---
            AppConfig c3 = testConfig(false, true, mockPort);
            PriceAlertService alertSvc3 = new PriceAlertService(db.store, c3, body -> true);
            long watchId3 = db.insertWatch("alice", product.getProductId(), "w-3", new BigDecimal("1.00"), new BigDecimal("5.00"));
            db.store.insertAlert(watchId3, "alert-key-3", baselineId, candidateId, "payload", nowTs, nowTs);
            db.store.updateAlert(db.store.pendingAlert(watchId3).getId(), PriceCodes.ALERT_READY, candidateId, "payload", nowTs, null, 0);

            alertSvc3.flush(now);
            assertEquals(1, db.count("select count(*) from price_event where owner_id='alice'"));
            assertEquals(0, db.count("select count(*) from price_event_delivery where channel='mail'"));
            assertEquals(1, db.count("select count(*) from price_event_delivery where channel='webhook'"));
            db.update("delete from price_event_delivery");
            db.update("delete from price_event");
            db.update("delete from price_alert_outbox");
            db.update("delete from price_watch");

            // --- 组合 4: notify=true, broadcast=true ---
            AppConfig c4 = testConfig(true, true, mockPort);
            PriceAlertService alertSvc4 = new PriceAlertService(db.store, c4, body -> true);
            long watchId4 = db.insertWatch("alice", product.getProductId(), "w-4", new BigDecimal("1.00"), new BigDecimal("5.00"));
            db.store.insertAlert(watchId4, "alert-key-4", baselineId, candidateId, "payload", nowTs, nowTs);
            db.store.updateAlert(db.store.pendingAlert(watchId4).getId(), PriceCodes.ALERT_READY, candidateId, "payload", nowTs, null, 0);

            alertSvc4.flush(now);
            assertEquals(1, db.count("select count(*) from price_event where owner_id='alice'"));
            assertEquals(1, db.count("select count(*) from price_event_delivery where channel='mail'"));
            assertEquals(1, db.count("select count(*) from price_event_delivery where channel='webhook'"));
        }
    }

    @Test
    void multiSubscriberDeliveryAndFaultIsolation() throws Exception {
        routeStatusCodes.put("/webhook-a", 200);
        routeStatusCodes.put("/webhook-b", 500);

        try (TestDb db = TestDb.open("multi-subscriber-isolation")) {
            AppConfig config = new AppConfig();
            config.getCustom().getPriceWatch().setNotifyEnabled(true);
            config.getCustom().getPriceWatch().setNotifyStartHour(0);
            config.getCustom().getPriceWatch().setNotifyEndHour(24);
            AppConfig.PriceBroadcastConfig broadcast = config.getCustom().getPriceWatch().getBroadcast();
            broadcast.setEnabled(true);

            AppConfig.PriceSubscriberConfig subA = new AppConfig.PriceSubscriberConfig();
            subA.setId("subscriber-a");
            subA.setOwnerIds(List.of("alice"));
            subA.setUrl("http://127.0.0.1:" + mockPort + "/webhook-a");
            subA.setSigningSecret("secret-a");

            AppConfig.PriceSubscriberConfig subB = new AppConfig.PriceSubscriberConfig();
            subB.setId("subscriber-b");
            subB.setOwnerIds(List.of("alice"));
            subB.setUrl("http://127.0.0.1:" + mockPort + "/webhook-b");
            subB.setSigningSecret("secret-b");

            broadcast.setSubscribers(List.of(subA, subB));

            AtomicInteger mailAttempt = new AtomicInteger();
            PriceAlertSender failingMailSender = body -> {
                mailAttempt.incrementAndGet();
                throw new RuntimeException("SMTP connect timeout");
            };

            PriceDeliveryService deliveryService = new PriceDeliveryService(config, db.store, failingMailSender, null, null);
            PriceAlertService alertService = new PriceAlertService(db.store, config, failingMailSender, deliveryService, new ObjectMapper());

            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Timestamp nowTs = Timestamp.from(now);
            PriceWatchStore.ProductHit product = db.store.upsertProduct(
                    PriceCodes.JD, "123456790", "电脑", "旗舰店", "http://img.jpg", nowTs);
            long baselineId = db.insertSnapshot(product.getProductId(), new BigDecimal("5000.00"), nowTs, 201);
            long candidateId = db.insertSnapshot(product.getProductId(), new BigDecimal("4500.00"), nowTs, 202);

            long watchId = db.insertWatch("alice", product.getProductId(), "w-multi", new BigDecimal("10.00"), new BigDecimal("5.00"));
            db.store.insertAlert(watchId, "alert-key-multi", baselineId, candidateId, "payload", nowTs, nowTs);
            db.store.updateAlert(db.store.pendingAlert(watchId).getId(), PriceCodes.ALERT_READY, candidateId, "payload", nowTs, null, 0);

            // 1. 发布事件并生成 3 个 delivery 草稿（subA、subB、mail）
            alertService.flush(now);
            assertEquals(1, db.count("select count(*) from price_event"));
            assertEquals(3, db.count("select count(*) from price_event_delivery"));

            // 2. 调度执行 delivery
            deliveryService.dispatchDueDeliveries(now);

            // 验证 A 成功
            assertEquals(1, db.count("select count(*) from price_event_delivery where subscriber_id='subscriber-a' and status=" + PriceCodes.DELIVERY_SUCCESS));
            assertEquals(1, db.count("select count(*) from price_event_delivery where subscriber_id='subscriber-a' and last_http_status=200"));

            // 验证 B 失败进入重试态（HTTP 500）
            assertEquals(1, db.count("select count(*) from price_event_delivery where subscriber_id='subscriber-b' and status=" + PriceCodes.DELIVERY_RETRY));
            assertEquals(1, db.count("select count(*) from price_event_delivery where subscriber_id='subscriber-b' and last_http_status=500"));

            // 验证 Mail 失败进入重试态
            assertEquals(1, db.count("select count(*) from price_event_delivery where channel='mail' and status=" + PriceCodes.DELIVERY_RETRY));

            // 3. 再次触发投递（此时 next_attempt_at 在未来，dueDeliveries 不应选出任何已成功的 A，也不选出未到时间的 B 和 mail）
            capturedRequests.clear();
            deliveryService.dispatchDueDeliveries(now);
            assertTrue(capturedRequests.isEmpty());

            // 4. 将时钟快进 2 分钟（超过 B 的第 1 次重试退避 60 秒）
            Instant retryTime = now.plusSeconds(120);
            routeStatusCodes.put("/webhook-b", 200); // 恢复正常
            deliveryService.dispatchDueDeliveries(retryTime);

            assertFalse(capturedRequests.containsKey("/webhook-a"));
            assertTrue(capturedRequests.containsKey("/webhook-b"));
            assertEquals(1, db.count("select count(*) from price_event_delivery where subscriber_id='subscriber-b' and status=" + PriceCodes.DELIVERY_SUCCESS));
            assertEquals(1, db.count("select count(*) from price_event_delivery where subscriber_id='subscriber-b' and attempts=2"));
        }
    }

    @Test
    void hmacSignatureAndHeadersAreStrictlyFormed() throws Exception {
        routeStatusCodes.put("/webhook-sign", 200);

        try (TestDb db = TestDb.open("hmac-signature")) {
            AppConfig config = new AppConfig();
            config.getCustom().getPriceWatch().setNotifyStartHour(0);
            config.getCustom().getPriceWatch().setNotifyEndHour(24);
            AppConfig.PriceBroadcastConfig broadcast = config.getCustom().getPriceWatch().getBroadcast();
            broadcast.setEnabled(true);

            String secret = "test-secret-key-987654";
            AppConfig.PriceSubscriberConfig sub = new AppConfig.PriceSubscriberConfig();
            sub.setId("sub-sign");
            sub.setOwnerIds(List.of("alice"));
            sub.setUrl("http://127.0.0.1:" + mockPort + "/webhook-sign");
            sub.setSigningSecret(secret);
            broadcast.setSubscribers(List.of(sub));

            PriceDeliveryService deliveryService = new PriceDeliveryService(config, db.store, null, null, null);
            PriceAlertService alertService = new PriceAlertService(db.store, config, null, deliveryService, new ObjectMapper());

            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Timestamp nowTs = Timestamp.from(now);
            PriceWatchStore.ProductHit product = db.store.upsertProduct(
                    PriceCodes.TB, "223456789", "跑鞋", "耐克官方旗舰店", "http://img.jpg", nowTs);
            long baselineId = db.insertSnapshot(product.getProductId(), new BigDecimal("899.00"), nowTs, 301);
            long candidateId = db.insertSnapshot(product.getProductId(), new BigDecimal("699.00"), nowTs, 302);

            long watchId = db.insertWatch("alice", product.getProductId(), "w-sign", new BigDecimal("10.00"), new BigDecimal("5.00"));
            db.store.insertAlert(watchId, "alert-sign", baselineId, candidateId, "payload", nowTs, nowTs);
            db.store.updateAlert(db.store.pendingAlert(watchId).getId(), PriceCodes.ALERT_READY, candidateId, "payload", nowTs, null, 0);

            alertService.flush(now);
            deliveryService.dispatchDueDeliveries(now);

            RequestCapture capture = capturedRequests.get("/webhook-sign");
            assertNotNull(capture);
            assertEquals("application/json", capture.contentType);
            assertNotNull(capture.eventId);
            assertNotNull(capture.deliveryId);
            assertNotNull(capture.timestamp);
            assertNotNull(capture.signature);
            assertTrue(capture.signature.startsWith("sha256="));

            long timestamp = Long.parseLong(capture.timestamp);
            assertTrue(PriceDeliveryService.verifySignature(capture.body, timestamp, secret, capture.signature));
            assertFalse(PriceDeliveryService.verifySignature(capture.body, timestamp, "wrong-secret", capture.signature));
            assertFalse(PriceDeliveryService.verifySignature(capture.body + "tampered", timestamp, secret, capture.signature));

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(capture.body);
            assertEquals(PriceCodes.EVENT_PRICE_DROPPED, root.path("type").asText());
            assertEquals(1, root.path("version").asInt());
            assertEquals(capture.eventId, root.path("eventId").asText());
            assertEquals("899.00", root.path("data").path("baselinePrice").asText());
            assertEquals("699.00", root.path("data").path("currentPrice").asText());
            assertEquals("200.00", root.path("data").path("dropAmount").asText());
            assertEquals("22.25", root.path("data").path("dropPercent").asText());
            assertEquals("CNY", root.path("data").path("currency").asText());
        }
    }

    @Test
    void errorClassificationRetryBackoffAndExhaustion() throws Exception {
        assertEquals(60L, PriceDeliveryService.calculateRetryDelaySeconds(1, Optional.empty()));
        assertEquals(300L, PriceDeliveryService.calculateRetryDelaySeconds(2, Optional.empty()));
        assertEquals(900L, PriceDeliveryService.calculateRetryDelaySeconds(3, Optional.empty()));
        assertEquals(3600L, PriceDeliveryService.calculateRetryDelaySeconds(4, Optional.empty()));
        assertEquals(3600L, PriceDeliveryService.calculateRetryDelaySeconds(10, Optional.empty()));
        assertEquals(45L, PriceDeliveryService.calculateRetryDelaySeconds(1, Optional.of("45")));
        assertEquals(5L, PriceDeliveryService.calculateRetryDelaySeconds(1, Optional.of("1")));
        assertEquals(3600L, PriceDeliveryService.calculateRetryDelaySeconds(1, Optional.of("99999")));
        assertEquals(60L, PriceDeliveryService.calculateRetryDelaySeconds(1, Optional.of("invalid-number")));

        routeStatusCodes.put("/webhook-429", 429);
        routeResponseHeaders.put("/webhook-429", Map.of("Retry-After", "10"));
        routeStatusCodes.put("/webhook-400", 400);

        try (TestDb db = TestDb.open("error-classification")) {
            AppConfig config = new AppConfig();
            AppConfig.PriceBroadcastConfig broadcast = config.getCustom().getPriceWatch().getBroadcast();
            broadcast.setEnabled(true);

            AppConfig.PriceSubscriberConfig sub429 = new AppConfig.PriceSubscriberConfig();
            sub429.setId("sub-429");
            sub429.setUrl("http://127.0.0.1:" + mockPort + "/webhook-429");

            AppConfig.PriceSubscriberConfig sub400 = new AppConfig.PriceSubscriberConfig();
            sub400.setId("sub-400");
            sub400.setUrl("http://127.0.0.1:" + mockPort + "/webhook-400");

            AppConfig.PriceSubscriberConfig subInsecure = new AppConfig.PriceSubscriberConfig();
            subInsecure.setId("sub-insecure");
            subInsecure.setUrl("http://untrusted-external-domain.com/webhook");

            broadcast.setSubscribers(List.of(sub429, sub400, subInsecure));
            PriceDeliveryService deliveryService = new PriceDeliveryService(config, db.store, null, null, null);

            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Timestamp nowTs = Timestamp.from(now);

            PriceDropEvent event = new PriceDropEvent();
            event.setOccurredAt(now.toString());
            List<PriceWatchStore.DeliveryDraft> drafts = List.of(
                    draft("sub-429", "webhook", sub429.getUrl()),
                    draft("sub-400", "webhook", sub400.getUrl()),
                    draft("sub-insecure", "webhook", subInsecure.getUrl())
            );

            long eventId = db.store.publishEventAndDeliveries(1L, "alice", event, drafts, nowTs, new BigDecimal("10.00"), 1L);

            deliveryService.dispatchDueDeliveries(now);

            PriceWatchStore.DeliveryRow row429 = db.store.dueDeliveries(Timestamp.from(now.plusSeconds(30)), 10).stream()
                    .filter(r -> "sub-429".equals(r.getSubscriberId())).findFirst().orElse(null);
            assertNotNull(row429);
            assertEquals(PriceCodes.DELIVERY_RETRY, row429.getStatus());
            assertEquals(1, row429.getAttempts());
            assertEquals(Integer.valueOf(429), row429.getLastHttpStatus());
            assertTrue(row429.getNextAttemptAt().after(nowTs));

            assertEquals(1, db.count("select count(*) from price_event_delivery where subscriber_id='sub-400' and status="
                    + PriceCodes.DELIVERY_EXHAUSTED + " and last_http_status=400"));

            assertEquals(1, db.count("select count(*) from price_event_delivery where subscriber_id='sub-insecure' and status="
                    + PriceCodes.DELIVERY_EXHAUSTED));

            long row429Id = row429.getId();
            db.store.updateDeliveryResult(row429Id, PriceCodes.DELIVERY_RETRY, 4, nowTs, 500, "error", null, nowTs);
            PriceWatchStore.DeliveryRow retryRow = db.store.dueDeliveries(nowTs, 10).stream()
                    .filter(r -> r.getId() == row429Id).findFirst().orElseThrow();
            deliveryService.processDelivery(retryRow, now);

            assertEquals(1, db.count("select count(*) from price_event_delivery where id=" + row429Id
                    + " and status=" + PriceCodes.DELIVERY_EXHAUSTED + " and attempts=5"));
        }
    }

    @Test
    void hangingDeliveriesAreRecoveredOnTickAndStartup() throws Exception {
        try (TestDb db = TestDb.open("hanging-recover")) {
            AppConfig config = new AppConfig();
            PriceDeliveryService deliveryService = new PriceDeliveryService(config, db.store, null, null, null);

            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Instant fiveMinAgo = now.minus(Duration.ofMinutes(5));
            Instant oneMinAgo = now.minus(Duration.ofMinutes(1));
            Timestamp fiveMinAgoTs = Timestamp.from(fiveMinAgo);
            Timestamp oneMinAgoTs = Timestamp.from(oneMinAgo);

            PriceDropEvent event = new PriceDropEvent();
            event.setOccurredAt(fiveMinAgo.toString());
            List<PriceWatchStore.DeliveryDraft> drafts = List.of(draft("sub-hang", "webhook", "http://127.0.0.1:" + mockPort + "/wh"));
            long eventId = db.store.publishEventAndDeliveries(1L, "alice", event, drafts, fiveMinAgoTs, new BigDecimal("10.00"), 1L);

            db.update("update price_event_delivery set status=?, updated_at=? where event_id=?",
                    PriceCodes.DELIVERY_IN_PROGRESS, fiveMinAgoTs, eventId);

            int recovered = db.store.recoverHangingDeliveries(Timestamp.from(now.minus(Duration.ofMinutes(2))), Timestamp.from(now));
            assertEquals(1, recovered);
            assertEquals(1, db.count("select count(*) from price_event_delivery where status=" + PriceCodes.DELIVERY_RETRY));

            db.update("update price_event_delivery set status=?, updated_at=? where event_id=?",
                    PriceCodes.DELIVERY_IN_PROGRESS, oneMinAgoTs, eventId);
            int recoveredRecent = db.store.recoverHangingDeliveries(Timestamp.from(now.minus(Duration.ofMinutes(2))), Timestamp.from(now));
            assertEquals(0, recoveredRecent);
        }
    }

    @Test
    void retentionCleanupDeletesExpiredEventsAndDeliveries() throws Exception {
        try (TestDb db = TestDb.open("retention-cleanup")) {
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Instant eightDaysAgo = now.minus(Duration.ofDays(8));
            Instant oneDayAgo = now.minus(Duration.ofDays(1));
            Timestamp eightDaysAgoTs = Timestamp.from(eightDaysAgo);
            Timestamp oneDayAgoTs = Timestamp.from(oneDayAgo);

            PriceDropEvent eventOld = new PriceDropEvent();
            eventOld.setOccurredAt(eightDaysAgo.toString());
            long oldId = db.store.publishEventAndDeliveries(1L, "alice", eventOld, List.of(draft("sub-old", "webhook", "http://localhost")),
                    eightDaysAgoTs, new BigDecimal("10.00"), 1L);

            PriceDropEvent eventNew = new PriceDropEvent();
            eventNew.setOccurredAt(oneDayAgo.toString());
            long newId = db.store.publishEventAndDeliveries(2L, "alice", eventNew, List.of(draft("sub-new", "webhook", "http://localhost")),
                    oneDayAgoTs, new BigDecimal("10.00"), 2L);

            assertEquals(2, db.count("select count(*) from price_event"));
            assertEquals(2, db.count("select count(*) from price_event_delivery"));
            assertEquals(Long.valueOf(oldId), db.store.getMinEventId());

            Timestamp sevenDaysCutoff = Timestamp.from(now.minus(Duration.ofDays(7)));
            int cleaned = db.store.cleanExpiredEvents(sevenDaysCutoff);
            assertEquals(1, cleaned);

            assertEquals(1, db.count("select count(*) from price_event"));
            assertEquals(1, db.count("select count(*) from price_event_delivery"));
            assertEquals(Long.valueOf(newId), db.store.getMinEventId());
        }
    }

    @Test
    void cursorPullApiProvidesAuthenticationIsolationAndExpiredHandling() throws Exception {
        try (TestDb db = TestDb.open("cursor-pull-api")) {
            TaskApiProperties props = new TaskApiProperties();
            props.setApiKeys(Map.of("alice", "secret-alice", "bob", "secret-bob"));
            ObjectMapper mapper = new ObjectMapper();

            PriceEventController controller = new PriceEventController(db.store, mapper);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                    .addFilters(new TaskApiAuthenticationFilter(props, mapper))
                    .build();

            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Timestamp nowTs = Timestamp.from(now);

            for (int i = 1; i <= 3; i++) {
                PriceDropEvent ev = new PriceDropEvent();
                ev.setOccurredAt(now.toString());
                db.store.publishEventAndDeliveries((long) i, "alice", ev, List.of(), nowTs, new BigDecimal("10.00"), (long) i);
            }
            PriceDropEvent evBob = new PriceDropEvent();
            evBob.setOccurredAt(now.toString());
            db.store.publishEventAndDeliveries(4L, "bob", evBob, List.of(), nowTs, new BigDecimal("10.00"), 4L);

            mvc.perform(get("/api/v1/price-events"))
                    .andExpect(status().isUnauthorized());

            mvc.perform(get("/api/v1/price-events").header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isUnauthorized());

            mvc.perform(get("/api/v1/price-events").header("Authorization", "Bearer secret-bob"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.events.length()").value(1))
                    .andExpect(jsonPath("$.data.events[0].eventId").value("4"))
                    .andExpect(jsonPath("$.data.hasMore").value(false));

            mvc.perform(get("/api/v1/price-events?limit=2").header("Authorization", "Bearer secret-alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.events.length()").value(2))
                    .andExpect(jsonPath("$.data.events[0].eventId").value("1"))
                    .andExpect(jsonPath("$.data.events[1].eventId").value("2"))
                    .andExpect(jsonPath("$.data.nextCursor").value("2"))
                    .andExpect(jsonPath("$.data.hasMore").value(true));

            mvc.perform(get("/api/v1/price-events?after=2&limit=2").header("Authorization", "Bearer secret-alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.events.length()").value(1))
                    .andExpect(jsonPath("$.data.events[0].eventId").value("3"))
                    .andExpect(jsonPath("$.data.nextCursor").value("3"))
                    .andExpect(jsonPath("$.data.hasMore").value(false));

            mvc.perform(get("/api/v1/price-events?after=notanumber").header("Authorization", "Bearer secret-alice"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));

            db.update("delete from price_event where id=1");
            assertEquals(Long.valueOf(2), db.store.getMinEventId());

            mvc.perform(get("/api/v1/price-events?after=1").header("Authorization", "Bearer secret-alice"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
        }
    }

    @Test
    void priceDropLifecycleFreshnessAndReviewCancellation() throws Exception {
        try (TestDb db = TestDb.open("price-drop-lifecycle")) {
            AppConfig config = testConfig(false, true, mockPort);
            config.getCustom().getPriceWatch().setSnapshotFreshMinutes(15);
            PriceAlertService alertService = new PriceAlertService(db.store, config, null, null, new ObjectMapper());

            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Timestamp nowTs = Timestamp.from(now);
            PriceWatchStore.ProductHit product = db.store.upsertProduct(
                    PriceCodes.JD, "123456791", "音箱", "自营店", "http://img.jpg", nowTs);
            long baselineId = db.insertSnapshot(product.getProductId(), new BigDecimal("100.00"), nowTs, 501);

            long watchId = db.insertWatch("alice", product.getProductId(), "w-life", new BigDecimal("5.00"), new BigDecimal("10.00"));

            long samePriceSnapshotId = db.insertSnapshot(product.getProductId(), new BigDecimal("100.00"), nowTs, 502);
            db.store.insertAlert(watchId, "alert-same", baselineId, samePriceSnapshotId, "payload", nowTs, nowTs);
            db.store.updateAlert(db.store.pendingAlert(watchId).getId(), PriceCodes.ALERT_READY, samePriceSnapshotId, "payload", nowTs, null, 0);

            alertService.flush(now);
            assertEquals(0, db.count("select count(*) from price_event"));
            assertEquals(1, db.count("select count(*) from price_alert_outbox where status=" + PriceCodes.ALERT_CANCEL));

            Timestamp staleTime = Timestamp.from(now.minus(Duration.ofMinutes(20)));
            long staleSnapshotId = db.insertSnapshot(product.getProductId(), new BigDecimal("80.00"), staleTime, 503);
            db.store.insertAlert(watchId, "alert-stale", baselineId, staleSnapshotId, "payload", nowTs, nowTs);
            db.store.updateAlert(db.store.pendingAlert(watchId).getId(), PriceCodes.ALERT_READY, staleSnapshotId, "payload", nowTs, null, 0);

            alertService.flush(now);
            assertEquals(0, db.count("select count(*) from price_event"));

            Timestamp freshTime = Timestamp.from(now.minus(Duration.ofMinutes(2)));
            long freshSnapshotId = db.insertSnapshot(product.getProductId(), new BigDecimal("80.00"), freshTime, 504);
            db.store.updateAlert(db.store.pendingAlert(watchId).getId(), PriceCodes.ALERT_READY, freshSnapshotId, "payload", nowTs, null, 0);

            alertService.flush(now);
            assertEquals(1, db.count("select count(*) from price_event"));
            assertEquals(1, db.count("select count(*) from price_watch where last_notified_price=80.00"));
        }
    }

    private static PriceWatchStore.DeliveryDraft draft(String subId, String channel, String url) {
        PriceWatchStore.DeliveryDraft draft = new PriceWatchStore.DeliveryDraft();
        draft.setSubscriberId(subId);
        draft.setChannel(channel);
        draft.setTargetUrl(url);
        return draft;
    }

    private static AppConfig testConfig(boolean mailEnabled, boolean broadcastEnabled, int port) {
        AppConfig config = new AppConfig();
        config.getCustom().getPriceWatch().setNotifyEnabled(mailEnabled);
        config.getCustom().getPriceWatch().setNotifyStartHour(0);
        config.getCustom().getPriceWatch().setNotifyEndHour(24);
        AppConfig.PriceBroadcastConfig bc = config.getCustom().getPriceWatch().getBroadcast();
        bc.setEnabled(broadcastEnabled);

        AppConfig.PriceSubscriberConfig sub = new AppConfig.PriceSubscriberConfig();
        sub.setId("sub-test");
        sub.setOwnerIds(List.of("alice"));
        sub.setUrl("http://127.0.0.1:" + port + "/webhook-test");
        sub.setSigningSecret("test-secret");
        bc.setSubscribers(List.of(sub));
        return config;
    }

    private static final class TestDb implements AutoCloseable {
        final EntityDatabase database;
        final PriceWatchStore store;

        TestDb(String name) {
            this.database = new EntityDatabaseImpl(
                    "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", null, 4, true);
            this.store = new PriceWatchStore(database);
            this.store.init();
        }

        static TestDb open(String name) {
            return new TestDb(name);
        }

        long insertWatch(String ownerId, long productId, String watchKey,
                         BigDecimal dropAmount, BigDecimal dropPercent) {
            PriceWatchStore.WatchDraft draft = new PriceWatchStore.WatchDraft();
            draft.setOwnerId(ownerId);
            draft.setProductId(productId);
            draft.setWatchKey(watchKey);
            draft.setProfileName("common");
            draft.setDropAmount(dropAmount);
            draft.setDropPercent(dropPercent);
            draft.setTier(PriceCodes.TIER_NORMAL);
            draft.setStatus(PriceCodes.WATCH_ACTIVE);
            draft.setNow(Timestamp.from(Instant.now()));
            draft.setNextCheckAt(draft.getNow());
            return store.ensureWatch(draft).getId();
        }

        long insertSnapshot(long productId, BigDecimal amount, Timestamp observedAt, long taskId) {
            PriceWatchStore.SnapshotWrite write = new PriceWatchStore.SnapshotWrite();
            write.setProductId(productId);
            write.setRawPrice(amount.toPlainString());
            write.setPriceAmount(amount);
            write.setSource(PriceCodes.SOURCE_UNION);
            write.setSourceField("price");
            write.setPriceKind(PriceCodes.KIND_FINAL);
            write.setComparableKey("1:price");
            write.setComparable(true);
            write.setObservedAt(observedAt);
            write.setTaskId(taskId);
            write.setItemIndex(0);
            write.setFetchStatus(PriceCodes.FETCH_OK);
            write.setCreatedAt(observedAt);
            return store.saveSnapshot(write).getId();
        }

        int update(String sql, Object... args) {
            return database.withConnection(connection -> {
                try (var statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < args.length; i++) {
                        statement.setObject(i + 1, args[i]);
                    }
                    return statement.executeUpdate();
                }
            });
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
