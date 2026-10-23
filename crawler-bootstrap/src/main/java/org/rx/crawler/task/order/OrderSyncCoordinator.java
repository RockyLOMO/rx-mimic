package org.rx.crawler.task.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.CustomCrawlQueueService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.CustomTaskQueueStatus;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.crawler.task.jd.JdTaskQueryResult;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersResult;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class OrderSyncCoordinator {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AppConfig appConfig;
    private final OrderStore orderStore;
    private final OrderDeliveryService deliveryService;
    private final CustomCrawlQueueService queueService;
    private final ObjectMapper mapper;

    @Autowired
    public OrderSyncCoordinator(AppConfig appConfig,
                                OrderStore orderStore,
                                OrderDeliveryService deliveryService,
                                @Lazy CustomCrawlQueueService queueService,
                                @Autowired(required = false) ObjectMapper mapper) {
        this.appConfig = appConfig;
        this.orderStore = orderStore;
        this.deliveryService = deliveryService;
        this.queueService = queueService;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        try {
            ensureSourceRegistrations();
            recoverPendingIngests();
        } catch (Exception e) {
            log.warn("OrderSyncCoordinator init error: {}", e.getMessage(), e);
        }
    }

    public void ensureSourceRegistrations() {
        List<AppConfig.OrderSourceConfig> sources = resolveSources();
        for (AppConfig.OrderSourceConfig source : sources) {
            if (source.getId() == null || source.getId().isBlank()) {
                continue;
            }
            String allowed = source.getAllowedCallers() != null && !source.getAllowedCallers().isEmpty()
                    ? String.join(",", source.getAllowedCallers()) : "*";
            orderStore.upsertSyncStateRegistration(source.getId(), source.getPlatform(), source.getProfileName(),
                    source.getAccountName(), allowed);
        }
    }

    public List<AppConfig.OrderSourceConfig> resolveSources() {
        AppConfig.OrderSyncConfig config = appConfig.getCustom().getOrderSync();
        List<AppConfig.OrderSourceConfig> list = new ArrayList<>();
        if (config != null && config.getSources() != null && !config.getSources().isEmpty()) {
            list.addAll(config.getSources());
        } else {
            // Default sources if none configured
            AppConfig.OrderSourceConfig jd = new AppConfig.OrderSourceConfig();
            jd.setId("jd-main");
            jd.setPlatform(OrderCodes.PLATFORM_JD);
            jd.setProfileName(appConfig.getCustom().getJdUnion().getProfileName() != null
                    ? appConfig.getCustom().getJdUnion().getProfileName() : "common");
            jd.setAccountName("jd-default");
            list.add(jd);

            AppConfig.OrderSourceConfig tb = new AppConfig.OrderSourceConfig();
            tb.setId("tb-main");
            tb.setPlatform(OrderCodes.PLATFORM_TB);
            tb.setProfileName(appConfig.getCustom().getTbPromotion().getProfileName() != null
                    ? appConfig.getCustom().getTbPromotion().getProfileName() : "common");
            tb.setAccountName("tb-default");
            list.add(tb);
        }
        return list;
    }

    @Scheduled(fixedDelay = 30000)
    public void scanAndSchedule() {
        AppConfig.OrderSyncConfig syncConfig = appConfig.getCustom().getOrderSync();
        if (syncConfig == null || !syncConfig.isEnabled()) {
            return;
        }

        List<AppConfig.OrderSourceConfig> sources = resolveSources();
        Instant now = Instant.now();

        for (AppConfig.OrderSourceConfig src : sources) {
            if (!src.isEnabled()) {
                continue;
            }
            try {
                processSource(src, now, syncConfig);
            } catch (Exception e) {
                log.warn("Process order sync source failed for sourceId={}: {}", src.getId(), e.getMessage());
            }
        }
    }

    private void processSource(AppConfig.OrderSourceConfig src, Instant now, AppConfig.OrderSyncConfig syncConfig) {
        OrderSyncState state = orderStore.getSyncState(src.getId());
        if (state == null) {
            ensureSourceRegistrations();
            state = orderStore.getSyncState(src.getId());
            if (state == null) {
                return;
            }
        }

        if (state.getCurrentTaskId() != null) {
            JdTaskQueryResult task = queueService.query(state.getCurrentTaskId());
            if (task != null) {
                if ("PENDING".equals(task.getStatus()) || "RUNNING".equals(task.getStatus())) {
                    // Task is currently active, do not enqueue duplicate
                    return;
                }
                if ("SUCCESS".equals(task.getStatus())) {
                    // Successfully finished, trigger ingest if needed
                    triggerIngestForTask(state.getCurrentTaskId(), src, state, task);
                    return;
                } else {
                    // Failed or timed out
                    log.warn("Order sync task failed taskId={}, sourceId={}, error={}",
                            task.getTaskId(), src.getId(), task.getErrorMessage());
                    orderStore.updateSyncStateFailure(src.getId(), task.getErrorMessage(),
                            Timestamp.from(now.plusSeconds(300)));
                    return;
                }
            }
        }

        // Check if due for next sync
        if (state.getNextSyncAt() != null && now.isBefore(state.getNextSyncAt().toInstant())) {
            return;
        }

        // Schedule next sync run
        int backfillDays = !state.isInitialized()
                ? (src.getInitialBackfillDays() > 0 ? src.getInitialBackfillDays() : syncConfig.getInitialBackfillDays())
                : (src.getRegularOverlapDays() > 0 ? src.getRegularOverlapDays() : syncConfig.getRegularOverlapDays());

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(backfillDays);

        String startTimeStr = startDate.format(DATE_FORMATTER);
        String endTimeStr = endDate.format(DATE_FORMATTER);

        String action;
        Object request;

        if (OrderCodes.PLATFORM_JD.equalsIgnoreCase(src.getPlatform())) {
            action = "getPromotionOrders";
            JdUnionPromotionOrdersRequest req = new JdUnionPromotionOrdersRequest();
            req.setStartTime(startTimeStr);
            req.setEndTime(endTimeStr);
            req.setProfileName(src.getProfileName());
            req.setSourceId(src.getId());
            request = req;
        } else {
            action = "getTbPromotionOrders";
            TbPromotionOrdersRequest req = new TbPromotionOrdersRequest();
            req.setStartTime(startTimeStr);
            req.setEndTime(endTimeStr);
            req.setProfileName(src.getProfileName());
            req.setSourceId(src.getId());
            request = req;
        }

        long taskId = queueService.enqueueBackground("orderSync", action, request, null, CustomCrawlQueueService.BACKGROUND_PRIORITY);
        orderStore.updateSyncStateTask(src.getId(), taskId, "SYNCING");
        log.info("Scheduled background order sync taskId={}, sourceId={}, platform={}, range=[{} - {}]",
                taskId, src.getId(), src.getPlatform(), startTimeStr, endTimeStr);
    }

    private void triggerIngestForTask(long taskId, AppConfig.OrderSourceConfig src,
                                      OrderSyncState state, JdTaskQueryResult task) {
        try {
            String resultJson = mapper.writeValueAsString(task.getResult());
            String requestJson = null; // Can be read if needed
            onTaskFinished(task.getAction(), "orderSync", taskId, requestJson, resultJson, "SUCCESS");
        } catch (Exception e) {
            log.warn("Failed to trigger ingest for completed task {}: {}", taskId, e.getMessage());
        }
    }

    public void onTaskFinished(String action, String ownerId, long taskId,
                               String requestJson, String resultJson, String businessStatus) {
        if (!"getPromotionOrders".equals(action) && !"getTbPromotionOrders".equals(action)) {
            return;
        }
        if (!"SUCCESS".equals(businessStatus)) {
            return;
        }

        try {
            boolean isJd = "getPromotionOrders".equals(action);
            String sourceId = null;
            String startTime = null;
            String endTime = null;
            boolean complete = false;
            List<PromotionOrderItem> orders = null;

            if (isJd) {
                JdUnionPromotionOrdersResult result = mapper.readValue(resultJson, JdUnionPromotionOrdersResult.class);
                sourceId = result.getSourceId();
                startTime = result.getStartTime();
                endTime = result.getEndTime();
                complete = result.getComplete() != null ? result.getComplete() : (result.getStatus() == CustomCrawlStatus.SUCCESS);
                orders = result.getOrders();
            } else {
                TbPromotionOrdersResult result = mapper.readValue(resultJson, TbPromotionOrdersResult.class);
                sourceId = result.getSourceId();
                startTime = result.getStartTime();
                endTime = result.getEndTime();
                complete = result.getComplete() != null ? result.getComplete() : (result.getStatus() == CustomCrawlStatus.SUCCESS);
                orders = result.getOrders();
            }

            if (sourceId == null || sourceId.isBlank()) {
                // If sourceId wasn't passed, find by platform
                sourceId = isJd ? "jd-main" : "tb-main";
            }

            OrderSyncState state = orderStore.getSyncState(sourceId);
            boolean isInitialBackfill = (state == null || !state.isInitialized());
            AppConfig.OrderSyncConfig syncConfig = appConfig.getCustom().getOrderSync();
            boolean bootstrapNotify = syncConfig != null ? syncConfig.isBootstrapNotify() : true;
            List<AppConfig.OrderSubscriberConfig> subscribers = (syncConfig != null && syncConfig.getWebhook() != null)
                    ? syncConfig.getWebhook().getSubscribers() : null;

            OrderStore.IngestBatchResult ingestResult = orderStore.ingestOrders(
                    sourceId,
                    isJd ? OrderCodes.PLATFORM_JD : OrderCodes.PLATFORM_TB,
                    taskId,
                    startTime,
                    endTime,
                    complete,
                    orders,
                    bootstrapNotify,
                    isInitialBackfill,
                    subscribers
            );

            log.info("Order batch ingested: sourceId={}, newOrders={}, updatedOrders={}, items={}, complete={}",
                    sourceId, ingestResult.newOrdersCount, ingestResult.updatedOrdersCount, ingestResult.itemsCount, complete);

            // Schedule next run
            int intervalMinutes = (syncConfig != null && syncConfig.getDefaultIntervalMinutes() > 0)
                    ? syncConfig.getDefaultIntervalMinutes() : 10;
            orderStore.updateNextSyncAt(sourceId, Timestamp.from(Instant.now().plusSeconds(intervalMinutes * 60L)));

            deliveryService.trigger(Instant.now());
        } catch (Exception e) {
            log.warn("onTaskFinished order ingest error for taskId={}: {}", taskId, e.getMessage(), e);
        }
    }

    public void recoverPendingIngests() {
        List<OrderSyncState> states = orderStore.listSyncStates();
        for (OrderSyncState state : states) {
            if (state.getPendingIngestTaskId() != null) {
                JdTaskQueryResult task = queueService.query(state.getPendingIngestTaskId());
                if (task != null && "SUCCESS".equals(task.getStatus())) {
                    log.info("Recovering pending order ingest for sourceId={}, taskId={}",
                            state.getSourceId(), task.getTaskId());
                    try {
                        String resultJson = mapper.writeValueAsString(task.getResult());
                        onTaskFinished(task.getAction(), "orderSync", task.getTaskId(), null, resultJson, "SUCCESS");
                    } catch (Exception e) {
                        log.warn("Recovery failed for taskId={}: {}", task.getTaskId(), e.getMessage());
                    }
                }
            }
        }
    }
}
