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

    private PromotionOrderItem fieldSample(String orderNo) {
        PromotionOrderItem item = new PromotionOrderItem();
        item.setOrderNo(orderNo);
        item.setSkuId("10001");
        item.setProductName("字段回归商品");
        item.setStoreName("测试店铺");
        item.setPromotionPosition("0");
        item.setOrderStatus("已完成");
        item.setProductQuantity("1");
        item.setOrderTime("2026-10-19 10:00:00");
        item.setPaidTime("2026-10-19 10:01:00");
        item.setFinishTime("2026-10-20 10:00:00");
        item.setSettleTime("2026-11-20 00:00:00");
        item.setEstimatedBillingAmount("100.00");
        item.setEstimatedCommission("5.00");
        item.setActualBillingAmount("0.00");
        item.setActualCommission("0.00");
        return item;
    }

    private long ingestSample(OrderStore store, String platform, PromotionOrderItem item) {
        store.ingestOrders("fields", platform, 1L, "2026-10-01", "2026-10-31", true,
                List.of(item), false, false, List.of());
        OrderQueryFilter filter = new OrderQueryFilter();
        filter.setAuthorizedSources(List.of("fields"));
        return store.queryOrders(filter).getItems().get(0).getId();
    }

    @Test
    void independentAmountsZeroCountsDatesAndExtensionsSurviveH2RoundTrip() throws Exception {
        try (EntityDatabase database = new EntityDatabaseImpl(directory.resolve("field_roundtrip").toString(), null)) {
            OrderStore store = new OrderStore(database, new ObjectMapper());
            store.init();
            PromotionOrderItem sample = fieldSample("FIELDS1");
            sample.setAfterSaleQuantity("2");
            sample.setReturnQuantity("0");
            sample.setShareRate("100%");
            sample.getExtensions().put("custom", java.util.Map.of("retained", true));
            long id = ingestSample(store, "jd", sample);
            OrderRecord stored = store.getOrderWithItems(id);
            assertEquals("已完成", stored.getOrderStatus());
            assertEquals("INVALID", stored.getNormalizedStatus());
            assertEquals("已完成", stored.getExtensions().get("rawOrderStatus"));
            assertEquals(2, stored.getExtensions().get("afterSaleQuantity"));
            assertEquals(0, stored.getExtensions().get("returnQuantity"));
            assertEquals("2026-11-20", stored.getSettleTime());
            assertEquals(sample.getPaidTime(), stored.getPaidTime());
            assertEquals(sample.getFinishTime(), stored.getFinishTime());
            assertEquals(new java.math.BigDecimal("100.00"), stored.getEstimatedBillingAmount());
            assertEquals(new java.math.BigDecimal("5.00"), stored.getEstimatedCommission());
            assertEquals(new java.math.BigDecimal("0.00"), stored.getActualBillingAmount());
            assertEquals(new java.math.BigDecimal("0.00"), stored.getActualCommission());
            assertEquals(new java.math.BigDecimal("0.00"), stored.getTotalAmount());
            assertEquals(new java.math.BigDecimal("0.00"), stored.getTotalCommission());
            assertEquals(stored.getExtensions(), stored.getItems().get(0).getExtensions());
            assertEquals("INVALID", stored.getItems().get(0).getNormalizedStatus());
            // A partial subsequent observation must retain unknown extension keys.
            sample.setExtensions(new java.util.LinkedHashMap<>());
            sample.setAfterSaleQuantity("0");
            ingestSample(store, "jd", sample);
            OrderRecord updated = store.getOrderWithItems(id);
            assertEquals("COMPLETED", updated.getNormalizedStatus());
            assertEquals(0, updated.getExtensions().get("afterSaleQuantity"));
            assertNotNull(updated.getExtensions().get("custom"));
            assertEquals(0, updated.getItems().get(0).getRefundQuantity());
            sample.setActualBillingAmount(null);
            sample.setActualCommission(null);
            ingestSample(store, "jd", sample);
            assertEquals(new java.math.BigDecimal("0.00"), store.getOrderWithItems(id).getActualBillingAmount());
            assertEquals(new java.math.BigDecimal("0.00"), store.getOrderWithItems(id).getTotalAmount());
            sample.setReturnQuantity("1");
            ingestSample(store, "jd", sample);
            assertEquals("INVALID", store.getOrderWithItems(id).getNormalizedStatus());
        }
    }

    @Test
    void migrationIsIdempotentAndLegacyJsonCountsAreExact() throws Exception {
        try (EntityDatabase database = new EntityDatabaseImpl(directory.resolve("field_legacy").toString(), null)) {
            OrderStore store = new OrderStore(database, new ObjectMapper());
            store.init();
            PromotionOrderItem sample = fieldSample("LEGACY1");
            sample.setActualBillingAmount(null);
            sample.setActualCommission(null);
            sample.setRawJson("{\"skuFrozenNum\":0,\"skuReturnNum\":0,\"mainOrderNo\":\"PARENT\",\"description\":\"退货99售后88\"}");
            long id = ingestSample(store, "jd", sample);
            // Simulate a pre-upgrade H2 row with no additive columns/data.
            for (String column : List.of("paid_time", "extensions_json", "estimated_billing_amount", "estimated_commission", "actual_billing_amount", "actual_commission")) {
                database.executeUpdate("alter table promotion_order drop column " + column);
            }
            for (String column : List.of("paid_time", "extensions_json", "normalized_status")) {
                database.executeUpdate("alter table promotion_order_item drop column " + column);
            }
            store.init();
            store.init();
            OrderRecord restored = store.getOrderWithItems(id);
            assertEquals("COMPLETED", restored.getNormalizedStatus());
            assertEquals(0, restored.getExtensions().get("afterSaleQuantity"));
            assertEquals(0, restored.getExtensions().get("returnQuantity"));
            assertEquals("PARENT", restored.getExtensions().get("parentOrderNo"));
            assertNull(restored.getActualBillingAmount());
            assertNull(restored.getActualCommission());
            assertEquals(new java.math.BigDecimal("100.00"), restored.getEstimatedBillingAmount());
            assertNull(OrderFields.strictQuantity("商品1 售后2 退货3"));
        }
    }

    @Test
    void platformExtensionNumbersDoNotInvalidateTaobaoAndUnknownIsNotZero() throws Exception {
        try (EntityDatabase database = new EntityDatabaseImpl(directory.resolve("field_tb").toString(), null)) {
            OrderStore store = new OrderStore(database, new ObjectMapper());
            store.init();
            PromotionOrderItem sample = fieldSample("TB1");
            sample.setOrderStatus("已结算");
            sample.getExtensions().put("afterSaleQuantity", 3);
            sample.getExtensions().put("parentOrderNo", "PARENT");
            sample.getExtensions().put("siteName", "MEDIA_123");
            sample.setRawJson("{\"tkEarningTime\":\"2026-09-11 16:52:30\",\"tbPaidTime\":\"2026-09-01 16:04:09\",\"tkPaidTime\":\"2026-09-01 16:04:24\"}");
            long id = ingestSample(store, "tb", sample);
            OrderRecord record = store.getOrderWithItems(id);
            assertEquals("COMPLETED", record.getNormalizedStatus());
            assertEquals("MEDIA_123", record.getItems().get(0).getExtensions().get("siteName"));
            assertEquals("PARENT", record.getExtensions().get("parentOrderNo"));
            assertEquals("2026-09-11 16:52:30", record.getFinishTime());
            assertEquals("2026-09-01 16:04:09", record.getPaidTime());
            assertEquals("2026-10-20", record.getSettleTime());
            assertEquals("receipt-next-month-20", record.getExtensions().get("settlementDateSource"));
            assertNull(OrderFields.settlementDate("--"));
            assertEquals("2026-11-20", OrderFields.settlementDate("20261120"));
            assertNull(OrderFields.settlementDate("bad-date"));
        }
    }

    @Test
    void latestTimeSemanticsApplyToNewAndHistoricalJdOrders() throws Exception {
        try (EntityDatabase database = new EntityDatabaseImpl(directory.resolve("payment_receipt").toString(), null)) {
            OrderStore store = new OrderStore(database, new ObjectMapper());
            store.init();
            PromotionOrderItem sample = fieldSample("JD_TIME");
            sample.setOrderStatus("3");
            sample.setPaidTime(null);
            sample.setFinishTime("2026-11-01 16:00:00");
            sample.setSettleTime("2026-11-20"); // stale source must not override the receipt rule
            long id = ingestSample(store, "jd", sample);
            OrderRecord record = store.getOrderWithItems(id);
            assertEquals(sample.getOrderTime(), record.getPaidTime());
            assertEquals("2026-12-20", record.getSettleTime());
            assertEquals("COMPLETED", record.getNormalizedStatus());
            database.executeUpdate("update promotion_order set paid_time = null, settle_time = '2026-11-20', normalized_status = 'UNKNOWN'");
            database.executeUpdate("update promotion_order_item set paid_time = null, normalized_status = 'UNKNOWN'");
            record = store.getOrderWithItems(id);
            assertEquals(sample.getOrderTime(), record.getPaidTime());
            assertEquals(sample.getOrderTime(), record.getItems().getFirst().getPaidTime());
            assertEquals("2026-12-20", record.getSettleTime());
            assertEquals("COMPLETED", record.getNormalizedStatus());
            assertEquals("COMPLETED", record.getItems().getFirst().getNormalizedStatus());
        }
    }

    @Test
    void taobaoProductIdsAreReusedWithinSourceAndCannotBeOverwritten() throws Exception {
        try (EntityDatabase database = new EntityDatabaseImpl(directory.resolve("tb_product_identity_cache").toString(), null)) {
            OrderStore store = new OrderStore(database, new ObjectMapper());
            store.init();
            PromotionOrderItem initial = fieldSample("TB-CACHED");
            long orderId = ingestSample(store, "tb", initial);

            PromotionOrderItem refresh = fieldSample("TB-CACHED");
            refresh.setSkuId(null);
            assertEquals(1, store.restoreTbProductIds("fields", List.of(refresh)));
            assertEquals("10001", refresh.getSkuId());
            refresh.setSkuId("99999");
            refresh.setActualCommission("8.00");
            ingestSample(store, "tb", refresh);
            assertEquals("10001", refresh.getSkuId());
            assertEquals("10001", store.getOrderWithItems(orderId).getItems().get(0).getSkuId());
            assertEquals(new java.math.BigDecimal("8.00"), store.getOrderWithItems(orderId).getItems().get(0).getActualCommission());

            refresh.setSkuId(" ");
            ingestSample(store, "tb", refresh);
            assertEquals("10001", store.getOrderWithItems(orderId).getItems().get(0).getSkuId());
            refresh.setSkuId(null);
            assertEquals(0, store.restoreTbProductIds("another-account", List.of(refresh)));
            assertEquals(0, store.restoreTbProductIds(null, List.of(refresh)));
            assertNull(refresh.getSkuId());
            PromotionOrderItem missing = fieldSample("TB-NEW");
            missing.setSkuId(null);
            assertEquals(0, store.restoreTbProductIds("fields", List.of(missing)));
            assertNull(missing.getSkuId());

            PromotionOrderItem jd = fieldSample("JD-ONLY");
            store.ingestOrders("another-source", "jd", 1L, "2026-10-01", "2026-10-31", true,
                    List.of(jd), false, false, List.of());
            jd.setSkuId(null);
            assertEquals(0, store.restoreTbProductIds("another-source", List.of(jd)));
            assertNull(jd.getSkuId());
        }
    }

    @Test
    void taobaoIdentityLookupBatchesAndSkipsUnresolvedLegacyValues() throws Exception {
        try (EntityDatabase database = new EntityDatabaseImpl(directory.resolve("tb_product_identity_batches").toString(), null)) {
            OrderStore store = new OrderStore(database, new ObjectMapper());
            store.init();
            java.util.ArrayList<PromotionOrderItem> batch = new java.util.ArrayList<>();
            for (int i = 0; i < 105; i++) batch.add(fieldSample("TB-" + i));
            store.ingestOrders("tb-batch", "tb", 1L, "2026-10-01", "2026-10-31", true,
                    batch, false, false, List.of());
            batch.forEach(item -> item.setSkuId(null));
            assertEquals(105, store.restoreTbProductIds("tb-batch", batch));
            assertTrue(batch.stream().allMatch(item -> "10001".equals(item.getSkuId())));

            PromotionOrderItem unresolved = fieldSample("TB-UNRESOLVED");
            unresolved.setSkuId("encrypted-old-id");
            ingestSample(store, "tb", unresolved);
            unresolved.setSkuId(null);
            assertEquals(0, store.restoreTbProductIds("fields", List.of(unresolved)));
            unresolved.setSkuId("123456789");
            long id = ingestSample(store, "tb", unresolved);
            assertEquals("123456789", store.getOrderWithItems(id).getItems().get(0).getSkuId());
        }
    }

    @Test
    void taobaoChildIdentitySurvivesProductDiscoveryAndLegacyLineIdsWithoutMergingJdSkus() throws Exception {
        try (EntityDatabase database = new EntityDatabaseImpl(directory.resolve("line_identity").toString(), null)) {
            OrderStore store = new OrderStore(database, new ObjectMapper());
            store.init();
            PromotionOrderItem sample = fieldSample("TBCHILD");
            sample.setSkuId(null);
            long orderId = ingestSample(store, "tb", sample);
            OrderItemRecord original = store.getOrderWithItems(orderId).getItems().get(0);
            assertEquals("TBCHILD", original.getSourceLineId());
            sample.setSkuId("12345"); // popup resolved the product ID
            ingestSample(store, "tb", sample);
            sample.setSourceLineId("TBCHILD"); // natural API response uses the child ID directly
            ingestSample(store, "tb", sample);
            OrderRecord record = store.getOrderWithItems(orderId);
            assertEquals(1, record.getItems().size());
            assertEquals(original.getId(), record.getItems().get(0).getId());
            assertEquals("12345", record.getItems().get(0).getSkuId());
            assertEquals(new java.math.BigDecimal("100.00"), record.getEstimatedBillingAmount());
            assertEquals(1, record.getItemCount());
            // Existing pre-upgrade single-line records are reused, without deleting or recreating them.
            database.executeUpdate("update promotion_order_item set source_line_id = 'TBCHILD:' where source_id = 'fields'");
            ingestSample(store, "tb", sample);
            sample.setSourceLineId(null); // DOM fallback previously supplied no sourceLineId
            ingestSample(store, "tb", sample);
            record = store.getOrderWithItems(orderId);
            assertEquals(1, record.getItems().size());
            assertEquals(original.getId(), record.getItems().get(0).getId());
            assertEquals("TBCHILD:", record.getItems().get(0).getSourceLineId());
            database.executeUpdate("update promotion_order_item set source_line_id = 'TBCHILD:12345' where source_id = 'fields'");
            sample.setSourceLineId("TBCHILD");
            ingestSample(store, "tb", sample);
            assertEquals(1, store.getOrderWithItems(orderId).getItems().size());
            assertEquals(original.getId(), store.getOrderWithItems(orderId).getItems().get(0).getId());

            PromotionOrderItem jd1 = fieldSample("JDMULTI");
            PromotionOrderItem jd2 = fieldSample("JDMULTI");
            jd2.setSkuId("10002");
            store.ingestOrders("jd-lines", "jd", 1L, "2026-10-01", "2026-10-31", true,
                    List.of(jd1, jd2), false, false, List.of());
            OrderQueryFilter filter = new OrderQueryFilter();
            filter.setAuthorizedSources(List.of("jd-lines"));
            OrderRecord jd = store.queryOrders(filter).getItems().get(0);
            assertEquals(2, jd.getItems().size());
            assertNotEquals(jd.getItems().get(0).getSourceLineId(), jd.getItems().get(1).getSourceLineId());
            assertEquals(new java.math.BigDecimal("200.00"), jd.getEstimatedBillingAmount());
        }
    }
}
