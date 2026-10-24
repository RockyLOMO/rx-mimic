package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.service.Browser;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TbUnionPageApiTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void allLegacyGatewayEntrypointsFailWithoutTouchingTheBrowser() {
        Browser browser = mock(Browser.class);
        var api = new TbUnionPageApi(mapper);
        assertFalse(api.searchProducts(browser, "sample", 2).success);
        assertFalse(api.searchProducts(browser, "123456789", 2).success);
        assertFalse(api.promote(browser, "123456789", "media", "0", "mm_1_2_3").success);
        assertFalse(api.promote(browser, "OpaqueMarketingProductAlpha123", "media", "0", "mm_1_2_3").success);
        assertFalse(api.listOrders(browser, "2026-10-01", "2026-10-24").success);
        verifyNoInteractions(browser);
    }

    @Test void filteredCardsCannotTurnMultipleRawResultsIntoAnExactIdentityMatch() throws Exception {
        var rows = mapper.readTree("""
                [{"outputMktId":"OpaqueMarketingProductAlpha123","itemName":"sample"},
                 {"outputMktId":"OpaqueMarketingProductBravo456"}]
                """);
        var parsed = TbUnionPageApi.parseSearchItems(rows, 2);
        var result = TbUnionPageApi.SearchHit.ok(parsed, 2, "passive response");
        result.rawCount = rows.size();
        assertEquals(1, result.items.size());
        assertFalse(result.uniqueMaterial());
    }

    @Test
    void pickAdzoneUsesConfiguredPidWhenNameMisses() throws Exception {
        String pid = "mm_10299978403_3442650231_116337200060";
        String json = "[{\"siteTypes\":[{\"sites\":[{\"siteName\":\"RockyLOMO\",\"adzones\":["
                + "{\"adzoneName\":\"0\",\"pid\":\"" + pid + "\"},"
                + "{\"adzoneName\":\"9\",\"pid\":\"mm_1_2_9\"}]}]}]}]";
        JsonNode data = mapper.readTree(json);
        assertEquals(pid, TbUnionPageApi.pickAdzone(data, "RockyLOMO", "0", pid).path("pid").asText());
        assertEquals(pid, TbUnionPageApi.pickAdzone(data, "RockyLOMO", "missing", pid).path("pid").asText());
        assertNull(TbUnionPageApi.pickAdzone(data, "RockyLOMO", "1", ""));
    }

    @Test
    void resolveZoneFallsBackToConfiguredPid() throws Exception {
        String pid = "mm_10299978403_3442650231_116333200319";
        JsonNode zone = TbUnionPageApi.resolveZone(mapper.readTree("[]"), mapper.readTree("null"),
                "RockyLOMO", "1", pid, mapper);
        assertEquals(pid, zone.path("pid").asText());
        assertEquals("1", zone.path("adzoneName").asText());
        assertTrue(TbUnionPageApi.isTbPid(pid));
        assertFalse(TbUnionPageApi.isTbPid("5"));
        assertNull(TbUnionPageApi.resolveZone(mapper.readTree("[]"), null, "RockyLOMO", "1", "5", mapper));
    }

    @Test void promotionHitKeepsReturnedTokenShortAndLongLinksSeparately() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"couponShortLinkUrl":"https://e.tb.cn/short","url":"https://s.click.taobao.com/long","couponLinkTaoToken":"tb-command"}
                """);
        var hit = TbUnionPageApi.PromotionHit.ok("https://e.tb.cn/short", null, "123456789", "opaque", "test", payload);
        assertEquals("https://e.tb.cn/short", hit.promotionUrl);
        assertEquals("https://e.tb.cn/short", hit.shortUrl);
        assertEquals("https://s.click.taobao.com/long", hit.longUrl);
        assertEquals("tb-command", hit.promotionToken);
    }

    @Test void promotionHitDoesNotInventUnreturnedLinkVariants() {
        JsonNode payload = mapper.createObjectNode();
        var hit = TbUnionPageApi.PromotionHit.ok("https://e.tb.cn/short", null, "123456789", "opaque", "test", payload);
        assertNull(hit.promotionToken);
        assertNull(hit.shortUrl);
        assertNull(hit.longUrl);
    }
}
