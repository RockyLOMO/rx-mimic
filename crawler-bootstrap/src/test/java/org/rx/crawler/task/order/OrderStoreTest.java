package org.rx.crawler.task.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderStoreTest {

    @TempDir
    Path directory;

    @Test
    void testOrderIngestionAndRequiredFields() throws Exception {
        try (EntityDatabase database = new EntityDatabaseImpl(directory.resolve("test_orders").toString(), null)) {
            ObjectMapper mapper = new ObjectMapper();
            OrderStore store = new OrderStore(database, mapper);
            store.init();

            AppConfig.OrderSubscriberConfig sub = new AppConfig.OrderSubscriberConfig();
            sub.setId("test-sub");
            sub.setUrl("http://localhost:9999/webhook");
            sub.setSigningSecret("secret123");
            sub.setSourceIds(List.of("jd-main"));
            List<AppConfig.OrderSubscriberConfig> subscribers = List.of(sub);

            // 1. Create items: one valid, four invalid each missing one of the 4 required fields
            PromotionOrderItem validItem = new PromotionOrderItem();
            validItem.setOrderNo("ORD1001");
            validItem.setProductName("测试商品A");
            validItem.setStoreName("测试店铺A");
            validItem.setPromotionPosition("默认位-1");
            validItem.setSkuId("SKU1001");
            validItem.setActualBillingAmount("99.00");
            validItem.setActualCommission("9.90");
            validItem.setQuantity("1");
            validItem.setOrderStatus("已完成");
            validItem.setOrderTime("2026-10-23 10:00:00");

            PromotionOrderItem missingOrderNo = new PromotionOrderItem();
            missingOrderNo.setProductName("测试商品B");
            missingOrderNo.setStoreName("测试店铺B");
            missingOrderNo.setPromotionPosition("默认位-1");

            PromotionOrderItem missingProduct = new PromotionOrderItem();
            missingProduct.setOrderNo("ORD1002");
            missingProduct.setStoreName("测试店铺B");
            missingProduct.setPromotionPosition("默认位-1");

            PromotionOrderItem missingStore = new PromotionOrderItem();
            missingStore.setOrderNo("ORD1003");
            missingStore.setProductName("测试商品C");
            missingStore.setPromotionPosition("默认位-1");

            PromotionOrderItem missingPosition = new PromotionOrderItem();
            missingPosition.setOrderNo("ORD1004");
            missingPosition.setProductName("测试商品D");
            missingPosition.setStoreName("测试店铺D");

            List<PromotionOrderItem> batch = List.of(validItem, missingOrderNo, missingProduct, missingStore, missingPosition);

            OrderStore.IngestBatchResult res = store.ingestOrders(
                    "jd-main", "jd", 100L, "2026-10-23 00:00:00", "2026-10-23 23:59:59",
                    true, batch, true, false, subscribers);

            assertEquals(1, res.newOrdersCount, "Only valid item should create a new order");
            assertEquals(1, res.itemsCount, "Only 1 valid item should be ingested");

            // 2. Query orders
            OrderQueryFilter filter = new OrderQueryFilter();
            filter.setAuthorizedSources(List.of("jd-main"));
            filter.setPage(1);
            filter.setPageSize(10);
            OrderPage page = store.queryOrders(filter);
            assertEquals(1, page.getTotal());
            OrderRecord rec = page.getItems().get(0);
            assertEquals("ORD1001", rec.getPlatformOrderNo());

            // 3. Verify items
            List<OrderItemRecord> items = store.getOrderItems(rec.getId());
            assertEquals(1, items.size());
            OrderItemRecord itemRec = items.get(0);
            assertEquals("测试商品A", itemRec.getProductName());
            assertEquals("测试店铺A", itemRec.getStoreName());
            assertEquals("默认位-1", itemRec.getPromotionPosition());

            // 4. Verify webhook deliveries registered
            Timestamp now = Timestamp.from(Instant.now());
            List<OrderEventDeliveryDto> deliveries = store.dueDeliveries(now, 10);
            assertEquals(1, deliveries.size());
            assertEquals("test-sub", deliveries.get(0).getSubscriberId());
            assertEquals("http://localhost:9999/webhook", deliveries.get(0).getTargetUrl());
            assertNotNull(deliveries.get(0).getPayload());

            // Claim delivery
            boolean claimed = store.claimDelivery(deliveries.get(0).getId(), now);
            assertTrue(claimed);

            // Mark delivery success
            store.updateDeliveryResult(deliveries.get(0).getId(), OrderCodes.DELIVERY_SUCCESS, 1, null, 200, null, now, now);
            List<OrderEventDeliveryDto> afterSuccess = store.dueDeliveries(now, 10);
            assertTrue(afterSuccess.isEmpty(), "No pending deliveries after success");

            // 5. Test idempotency: re-ingest the same order
            OrderStore.IngestBatchResult res2 = store.ingestOrders(
                    "jd-main", "jd", 101L, "2026-10-23 00:00:00", "2026-10-23 23:59:59",
                    true, List.of(validItem), true, false, subscribers);

            assertEquals(0, res2.newOrdersCount, "Re-ingesting same order should not create new order");
            page = store.queryOrders(filter);
            assertEquals(1, page.getTotal(), "Total order count should remain 1");
        }
    }
}
