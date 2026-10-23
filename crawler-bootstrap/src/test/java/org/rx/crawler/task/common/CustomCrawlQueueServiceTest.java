package org.rx.crawler.task.common;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.config.TaskApiProperties;
import org.rx.crawler.task.jd.*;
import org.rx.crawler.task.tb.*;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
class CustomCrawlQueueServiceTest {
    @TempDir Path temp;
    EntityDatabase db;
    CustomCrawlQueueService queue;
    BrowserOwnerExecutor owner;
    TaskApiProperties limits;
    JdMallTask jd;
    JdUnionPromotionTask promotion;
    @BeforeEach void setup() {
        db=new EntityDatabaseImpl(temp.resolve("tasks").toString(),null);
        limits=new TaskApiProperties();owner=new BrowserOwnerExecutor();jd=mock(JdMallTask.class);promotion=mock(JdUnionPromotionTask.class);
        queue=create();queue.setExecutionGate(()->false);queue.init();
    }
    CustomCrawlQueueService create() {
        CustomCrawlQueueService service=new CustomCrawlQueueService(db,new ObjectMapper(),new AppConfig(),promotion,jd,
                mock(TbPromotionOrdersTask.class),mock(TbPromotionUrlTask.class),mock(TbMallTask.class));
        ReflectionTestUtils.setField(service,"taskApiProperties",limits);
        ReflectionTestUtils.setField(service,"ownerExecutor",owner);return service;
    }
    @AfterEach void close() throws Exception {queue.destroy();owner.call(()->null);owner.shutdown();db.close();}
    long submit(String key) {return queue.enqueueOwned("alice","jdProductDetail",Map.of("skuId","123"),key);}
    TaskApiException error(Runnable runnable,int status) {TaskApiException e=assertThrows(TaskApiException.class,runnable::run);assertEquals(status,e.getStatus());return e;}
    void sql(String sql) {db.executeUpdate(sql);}
    @Test void unionRequestAndResultKeepNumericIdentityAndAllowPromotionLink() throws Exception {
        String material = "EncryptedMaterialAlpha123456";
        String link = "https://u.jd.com/ephemeral";
        var resolver = mock(org.rx.crawler.task.price.ProductIdentityResolver.class);
        when(resolver.resolve(anyInt(), anyString(), anyString())).thenReturn("123456789");
        var boundary = new org.rx.crawler.task.price.UnionMaterialBoundary(new ObjectMapper(), resolver);
        ReflectionTestUtils.setField(queue, "materialBoundary", boundary);
        PromotionUrlResult promoted = new PromotionUrlResult();
        promoted.setStatus(CustomCrawlStatus.SUCCESS); promoted.setKeyword("123456789");
        promoted.setPromotionUrl(link); promoted.setEncryptedProductId(material);
        promoted.setProductInfo(new ProductInfoDto());
        promoted.getProductInfo().setProductName("sample");
        promoted.getDiagnostics().put("raw", material);
        when(promotion.getPromotionUrl(any())).thenReturn(promoted);
        long id = queue.enqueueOwned("alice", "getPromotionUrl", Map.of("keyword", material, "adSiteName", "0"), "numeric-boundary");
        queue.setExecutionGate(() -> true); queue.refreshQueue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"SUCCEEDED".equals(queue.queryOwned(id, "alice").getStatus()) && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals("SUCCEEDED", queue.queryOwned(id, "alice").getStatus());
        var live = (com.fasterxml.jackson.databind.JsonNode) queue.resultOwned(id, "alice");
        assertEquals(link, live.path("promotionUrl").asText());
        assertEquals("123456789", live.path("productId").asText());
        db.withConnection(connection -> {
            try (var statement = connection.prepareStatement("select request_json,result_json from custom_crawl_task_queue where id=?")) {
                statement.setLong(1, id);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    String stored = rows.getString(1) + rows.getString(2);
                    assertFalse(stored.contains(material)); assertTrue(stored.contains(link));
                    assertFalse(stored.contains("encryptedProductId")); assertFalse(stored.contains("diagnostics"));
                    assertTrue(stored.contains("123456789"));
                }
            }
            return null;
        });
        error(() -> queue.resultOwned(id, "bob"), 404);
    }

    @Test void concurrentIdempotencyAndDigestConflict() throws Exception {
        ExecutorService clients=Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> ids=clients.invokeAll(java.util.stream.IntStream.range(0,16).<Callable<Long>>mapToObj(i->()->submit("one")).toList());
            long expected=ids.get(0).get();for(Future<Long> id:ids)assertEquals(expected,id.get());
            assertEquals("PENDING",queue.queryOwned(expected,"alice").getStatus());
            error(()->queue.enqueueOwned("alice","jdProductDetail",Map.of("skuId","456"),"one"),409);
        } finally {clients.shutdownNow();}
    }
    @Test void capacityAndOwnerIsolationAndIdempotentCancel() {
        limits.setCapacity(1);long id=submit("one");
        assertEquals(id,submit("one"));
        error(()->submit("two"),429);error(()->queue.queryOwned(id,"bob"),404);error(()->queue.cancelOwned(id,"bob"),404);
        assertEquals("CANCELLED",queue.cancelOwned(id,"alice").getStatus());
        assertEquals("CANCELLED",queue.cancelOwned(id,"alice").getStatus());
        assertTrue(submit("two")>id);
    }
    @Test void canonicalDigestIgnoresObjectFieldOrder() {
        Map<String,Object> first=new java.util.LinkedHashMap<>();first.put("skuId","123");first.put("downloadImages",false);
        Map<String,Object> second=new java.util.LinkedHashMap<>();second.put("downloadImages",false);second.put("skuId","123");
        long id=queue.enqueueOwned("alice","jdProductDetail",first,"order");
        assertEquals(id,queue.enqueueOwned("alice","jdProductDetail",second,"order"));
    }
    @Test void pendingSurvivesRealDatabaseReopenAndRunningNeverReplays() throws Exception {
        long pending=submit("pending");long interrupted=submit("running");
        sql("update custom_crawl_task_queue set status='RUNNING' where id="+interrupted);
        queue.destroy();db.close();db=new EntityDatabaseImpl(temp.resolve("tasks").toString(),null);
        queue=create();queue.setExecutionGate(()->false);queue.init();
        assertEquals(pending,submit("pending"));assertEquals("PENDING",queue.queryOwned(pending,"alice").getStatus());
        AsyncTaskView failed=queue.queryOwned(interrupted,"alice");
        assertEquals("FAILED",failed.getStatus());assertEquals("PROCESS_RESTARTED",failed.getErrorCode());assertNotNull(failed.getExpiresAt());
    }
    @Test void queueDeadlineAndExpiredResult() {
        long id=submit("one");sql("update custom_crawl_task_queue set queue_deadline=dateadd('SECOND',-1,current_timestamp) where id="+id);
        assertEquals("TIMED_OUT",queue.queryOwned(id,"alice").getStatus());
        sql("update custom_crawl_task_queue set expires_at=dateadd('SECOND',-1,current_timestamp) where id="+id);
        error(()->queue.resultOwned(id,"alice"),410);
    }
    @Test void resultNotReadyAndDraining() {
        long id=submit("one");error(()->queue.resultOwned(id,"alice"),409);
        queue.setDraining(true);assertEquals(id,submit("one"));error(()->submit("two"),503);
        assertEquals("PENDING",queue.queryOwned(id,"alice").getStatus());
    }
    @Test void businessFailureAndSensitiveArtifactRedaction() throws Exception {
        JdProductDetailResult failure=new JdProductDetailResult();failure.setStatus(CustomCrawlStatus.PAGE_CHANGED);failure.setMessage("D:/private/output");
        when(jd.productDetail(any())).thenReturn(failure);
        long id=submit("failure");runToBoundary();
        assertEquals("FAILED",queue.queryOwned(id,"alice").getStatus());
        String result=queue.resultOwned(id,"alice").toString();assertFalse(result.contains("D:/private"));assertTrue(result.contains("PAGE_CHANGED"));
        assertEquals("FAILED",queue.query(id).getStatus());
    }
    @Test void runningCancelWaitsForSafeBoundary() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(jd.productDetail(any())).thenAnswer(inv->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));JdProductDetailResult r=new JdProductDetailResult();r.setStatus(CustomCrawlStatus.SUCCESS);return r;});
        long id=submit("cancel");queue.setExecutionGate(()->true);queue.refreshQueue();assertTrue(entered.await(5,TimeUnit.SECONDS));
        assertEquals("CANCEL_REQUESTED",queue.cancelOwned(id,"alice").getStatus());
        release.countDown();owner.call(()->null);assertEquals("CANCELLED",queue.queryOwned(id,"alice").getStatus());
    }
    @Test void loginWaitBlocksOtherTasksAndResumeRechecksAction() throws Exception {
        JdProductDetailResult login=new JdProductDetailResult();login.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
        JdProductDetailResult success=new JdProductDetailResult();success.setStatus(CustomCrawlStatus.SUCCESS);
        when(jd.productDetail(any())).thenReturn(login,success);
        long id=submit("login");runToBoundary();assertEquals("WAITING_USER",queue.queryOwned(id,"alice").getStatus());
        assertEquals("RUNNING",queue.query(id).getStatus());
        long next=queue.enqueueOwned("alice","jdProductDetail",Map.of("skuId","456"),"next");
        owner.call(()->null);assertEquals("PENDING",queue.queryOwned(next,"alice").getStatus());
        queue.resumeOwned(id,"alice");owner.call(()->null);assertEquals("SUCCEEDED",queue.queryOwned(id,"alice").getStatus());
        verify(jd,times(2)).productDetail(argThat(request -> "123".equals(request.getSkuId())));
    }
    @Test void batchChildrenAreDurableAndPartialResultsAreTraceable() throws Exception {
        PromotionUrlResult success=new PromotionUrlResult();success.setStatus(CustomCrawlStatus.SUCCESS);
        PromotionUrlResult failed=new PromotionUrlResult();failed.setStatus(CustomCrawlStatus.FAILED);
        when(promotion.getPromotionUrl(any())).thenReturn(success,failed);
        long id=queue.enqueueOwned("alice","getPromotionUrls",List.of("a","b"),"batch");
        List<String> children=queue.queryOwned(id,"alice").getChildTaskIds();assertEquals(2,children.size());
        runToBoundary();queue.refreshQueue();owner.call(()->null);
        assertEquals("FAILED",queue.queryOwned(id,"alice").getStatus());
        String result=queue.resultOwned(id,"alice").toString();assertTrue(result.contains(children.get(0)));assertTrue(result.contains(children.get(1)));
        verify(promotion,times(2)).getPromotionUrl(any());
    }
    @Test void executionDeadlineIsAppliedAtSafeBoundary() throws Exception {
        when(jd.productDetail(any())).thenAnswer(inv->{sql("update custom_crawl_task_queue set execution_deadline=dateadd('SECOND',-1,current_timestamp)");JdProductDetailResult r=new JdProductDetailResult();r.setStatus(CustomCrawlStatus.SUCCESS);return r;});
        long id=submit("deadline");runToBoundary();
        assertEquals("TIMED_OUT",queue.queryOwned(id,"alice").getStatus());
        assertEquals("EXECUTION_TIMEOUT",queue.queryOwned(id,"alice").getErrorCode());
    }
    @Test void batchResumeDoesNotReplayFinishedChildren() throws Exception {
        PromotionUrlResult success=new PromotionUrlResult();success.setStatus(CustomCrawlStatus.SUCCESS);
        PromotionUrlResult login=new PromotionUrlResult();login.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
        when(promotion.getPromotionUrl(any())).thenReturn(success,login,success);
        long id=queue.enqueueOwned("alice","getPromotionUrls",List.of("first","second"),"batch-wait");
        runToBoundary();queue.refreshQueue();owner.call(()->null);
        assertEquals("WAITING_USER",queue.queryOwned(id,"alice").getStatus());
        queue.resumeOwned(id,"alice");owner.call(()->null);
        assertEquals("SUCCEEDED",queue.queryOwned(id,"alice").getStatus());
        verify(promotion,times(1)).getPromotionUrl(argThat(request -> "first".equals(request.getKeyword())));
        verify(promotion,times(2)).getPromotionUrl(argThat(request -> "second".equals(request.getKeyword())));
    }
    @Test void userWaitExpiryReleasesProfileBeforeNextTask() throws Exception {
        JdProductDetailResult login=new JdProductDetailResult();login.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
        when(jd.productDetail(any())).thenReturn(login);
        long id=submit("wait-expiry");runToBoundary();
        sql("update custom_crawl_task_queue set user_deadline=dateadd('SECOND',-1,current_timestamp) where id="+id);
        assertEquals("TIMED_OUT",queue.queryOwned(id,"alice").getStatus());owner.call(()->null);
        verify(jd).closeProfile("common");
    }
    @Test void resumeStartsFreshUserWaitWindow() {
        JdProductDetailResult login=new JdProductDetailResult();login.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
        when(jd.productDetail(any())).thenReturn(login);
        long id=submit("wait-again");runToBoundary();
        queue.setExecutionGate(()->false);
        sql("update custom_crawl_task_queue set user_deadline=dateadd('SECOND',1,current_timestamp) where id="+id);
        queue.resumeOwned(id,"alice");
        assertEquals(1,db.<Integer>withConnection(c -> {
            try(var statement=c.prepareStatement("select count(*) from custom_crawl_task_queue where id=? and user_deadline is null")) {
                statement.setLong(1,id);try(var rows=statement.executeQuery()){rows.next();return rows.getInt(1);}
            }
        }));
        runToBoundary();
        assertEquals("WAITING_USER",queue.queryOwned(id,"alice").getStatus());
        assertEquals(1,db.<Integer>withConnection(c -> {
            try(var statement=c.prepareStatement("select count(*) from custom_crawl_task_queue where id=? and user_deadline > dateadd('SECOND',5,current_timestamp)")) {
                statement.setLong(1,id);try(var rows=statement.executeQuery()){rows.next();return rows.getInt(1);}
            }
        }));
    }
    @Test void waitingCancellationReleasesActualResultProfile() {
        JdProductDetailResult login=new JdProductDetailResult();login.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
        login.setProfileName("actual-profile");
        when(jd.productDetail(any())).thenReturn(login);
        long id=submit("wait-custom");runToBoundary();
        queue.cancelOwned(id,"alice");owner.call(()->null);
        verify(jd).closeProfile("actual-profile");verify(jd,never()).closeProfile("common");
    }
    @Test void waitExpiryFallsBackToConfiguredDefaultProfile() {
        AppConfig app=(AppConfig)ReflectionTestUtils.getField(queue,"appConfig");
        app.getCustom().getChrome().setDefaultProfileName("configured-default");
        JdProductDetailResult login=new JdProductDetailResult();login.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
        when(jd.productDetail(any())).thenReturn(login);
        long id=submit("wait-default");runToBoundary();
        sql("update custom_crawl_task_queue set user_deadline=dateadd('SECOND',-1,current_timestamp) where id="+id);
        assertEquals("TIMED_OUT",queue.queryOwned(id,"alice").getStatus());owner.call(()->null);
        verify(jd).closeProfile("configured-default");verify(jd,never()).closeProfile("common");
    }
    @Test void notFoundIsSuccessfulExecutionWithSeparateBusinessStatus() throws Exception {
        JdProductDetailResult empty=new JdProductDetailResult();empty.setStatus(CustomCrawlStatus.NOT_FOUND);
        when(jd.productDetail(any())).thenReturn(empty);long id=submit("empty");runToBoundary();
        assertEquals("SUCCEEDED",queue.queryOwned(id,"alice").getStatus());
        assertEquals("NOT_FOUND",queue.queryOwned(id,"alice").getBusinessStatus());
    }
    @Test void foregroundPriorityRunsBeforeBackgroundPriceRefresh() throws Exception {
        JdProductDetailResult success=new JdProductDetailResult();success.setStatus(CustomCrawlStatus.SUCCESS);
        when(jd.productDetail(any())).thenReturn(success);
        long background=queue.enqueueBackground("price-watch","priceRefresh",Map.of("watchId",1),"bg",10);
        long foreground=submit("front");
        assertTrue(foreground>background);
        assertEquals(10,priorityOf(background));
        assertEquals(CustomCrawlQueueService.FOREGROUND_PRIORITY,priorityOf(foreground));
        error(()->queue.enqueueOwned("alice","priceRefresh",Map.of("watchId",1),"no"),400);
        runToBoundary();
        assertEquals("SUCCEEDED",queue.queryOwned(foreground,"alice").getStatus());
        assertEquals("PENDING",queue.queryOwned(background,"price-watch").getStatus());
    }
    @Test void startupPromotesActiveUserTasksFromPriorityZero() {
        sql("insert into custom_crawl_task_queue(task_action,request_json,status,priority,created_at,updated_at,owner_id,batch_parent) values ('jdProductDetail','{}','PENDING',0,current_timestamp,current_timestamp,'alice',false)");
        sql("insert into custom_crawl_task_queue(task_action,request_json,status,priority,created_at,updated_at,owner_id,batch_parent) values ('priceRefresh','{}','PENDING',0,current_timestamp,current_timestamp,'price-watch',false)");
        queue.init();
        assertEquals(CustomCrawlQueueService.FOREGROUND_PRIORITY,priorityOfAction("jdProductDetail"));
        assertEquals(0,priorityOfAction("priceRefresh"));
    }
    @Test void freshProductCacheCompletesImmediatelyWithoutInvokingPromotionTask() {
        var mapper = new ObjectMapper();
        var cache = mock(org.rx.crawler.task.price.ProductCacheService.class);
        var cached = mapper.createObjectNode().put("status", "SUCCESS").put("productId", "123456789")
                .put("identityResolved", true).put("cacheHit", true).put("createdAt", 123456L)
                .put("promotionUrl", "https://u.jd.com/cached");
        when(cache.fresh(eq("getPromotionUrl"), any())).thenReturn(cached);
        ReflectionTestUtils.setField(queue, "productCache", cache);
        long id = queue.enqueueOwned("alice", "getPromotionUrl", Map.of("keyword", "123456789"), "fresh-hit");
        assertEquals("SUCCEEDED", queue.queryOwned(id, "alice").getStatus());
        var response = (com.fasterxml.jackson.databind.JsonNode) queue.resultOwned(id, "alice");
        assertTrue(response.path("cacheHit").asBoolean());
        assertEquals(123456L, response.path("createdAt").asLong());
        assertEquals("https://u.jd.com/cached", response.path("promotionUrl").asText());
        verify(promotion, never()).getPromotionUrl(any());
        verifyNoInteractions(jd);
    }

    @Test void thrownTaskFailureReturnsSeparateFallbackAndKeepsMainFailedStatus() {
        var cache = fallbackCache("getPromotionUrl");
        when(promotion.getPromotionUrl(any())).thenThrow(new IllegalStateException("boom"));
        long id = queue.enqueueOwned("alice", "getPromotionUrl", Map.of("keyword", "123456789"), "fallback-failure");
        runToBoundary();
        assertEquals("FAILED", queue.queryOwned(id, "alice").getStatus());
        var response = (com.fasterxml.jackson.databind.JsonNode) queue.resultOwned(id, "alice");
        assertEquals("FAILED", response.path("status").asText());
        assertEquals("SUCCESS", response.path("fallback").path("status").asText());
        assertEquals("123456789", response.path("fallback").path("productId").asText());
        assertFalse(response.has("productId"));
        verify(cache).failure(eq("getPromotionUrl"), any(), eq("EXECUTION_FAILED"));
    }

    @Test void queueWaitDeadlineReturnsFallbackWithoutRunningBrowserTask() {
        var cache = fallbackCache("jdProductDetail");
        long id = submit("fallback-queue-timeout");
        sql("update custom_crawl_task_queue set queue_deadline=dateadd('SECOND',-1,current_timestamp) where id=" + id);
        assertEquals("TIMED_OUT", queue.queryOwned(id, "alice").getStatus());
        var response = (com.fasterxml.jackson.databind.JsonNode) queue.resultOwned(id, "alice");
        assertEquals("FAILED", response.path("status").asText());
        assertEquals("SUCCESS", response.path("fallback").path("status").asText());
        assertEquals("123456789", response.path("fallback").path("productId").asText());
        verify(cache).failure(eq("jdProductDetail"), any(), anyString());
        verifyNoInteractions(jd);
    }

    org.rx.crawler.task.price.ProductCacheService fallbackCache(String action) {
        var cache = mock(org.rx.crawler.task.price.ProductCacheService.class);
        when(cache.supports(action)).thenReturn(true);
        when(cache.failure(eq(action), any(), anyString())).thenAnswer(call -> {
            var response = new ObjectMapper().createObjectNode().put("status", "FAILED").put("errorCode", call.getArgument(2, String.class));
            response.putObject("fallback").put("status", "SUCCESS").put("productId", "123456789").put("cacheHit", false);
            return response;
        });
        when(cache.complete(eq(action), any(), any())).thenAnswer(call -> call.getArgument(2));
        ReflectionTestUtils.setField(queue, "productCache", cache);
        return cache;
    }

    int priorityOf(long id) {
        return db.withConnection(connection -> {
            try (PreparedStatement statement=connection.prepareStatement("select priority from custom_crawl_task_queue where id=?")) {
                statement.setLong(1,id);
                try (ResultSet rows=statement.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
            }
        });
    }
    int priorityOfAction(String action) {
        return db.withConnection(connection -> {
            try (PreparedStatement statement=connection.prepareStatement("select priority from custom_crawl_task_queue where task_action=? order by id desc")) {
                statement.setString(1,action);
                try (ResultSet rows=statement.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
            }
        });
    }
    void runToBoundary() {queue.setExecutionGate(()->true);queue.refreshQueue();owner.call(()->null);}
}
