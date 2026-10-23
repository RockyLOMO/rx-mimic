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
    private static final String ID = "123456789";
    private static final String MATERIAL_A = "EncryptedMaterialAlpha123";
    private static final String MATERIAL_B = "EncryptedMaterialBravo456";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
