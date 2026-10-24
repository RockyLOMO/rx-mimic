package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.task.common.OrderTimeFields;
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
        assertEquals("sample_position_123", order.getPromotionPosition());
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
}
