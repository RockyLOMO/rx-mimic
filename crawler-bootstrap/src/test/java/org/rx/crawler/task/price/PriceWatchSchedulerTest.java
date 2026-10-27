package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.CustomCrawlQueueService;
import org.rx.crawler.task.common.TaskApiException;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PriceWatchSchedulerTest {
    @TempDir Path temp;
    EntityDatabase db;
    PriceWatchStore store;
    AppConfig config;
    CustomCrawlQueueService queue;
    PriceWatchCoordinator coordinator;
    final ObjectMapper mapper=new ObjectMapper();

    @BeforeEach void open() {
        db=new EntityDatabaseImpl(temp.resolve("prices").toString(),null);
        store=new PriceWatchStore(db);store.init();
        config=new AppConfig();
        var clock=LocalTime.now(ZoneOffset.UTC);
        var settings=config.getCustom().getPriceWatch();
        settings.setTimezone("UTC");
        settings.setWindowStart(clock.minusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));
        settings.setWindowEnd(clock.plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));
        queue=mock(CustomCrawlQueueService.class);
        coordinator=new PriceWatchCoordinator(config,mapper,store,mock(UnionPriceProbe.class),mock(PromotionNavigator.class),body->false,mock(PriceRiskGate.class));
        coordinator.setQueue(queue);
    }
    @AfterEach void close() throws Exception { db.close(); }

    @Test void oldKeyCollisionDoesNotBlockNewRefreshAndPlanAttaches() {
        var watch=watch("123456789");
        when(queue.enqueueBackground(anyString(),eq("priceRefresh"),any(),anyString(),anyInt())).thenAnswer(call->{
            String key=call.getArgument(3);
            if(key.equals(watch.getId()+":1:priceRefresh")) throw new TaskApiException(409,"IDEMPOTENCY_CONFLICT","old request");
            assertTrue(key.startsWith("price:v2:"));assertTrue(key.length()<=128);
            return 101L;
        });
        var result=coordinator.manualRefresh("app",watch.getId());
        assertTrue(result.isOk());assertEquals(101L,result.getTaskId());
        assertEquals(101L,store.watch(watch.getId()).getCurrentTaskId());
        assertEquals(1,store.watch(watch.getId()).getPlanSeq());
    }
    @Test void refreshIdentityIncludesChangedProductAndProfileButCoalescesIdenticalRequests() {
        var body=mapper.createObjectNode().put("watchId",1).put("planId",1).put("platform",1).put("productId","123456789").put("profileName","common");
        String key=PriceWatchCoordinator.refreshKey(body);
        assertEquals(key,PriceWatchCoordinator.refreshKey(body.deepCopy()));
        assertNotEquals(key,PriceWatchCoordinator.refreshKey(body.deepCopy().put("productId","223456789")));
        assertNotEquals(key,PriceWatchCoordinator.refreshKey(body.deepCopy().put("profileName","changed")));
    }
    @Test void oneBrokenWatchDoesNotStarveFollowingOverdueProducts() {
        var first=watch("123456789");var second=watch("223456789");
        when(queue.enqueueBackground(anyString(),anyString(),any(),anyString(),anyInt()))
                .thenThrow(new TaskApiException(409,"IDEMPOTENCY_CONFLICT","conflict")).thenReturn(202L);
        coordinator.tick();
        assertEquals("ENQUEUE_IDEMPOTENCY_CONFLICT",store.watch(first.getId()).getPauseReason());
        assertTrue(store.watch(first.getId()).getNextCheckAt().toInstant().isAfter(Instant.now()));
        assertEquals(202L,store.watch(second.getId()).getCurrentTaskId());
    }
    @Test void duplicateEnqueuerDoesNotCancelTaskAlreadyAttachedByWinner() {
        var stale=watch("123456789");
        when(queue.enqueueBackground(anyString(),anyString(),any(),anyString(),anyInt())).thenReturn(101L);
        ReflectionTestUtils.invokeMethod(coordinator,"enqueue",stale,false,Instant.now(),10);
        ReflectionTestUtils.invokeMethod(coordinator,"enqueue",stale,false,Instant.now(),10);
        assertEquals(101L,store.watch(stale.getId()).getCurrentTaskId());
        verify(queue,never()).deferTask(eq(101L),anyString());
        store.clearTask(stale.getId());
        assertFalse(store.attachTask(stale.getId(),1,102)); // 旧计划不能再次关联。
    }
    @Test void persistedDailyLimitCoversSchedulerManualAndAlreadyQueuedTasks() {
        var watch = watch("123456789");
        store.noteAttempt(watch.getId(), Timestamp.from(Instant.now()));
        // A failed attempt still consumes today's check, preventing repeated retries.
        coordinator.tick();
        assertTrue(coordinator.manualRefresh("app", watch.getId()).isCached());
        assertEquals("DAILY_CACHED", coordinator.deferReason("{\"watchId\":" + watch.getId() + "}"));
        var result = coordinator.refresh("{\"watchId\":" + watch.getId() + "}", 500);
        assertEquals("DAILY_CACHED", result.getMessage());
        verify(queue, never()).enqueueBackground(anyString(), anyString(), any(), anyString(), anyInt());
        assertTrue(store.watch(watch.getId()).getNextCheckAt().toInstant().isAfter(Instant.now()));
    }
    @Test void budgetAdmissionFailureDoesNotConsumeDailyAttemptButActualFailureDoes() {
        var watch = watch("123456789");
        var probe = mock(UnionPriceProbe.class);
        ReflectionTestUtils.setField(coordinator, "probe", probe);
        String body = "{\"watchId\":" + watch.getId() + "}";
        when(probe.probe(anyInt(),anyString(),anyString()))
                .thenThrow(new TaskApiException(429,"DAILY_VISIT_LIMIT","limit"))
                .thenThrow(new IllegalStateException("timeout after visit"));
        assertThrows(TaskApiException.class, () -> coordinator.refresh(body,501));
        assertNull(store.watch(watch.getId()).getLastAttemptAt());
        assertThrows(IllegalStateException.class, () -> coordinator.refresh(body,502));
        assertNotNull(store.watch(watch.getId()).getLastAttemptAt());
        assertTrue(coordinator.manualRefresh("app",watch.getId()).isCached());
    }
    private PriceWatchStore.WatchRow watch(String numericId) {
        var now=Timestamp.from(Instant.now());
        var product=store.upsertProduct(PriceCodes.JD,numericId,"sample","shop","",now);
        var draft=new PriceWatchStore.WatchDraft();
        draft.setOwnerId("app");draft.setProductId(product.getProductId());draft.setWatchKey("app|"+product.getProductId());
        draft.setProfileName("common");draft.setDropAmount(BigDecimal.ONE);draft.setDropPercent(BigDecimal.ONE);
        draft.setTier(PriceCodes.TIER_NORMAL);draft.setStatus(PriceCodes.WATCH_ACTIVE);
        draft.setNow(now);draft.setNextCheckAt(Timestamp.from(Instant.now().minusSeconds(3600)));
        return store.ensureWatch(draft);
    }
}
