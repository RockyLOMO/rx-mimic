package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.task.common.TaskApiException;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JSON normalization with a mocked identity resolver; never opens Chrome. */
class UnionMaterialBoundaryTest {
    @Test void jdCouponRemainsOutputButOnlyOrdinaryPromotionIsOpenedForIdentity() throws Exception {
        var resolver=mock(ProductIdentityResolver.class);
        var boundary=new UnionMaterialBoundary(MAPPER,resolver);
        var input=(com.fasterxml.jackson.databind.node.ObjectNode) promotionWithKeyword(ID);
        input.put("promotionUrl","https://u.jd.com/coupon").put("shortUrl","https://u.jd.com/coupon")
                .put("promotionToken","! short! code");
        input.putObject("diagnostics").put("identityPromotionUrl","https://u.jd.com/product");
        when(resolver.resolve(PriceCodes.JD,"common","https://u.jd.com/product")).thenReturn(ID);
        JsonNode result=boundary.result("getPromotionUrl",input,301);
        assertEquals("https://u.jd.com/coupon",result.path("shortUrl").asText());
        assertEquals("https://u.jd.com/coupon",result.path("promotionUrl").asText());
        assertEquals("! short! code",result.path("promotionToken").asText());
        assertTrue(result.path("identityResolved").asBoolean());
        assertFalse(result.toString().contains("https://u.jd.com/product"));
        verify(resolver).resolve(PriceCodes.JD,"common","https://u.jd.com/product");
        verify(resolver,never()).resolve(PriceCodes.JD,"common","https://u.jd.com/coupon");
        verify(resolver).remember(PriceCodes.JD,"common","https://u.jd.com/coupon",ID);
    }

    @Test void jdMobileLoginIsRecognizedAndReportedInsteadOfPretendingLinkIdentitySucceeded() throws Exception {
        assertEquals(ProductIdentityParser.PageKind.LOGIN, ProductIdentityParser.inspect(PriceCodes.JD,
                "https://plogin.m.jd.com/login/login?appid=300&returnurl=https%3A%2F%2Fprom.m.jd.com").kind);
        assertEquals(ProductIdentityParser.PageKind.OTHER, ProductIdentityParser.inspect(PriceCodes.JD,
                "https://plogin.m.jd.com.other.test/login/login").kind);
        var resolver=mock(ProductIdentityResolver.class);
        when(resolver.resolve(anyInt(),anyString(),anyString())).thenThrow(new TaskApiException(409,"LOGIN_REQUIRED","LOGIN_REQUIRED"));
        var boundary=new UnionMaterialBoundary(MAPPER,resolver);
        var result=boundary.result("getPromotionUrl",promotion(),302);
        assertEquals("LOGIN_REQUIRED",result.path("status").asText());
        assertTrue(result.path("loginRequired").asBoolean());
        assertFalse(result.path("identityResolved").asBoolean());
        assertFalse(result.has("productInfo"));
    }
    @org.junit.jupiter.api.Test void ordinaryPromotionKeywordsReachTheSelectionPageWithoutIdentityResolution() {
        var resolver = org.mockito.Mockito.mock(ProductIdentityResolver.class);
        var boundary = new UnionMaterialBoundary(MAPPER, resolver);
        var single = MAPPER.valueToTree(boundary.request("getTbPromotionUrl", java.util.Map.of("keyword", "抽纸")));
        org.junit.jupiter.api.Assertions.assertEquals("抽纸", single.path("keyword").asText());
        var batch = MAPPER.valueToTree(boundary.request("getTbPromotionUrls", java.util.List.of("抽纸", "猫粮")));
        org.junit.jupiter.api.Assertions.assertEquals("猫粮", batch.get(1).asText());
        org.mockito.Mockito.verifyNoInteractions(resolver);
    }
    private static final String MATERIAL_A = "EncryptedMaterialAlpha123";
    private static final String MATERIAL_B = "EncryptedMaterialBravo456";
    private static final String ID = "123456789";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void databaseCacheHitDoesNotRevisitPromotionUrlAfterIdentityMemoryIsLost() {
        var resolver=mock(ProductIdentityResolver.class);
        var boundary=new UnionMaterialBoundary(MAPPER,resolver);
        var cached=MAPPER.createObjectNode().put("status","SUCCESS").put("productId",ID)
                .put("identityResolved",true).put("cacheHit",true).put("promotionUrl","https://u.jd.com/cached");
        JsonNode durable=boundary.result("getPromotionUrl",cached,202);
        assertEquals(ID,durable.path("productId").asText());
        assertEquals("https://u.jd.com/cached",durable.path("promotionUrl").asText());
        verifyNoInteractions(resolver);
    }

    @Test
    void numericSearchKeepsExplicitIdentityVerificationInDurableAndLiveResults() {
        var boundary = new UnionMaterialBoundary(MAPPER, mock(ProductIdentityResolver.class));
        var result = MAPPER.createObjectNode().put("status", "SUCCESS");
        var items = result.putArray("items");
        items.addObject().put("productId", ID).put("identityResolved", true).put("price", "105.00");
        items.addObject().put("productId", "223456789").put("identityResolved", false);
        items.addObject().put("productId", "323456789");
        JsonNode durable = boundary.result("tbUnionSearchProducts", result, 201);
        for (JsonNode projected : List.of(durable, boundary.liveResult(201))) {
            assertTrue(projected.path("items").get(0).path("identityResolved").asBoolean());
            assertEquals("105.00", projected.path("items").get(0).path("price").asText());
            assertFalse(projected.path("items").get(1).path("identityResolved").asBoolean());
            assertFalse(projected.path("items").get(2).has("identityResolved"), "numeric shape alone is not verification");
        }
    }

    @Test
    void encryptedJdInputIsResolvedBeforeItBecomesDurableRequest() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.JD, "common", MATERIAL_A)).thenReturn(ID);
        JsonNode input = MAPPER.readTree("{\"keyword\":\"" + MATERIAL_A + "\",\"adSiteName\":\"0\"}");
        JsonNode clean = MAPPER.valueToTree(boundary.request("jdUnionSearchProducts", input));
        assertEquals(ID, clean.path("keyword").asText());
        assertEquals("0", clean.path("adSiteName").asText());
        assertFalse(clean.toString().contains(MATERIAL_A));
        verify(resolver).resolve(PriceCodes.JD, "common", MATERIAL_A);
        verifyNoMoreInteractions(resolver);
        assertEquals(MATERIAL_A, input.path("keyword").asText(), "normalization must not mutate caller input");
    }

    @Test
    void batchPromotionKeywordsStayOpaqueOnTheQueuedTask() {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        // 提交路径不解析：入队前打开 Chrome 会让 POST /tasks 无法及时返回 202（公网网关 30 秒关断）。
        JsonNode clean = MAPPER.valueToTree(boundary.request("getPromotionUrls", List.of(MATERIAL_A, MATERIAL_B)));
        assertEquals(List.of(MATERIAL_A, MATERIAL_B), MAPPER.convertValue(clean, List.class));
        verifyNoInteractions(resolver);
    }

    @Test
    void changingEncryptedIdsForSameProductResolveToSameNumericIdentity() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.JD, "common", "https://u.jd.com/private")).thenReturn(ID);
        JsonNode first = boundary.result("getPromotionUrl", promotionWithKeyword(MATERIAL_A), 91);
        JsonNode second = boundary.result("getPromotionUrl", promotionWithKeyword(MATERIAL_B), 92);
        assertEquals(ID, first.path("productId").asText());
        assertEquals(first.path("productId").asText(), second.path("productId").asText());
        verify(resolver).remember(PriceCodes.JD, "common", MATERIAL_A, ID);
        verify(resolver).remember(PriceCodes.JD, "common", MATERIAL_B, ID);
    }

    @Test
    void platformAndProfileAreForwardedAtResultTimeWithoutCrossPlatformReuse() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.JD, "jd-account", "https://u.jd.com/private")).thenReturn(ID);
        when(resolver.resolve(PriceCodes.TB, "tb-account", "https://s.click.taobao.com/private")).thenReturn("223456789");
        var jdInput = (com.fasterxml.jackson.databind.node.ObjectNode) promotion(); jdInput.put("profileName", "jd-account");
        var tbInput = (com.fasterxml.jackson.databind.node.ObjectNode) promotion();
        tbInput.put("profileName", "tb-account"); tbInput.put("promotionUrl", "https://s.click.taobao.com/private");
        JsonNode jd = boundary.result("getPromotionUrl", jdInput, 95);
        JsonNode tb = boundary.result("getTbPromotionUrl", tbInput, 96);
        assertEquals(ID, jd.path("productId").asText());
        assertEquals("223456789", tb.path("productId").asText());
        verify(resolver).resolve(PriceCodes.JD, "jd-account", "https://u.jd.com/private");
        verify(resolver).resolve(PriceCodes.TB, "tb-account", "https://s.click.taobao.com/private");
        verify(resolver, never()).resolve(eq(PriceCodes.JD), eq("tb-account"), anyString());
        verify(resolver, never()).resolve(eq(PriceCodes.TB), eq("jd-account"), anyString());
    }

    @Test
    void persistentResultDropsOpaqueIdentityButRetainsPromotionLinkForCaching() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.JD, "common", "https://u.jd.com/private")).thenReturn(ID);
        JsonNode clean = boundary.result("getPromotionUrl", promotion(), 101);
        assertTrue(clean.path("identityResolved").asBoolean());
        assertEquals(ID, clean.path("productId").asText());
        assertEquals("https://item.jd.com/123456789.html", clean.path("productInfo").path("productLink").asText());
        assertEquals("https://u.jd.com/private", clean.path("promotionUrl").asText());
        for (String forbidden : new String[]{"EncryptedMaterial", "encryptedProductId", "diagnostics"})
            assertFalse(clean.toString().contains(forbidden), clean.toString());
        JsonNode live = boundary.liveResult(101);
        assertEquals("https://u.jd.com/private", live.path("promotionUrl").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) live).put("promotionUrl", "modified");
        assertEquals("https://u.jd.com/private", boundary.liveResult(101).path("promotionUrl").asText());
        verify(resolver).remember(PriceCodes.JD, "common", MATERIAL_A, ID);
        verify(resolver).remember(PriceCodes.JD, "common", MATERIAL_B, ID);
    }

    @Test
    void liveResultExpiresAndCannotSurviveNewServiceInstance() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        AtomicLong time = new AtomicLong(1000);
        Field field = UnionMaterialBoundary.class.getDeclaredField("live");
        field.setAccessible(true);
        field.set(boundary, new BoundedMemoryCache<JsonNode>(500, 2 * 1024 * 1024, time::get));
        when(resolver.resolve(PriceCodes.JD, "common", "https://u.jd.com/private")).thenReturn(ID);
        boundary.result("getPromotionUrl", promotion(), 102);
        assertNotNull(boundary.liveResult(102));
        assertNull(new UnionMaterialBoundary(MAPPER, resolver).liveResult(102));
        time.addAndGet(Duration.ofHours(6).toMillis() - 1);
        assertNotNull(boundary.liveResult(102));
        time.incrementAndGet();
        assertNull(boundary.liveResult(102));
    }

    @Test
    void resolutionFailureCannotRecreateProductIdentityFromFallbackFields() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.JD, "common", "https://u.jd.com/private"))
                .thenThrow(new TaskApiException(409, "IDENTITY_UNRESOLVED", "IDENTITY_UNRESOLVED"));
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) promotion();
        input.put("productId", ID);
        input.put("skuId", ID);
        input.put("itemId", ID);
        input.put("productLink", "https://item.jd.com/123456789.html");
        JsonNode clean = boundary.result("getPromotionUrl", input, 103);
        assertFalse(clean.path("identityResolved").asBoolean());
        assertEquals("", clean.path("productId").asText(""));
        assertFalse(clean.has("productInfo"));
        assertEquals("", clean.path("keyword").asText(""));
        verify(resolver, never()).remember(anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void deferredResolutionFailureKeepsLinkButNeverClaimsIdentity() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.JD, "common", "https://u.jd.com/private"))
                .thenThrow(new TaskApiException(409, "IDENTITY_UNRESOLVED", "IDENTITY_UNRESOLVED"));
        // 提交阶段不解析也不抛错；失败只在结果阶段出现，转链链接仍可用，但身份绝不被宣称。
        JsonNode input = MAPPER.readTree("{\"keyword\":\"" + MATERIAL_A + "\"}");
        JsonNode queued = (JsonNode) boundary.request("getPromotionUrl", input);
        assertEquals(MATERIAL_A, queued.path("keyword").asText());
        assertFalse(queued.has("productId"));
        JsonNode durable = boundary.result("getPromotionUrl", promotion(), 104);
        JsonNode live = boundary.liveResult(104);
        assertFalse(durable.path("identityResolved").asBoolean());
        assertEquals("", live.path("keyword").asText());
        assertFalse(live.has("productId"));
        assertFalse(live.toString().contains("EncryptedMaterial"));
        assertEquals("https://u.jd.com/private", live.path("promotionUrl").asText());
    }

    @Test
    void liveResultsRemainIsolatedByTaskAcrossPlatforms() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.JD, "common", "https://u.jd.com/private")).thenReturn(ID);
        when(resolver.resolve(PriceCodes.TB, "common", "https://s.click.taobao.com/private")).thenReturn("223456789");
        boundary.result("getPromotionUrl", promotion(), 105);
        var tbInput = (com.fasterxml.jackson.databind.node.ObjectNode) promotion();
        tbInput.put("promotionUrl", "https://s.click.taobao.com/private");
        JsonNode clean = boundary.result("getTbPromotionUrl", tbInput, 106);
        assertEquals("223456789", clean.path("productId").asText());
        assertEquals(ID, boundary.liveResult(105).path("productId").asText());
        assertEquals("223456789", boundary.liveResult(106).path("productId").asText());
    }

    @Test void redirectedDifferentNumericProductCannotBeTracked() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.JD, "common", "https://u.jd.com/private")).thenReturn("999999999");
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) promotion();
        input.put("keyword", ID);
        JsonNode clean = boundary.result("getPromotionUrl", input, 201);
        assertFalse(clean.path("identityResolved").asBoolean());
        assertFalse(clean.has("productId")); assertFalse(clean.has("productInfo"));
        verify(resolver, never()).remember(anyInt(), anyString(), anyString(), anyString());
    }

    @Test void taobaoRedirectStillRejectsWrongProductWhenGatewayHasNoNumericIdentity() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.TB, "common", "https://s.click.taobao.com/private")).thenReturn("999999999");
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) promotion();
        input.put("keyword", ID);
        input.put("promotionUrl", "https://s.click.taobao.com/private");
        JsonNode clean = boundary.result("getTbPromotionUrl", input, 202);
        assertFalse(clean.path("identityResolved").asBoolean());
        assertFalse(clean.has("productId"));
        assertFalse(clean.has("productInfo"));
        verify(resolver, never()).remember(anyInt(), anyString(), anyString(), anyString());
    }

    @Test void failedTaobaoGatewayExposesOnlyConstantLiveDiagnostic() throws Exception {
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, mock(ProductIdentityResolver.class));
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) promotion();
        input.put("status", "FAILED");
        input.putObject("diagnostics").put("ajaxPromotion",
                "first search result is item EncryptedMaterialPrivate123, not 123456789");
        JsonNode durable = boundary.result("getTbPromotionUrl", input, 203);
        assertFalse(durable.has("failureCode"));
        assertFalse(durable.has("diagnostics"));
        JsonNode live = boundary.liveResult(203);
        assertEquals("TB_GATEWAY_NUMERIC_MISMATCH", live.path("failureCode").asText());
        assertFalse(live.toString().contains("EncryptedMaterialPrivate123"));
    }

    @Test void nonRebateBusinessErrorSurvivesDurableAndLiveProjection() {
        var resolver = mock(ProductIdentityResolver.class);
        var boundary = new UnionMaterialBoundary(MAPPER, resolver);
        var input = MAPPER.createObjectNode().put("status", "FAILED")
                .put("errorCode", "NO_REBATE_PRODUCT").put("message", "非返利商品，暂不支持转链");
        JsonNode durable = boundary.result("getTbPromotionUrl", input, 204);
        for (JsonNode result : List.of(durable, boundary.liveResult(204))) {
            assertEquals("NO_REBATE_PRODUCT", result.path("errorCode").asText());
            assertEquals("非返利商品，暂不支持转链", result.path("message").asText());
        }
        assertEquals("NO_REBATE_PRODUCT", boundary.liveResult(204).path("failureCode").asText());
        verifyNoInteractions(resolver);
    }

    private static JsonNode promotion() throws Exception {
        return MAPPER.readTree("""
                {"status":"SUCCESS","profileName":"common","keyword":"EncryptedMaterialAlpha123",
                 "encryptedProductId":"EncryptedMaterialBravo456","promotionUrl":"https://u.jd.com/private",
                 "diagnostics":{"raw":"EncryptedMaterialAlpha123"},
                 "productInfo":{"productName":"test","price":"99.00","priceField":"finalPrice"}}
                """);
    }

    /** 一个带指定别名的推广成功结果；去掉 encryptedProductId 避免与别名 remember 校验计数重叠。 */
    private static JsonNode promotionWithKeyword(String keyword) throws Exception {
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) promotion();
        input.put("keyword", keyword);
        input.remove("encryptedProductId");
        return input;
    }
}
