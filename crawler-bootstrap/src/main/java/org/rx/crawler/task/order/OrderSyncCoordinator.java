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
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
        AppConfig.OrderSyncConfig syncConfig = appConfig.getCustom().getOrderSync();
        ZoneId zone = resolveZoneId(syncConfig);
        Instant now = Instant.now();
        for (AppConfig.OrderSourceConfig source : sources) {
            if (source.getId() == null || source.getId().isBlank()) {
                continue;
            }
            String allowed = source.getAllowedCallers() != null && !source.getAllowedCallers().isEmpty()
                    ? String.join(",", source.getAllowedCallers()) : "*";
            orderStore.upsertSyncStateRegistration(source.getId(), source.getPlatform(), source.getProfileName(),
                    source.getAccountName(), allowed);
            OrderSyncState state = orderStore.getSyncState(source.getId());
            if (state != null && !isSyncAllowed(now, zone, syncConfig, source)) {
                if (state.getNextSyncAt() == null || !isSyncAllowed(state.getNextSyncAt().toInstant(), zone, syncConfig, source)
                        || state.getNextSyncAt().toInstant().isBefore(now)) {
                    Instant resumeAt = calculateQuietHoursEnd(now, zone, syncConfig, source);
                    orderStore.updateNextSyncAt(source.getId(), Timestamp.from(resumeAt));
                }
            }
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
                    long failureDelaySeconds = java.util.concurrent.ThreadLocalRandom.current().nextLong(180L, 420L);
                    Instant nextRetry = now.plusSeconds(failureDelaySeconds);
                    ZoneId zone = resolveZoneId(syncConfig);
                    if (!isSyncAllowed(nextRetry, zone, syncConfig, src)) {
                        nextRetry = calculateQuietHoursEnd(nextRetry, zone, syncConfig, src);
                    }
                    log.warn("Order sync task failed taskId={}, sourceId={}, error={}, retry at {}",
                            task.getTaskId(), src.getId(), task.getErrorMessage(), nextRetry);
                    orderStore.updateSyncStateFailure(src.getId(), task.getErrorMessage(),
                            Timestamp.from(nextRetry));
                    return;
                }
            }
        }

        ZoneId zone = resolveZoneId(syncConfig);
        if (!isSyncAllowed(now, zone, syncConfig, src)) {
            Instant resumeAt = calculateQuietHoursEnd(now, zone, syncConfig, src);
            if (state.getNextSyncAt() == null || !isSyncAllowed(state.getNextSyncAt().toInstant(), zone, syncConfig, src)
                    || state.getNextSyncAt().toInstant().isBefore(now)) {
                orderStore.updateNextSyncAt(src.getId(), Timestamp.from(resumeAt));
            }
            log.debug("Skip order sync during quiet hours for sourceId={}, next sync at {}", src.getId(), resumeAt);
            return;
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

            // Schedule next run with randomized delay within range
            AppConfig.OrderSourceConfig sourceConfig = findSourceConfig(sourceId);
            long delaySeconds = calculateNextSyncDelaySeconds(sourceConfig, syncConfig);
            Instant nextCandidate = Instant.now().plusSeconds(delaySeconds);
            ZoneId zone = resolveZoneId(syncConfig);
            if (!isSyncAllowed(nextCandidate, zone, syncConfig, sourceConfig)) {
                nextCandidate = calculateQuietHoursEnd(nextCandidate, zone, syncConfig, sourceConfig);
                log.info("Next order sync for sourceId={} falls into quiet hours, rescheduled to {}", sourceId, nextCandidate);
            } else {
                log.info("Scheduled next order sync for sourceId={} in {}s (~{} min, randomized range)",
                        sourceId, delaySeconds, String.format("%.1f", delaySeconds / 60.0));
            }
            orderStore.updateNextSyncAt(sourceId, Timestamp.from(nextCandidate));

            deliveryService.trigger(Instant.now());
        } catch (Exception e) {
            log.warn("onTaskFinished order ingest error for taskId={}: {}", taskId, e.getMessage(), e);
        }
    }

    public static long calculateNextSyncDelaySeconds(AppConfig.OrderSourceConfig source, AppConfig.OrderSyncConfig syncConfig) {
        int minMinutes;
        int maxMinutes;

        if (source != null && source.getMinIntervalMinutes() != null && source.getMaxIntervalMinutes() != null
                && source.getMaxIntervalMinutes() >= source.getMinIntervalMinutes() && source.getMinIntervalMinutes() > 0) {
            minMinutes = source.getMinIntervalMinutes();
            maxMinutes = source.getMaxIntervalMinutes();
        } else if (syncConfig != null && syncConfig.getMinIntervalMinutes() > 0
                && syncConfig.getMaxIntervalMinutes() >= syncConfig.getMinIntervalMinutes()) {
            minMinutes = syncConfig.getMinIntervalMinutes();
            maxMinutes = syncConfig.getMaxIntervalMinutes();
        } else {
            int baseMinutes = (source != null && source.getIntervalMinutes() > 0)
                    ? source.getIntervalMinutes()
                    : ((syncConfig != null && syncConfig.getDefaultIntervalMinutes() > 0) ? syncConfig.getDefaultIntervalMinutes() : 10);
            minMinutes = Math.max(1, (int) Math.round(baseMinutes * 0.8));
            maxMinutes = Math.max(minMinutes + 1, (int) Math.round(baseMinutes * 1.3));
        }

        long minSeconds = minMinutes * 60L;
        long maxSeconds = maxMinutes * 60L;
        if (maxSeconds <= minSeconds) {
            return minSeconds;
        }
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(minSeconds, maxSeconds + 1);
    }

    private AppConfig.OrderSourceConfig findSourceConfig(String sourceId) {
        if (sourceId == null) {
            return null;
        }
        for (AppConfig.OrderSourceConfig s : resolveSources()) {
            if (sourceId.equals(s.getId())) {
                return s;
            }
        }
        return null;
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

    public static ZoneId resolveZoneId(AppConfig.OrderSyncConfig config) {
        if (config != null && config.getTimezone() != null && !config.getTimezone().isBlank()) {
            try {
                return ZoneId.of(config.getTimezone().trim());
            } catch (Exception ignored) {
            }
        }
        return ZoneId.of("Asia/Shanghai");
    }

    public static boolean isInQuietHours(Instant instant, ZoneId zone, int quietStart, int quietEnd) {
        if (quietStart == quietEnd) {
            return false;
        }
        int hour = instant.atZone(zone).getHour();
        if (quietStart < quietEnd) {
            return hour >= quietStart && hour < quietEnd;
        } else {
            return hour >= quietStart || hour < quietEnd;
        }
    }

    public static boolean isSyncAllowed(Instant instant, ZoneId zone,
                                       AppConfig.OrderSyncConfig config,
                                       AppConfig.OrderSourceConfig source) {
        if (config == null || !config.isEnabled()) {
            return false;
        }
        if (source != null && !source.isEnabled()) {
            return false;
        }
        if (!config.isQuietHoursEnabled()) {
            return true;
        }

        int quietStart = (source != null && source.getQuietHoursStart() != null)
                ? source.getQuietHoursStart() : config.getQuietHoursStart();
        int quietEnd = (source != null && source.getQuietHoursEnd() != null)
                ? source.getQuietHoursEnd() : config.getQuietHoursEnd();

        if (isInQuietHours(instant, zone, quietStart, quietEnd)) {
            return false;
        }

        Integer syncStart = (source != null && source.getSyncStartHour() != null)
                ? source.getSyncStartHour() : config.getSyncStartHour();
        Integer syncEnd = (source != null && source.getSyncEndHour() != null)
                ? source.getSyncEndHour() : config.getSyncEndHour();

        if (syncStart != null && syncEnd != null && !syncStart.equals(syncEnd)) {
            int hour = instant.atZone(zone).getHour();
            if (syncStart < syncEnd) {
                if (hour < syncStart || hour >= syncEnd) {
                    return false;
                }
            } else {
                if (hour < syncStart && hour >= syncEnd) {
                    return false;
                }
            }
        }
        return true;
    }

    public static Instant calculateQuietHoursEnd(Instant instant, ZoneId zone,
                                                AppConfig.OrderSyncConfig config,
                                                AppConfig.OrderSourceConfig source) {
        int quietStart = (source != null && source.getQuietHoursStart() != null)
                ? source.getQuietHoursStart() : (config != null ? config.getQuietHoursStart() : 0);
        int quietEnd = (source != null && source.getQuietHoursEnd() != null)
                ? source.getQuietHoursEnd() : (config != null ? config.getQuietHoursEnd() : 8);

        if (quietStart == quietEnd) {
            return instant;
        }

        ZonedDateTime zdt = instant.atZone(zone);
        int currentHour = zdt.getHour();

        ZonedDateTime resumeZdt;
        if (quietStart < quietEnd) {
            if (currentHour < quietEnd) {
                resumeZdt = zdt.toLocalDate().atTime(quietEnd, 0).atZone(zone);
            } else {
                resumeZdt = zdt.toLocalDate().plusDays(1).atTime(quietEnd, 0).atZone(zone);
            }
        } else {
            if (currentHour >= quietStart) {
                resumeZdt = zdt.toLocalDate().plusDays(1).atTime(quietEnd, 0).atZone(zone);
            } else {
                resumeZdt = zdt.toLocalDate().atTime(quietEnd, 0).atZone(zone);
            }
        }

        long jitterSeconds = java.util.concurrent.ThreadLocalRandom.current().nextLong(60L, 300L);
        return resumeZdt.toInstant().plusSeconds(jitterSeconds);
    }
}
