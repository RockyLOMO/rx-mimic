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

    @Test void challengeGateCannotBeSkippedByTheGatewayShortcut() {
        Browser browser = mock(Browser.class);
        var api = new TbUnionPageApi(mapper, id -> null, current -> false);
        assertFalse(api.searchProducts(browser, "sample", 2).success);
        verify(browser, never()).executeScript(anyString(), any());
    }

    @Test void challengeAfterContextLossStillReachesTheSharedPostGatewayHook() {
        Browser browser = mock(Browser.class);
        when(browser.executeScript(anyString(), any())).thenThrow(new IllegalStateException("context destroyed"));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var api = new TbUnionPageApi(mapper, id -> null, current -> calls.incrementAndGet() == 1);
        var result = api.searchProducts(browser, "sample", 2);
        assertFalse(result.success);
        assertEquals(2, calls.get());
        assertTrue(result.diagnostic.contains("TB_SLIDER_VERIFICATION_REQUIRED"));
    }

    @Test void filteredCardsCannotTurnMultipleRawResultsIntoAnExactIdentityMatch() {
        Browser browser = mock(Browser.class);
        when(browser.executeScript(anyString(), any())).thenReturn("""
                {"ok":true,"body":{"data":{"siteData":{"totalCount":2,"resultList":[
                  {"outputMktId":"OpaqueMarketingProductAlpha123","itemName":"sample"},
                  {"outputMktId":"OpaqueMarketingProductBravo456"}
                ]}}}}
                """);
        var result = new TbUnionPageApi(mapper).searchProducts(browser, "OpaqueMarketingProductAlpha123", 2);
        assertEquals(1, result.items.size());
        assertEquals(2, result.rawCount);
        assertFalse(result.uniqueMaterial());
    }

    @Test void numericSearchUsesOnlyTheTemporaryMaterialHintInGateway() {
        Browser browser = mock(Browser.class);
        when(browser.executeScript(anyString(), any())).thenAnswer(call -> {
            JsonNode spec = mapper.readTree(call.getArgument(1, String.class));
            JsonNode extras = mapper.readTree(spec.path("form").path("extras").asText());
            assertEquals("OpaqueMarketingProductAlpha123", extras.path("variableMap").path("q").asText());
            return "{\"ok\":true,\"body\":{\"data\":{\"siteData\":{\"resultList\":[]}}}}";
        });
        assertTrue(new TbUnionPageApi(mapper, id -> "OpaqueMarketingProductAlpha123")
                .searchProducts(browser, "123456789", 2).success);
    }

    @Test void numericPromotionAcceptsMissingOrOpaqueGatewayIdentityForRedirectResolution() throws Exception {
        for (String gatewayId : new String[]{"", "OpaqueMarketingProductAlpha123"}) {
            TbUnionPageApi.PromotionHit hit = promoteNumeric(gatewayId);
            assertTrue(hit.success, hit.diagnostic);
            assertEquals("", hit.itemId);
            assertEquals("https://s.click.taobao.com/example", hit.promotionUrl);
        }
    }

    @Test void explicitDifferentNumericGatewayProductIsStillRejected() throws Exception {
        TbUnionPageApi.PromotionHit hit = promoteNumeric("999999999");
        assertFalse(hit.success);
    }

    private TbUnionPageApi.PromotionHit promoteNumeric(String gatewayId) throws Exception {
        Browser browser = mock(Browser.class);
        when(browser.executeScript(anyString(), any())).thenAnswer(call -> {
            JsonNode spec = mapper.readTree(call.getArgument(1, String.class));
            String path = spec.path("path").asText();
            var response = mapper.createObjectNode();
            response.put("ok", true);
            var data = response.putObject("body").putObject("data");
            if (path.endsWith("skyleap.distribution.site.json")) {
                data.putObject("siteData").putArray("resultList").addObject()
                        .put("outputMktId", "OpaqueMarketingProductAlpha123").put("itemName", "sample");
            } else if (path.endsWith("xt.entry.json")) {
                data.putArray("resultList").addObject().put("itemId", gatewayId)
                        .put("itemName", "sample").put("shortLinkUrl", "https://s.click.taobao.com/example");
            }
            return response.toString();
        });
        return new TbUnionPageApi(mapper).promote(browser, "123456789", "media", "0", "mm_1_2_3");
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
