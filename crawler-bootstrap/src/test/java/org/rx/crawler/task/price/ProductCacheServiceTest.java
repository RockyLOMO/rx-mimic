package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.config.ProductCacheProperties;
import org.rx.crawler.task.common.TaskApiException;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;

import java.nio.file.Path;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 缓存行为只使用临时 H2 与 mocked 身份解析，不启动浏览器。 */
class ProductCacheServiceTest {
    @Test void successfulLinkWithoutNumericProofCannotEnterPromotionCache() {
        var result=mapper.createObjectNode().put("status","SUCCESS").put("promotionUrl","https://e.tb.cn/test").put("identityResolved",false);
        assertThrows(TaskApiException.class,()->service.complete("getTbPromotionUrl",promotionRequest("123456789","0"),result));
        assertEquals(0,count("product_promotion_cache"));
        assertEquals(0,count("price_product"));
    }
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private EntityDatabase database;
    private PriceWatchStore products;
    private ProductCacheStore store;
    private ProductCacheProperties settings;
    private ProductIdentityResolver identities;
    private ProductCacheService service;

    @BeforeEach void open() {
        database = new EntityDatabaseImpl(directory.resolve("product-cache").toString(), null);
        products = new PriceWatchStore(database);
        products.init();
        store = new ProductCacheStore(database, mapper, products);
        store.init();
        settings = new ProductCacheProperties();
        identities = mock(ProductIdentityResolver.class);
        service = new ProductCacheService(store, mapper, settings, identities, new AppConfig());
    }

    @AfterEach void close() throws Exception {
        if (database != null) database.close();
    }

    @Test void searchSeparatesPlatformSourceKeywordAndResultLimit() {
        ObjectNode request = searchRequest("键盘", 5);
        service.complete("jdUnionSearchProducts", request, searchResult("123456789"));
        assertNotNull(service.fresh("jdUnionSearchProducts", request));
        assertNull(service.fresh("tbUnionSearchProducts", request));
        assertNull(service.fresh("jdSearchProduct", request));
        assertNull(service.fresh("jdUnionSearchProducts", searchRequest("鼠标", 5)));
        assertNull(service.fresh("jdUnionSearchProducts", searchRequest("键盘", 10)));

        service.complete("jdSearchProduct", request, searchResult("223456789"));
        service.complete("tbUnionSearchProducts", request, searchResult("323456789"));
        assertEquals("123456789", service.fresh("jdUnionSearchProducts", request).path("items").get(0).path("productId").asText());
        assertEquals("223456789", service.fresh("jdSearchProduct", request).path("items").get(0).path("productId").asText());
        assertEquals("323456789", service.fresh("tbUnionSearchProducts", request).path("items").get(0).path("productId").asText());
    }

    @Test void emptyUnionFieldsCannotErasePermanentDetailAndBasicData() {
        ObjectNode detail = detailResult("123456789");
        detail.put("title", "完整商品标题").put("shopName", "主站店铺").put("price", "99.00");
        detail.putArray("specs").addObject().put("name", "color").put("value", "black");
        service.complete("jdProductDetail", detailRequest("123456789"), detail);
        ObjectNode union = searchResult("123456789");
        ObjectNode card = (ObjectNode) union.path("items").get(0);
        card.put("productName", "").putNull("storeName");
        card.put("price", "").put("commissionRate", "10%");
        service.complete("jdUnionSearchProducts", searchRequest("键盘", 5), union);

        JsonNode master = service.product("jd", "123456789");
        assertEquals("完整商品标题", master.path("basic").path("productName").asText());
        assertEquals("主站店铺", master.path("basic").path("storeName").asText());
        assertEquals("99.00", master.path("basic").path("price").asText());
        assertEquals("black", master.path("detail").path("specs").get(0).path("value").asText());
        assertEquals("10%", master.path("rebate").path("commissionRate").asText());
        assertEquals("完整商品标题", products.findProduct(PriceCodes.JD, "123456789").getTitle());
    }

    @Test void expiredSearchRetainsFallbackWithoutChangingFailureOrRenewingCache() {
        ObjectNode request = searchRequest("键盘", 5);
        ObjectNode success = searchResult("123456789");
        success.put("createdAt", 1234567L);
        service.complete("jdUnionSearchProducts", request, success);
        expireSearch();
        assertNull(service.fresh("jdUnionSearchProducts", request));
        ObjectNode failure = mapper.createObjectNode().put("status", "TIMEOUT")
                .put("message", "本次超时").put("createdAt", 7654321L).put("resultCount", 0);
        failure.putArray("items");
        JsonNode first = service.complete("jdUnionSearchProducts", request, failure);
        JsonNode second = service.complete("jdUnionSearchProducts", request, failure);
        assertEquals("TIMEOUT", first.path("status").asText());
        assertEquals("本次超时", first.path("message").asText());
        assertEquals(7654321L, first.path("createdAt").asLong());
        assertTrue(first.path("items").isEmpty());
        assertEquals(0, first.path("resultCount").asInt());
        assertEquals("SUCCESS", first.path("fallback").path("status").asText());
        assertEquals("123456789", first.path("fallback").path("items").get(0).path("productId").asText());
        assertEquals(1234567L, first.path("fallback").path("createdAt").asLong());
        assertFalse(first.path("fallback").path("cacheHit").asBoolean());
        assertEquals(first.path("fallback").path("cachedAt"), second.path("fallback").path("cachedAt"));
        assertEquals(java.sql.Timestamp.valueOf("2000-01-01 00:00:00").toInstant().toEpochMilli(), second.path("fallback").path("cacheExpiresAt").asLong());
        assertNull(service.fresh("jdUnionSearchProducts", request));
        assertEquals("99.00", service.product("jd", "123456789").path("basic").path("price").asText());
    }

    @Test void promotionUsesPlatformNumericIdAndAdSiteAcrossProfilesAndMedia() {
        ObjectNode request = promotionRequest("123456789", "site-a");
        request.put("profileName", "profile-a").put("mediaName", "media-a");
        ObjectNode success = promotionResult("123456789");
        success.put("createdAt", 445566L);
        service.complete("getTbPromotionUrl", request, success);
        ObjectNode other = promotionRequest("123456789", "site-a");
        other.put("profileName", "profile-b").put("mediaName", "media-b");
        JsonNode cached = service.fresh("getTbPromotionUrl", other);
        assertNotNull(cached);
        assertTrue(cached.path("cacheHit").asBoolean());
        assertEquals(445566L, cached.path("createdAt").asLong());
        assertEquals("https://e.tb.cn/test", cached.path("promotionUrl").asText());
        assertNull(service.fresh("getPromotionUrl", other));
        assertNull(service.fresh("getTbPromotionUrl", promotionRequest("123456789", "site-b")));
        assertNull(service.fresh("getTbPromotionUrl", promotionRequest("223456789", "site-a")));
        assertEquals(1, count("product_promotion_cache"));
    }

    @Test void finalDetailProductIdConflictDoesNotCreateProductOrCache() {
        assertThrows(TaskApiException.class, () -> service.complete("jdProductDetail",
                detailRequest("123456789"), detailResult("223456789")));
        assertNull(products.findProduct(PriceCodes.JD, "123456789"));
        assertNull(products.findProduct(PriceCodes.JD, "223456789"));
        assertEquals(0, count("price_product"));
        assertNull(store.detail(PriceCodes.JD, "123456789"));
    }

    @Test void opaqueUnionIdentityIsResolvedButNeverPersistedInAnyPayload() {
        String opaque = "opaque-material-never-persist-abc";
        when(identities.resolve(eq(PriceCodes.TB), anyString(), eq(opaque))).thenReturn("123456789");
        ObjectNode result = searchResult("123456789");
        ObjectNode card = (ObjectNode) result.path("items").get(0);
        card.remove("productId");
        card.put("itemId", opaque).put("encryptedProductId", opaque).put("mktItemId", opaque);
        service.complete("tbUnionSearchProducts", searchRequest("键盘", 5), result);
        verify(identities).resolve(eq(PriceCodes.TB), anyString(), eq(opaque));
        assertEquals(1, count("price_product"));
        assertEquals("123456789", service.product("tb", "123456789").path("productId").asText());
        assertFalse(allPayloads().contains(opaque), "Temporary material must not survive in master or search/promotion cache JSON");
    }

    @Test void searchAndDetailCacheResponsesKeepOriginalCreatedAt() {
        ObjectNode request = searchRequest("键盘", 5);
        ObjectNode result = searchResult("123456789");
        result.put("createdAt", 101010L);
        service.complete("jdSearchProduct", request, result);
        assertEquals(101010L, service.fresh("jdSearchProduct", request).path("createdAt").asLong());
        ObjectNode detail = detailResult("123456789");
        detail.put("createdAt", 202020L);
        service.complete("jdProductDetail", detailRequest("123456789"), detail);
        JsonNode cached = service.fresh("jdProductDetail", detailRequest("123456789"));
        assertNotNull(cached);
        assertEquals(202020L, cached.path("createdAt").asLong());
        assertFalse(cached.has("_options"));
    }

    private ObjectNode searchRequest(String keyword, int limit) {
        return mapper.createObjectNode().put("keyword", keyword).put("maxResults", limit);
    }
    private ObjectNode searchResult(String id) {
        ObjectNode result = mapper.createObjectNode().put("status", "SUCCESS").put("createdAt", 123L);
        result.putArray("items").addObject().put("productId", id).put("productName", "商品")
                .put("storeName", "店铺").put("price", "99.00");
        result.put("resultCount", 1);
        return result;
    }
    private ObjectNode detailRequest(String id) { return mapper.createObjectNode().put("skuId", id); }
    private ObjectNode detailResult(String id) {
        return mapper.createObjectNode().put("status", "SUCCESS").put("skuId", id)
                .put("currentUrl", "https://item.jd.com/" + id + ".html");
    }
    private ObjectNode promotionRequest(String id, String site) {
        return mapper.createObjectNode().put("keyword", id).put("adSiteName", site);
    }
    private ObjectNode promotionResult(String id) {
        ObjectNode result = mapper.createObjectNode().put("status", "SUCCESS").put("productId", id)
                .put("identityResolved", true).put("promotionUrl", "https://e.tb.cn/test");
        result.putObject("productInfo").put("productId", id).put("productName", "商品");
        return result;
    }
    private void expireSearch() {
        database.executeUpdate("update product_search_cache set expires_at=timestamp '2000-01-01 00:00:00'");
    }
    private int count(String table) {
        return database.withConnection(connection -> {
            try (Statement statement = connection.createStatement(); var rows = statement.executeQuery("select count(*) from " + table)) {
                rows.next(); return rows.getInt(1);
            }
        });
    }
    private String allPayloads() {
        return database.withConnection(connection -> {
            StringBuilder payloads = new StringBuilder();
            for (String sql : new String[]{
                    "select platform_product_id,basic_json,union_json,detail_json,detail_result_json from price_product",
                    "select keyword,parameters_json,result_json from product_search_cache",
                    "select promotion_token,short_url,long_url,result_json from product_promotion_cache"}) {
                try (Statement statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                    int columns = rows.getMetaData().getColumnCount();
                    while (rows.next()) for (int i = 1; i <= columns; i++) payloads.append(rows.getString(i));
                }
            }
            return payloads.toString();
        });
    }
}
