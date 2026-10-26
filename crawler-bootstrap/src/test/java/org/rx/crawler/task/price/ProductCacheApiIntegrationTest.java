package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.Application;
import org.rx.crawler.config.ProductCacheProperties;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真 HTTP、认证、队列、H2 和响应投影；平台任务替身，不启动第二个浏览器。 */
@SpringBootTest(classes=Application.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"app.fiddler-enabled=false","app.custom.chrome.preWarm=false","app.custom.remotingEnabled=false",
                "app.custom.priceWatch.enabled=false","app.custom.loginKeepAlive.enabled=false",
                "crawler.task-api.api-keys.integration=product-cache-integration-only"})
@DirtiesContext
class ProductCacheApiIntegrationTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) {
        registry.add("app.baseDir",()->temp.toString());
        registry.add("app.storage.h2DbPath",()->temp.resolve("tasks").toString());
        registry.add("app.storage.priceDbPath",()->temp.resolve("products").toString());
    }
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired ProductCacheService catalog;
    @Autowired ProductCacheProperties settings;
    @Autowired org.rx.crawler.task.common.CustomCrawlQueueService queue;
    @MockBean JdUnionPromotionTask platformTask;

    @Test void cachedPromotionAndExpiredFailureWorkThroughPublicApiAndDurableQueue() throws Exception {
        var request=mapper.valueToTree(Map.of("keyword","123456789","adSiteName","0"));
        // 链接式快照缺 shortUrl/promotionToken 时 fresh() 会强制回源刷新，种子必须带全原生口令字段。
        var successful=mapper.createObjectNode().put("status","SUCCESS").put("productId","123456789")
                .put("identityResolved",true).put("promotionUrl","https://u.jd.com/integration")
                .put("shortUrl","https://u.jd.com/integration").put("promotionToken","integration-token")
                .put("createdAt",123456L);
        successful.putObject("productInfo").put("productName","integration item").put("price","99.00").put("commissionRate","5%");
        catalog.complete("getPromotionUrl",request,successful);
        queue.setExecutionGate(()->false); // 缓存命中不依赖桌面可用性。
        String hit=submit();
        assertEquals("SUCCEEDED",get("/api/v1/tasks/"+hit).path("status").asText());
        JsonNode cached=get("/api/v1/tasks/"+hit+"/result");
        assertTrue(cached.path("cacheHit").asBoolean());
        assertEquals(123456L,cached.path("createdAt").asLong());
        assertEquals("https://u.jd.com/integration",cached.path("promotionUrl").asText());
        verify(platformTask,never()).getPromotionUrl(any());
        assertEquals("5%",get("/api/v1/products/jd/123456789").path("rebate").path("commissionRate").asText());

        settings.setPromotionTtlSeconds(0);
        catalog.complete("getPromotionUrl",request,successful);
        when(platformTask.getPromotionUrl(any())).thenThrow(new IllegalStateException("simulated timeout"));
        queue.setExecutionGate(()->true); // 仅允许测试替身执行；Linux runner 没有交互桌面。
        String failed=submit();
        long deadline=System.currentTimeMillis()+10000;
        while (!get("/api/v1/tasks/"+failed).path("status").asText().equals("FAILED") && System.currentTimeMillis()<deadline) Thread.sleep(20);
        assertEquals("FAILED",get("/api/v1/tasks/"+failed).path("status").asText());
        JsonNode result=get("/api/v1/tasks/"+failed+"/result");
        assertEquals("FAILED",result.path("status").asText());
        assertFalse(result.hasNonNull("promotionUrl"));
        assertEquals("SUCCESS",result.path("fallback").path("status").asText());
        assertEquals("https://u.jd.com/integration",result.path("fallback").path("promotionUrl").asText());
        assertEquals("123456789",result.path("fallback").path("productId").asText());
    }
    private String submit() {
        var response=http.exchange("/api/v1/tasks",HttpMethod.POST,entity(Map.of("action","getPromotionUrl",
                "request",Map.of("keyword","123456789","adSiteName","0"))),JsonNode.class);
        assertEquals(HttpStatus.ACCEPTED,response.getStatusCode());
        return response.getBody().path("data").path("taskId").asText();
    }
    private JsonNode get(String path) {
        var response=http.exchange(path,HttpMethod.GET,entity(null),JsonNode.class);
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertTrue(response.getBody().path("success").asBoolean());
        return response.getBody().path("data");
    }
    private HttpEntity<?> entity(Object body) {
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth("product-cache-integration-only");
        return new HttpEntity<>(body,headers);
    }
}
