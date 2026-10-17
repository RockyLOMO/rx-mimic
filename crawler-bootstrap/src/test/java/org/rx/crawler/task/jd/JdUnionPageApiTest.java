package org.rx.crawler.task.jd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.task.common.PromotionOrderItem;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdUnionPageApiTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseSearchItemsReadsGoodsSynopsisList() throws Exception {
        String json = "{\"result\":{\"goodsSynopsisList\":[{\"skuId\":100135442777,\"skuName\":\"维达细韧\","
                + "\"wlPrice\":76.66,\"wlCommissionRatio\":1.7,\"shopInfo\":{\"shopName\":\"维达京东自营旗舰店\"},"
                + "\"imageUrl\":\"jfs/t1/a.jpg\"}]}}";
        List<JdUnionProductItem> items = JdUnionPageApi.parseSearchItems(mapper.readTree(json), 20);
        assertEquals(1, items.size());
        assertEquals("100135442777", items.get(0).getSkuId());
        assertEquals("https://item.jd.com/100135442777.html", items.get(0).getProductLink());
        assertEquals("76.66", items.get(0).getPrice());
        assertEquals("1.7%", items.get(0).getCommissionRate());
        assertEquals("维达京东自营旗舰店", items.get(0).getStoreName());
        assertTrue(items.get(0).getImageUrl().startsWith("https://img14.360buyimg.com/n1/"));
    }

    @Test
    void parseSearchItemsFindsSkuListNestedAwayFromTheKnownKeys() throws Exception {
        String json = "{\"code\":\"200\",\"payload\":{\"rows\":[{\"skuId\":\"7224900\",\"skuName\":\"洁柔抽纸\"}]}}";
        List<JdUnionProductItem> items = JdUnionPageApi.parseSearchItems(mapper.readTree(json), 20);
        assertEquals(1, items.size());
        assertEquals("7224900", items.get(0).getSkuId());
    }

    @Test
    void parseSearchItemsReturnsNullWhenShapeIsUnknown() throws Exception {
        assertNull(JdUnionPageApi.parseSearchItems(mapper.readTree("{\"message\":\"no\"}"), 20));
    }

    @Test
    void findPromotionUrlPrefersUnionShortLink() throws Exception {
        String json = "{\"code\":\"200\",\"data\":{\"clickUrl\":\"https://item.jd.com/1.html\","
                + "\"shortUrl\":\"https://u.jd.com/abc\"}}";
        assertEquals("https://u.jd.com/abc", JdUnionPageApi.findPromotionUrl(mapper.readTree(json).path("data")));
    }

    @Test
    void pickMediaFallsBackToAccountMediaWhenNameMissing() throws Exception {
        String json = "[{\"id\":-1,\"mediaName\":\"请选择\"},{\"id\":88,\"mediaName\":\"历史聊天工具 wx\"}]";
        assertEquals("88", JdUnionPageApi.pickMedia(mapper.readTree(json), "微信").path("id").asText());
    }

    @Test
    void pickSiteMatchesAdSitePrefix() throws Exception {
        String json = "[{\"id\":1,\"promotionName\":\"1会员\"},{\"id\":5,\"promotionName\":\"5会员\"}]";
        assertEquals("5", JdUnionPageApi.pickSite(mapper.readTree(json), "5").path("id").asText());
    }

    @Test
    void orderRowKeepsRequiredFields() throws Exception {
        String body = "{\"code\":\"200\",\"data\":{\"result\":[{\"orderId\":1234567890123456,\"skuName\":\"抽纸\","
                + "\"skuId\":1001,\"validCodeStr\":\"已付款\",\"estimateCosPrice\":\"10.00\",\"estimateFeeOrder\":\"1.20\","
                + "\"orderTimeStr\":\"2026-10-17 00:01:00\",\"skuShopName\":\"京东自营\"}],\"hasNext\":false}}";
        List<PromotionOrderItem> parsed = JdUnionPageApi.parseOrderPage(mapper.readTree(body));
        assertEquals(1, parsed.size());
        assertEquals("1234567890123456", parsed.get(0).getOrderNo());
        assertEquals("抽纸", parsed.get(0).getProductName());
        assertEquals("京东自营", parsed.get(0).getStoreName());
        assertEquals("已付款", parsed.get(0).getOrderStatus());
        assertEquals("￥1.20", parsed.get(0).getEstimatedCommission());
        assertEquals("https://item.jd.com/1001.html", parsed.get(0).getProductLink());
    }

    @Test
    void orderQueryTimeAddsClockToDateOnlyValues() {
        assertEquals("2026-10-10 00:00:00", JdUnionPageApi.orderQueryTime("2026-10-10", false));
        assertEquals("2026-10-17 23:59:59", JdUnionPageApi.orderQueryTime(" 2026-10-17 ", true));
        assertEquals("2026-10-17 12:00:00", JdUnionPageApi.orderQueryTime("2026-10-17 12:00:00", true));
        assertEquals("", JdUnionPageApi.orderQueryTime(null, false));
    }
}
