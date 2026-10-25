package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.task.common.OrderTimeFields;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.crawler.task.common.PromotionSlot;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TbOrderResponseFieldsTest {
    @Test void naturallyCapturedResponseKeepsAllDistinctFields() throws Exception {
        var rows = new ObjectMapper().readTree("""
            [{"tradeId":"512700000000000001","tradeParentId":"512700000000000000",
            "itemTitle":"sample item","sellerShopTitle":"sample shop","itemNum":2,
            "siteName":"sample_position_123","adzoneName":"sample zone","adzoneId":"456","siteId":"789",
            "tkCreateTime":"2026-09-01 16:04:19","tbPaidTime":"2026-09-01 16:04:09",
            "tkPaidTime":"2026-09-01 16:04:40","tkEarningTime":"2026-09-11 16:52:30",
            "alipayTotalPrice":27.55,"payPrice":20.00,"pubSharePreFee":1.98,"pubShareFee":0,
            "tkTotalRateForCommissionAndSdy":7.19,"pubShareRate":100,"tkStatusText":"已结算",
            "mktId":"OpaqueMarketingProductExample123"}]
            """);
        var order = TbUnionPageApi.parseOrders(rows).getFirst();
        assertEquals("512700000000000001", order.getOrderNo());
        assertEquals("512700000000000001", order.getSourceLineId());
        assertEquals("sample zone", order.getPromotionPosition());
        assertEquals("sample_position_123", order.getExtensions().get("siteName"));
        assertEquals("2026-09-01 16:04:19", order.getOrderTime());
        assertEquals("2026-09-01 16:04:09", order.getPaidTime());
        assertEquals("2026-09-11 16:52:30", order.getFinishTime());
        assertEquals("2026-10-20", order.getSettleTime());
        assertEquals("￥27.55", order.getEstimatedBillingAmount());
        assertEquals("￥20.0", order.getActualBillingAmount());
        assertEquals("￥1.98", order.getEstimatedCommission());
        assertEquals("￥0", order.getActualCommission());
        assertEquals("7.19%", order.getCommissionRate());
        assertEquals("2", order.getProductQuantity());
        assertEquals("", order.getSkuId());
        assertEquals("COMPLETED", order.getNormalizedStatus());
        assertEquals("456", order.getExtensions().get("adzoneId"));
        assertEquals("512700000000000000", order.getExtensions().get("parentOrderNo"));
    }

    @Test void unknownAmountsAndDatesStayUnknown() throws Exception {
        var rows = new ObjectMapper().readTree("""
            [{"tradeId":"512700000000000002","itemTitle":"sample item","tkStatusText":"已付款",
            "alipayTotalPrice":14.08,"pubSharePreFee":0.42,"pubShareFee":0}]
            """);
        var item = TbUnionPageApi.parseOrders(rows).getFirst();
        assertTrue(item.getActualBillingAmount() == null || item.getActualBillingAmount().isEmpty());
        assertEquals("￥0", item.getActualCommission());
        assertEquals("", item.getSettleTime());
        assertEquals("", item.getPromotionPosition());
    }

    @Test void settlementDatesFollowConfirmedBusinessRule() {
        assertEquals("2026-11-20", OrderTimeFields.nextMonthSettlementDate("2026-10-19 12:00:00"));
        assertEquals("2026-12-20", OrderTimeFields.nextMonthSettlementDate("2026-11-01"));
        assertEquals("2027-01-20", OrderTimeFields.nextMonthSettlementDate("2026-12-31"));
        assertEquals("2026-11-20", OrderTimeFields.settlementDate("20261120"));
        assertEquals("", OrderTimeFields.nextMonthSettlementDate("--"));
        assertEquals("", OrderTimeFields.settlementDate("2026-02-30"));
    }

    @Test void adzoneIdsResolveOnlyTheFourConfiguredSlots() throws Exception {
        String[] ids = {"116337200060", "116333200319", "116335500196", "116332950344"};
        for (int i = 0; i < ids.length; i++) {
            var rows = new ObjectMapper().readTree("""
                [{"tradeId":"1","itemTitle":"sample item","siteName":"RockyLOMO",
                "siteId":"3442650231","adzoneName":"old name","adzoneId":"%s"}]
                """.formatted(ids[i]));
            var item = TbUnionPageApi.parseOrders(rows).getFirst();
            assertEquals(Integer.toString(i), item.getPromotionPosition());
            assertEquals(Integer.toString(i), item.getPromotionInfo());
            assertEquals(ids[i], item.getExtensions().get("promotionPositionId"));
            assertEquals("old name", item.getExtensions().get("rawPromotionPositionName"));
            assertEquals("RockyLOMO", item.getExtensions().get("siteName"));
        }
    }

    @Test void mediaSiteDoesNotBecomeAPromotionNameAndUnknownIdsStayRaw() throws Exception {
        var rows = new ObjectMapper().readTree("""
            [{"tradeId":"1","itemTitle":"sample item","siteName":"RockyLOMO","siteId":"3442650231"},
             {"tradeId":"2","itemTitle":"sample item","adzoneId":"3442650231"},
             {"tradeId":"3","itemTitle":"sample item","adzoneId":"999999","adzoneName":"external slot"}]
            """);
        var items = TbUnionPageApi.parseOrders(rows);
        assertEquals("", items.get(0).getPromotionPosition());
        assertEquals("3442650231", items.get(1).getPromotionPosition());
        assertEquals("external slot", items.get(2).getPromotionPosition());
    }

    @Test void suppliedConfigurationAndDomFallbackUseTheSameExactMapping() throws Exception {
        var slots = List.of(new PromotionSlot("2", "mm_10_20_30"));
        var rows = new ObjectMapper().readTree("""
            [{"tradeId":"1","itemTitle":"sample item","adzoneId":"30","siteName":"media"}]
            """);
        assertEquals("2", TbUnionPageApi.parseOrders(rows, slots).getFirst().getPromotionPosition());
        var dom = new PromotionOrderItem();
        dom.setPromotionPosition("30");
        TbUnionPageApi.normalizeOrderPromotionPosition(dom, slots);
        assertEquals("2", dom.getPromotionPosition());
        assertEquals("30", dom.getExtensions().get("rawPromotionPosition"));
    }
}
