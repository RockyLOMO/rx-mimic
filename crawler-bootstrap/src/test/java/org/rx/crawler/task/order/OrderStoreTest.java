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

    @Test
    void testOrderPaginationAndSearchFilters() throws Exception {
        try (EntityDatabase database = new EntityDatabaseImpl(directory.resolve("test_orders_search").toString(), null)) {
            ObjectMapper mapper = new ObjectMapper();
            OrderStore store = new OrderStore(database, mapper);
            store.init();

            PromotionOrderItem item1 = new PromotionOrderItem();
            item1.setOrderNo("ORD_AAA");
            item1.setProductName("苹果手机 iPhone 16");
            item1.setStoreName("苹果京东自营旗舰店");
            item1.setPromotionPosition("推广位-1");
            item1.setSkuId("SKU_999");
            item1.setActualBillingAmount("5999.00");
            item1.setActualCommission("120.00");
            item1.setOrderTime("2026-10-23 10:00:00");

            PromotionOrderItem item2 = new PromotionOrderItem();
            item2.setOrderNo("ORD_BBB");
            item2.setProductName("联想电脑 ThinkPad");
            item2.setStoreName("联想官方旗舰店");
            item2.setPromotionPosition("推广位-2");
            item2.setSkuId("SKU_888");
            item2.setActualBillingAmount("7999.00");
            item2.setActualCommission("150.00");
            item2.setOrderTime("2026-10-23 11:00:00"); // Later time

            store.ingestOrders("jd-main", "jd", 200L, "2026-10-23 00:00:00", "2026-10-23 23:59:59",
                    true, List.of(item1, item2), true, false, null);

            // 1. Test pagination & orderTime DESC (ORD_BBB first)
            OrderQueryFilter page1Filter = new OrderQueryFilter();
            page1Filter.setAuthorizedSources(List.of("jd-main"));
            page1Filter.setPage(1);
            page1Filter.setPageSize(1);
            OrderPage page1 = store.queryOrders(page1Filter);
            assertEquals(2, page1.getTotal());
            assertEquals(1, page1.getItems().size());
            assertEquals("ORD_BBB", page1.getItems().get(0).getPlatformOrderNo(), "Order with later orderTime must be first");
            assertFalse(page1.getItems().get(0).getItems().isEmpty(), "Order items must be populated in list");
            assertEquals("SKU_888", page1.getItems().get(0).getItems().get(0).getSkuId());

            OrderQueryFilter page2Filter = new OrderQueryFilter();
            page2Filter.setAuthorizedSources(List.of("jd-main"));
            page2Filter.setPage(2);
            page2Filter.setPageSize(1);
            OrderPage page2 = store.queryOrders(page2Filter);
            assertEquals(2, page2.getTotal());
            assertEquals(1, page2.getItems().size());
            assertEquals("ORD_AAA", page2.getItems().get(0).getPlatformOrderNo());

            // 2. Search by orderNo
            OrderQueryFilter orderNoFilter = new OrderQueryFilter();
            orderNoFilter.setAuthorizedSources(List.of("jd-main"));
            orderNoFilter.setOrderNo("ORD_AAA");
            OrderPage orderNoPage = store.queryOrders(orderNoFilter);
            assertEquals(1, orderNoPage.getTotal());
            assertEquals("ORD_AAA", orderNoPage.getItems().get(0).getPlatformOrderNo());

            // 3. Search by productId / skuId
            OrderQueryFilter skuFilter = new OrderQueryFilter();
            skuFilter.setAuthorizedSources(List.of("jd-main"));
            skuFilter.setSkuId("SKU_888");
            OrderPage skuPage = store.queryOrders(skuFilter);
            assertEquals(1, skuPage.getTotal());
            assertEquals("ORD_BBB", skuPage.getItems().get(0).getPlatformOrderNo());

            OrderQueryFilter pidFilter = new OrderQueryFilter();
            pidFilter.setAuthorizedSources(List.of("jd-main"));
            pidFilter.setProductId("SKU_999");
            OrderPage pidPage = store.queryOrders(pidFilter);
            assertEquals(1, pidPage.getTotal());
            assertEquals("ORD_AAA", pidPage.getItems().get(0).getPlatformOrderNo());

            // 4. Search by productName fuzzy
            OrderQueryFilter nameFilter = new OrderQueryFilter();
            nameFilter.setAuthorizedSources(List.of("jd-main"));
            nameFilter.setProductName("电脑");
            OrderPage namePage = store.queryOrders(nameFilter);
            assertEquals(1, namePage.getTotal());
            assertEquals("ORD_BBB", namePage.getItems().get(0).getPlatformOrderNo());

            OrderQueryFilter noMatchFilter = new OrderQueryFilter();
            noMatchFilter.setAuthorizedSources(List.of("jd-main"));
            noMatchFilter.setProductName("不存在的商品名称");
            OrderPage noMatchPage = store.queryOrders(noMatchFilter);
            assertEquals(0, noMatchPage.getTotal());
        }
    }
}
