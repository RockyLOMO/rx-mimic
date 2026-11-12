package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.config.ProductCacheProperties;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.BrowserOwnerExecutor;
import org.rx.crawler.task.common.CustomCrawlQueueService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.ProductInfoDto;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.jd.JdMallTask;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.rx.crawler.task.tb.TbMallTask;
import org.rx.crawler.task.tb.TbPromotionOrdersTask;
import org.rx.crawler.task.tb.TbPromotionUrlTask;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromotionTwoLayerCacheTest {
    private static final String JD_ID = "1001234567";
    private static final String TB_ID = "6543210987";

    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private EntityDatabase database;
    private PriceWatchStore products;
    private ProductCacheStore store;
    private ProductCacheService cache;
    private ProductIdentityResolver identities;
    private PromotionNavigator navigator;
    private BrowserOwnerExecutor ownerExecutor;
    private AppConfig config;
    private CustomCrawlQueueService queue;
    private JdUnionPromotionTask jdMock;
    private TbPromotionUrlTask tbMock;

    @BeforeEach
    void setUp() {
        database = new EntityDatabaseImpl(temp.resolve("two-layer").toString(), null);
        products = new PriceWatchStore(database);
        products.init();
        store = new ProductCacheStore(database, mapper, products);
        store.init();
        config = new AppConfig();
        config.getCustom().setQueueMaxConcurrency(1);
        config.getCustom().setQueueTimeoutSeconds(5);
        config.getCustom().getPriceWatch().setWindowStart("00:00");
        config.getCustom().getPriceWatch().setWindowEnd("23:59");
        ownerExecutor = mock(BrowserOwnerExecutor.class);
        when(ownerExecutor.isOwnerThread()).thenReturn(true);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(ownerExecutor).execute(any(Runnable.class));
        navigator = mock(PromotionNavigator.class);
        jdMock = mock(JdUnionPromotionTask.class);
        tbMock = mock(TbPromotionUrlTask.class);
        identities = new ProductIdentityResolver(ownerExecutor, navigator, tbMock,
                mock(PriceRiskGate.class), products, config);
        ReflectionTestUtils.setField(identities, "jd", jdMock);
        ReflectionTestUtils.setField(identities, "budget", mock(ProductVisitBudget.class));
        cache = new ProductCacheService(store, mapper, new ProductCacheProperties(), identities, config);
        UnionMaterialBoundary boundary = new UnionMaterialBoundary(mapper, identities);
        queue = new CustomCrawlQueueService(database, mapper, config, jdMock, mock(JdMallTask.class),
                mock(TbPromotionOrdersTask.class), tbMock, mock(TbMallTask.class));
        ReflectionTestUtils.setField(queue, "productCache", cache);
        ReflectionTestUtils.setField(queue, "materialBoundary", boundary);
        queue.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (queue != null) queue.destroy();
        if (database != null) database.close();
    }

    @Test
    void shortLinkShareTextGeneratedOutputsAndNumericIdShareSingleDurableEntry() throws Exception {
        var navOut = new PromotionNavigator.Nav();
        navOut.setStatus(CustomCrawlStatus.SUCCESS);
        navOut.setPageKind(ProductIdentityParser.PageKind.PRODUCT);
        navOut.setFinalUrl("https://item.jd.com/" + JD_ID + ".html");
        navOut.setProductId(JD_ID);
        when(navigator.open(PriceCodes.JD, "common", "https://u.jd.com/generatedOut")).thenReturn(navOut);

        when(jdMock.getPromotionUrl(any())).thenAnswer(inv -> {
            PromotionUrlResult res = new PromotionUrlResult();
            res.setStatus(CustomCrawlStatus.SUCCESS);
            res.setTaskType("getPromotionUrl");
            res.setKeyword("https://3.cn/shareFirst");
            res.setPromotionUrl("https://u.jd.com/generatedOut");
            res.setShortUrl("https://u.jd.com/generatedOut");
            res.setPromotionToken("!jdShort99!");
            ProductInfoDto info = new ProductInfoDto();
            info.setProductId(JD_ID);
            info.setProductName("测试商品");
            info.setPrice("59.00");
            info.setPriceField("finalPrice");
            info.setProductLink("https://item.jd.com/" + JD_ID + ".html");
            res.setProductInfo(info);
            return res;
        });

        long firstId = queue.enqueueOwned("android", "getPromotionUrl",
                Map.of("keyword", "https://3.cn/shareFirst", "adSiteName", "0", "useCache", true), null);
        long deadline = System.currentTimeMillis() + 5000;
        while (!"SUCCEEDED".equals(queue.queryOwned(firstId, "android").getStatus())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("SUCCEEDED", queue.queryOwned(firstId, "android").getStatus());
        verify(jdMock, times(1)).getPromotionUrl(any());

        // Second submission via same short link, token, generated link, or numeric ID must hit synchronously at enqueue.
        queue.setExecutionGate(() -> false);
        for (String variant : new String[]{
                "https://3.cn/shareFirst",
                "【京东】https://3.cn/shareFirst 商品",
                "!jdShort99!",
                "https://u.jd.com/generatedOut",
                "https://item.jd.com/" + JD_ID + ".html",
                JD_ID
        }) {
            long hitId = queue.enqueueOwned("android", "getPromotionUrl",
                    Map.of("keyword", variant, "adSiteName", "0", "useCache", true), null);
            assertEquals("SUCCEEDED", queue.queryOwned(hitId, "android").getStatus(), "Expected sync hit for " + variant);
            JsonNode cached = mapper.valueToTree(queue.resultOwned(hitId, "android"));
            assertTrue(cached.path("cacheHit").asBoolean(), "Expected cacheHit=true for " + variant);
            assertEquals(JD_ID, cached.path("productId").asText());
        }
        verify(jdMock, times(1)).getPromotionUrl(any());
    }

    @Test
    void jdTaskWithColdShortUrlResolvesNumericIdAndHitsDatabaseCacheWithoutOpeningBrowser() {
        ObjectNode seedReq = mapper.createObjectNode().put("keyword", JD_ID).put("adSiteName", "0");
        ObjectNode seedRes = mapper.createObjectNode().put("status", "SUCCESS").put("productId", JD_ID)
                .put("identityResolved", true).put("promotionUrl", "https://u.jd.com/oldOut")
                .put("shortUrl", "https://u.jd.com/oldOut").put("promotionToken", "!oldToken!")
                .put("createdAt", 123456L);
        seedRes.putObject("productInfo").put("productId", JD_ID).put("productName", "老缓存商品")
                .put("price", "39.00").put("priceField", "finalPrice");
        cache.complete("getPromotionUrl", seedReq, seedRes);

        var nav = new PromotionNavigator.Nav();
        nav.setStatus(CustomCrawlStatus.SUCCESS);
        nav.setPageKind(ProductIdentityParser.PageKind.PRODUCT);
        nav.setFinalUrl("https://item.jd.com/" + JD_ID + ".html");
        nav.setProductId(JD_ID);
        when(navigator.open(PriceCodes.JD, "common", "https://3.cn/newShortAfterRestart")).thenReturn(nav);

        // null profileManager / entryService ensures any attempt to open Chrome fails immediately.
        JdUnionPromotionTask realJdTask = new JdUnionPromotionTask(config, null, null, mapper);
        ReflectionTestUtils.setField(realJdTask, "identities", identities);
        ReflectionTestUtils.setField(realJdTask, "productCache", cache);

        PromotionUrlRequest req = new PromotionUrlRequest();
        req.setKeyword("https://3.cn/newShortAfterRestart");
        req.setAdSiteName("0");
        PromotionUrlResult result = realJdTask.getPromotionUrl(req);
        assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
        assertTrue(result.isCacheHit());
        assertEquals(JD_ID, result.getProductId());
        assertEquals("https://u.jd.com/oldOut", result.getShortUrl());
        assertEquals(JD_ID, identities.cached(PriceCodes.JD, "common", "https://3.cn/newShortAfterRestart"));
    }

    @Test
    void tbTaskWithMappedShortUrlReturnsDatabaseCacheWithoutAcquiringBrowserAndQueuePreservesCachedAt() throws Exception {
        ObjectNode seedReq = mapper.createObjectNode().put("keyword", TB_ID).put("adSiteName", "0");
        ObjectNode seedRes = mapper.createObjectNode().put("status", "SUCCESS").put("productId", TB_ID)
                .put("itemId", TB_ID).put("identityResolved", true)
                .put("promotionUrl", "https://m.tb.cn/h.oldTb").put("shortUrl", "https://m.tb.cn/h.oldTb")
                .put("promotionToken", "￥OldTbTok1￥").put("createdAt", 654321L);
        seedRes.putObject("productInfo").put("productId", TB_ID).put("productName", "淘宝缓存商品")
                .put("price", "49.00").put("priceField", "finalPrice");
        cache.complete("getTbPromotionUrl", seedReq, seedRes);
        long initialCachedAt = cache.fresh("getTbPromotionUrl", seedReq).path("cachedAt").asLong();
        assertTrue(initialCachedAt > 0);

        identities.remember(PriceCodes.TB, "common", "https://m.tb.cn/h.known", TB_ID);
        BrowserProfileManager profileManager = mock(BrowserProfileManager.class);
        when(profileManager.normalizeProfileName(anyString())).thenAnswer(inv -> inv.getArgument(0));
        CrawlEntryService entryService = mock(CrawlEntryService.class);
        TbPromotionUrlTask realTbTask = new TbPromotionUrlTask(config, profileManager, entryService, mapper);
        ReflectionTestUtils.setField(realTbTask, "identities", identities);
        ReflectionTestUtils.setField(realTbTask, "productCache", cache);

        PromotionUrlRequest req = new PromotionUrlRequest();
        req.setKeyword("https://m.tb.cn/h.known");
        req.setAdSiteName("0");
        PromotionUrlResult hit = realTbTask.getPromotionUrl(req);
        assertEquals(CustomCrawlStatus.SUCCESS, hit.getStatus());
        assertTrue(hit.isCacheHit());
        assertEquals(TB_ID, hit.getProductId());
        assertEquals("￥OldTbTok1￥", hit.getPromotionToken());
        verify(profileManager, never()).acquire(any(), any());
        verifyNoInteractions(entryService);

        // When worker returns cacheHit=true during CustomCrawlQueueService.execute, cachedAt must stay unchanged.
        Thread.sleep(5);
        when(tbMock.getPromotionUrl(any())).thenReturn(hit);
        long taskId = queue.enqueueOwned("android", "getTbPromotionUrl",
                Map.of("keyword", "https://m.tb.cn/h.uncachedKey", "adSiteName", "0", "useCache", true), null);
        long deadline = System.currentTimeMillis() + 5000;
        while (!"SUCCEEDED".equals(queue.queryOwned(taskId, "android").getStatus())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("SUCCEEDED", queue.queryOwned(taskId, "android").getStatus());
        JsonNode stored = cache.fresh("getTbPromotionUrl", seedReq);
        assertEquals(initialCachedAt, stored.path("cachedAt").asLong());
    }
}
