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
    void batchPromotionRequestsContainOnlyResolvedNumericIds() {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.JD, "common", MATERIAL_A)).thenReturn(ID);
        when(resolver.resolve(PriceCodes.JD, "common", MATERIAL_B)).thenReturn("123456790");
        JsonNode clean = MAPPER.valueToTree(boundary.request("getPromotionUrls", List.of(MATERIAL_A, MATERIAL_B)));
        assertEquals(List.of(ID, "123456790"), MAPPER.convertValue(clean, List.class));
        assertFalse(clean.toString().contains("EncryptedMaterial"));
    }

    @Test
    void changingEncryptedIdsForSameProductProducesSameNumericRequests() {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(eq(PriceCodes.JD), eq("common"), anyString())).thenReturn(ID);
        JsonNode first = MAPPER.valueToTree(boundary.request("getPromotionUrls", List.of(MATERIAL_A)));
        JsonNode second = MAPPER.valueToTree(boundary.request("getPromotionUrls", List.of(MATERIAL_B)));
        assertEquals(first, second);
        assertEquals(ID, first.get(0).asText());
    }

    @Test
    void platformAndProfileAreForwardedWithoutCrossPlatformReuse() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.JD, "jd-account", MATERIAL_A)).thenReturn(ID);
        when(resolver.resolve(PriceCodes.TB, "tb-account", MATERIAL_A)).thenReturn("223456789");
        JsonNode jd = MAPPER.valueToTree(boundary.request("getPromotionUrl",
                MAPPER.readTree("{\"keyword\":\"" + MATERIAL_A + "\",\"profileName\":\"jd-account\"}")));
        JsonNode tb = MAPPER.valueToTree(boundary.request("getTbPromotionUrl",
                MAPPER.readTree("{\"keyword\":\"" + MATERIAL_A + "\",\"profileName\":\"tb-account\"}")));
        assertEquals(ID, jd.path("keyword").asText());
        assertEquals("223456789", tb.path("keyword").asText());
        verify(resolver).resolve(PriceCodes.JD, "jd-account", MATERIAL_A);
        verify(resolver).resolve(PriceCodes.TB, "tb-account", MATERIAL_A);
    }

    @Test
    void persistentResultDropsOriginalMaterialButLiveResponseRetainsLink() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.JD, "common", "https://u.jd.com/private")).thenReturn(ID);
        JsonNode clean = boundary.result("getPromotionUrl", promotion(), 101);
        assertTrue(clean.path("identityResolved").asBoolean());
        assertEquals(ID, clean.path("productId").asText());
        assertEquals("https://item.jd.com/123456789.html", clean.path("productInfo").path("productLink").asText());
        for (String forbidden : new String[]{"EncryptedMaterial", "encryptedProductId", "promotionUrl", "diagnostics", "private"})
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
    void failedRequestResolutionDoesNotReturnUsableRequestOrLiveResult() throws Exception {
        ProductIdentityResolver resolver = mock(ProductIdentityResolver.class);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(MAPPER, resolver);
        when(resolver.resolve(PriceCodes.JD, "common", MATERIAL_A))
                .thenThrow(new TaskApiException(409, "IDENTITY_UNRESOLVED", "IDENTITY_UNRESOLVED"));
        JsonNode input = MAPPER.readTree("{\"keyword\":\"" + MATERIAL_A + "\"}");
        assertThrows(TaskApiException.class, () -> boundary.request("getPromotionUrl", input));
        assertNull(boundary.liveResult(104));
        assertFalse(input.has("productId"));
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

    private static JsonNode promotion() throws Exception {
        return MAPPER.readTree("""
                {"status":"SUCCESS","profileName":"common","keyword":"EncryptedMaterialAlpha123",
                 "encryptedProductId":"EncryptedMaterialBravo456","promotionUrl":"https://u.jd.com/private",
                 "diagnostics":{"raw":"EncryptedMaterialAlpha123"},
                 "productInfo":{"productName":"test","price":"99.00","priceField":"finalPrice"}}
                """);
    }
}
