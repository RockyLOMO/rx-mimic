package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.BrowserOwnerExecutor;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.TaskApiException;
import org.rx.crawler.task.tb.TbPromotionUrlTask;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Only mocked browser operations; no Chrome process, network or external delivery. */
class ProductIdentityResolverTest {
    @Test void taobaoShortLinksUseAnalysisAndReuseItsVerifiedTitlePopupProof() {
        Fixture f = new Fixture();
        when(f.tb.getPromotionUrl(any())).thenAnswer(call -> {
            assertEquals("https://m.tb.cn/h.example", call.getArgument(0, org.rx.crawler.task.common.PromotionUrlRequest.class).getKeyword());
            var promoted = new org.rx.crawler.task.common.PromotionUrlResult();
            promoted.setStatus(CustomCrawlStatus.SUCCESS); promoted.setItemId(ID);
            promoted.setPromotionUrl("https://s.click.taobao.com/verified");
            f.resolver.remember(PriceCodes.TB, "common", promoted.getPromotionUrl(), ID);
            return promoted;
        });
        assertEquals(ID, f.resolver.resolve(PriceCodes.TB, "common", "https://m.tb.cn/h.example"));
        verifyNoInteractions(f.navigator);
        assertEquals(ID, f.resolver.cached(PriceCodes.TB, "common", "https://m.tb.cn/h.example"));
    }
    @Test void tokensAreNormalizedBeforePersistenceThroughThePlatformPromotionPage() {
        for (int platform : new int[]{PriceCodes.JD, PriceCodes.TB}) {
            Fixture f = new Fixture();
            var jd = mock(org.rx.crawler.task.jd.JdUnionPromotionTask.class);
            org.springframework.test.util.ReflectionTestUtils.setField(f.resolver, "jd", jd);
            var promoted = new org.rx.crawler.task.common.PromotionUrlResult();
            promoted.setStatus(CustomCrawlStatus.SUCCESS); promoted.setItemId(ID);
            promoted.setPromotionUrl(platform == PriceCodes.JD ? "https://u.jd.com/example" : "https://s.click.taobao.com/example");
            when(f.tb.getPromotionUrl(any())).thenReturn(promoted);
            when(jd.getPromotionUrl(any())).thenReturn(promoted);
            when(f.navigator.open(eq(platform), eq("common"), anyString())).thenReturn(resolved(platform));
            assertEquals(ID, f.resolver.resolve(platform, "common", "复制 ￥Abcd1234567￥ 打开"));
            assertEquals(ID, f.resolver.cached(platform, "common", "复制 ￥Abcd1234567￥ 打开"));
        }
    }
    private static final String ID = "123456789";
    private static final String MATERIAL_A = "EncryptedMaterialAlpha123";
    private static final String MATERIAL_B = "EncryptedMaterialBravo456";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void materialHintIsProfileScopedMemoryOnlyAndNotUsedForJd() {
        Fixture f = new Fixture();
        f.resolver.remember(PriceCodes.TB, "common", MATERIAL_A, ID);
        assertEquals(MATERIAL_A, f.resolver.recoverMaterial("common", ID));
        assertEquals(ID, f.resolver.cached(PriceCodes.TB, "common", MATERIAL_A));
        assertNull(f.resolver.cachedMaterial(PriceCodes.TB, "other", ID));
        assertNull(f.resolver.cachedMaterial(PriceCodes.JD, "common", ID));
        assertNull(new Fixture().resolver.cachedMaterial(PriceCodes.TB, "common", ID));
        verify(f.store, never()).findProduct(anyInt(), anyString());
        verify(f.tb, never()).searchProducts(any());
    }

    @Test void coldRecoveryPrioritizesShopButStillVerifiesFinalId() {
        Fixture f = new Fixture();
        when(f.store.findProduct(PriceCodes.TB, ID)).thenReturn(metadata("full title", "same shop"));
        when(f.tb.searchProducts(any())).thenReturn(candidates(
                candidate(MATERIAL_A, "other shop"), candidate(MATERIAL_B, "same shop")));
        var promoted = new org.rx.crawler.task.common.PromotionUrlResult();
        promoted.setStatus(CustomCrawlStatus.SUCCESS); promoted.setItemId(ID); promoted.setPromotionUrl("https://s.click.taobao.com/private");
        when(f.tb.getPromotionUrl(any())).thenReturn(promoted);
        when(f.navigator.open(eq(PriceCodes.TB), eq("common"), anyString())).thenReturn(resolved(PriceCodes.TB));
        assertEquals(MATERIAL_B, f.resolver.recoverMaterial("common", ID));
        verify(f.tb).getPromotionUrl(argThat(request -> MATERIAL_B.equals(request.getKeyword())));
        verify(f.tb).searchProducts(argThat(request -> "full title".equals(request.getKeyword())));
    }

    @Test void freshDetailSearchSharesTheSameCandidateLimit() {
        Fixture f = new Fixture();
        var mall = mock(org.rx.crawler.task.tb.TbMallTask.class);
        org.springframework.test.util.ReflectionTestUtils.setField(f.resolver, "tbMall", mall);
        when(f.store.findProduct(PriceCodes.TB, ID)).thenReturn(metadata("old title", "shop"));
        var detail = new org.rx.crawler.task.tb.TbProductDetailResult();
        detail.setStatus(CustomCrawlStatus.SUCCESS); detail.setItemId(ID); detail.setTitle("new full title"); detail.setShopName("shop");
        when(mall.productIdentity(any())).thenReturn(detail);
        String third = "EncryptedMaterialCharlie123";
        String fourth = "EncryptedMaterialDelta12345";
        f.resolver.remember(PriceCodes.TB, "common", MATERIAL_A, "991");
        f.resolver.remember(PriceCodes.TB, "common", MATERIAL_B, "992");
        f.resolver.remember(PriceCodes.TB, "common", third, "993");
        when(f.tb.searchProducts(any())).thenAnswer(call -> {
            var request = call.getArgument(0, org.rx.crawler.task.tb.TbUnionProductSearchRequest.class);
            return request.getKeyword().equals("old title") ? candidates(candidate(MATERIAL_A, "shop"))
                    : candidates(candidate(MATERIAL_B, "shop"), candidate(third, "shop"), candidate(fourth, "shop"));
        });
        TaskApiException error = assertThrows(TaskApiException.class, () -> f.resolver.recoverMaterial("common", ID));
        assertEquals("TB_IDENTITY_CANDIDATE_LIMIT", error.getCode());
        verify(f.tb, never()).getPromotionUrl(any());
        verify(mall).productIdentity(argThat(request -> ID.equals(request.getItemId())
                && Boolean.FALSE.equals(request.getDownloadImages()) && Boolean.FALSE.equals(request.getIncludeSpecs())
                && Boolean.FALSE.equals(request.getKeepBrowserOpenOnLoginRequired())));
        assertNull(f.resolver.cachedMaterial(PriceCodes.TB, "common", ID));
    }

    @Test void sameShopFirstCandidateIsRejectedWhenItsNumericIdDiffers() {
        Fixture f = new Fixture();
        when(f.store.findProduct(PriceCodes.TB, ID)).thenReturn(metadata("full title", "same shop"));
        f.resolver.remember(PriceCodes.TB, "common", MATERIAL_A, "999999999");
        when(f.tb.searchProducts(any())).thenReturn(candidates(
                candidate(MATERIAL_A, "same shop"), candidate(MATERIAL_B, "same shop")));
        var promoted = new org.rx.crawler.task.common.PromotionUrlResult();
        promoted.setStatus(CustomCrawlStatus.SUCCESS); promoted.setItemId(ID); promoted.setPromotionUrl("https://s.click.taobao.com/private");
        when(f.tb.getPromotionUrl(any())).thenReturn(promoted);
        when(f.navigator.open(eq(PriceCodes.TB), eq("common"), anyString())).thenReturn(resolved(PriceCodes.TB));
        assertEquals(MATERIAL_B, f.resolver.recoverMaterial("common", ID));
        verify(f.tb).getPromotionUrl(argThat(request -> MATERIAL_B.equals(request.getKeyword())));
    }

    @Test void unknownNumericIdReadsDetailThenSearchesByFullTitle() {
        Fixture f = new Fixture();
        var mall = mock(org.rx.crawler.task.tb.TbMallTask.class);
        org.springframework.test.util.ReflectionTestUtils.setField(f.resolver, "tbMall", mall);
        var detail = new org.rx.crawler.task.tb.TbProductDetailResult();
        detail.setStatus(CustomCrawlStatus.SUCCESS); detail.setItemId(ID);
        detail.setTitle("full detail title"); detail.setShopName("shop");
        when(mall.productIdentity(any())).thenReturn(detail);
        when(f.tb.searchProducts(any())).thenReturn(candidates(candidate(MATERIAL_A, "shop")));
        var promoted = new org.rx.crawler.task.common.PromotionUrlResult();
        promoted.setStatus(CustomCrawlStatus.SUCCESS); promoted.setItemId(ID); promoted.setPromotionUrl("https://s.click.taobao.com/private");
        when(f.tb.getPromotionUrl(any())).thenReturn(promoted);
        when(f.navigator.open(eq(PriceCodes.TB), eq("common"), anyString())).thenReturn(resolved(PriceCodes.TB));
        assertEquals(MATERIAL_A, f.resolver.recoverMaterial("common", ID));
        verify(mall).productIdentity(argThat(request -> ID.equals(request.getItemId())));
        verify(f.tb).searchProducts(argThat(request -> "full detail title".equals(request.getKeyword())));
        verify(f.store).upsertProduct(eq(PriceCodes.TB), eq(ID), eq("full detail title"), eq("shop"), eq(""), any());
    }

    @Test void conflictingGatewayAndCachedBridgeIdentityIsRejectedWithoutOpeningRetailPages() {
        Fixture f = new Fixture();
        var promoted = new org.rx.crawler.task.common.PromotionUrlResult();
        promoted.setStatus(CustomCrawlStatus.SUCCESS); promoted.setItemId("999999999");
        promoted.setPromotionUrl("https://s.click.taobao.com/private");
        when(f.tb.getPromotionUrl(any())).thenReturn(promoted);
        when(f.navigator.open(eq(PriceCodes.TB), eq("common"), anyString())).thenReturn(resolved(PriceCodes.TB));
        f.resolver.remember(PriceCodes.TB, "common", promoted.getPromotionUrl(), ID);
        assertEquals("PRODUCT_ID_MISMATCH", assertThrows(TaskApiException.class,
                () -> f.resolver.resolve(PriceCodes.TB, "common", MATERIAL_A)).getCode());
        verifyNoInteractions(f.navigator);
        assertNull(f.resolver.cachedMaterial(PriceCodes.TB, "common", ID));
    }

    @Test void recoveryTimeBudgetStopsBeforeTryingCandidates() {
        Fixture f = new Fixture();
        AtomicLong now = new AtomicLong();
        org.springframework.test.util.ReflectionTestUtils.setField(f.resolver, "nanoTime", (java.util.function.LongSupplier) now::get);
        when(f.store.findProduct(PriceCodes.TB, ID)).thenReturn(metadata("full title", "shop"));
        when(f.tb.searchProducts(any())).thenAnswer(call -> {
            now.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(90));
            return candidates(candidate(MATERIAL_A, "shop"));
        });
        assertEquals("TB_IDENTITY_TIME_LIMIT", assertThrows(TaskApiException.class,
                () -> f.resolver.recoverMaterial("common", ID)).getCode());
        verify(f.tb, never()).getPromotionUrl(any());
    }

    private static PriceWatchStore.ProductHit metadata(String title, String shop) {
        var product = new PriceWatchStore.ProductHit(); product.setTitle(title); product.setShopName(shop); return product;
    }
    private static org.rx.crawler.task.tb.TbUnionProductItem candidate(String material, String shop) {
        var item = new org.rx.crawler.task.tb.TbUnionProductItem(); item.setItemId(material); item.setStoreName(shop); return item;
    }
    private static org.rx.crawler.task.tb.TbUnionProductSearchResult candidates(org.rx.crawler.task.tb.TbUnionProductItem... items) {
        var result = new org.rx.crawler.task.tb.TbUnionProductSearchResult();
        result.setStatus(CustomCrawlStatus.SUCCESS); result.setItems(java.util.List.of(items)); return result;
    }

    @Test
    void cacheExpiresAtWriteDeadlineEvenAfterHits() {
        AtomicLong now = new AtomicLong(1000);
        BoundedMemoryCache<String> cache = new BoundedMemoryCache<>(3, 1000, now::get);
        cache.put("a", ID, Duration.ofMillis(100), 18);
        now.set(1099);
        assertEquals(ID, cache.get("a"));
        now.set(1100);
        assertNull(cache.get("a"));
        assertEquals(0, cache.size());
        assertEquals(0, cache.weight());
    }

    @Test
    void cacheHonorsCountWeightLruAndReplacement() {
        AtomicLong now = new AtomicLong();
        BoundedMemoryCache<String> cache = new BoundedMemoryCache<>(2, 180, now::get);
        cache.put("a", "one", Duration.ofSeconds(10), 20);
        cache.put("b", "two", Duration.ofSeconds(10), 20);
        assertEquals(172, cache.weight());
        assertEquals("one", cache.get("a"));
        cache.put("c", "three", Duration.ofSeconds(10), 20);
        assertNull(cache.get("b"));
        assertEquals(2, cache.size());
        assertTrue(cache.weight() <= 180);
        cache.put("a", "replacement", Duration.ofSeconds(10), 1);
        assertEquals(153, cache.weight());
        cache.put("oversized", "no", Duration.ofSeconds(10), 500);
        assertNull(cache.get("oversized"));
        assertEquals(2, cache.size());
        cache.put("a", "no", Duration.ZERO, 1);
        assertNull(cache.get("a"));
        now.set(10000);
        assertEquals(0, cache.size());
        assertEquals(0, cache.weight());
    }

    @Test
    void cacheWeightEvictsBeforeCountLimit() {
        BoundedMemoryCache<String> cache = new BoundedMemoryCache<>(10, 150, () -> 0L);
        cache.put("a", "first", Duration.ofHours(1), 20);
        cache.put("b", "second", Duration.ofHours(1), 20);
        assertEquals(1, cache.size());
        assertNull(cache.get("a"));
        assertEquals("second", cache.get("b"));
        assertEquals(86, cache.weight());
    }

    @Test
    void jdEncryptedInputUsesJingfenThenReusesCacheWithoutTbSearch() {
        Fixture f = new Fixture();
        when(f.navigator.open(eq(PriceCodes.JD), eq("common"), anyString())).thenReturn(resolved(PriceCodes.JD));
        assertEquals(ID, f.resolver.resolve(PriceCodes.JD, "common", MATERIAL_A));
        assertEquals(ID, f.resolver.resolve(PriceCodes.JD, "common", MATERIAL_A));
        verify(f.navigator, times(1)).open(PriceCodes.JD, "common", "https://jingfen.jd.com/detail/" + MATERIAL_A + ".html");
        verifyNoInteractions(f.tb);
        verify(f.budget, times(1)).reserve(PriceCodes.JD, false);
        verifyNoMoreInteractions(f.navigator);
    }

    @Test
    void differentEncryptedIdsResolveToSameNumericIdentityAndRemainProfileScoped() {
        Fixture f = new Fixture();
        when(f.navigator.open(eq(PriceCodes.JD), eq("common"), anyString())).thenReturn(resolved(PriceCodes.JD));
        assertEquals(ID, f.resolver.resolve(PriceCodes.JD, "common", MATERIAL_A));
        assertEquals(ID, f.resolver.resolve(PriceCodes.JD, "common", MATERIAL_B));
        assertEquals(ID, f.resolver.cached(PriceCodes.JD, "common", MATERIAL_A));
        assertEquals(ID, f.resolver.cached(PriceCodes.JD, "common", MATERIAL_B));
        assertNull(f.resolver.cached(PriceCodes.JD, "other", MATERIAL_A));
        assertNull(f.resolver.cached(PriceCodes.TB, "common", MATERIAL_A));
        verify(f.navigator, times(2)).open(eq(PriceCodes.JD), eq("common"), anyString());
        verifyNoInteractions(f.tb);
    }

    @Test
    void resolverMappingCapacityIsBoundedAcrossManyEncryptedInputs() {
        Fixture f = new Fixture();
        for (int i = 0; i < 600; i++) f.resolver.remember(PriceCodes.JD, "common", MATERIAL_A + i, ID);
        assertNull(f.resolver.cached(PriceCodes.JD, "common", MATERIAL_A + 0));
        assertEquals(ID, f.resolver.cached(PriceCodes.JD, "common", MATERIAL_A + 599));
        verifyNoInteractions(f.navigator, f.tb, f.store);
    }

    @Test
    void ordinaryNavigationFailureCanBeRetriedAndDoesNotLeakMaterial() {
        Fixture f = new Fixture();
        when(f.navigator.open(eq(PriceCodes.JD), eq("common"), anyString()))
                .thenThrow(new IllegalStateException("network failed for " + MATERIAL_A))
                .thenReturn(resolved(PriceCodes.JD));
        TaskApiException error = assertThrows(TaskApiException.class,
                () -> f.resolver.resolve(PriceCodes.JD, "common", MATERIAL_A));
        assertEquals("IDENTITY_UNRESOLVED", error.getCode());
        assertFalse(error.getMessage().contains(MATERIAL_A));
        assertEquals(ID, f.resolver.resolve(PriceCodes.JD, "common", MATERIAL_A));
        verify(f.navigator, times(2)).open(eq(PriceCodes.JD), eq("common"), anyString());
    }

    @Test
    void untrustedAndCrossPlatformLinksNeverNavigate() {
        Fixture f = new Fixture();
        for (String input : new String[]{"https://example.com/item?id=123456789", "https://u.jd.com.evil.test/x",
                "https://user@u.jd.com/x", "https://u.jd.com:8443/x", "https://s.click.taobao.com/x",
                "ftp://item.jd.com/123456789.html", "https://user@item.jd.com/123456789.html",
                "https://item.jd.com:8443/123456789.html"}) {
            assertThrows(TaskApiException.class, () -> f.resolver.resolve(PriceCodes.JD, "common", input));
        }
        verifyNoInteractions(f.navigator, f.tb);
    }

    @Test
    void numericIdsAndCanonicalProductLinksDoNotNavigateOrReserveVisits() {
        Fixture f = new Fixture();
        assertEquals(ID, f.resolver.resolve(PriceCodes.JD, "common", ID));
        assertEquals(ID, f.resolver.resolve(PriceCodes.JD, "common", "https://item.jd.com/" + ID + ".html"));
        assertEquals(ID, f.resolver.resolve(PriceCodes.TB, "common", "https://item.taobao.com/item.htm?id=" + ID));
        verifyNoInteractions(f.navigator, f.tb, f.store);
    }

    @Test
    void durableProjectionRemovesMaterialUrlsDiagnosticsAndUnresolvedCards() throws Exception {
        JsonNode input = MAPPER.readTree("""
                {"status":"SUCCESS","mktItemId":"EncryptedMaterialAlpha123",
                 "promotionUrl":"https://u.jd.com/private","diagnostics":{"raw":"EncryptedMaterialAlpha123"},
                 "items":[{"skuId":"123456789","productName":"known","price":"12.00","priceField":"finalPrice",
                           "productLink":"https://item.jd.com/123456789.html?material=EncryptedMaterialAlpha123",
                           "mktItemId":"EncryptedMaterialAlpha123","promotionUrl":"https://u.jd.com/private"},
                          {"skuId":"EncryptedMaterialBravo456","productName":"unknown","price":"1.00"}]}
                """);
        JsonNode clean = UnionPersistencePolicy.project(input, PriceCodes.JD);
        assertEquals(1, clean.path("resultCount").asInt());
        assertEquals(ID, clean.path("items").get(0).path("productId").asText());
        assertEquals("https://item.jd.com/123456789.html", clean.path("items").get(0).path("productLink").asText());
        assertEquals("12.00", clean.path("items").get(0).path("price").asText());
        String json = clean.toString();
        for (String forbidden : new String[]{"EncryptedMaterial", "mktItemId", "promotionUrl", "diagnostics", "unknown"})
            assertFalse(json.contains(forbidden), json);
    }

    @Test
    void durablePromotionProjectionKeepsNumericIdentityButNoLinkOrRawKeyword() throws Exception {
        JsonNode input = MAPPER.readTree("""
                {"status":"SUCCESS","keyword":"EncryptedMaterialAlpha123","promotionUrl":"https://u.jd.com/private",
                 "diagnostics":{"mktItemId":"EncryptedMaterialAlpha123"},
                 "productInfo":{"productId":"123456789","productName":"known","price":"99.00"}}
                """);
        JsonNode clean = UnionPersistencePolicy.project(input, PriceCodes.JD);
        assertEquals(ID, clean.path("keyword").asText());
        assertEquals(ID, clean.path("productInfo").path("productId").asText());
        assertFalse(clean.toString().contains("EncryptedMaterial"));
        assertFalse(clean.has("promotionUrl"));
        assertFalse(clean.has("diagnostics"));
    }

    @Test
    void taobaoNumericItemIdTakesPrecedenceOverSkuId() throws Exception {
        JsonNode item = MAPPER.readTree("{\"itemId\":\"123456789\",\"skuId\":\"987654321\"}");
        assertEquals(ID, UnionPersistencePolicy.numericIdentity(item, PriceCodes.TB));
        JsonNode urlOnly = MAPPER.readTree("{\"skuId\":\"987654321\",\"productLink\":\"https://item.taobao.com/item.htm?id=123456789\"}");
        assertEquals(ID, UnionPersistencePolicy.numericIdentity(urlOnly, PriceCodes.TB));
    }

    private static PromotionNavigator.Nav resolved(int platform) {
        PromotionNavigator.Nav nav = new PromotionNavigator.Nav();
        nav.setStatus(CustomCrawlStatus.SUCCESS);
        nav.setPageKind(ProductIdentityParser.PageKind.PRODUCT);
        nav.setFinalUrl(ProductIdentityParser.canonicalUrl(platform, ID));
        nav.setProductId(ID);
        return nav;
    }

    private static final class Fixture {
        final BrowserOwnerExecutor executor = mock(BrowserOwnerExecutor.class);
        final PromotionNavigator navigator = mock(PromotionNavigator.class);
        final TbPromotionUrlTask tb = mock(TbPromotionUrlTask.class);
        final PriceRiskGate risk = mock(PriceRiskGate.class);
        final PriceWatchStore store = mock(PriceWatchStore.class);
        final ProductVisitBudget budget = mock(ProductVisitBudget.class);
        final ProductIdentityResolver resolver;

        Fixture() {
            when(executor.isOwnerThread()).thenReturn(true);
            doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                    .when(executor).execute(any(Runnable.class));
            when(store.reserveVisit(anyInt(), anyString(), anyInt())).thenReturn(1);
            AppConfig config = new AppConfig();
            config.getCustom().getPriceWatch().setWindowStart("00:00");
            config.getCustom().getPriceWatch().setWindowEnd("23:59");
            resolver = new ProductIdentityResolver(executor, navigator, tb, risk, store, config);
            org.springframework.test.util.ReflectionTestUtils.setField(resolver, "budget", budget);
        }
    }
}
