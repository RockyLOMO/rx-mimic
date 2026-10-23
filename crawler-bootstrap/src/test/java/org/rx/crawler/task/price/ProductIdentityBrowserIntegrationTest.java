package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.*;
import org.rx.crawler.task.jd.*;
import org.rx.crawler.task.tb.*;
import org.rx.io.EntityDatabaseImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, one selected product per platform, actual local Chrome with the existing common profile. */
@EnabledIfSystemProperty(named = "identity.browser.integration", matches = "true")
class ProductIdentityBrowserIntegrationTest {
    @TempDir Path temp;

    @Test void jdRoundTrip() throws Exception { roundTrip(PriceCodes.JD); }
    @Test void tbRoundTrip() throws Exception { roundTrip(PriceCodes.TB); }

    private void roundTrip(int platform) throws Exception {
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(true);
        ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        config.getCustom().getJdUnion().setLoginWaitSeconds(30);
        config.getCustom().getJdUnion().setKeepBrowserOpenOnLoginRequired(false);
        config.getCustom().getTbPromotion().setLoginWaitSeconds(30);
        config.getCustom().getTbPromotion().setKeepBrowserOpenOnLoginRequired(false);
        ObjectMapper mapper = new ObjectMapper();
        BrowserOwnerExecutor executor = new BrowserOwnerExecutor();
        BrowserProfileManager profiles = new BrowserProfileManager(config);
        CrawlEntryService entry = new CrawlEntryService(new BrowserPreflightService());
        JdUnionPromotionTask jd = new JdUnionPromotionTask(config, profiles, entry, mapper);
        TbPromotionUrlTask tb = new TbPromotionUrlTask(config, profiles, entry, mapper);
        try (EntityDatabaseImpl db = new EntityDatabaseImpl(temp.resolve("prices").toString(), null)) {
            PriceWatchStore store = new PriceWatchStore(db); store.init();
            ProductIdentityResolver resolver = new ProductIdentityResolver(executor,
                    new BrowserPromotionNavigator(config, profiles), tb, new PriceRiskGate(store, config), store, config);
            ReflectionTestUtils.setField(jd, "identities", resolver);
            String material = executor.call(() -> {
                if (platform == PriceCodes.JD) {
                    JdUnionProductSearchRequest request = new JdUnionProductSearchRequest();
                    request.setKeyword("抽纸"); request.setMaxResults(2); request.setDebugEnabled(true);
                    JdUnionProductSearchResult result = jd.searchProducts(request);
                    assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus(), "JD search must succeed with logged-in common profile");
                    assertFalse(result.getItems().isEmpty());
                    return result.getItems().get(0).getSkuId();
                }
                TbUnionProductSearchRequest request = new TbUnionProductSearchRequest();
                request.setKeyword("抽纸"); request.setMaxResults(2); request.setDebugEnabled(true);
                TbUnionProductSearchResult result = tb.searchProducts(request);
                assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus(), "TB search must succeed with logged-in common profile");
                assertFalse(result.getItems().isEmpty());
                return result.getItems().get(0).getItemId();
            });
            PromotionUrlResult promotion = executor.call(() -> {
                PromotionUrlRequest request = new PromotionUrlRequest();
                request.setKeyword(material); request.setAdSiteName("0"); request.setDebugEnabled(true);
                return platform == PriceCodes.JD ? jd.getPromotionUrl(request) : tb.getPromotionUrl(request);
            });
            assertEquals(CustomCrawlStatus.SUCCESS, promotion.getStatus(), "Selected product promotion must succeed");
            String id = resolver.resolve(platform, "common", promotion.getPromotionUrl());
            assertTrue(ProductIdentityParser.isNumeric(id));
            assertEquals(id, resolver.resolve(platform, "common", promotion.getPromotionUrl()), "Second access is cached");
            resolver.remember(platform, "common", material, id);
            PriceWatchStore.ProductHit product = store.upsertProduct(platform, id, promotion.getProductInfo().getProductName(),
                    promotion.getProductInfo().getStoreName(), promotion.getProductInfo().getImageUrl(),
                    java.sql.Timestamp.from(java.time.Instant.now()));
            assertEquals(id, store.product(product.getProductId()).getPlatformProductId());
            executor.call(() -> {
                if (platform == PriceCodes.JD) {
                    JdUnionProductSearchRequest request = new JdUnionProductSearchRequest();
                    request.setKeyword(id); request.setMaxResults(2); request.setDebugEnabled(true);
                    assertEquals(CustomCrawlStatus.SUCCESS, jd.searchProducts(request).getStatus(), "JD numeric ID search must complete the loop");
                } else {
                    TbUnionProductSearchRequest request = new TbUnionProductSearchRequest();
                    request.setKeyword(id); request.setMaxResults(2); request.setDebugEnabled(true);
                    assertEquals(CustomCrawlStatus.SUCCESS, tb.searchProducts(request).getStatus(), "TB numeric ID search must complete the loop");
                }
                return null;
            });
            System.out.println("IDENTITY_BROWSER_OK platform=" + PriceCodes.platformKey(platform) + " productId=" + id
                    + " price=" + promotion.getProductInfo().getPrice());
        } finally {
            executor.call(() -> { profiles.closeSession("common"); return null; });
            executor.shutdown();
        }
    }
}
