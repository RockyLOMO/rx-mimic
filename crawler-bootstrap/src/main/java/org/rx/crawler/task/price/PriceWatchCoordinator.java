package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.CustomCrawlQueueService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class PriceWatchCoordinator {
    private final AppConfig appConfig;
    private final ObjectMapper mapper;
    private final PriceWatchStore store;
    private final UnionPriceProbe probe;
    private final PromotionNavigator navigator;
    private final PriceRiskGate riskGate;
    private final PriceIngestService ingest;
    private final PriceAlertService alerts;
    private CustomCrawlQueueService queue;
    private PriceDeliveryService deliveryService;
    private final AtomicBoolean invalidLogged = new AtomicBoolean();

    public PriceWatchCoordinator(AppConfig appConfig, ObjectMapper mapper, PriceWatchStore store,
            UnionPriceProbe probe, PromotionNavigator navigator, PriceAlertSender sender, PriceRiskGate riskGate) {
        this(appConfig, mapper, store, probe, navigator, sender, riskGate, null);
    }

    @Autowired
    public PriceWatchCoordinator(AppConfig appConfig, ObjectMapper mapper, PriceWatchStore store,
            UnionPriceProbe probe, PromotionNavigator navigator,
            @Autowired(required = false) PriceAlertSender sender,
            PriceRiskGate riskGate,
            @Autowired(required = false) PriceDeliveryService deliveryService) {
        this.appConfig = appConfig;
        this.mapper = mapper;
        this.store = store;
        this.probe = probe;
        this.navigator = navigator;
        this.riskGate = riskGate;
        this.deliveryService = deliveryService;
        this.alerts = new PriceAlertService(store, appConfig, sender, deliveryService, mapper);
        this.ingest = new PriceIngestService(store, appConfig, mapper, alerts);
    }

    @Autowired(required = false)
    public void setDeliveryService(PriceDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
        if (this.alerts != null) {
            this.alerts.setDeliveryService(deliveryService);
        }
    }

    @Autowired(required = false)
    public void setQueue(@Lazy CustomCrawlQueueService queue) {
        this.queue = queue;
    }

    public void onFinished(String action, String owner, long taskId, String requestJson, String resultJson) {
        if (!enabled() || !unionAction(action)) {
            return;
        }
        ingest.accept(action, owner, taskId, requestJson, resultJson);
    }

    public void tick() {
        AppConfig.PriceWatchConfig config = config();
        if (!config.isEnabled()) {
            return;
        }
        PriceWatchClock clock = clock(config);
        Instant now = Instant.now();
        if (!clock.valid()) {
            if (invalidLogged.compareAndSet(false, true)) {
                log.error("Price watch schedule stayed closed because time config is invalid: {}", clock.error());
            }
            return;
        }
        invalidLogged.set(false);
        reconcile(now, clock);
        alerts.flush(now);
        if (deliveryService != null) {
            deliveryService.tick(now);
        }
        if (!clock.open(now) || queue == null || queue.hasWaitingUser() || queue.hasForegroundWork()) {
            return;
        }
        int room = Math.max(0, config.getBackgroundQueuedLimit() - queue.countPendingPriceTasks());
        if (room == 0) {
            return;
        }
        for (PriceWatchStore.WatchRow watch : store.due(Timestamp.from(now), room)) {
            if (riskGate.stopped(watch.getPlatform(), now)) {
                defer(watch, "RISK_STOP", now, clock);
                continue;
            }
            if (store.visitCount(watch.getPlatform(), clock.businessDate(now).toString()) >= limit(config, watch.getPlatform())) {
                defer(watch, "DAILY_VISIT_LIMIT", now, clock);
                continue;
            }
            enqueue(watch, needsResolve(config, watch), now);
        }
    }

    public boolean allowKeepAlive() {
        AppConfig.PriceWatchConfig config = config();
        if (!config.isEnabled()) {
            return true;
        }
        PriceWatchClock clock = clock(config);
        return clock.valid() && clock.open(Instant.now());
    }

    public boolean allowKeepAlive(String platformKey) {
        if (!allowKeepAlive()) {
            return false;
        }
        return !riskGate.stopped(PriceCodes.platform(platformKey), Instant.now());
    }

    public boolean allowBackground(String requestJson) {
        return "OK".equals(deferReason(requestJson));
    }

    public String deferReason(String requestJson) {
        AppConfig.PriceWatchConfig config = config();
        PriceWatchClock clock = clock(config);
        Instant now = Instant.now();
        if (!config.isEnabled() || !clock.valid()) {
            return "CONFIG";
        }
        if (!clock.open(now)) {
            return "WINDOW";
        }
        PriceWatchStore.WatchRow watch = watchOf(requestJson);
        if (watch == null || watch.getStatus() != PriceCodes.WATCH_ACTIVE) {
            return "PAUSED";
        }
        if (riskGate.stopped(watch.getPlatform(), now)) {
            return "RISK_STOP";
        }
        if (store.visitCount(watch.getPlatform(), clock.businessDate(now).toString()) >= limit(config, watch.getPlatform())) {
            return "DAILY_VISIT_LIMIT";
        }
        return "OK";
    }

    public void onDeferred(String requestJson) {
        PriceWatchStore.WatchRow watch = watchOf(requestJson);
        if (watch == null) {
            return;
        }
        PriceWatchClock clock = clock(config());
        if (!clock.valid()) {
            store.clearTask(watch.getId());
            return;
        }
        defer(watch, deferReason(requestJson), Instant.now(), clock);
    }

    public PriceTaskResult refresh(String requestJson, long taskId) {
        return runPriced(requestJson, taskId, false);
    }

    public PriceTaskResult resolve(String requestJson, long taskId) {
        return runPriced(requestJson, taskId, true);
    }

    public Command follow(String owner, String platformKey, String unionProductId, BigDecimal targetPrice, Integer tier) {
        int platform = PriceCodes.platform(platformKey);
        if (platform == 0 || unionProductId == null || unionProductId.isBlank()) {
            return Command.fail("INVALID_REQUEST", "platform and unionProductId are required");
        }
        PriceWatchStore.AliasHit alias = store.findAliasAny(platform, unionProductId.trim());
        if (alias == null) {
            return Command.fail("PRODUCT_NOT_FOUND", "PRODUCT_NOT_FOUND");
        }
        AppConfig.PriceWatchConfig config = config();
        Instant now = Instant.now();
        PriceWatchClock clock = clock(config);
        int status = store.countActive() >= Math.max(1, config.getActiveWatchLimit())
                ? PriceCodes.WATCH_PENDING : PriceCodes.WATCH_ACTIVE;
        PriceWatchStore.WatchDraft draft = new PriceWatchStore.WatchDraft();
        draft.setOwnerId(owner);
        draft.setProductId(alias.getProductId());
        draft.setAliasId(alias.getAliasId());
        draft.setWatchKey(owner + "|" + alias.getProductId() + "|" + alias.getAliasId());
        draft.setProfileName(alias.getAccountContext());
        draft.setTargetPrice(targetPrice);
        draft.setDropAmount(config.getDropAmount());
        draft.setDropPercent(config.getDropPercent());
        draft.setTier(tier == null ? PriceCodes.TIER_NORMAL : tier);
        draft.setStatus(status);
        draft.setPauseReason(status == PriceCodes.WATCH_PENDING ? "ACTIVE_LIMIT" : null);
        draft.setNow(Timestamp.from(now));
        draft.setNextCheckAt(Timestamp.from(clock.valid() && clock.open(now) ? now.plusSeconds(120) : now.plusSeconds(3600)));
        PriceWatchStore.WatchRow watch = store.ensureWatch(draft);
        if (targetPrice != null || tier != null) {
            store.setTarget(watch.getId(), owner, targetPrice == null ? watch.getTargetPrice() : targetPrice,
                    tier == null ? watch.getTier() : tier);
            watch = store.watch(watch.getId());
        }
        if (watch.getBaselineSnapshotId() == null) {
            List<PriceWatchStore.SnapshotRow> rows = store.snapshots(watch.getProductId(), 20);
            for (PriceWatchStore.SnapshotRow row : rows) {
                if (row.isComparable()) {
                    store.setBaseline(watch.getId(), row.getId());
                    break;
                }
            }
        }
        Command command = Command.ok(watch.getId());
        command.message = watch.getStatus() == PriceCodes.WATCH_PENDING ? "ACTIVE_LIMIT" : "WATCHING";
        return command;
    }

    public Command pause(String owner, long watchId) {
        if (!store.setStatus(watchId, owner, PriceCodes.WATCH_PAUSED, "USER_PAUSE", null)) {
            return Command.fail("WATCH_NOT_FOUND", "WATCH_NOT_FOUND");
        }
        store.clearTask(watchId);
        return Command.ok(watchId);
    }

    public Command resume(String owner, long watchId) {
        PriceWatchStore.WatchRow watch = owned(owner, watchId);
        if (watch == null) {
            return Command.fail("WATCH_NOT_FOUND", "WATCH_NOT_FOUND");
        }
        int status = store.countActive() >= Math.max(1, config().getActiveWatchLimit()) && watch.getStatus() != PriceCodes.WATCH_ACTIVE
                ? PriceCodes.WATCH_PENDING : PriceCodes.WATCH_ACTIVE;
        Instant now = Instant.now();
        PriceWatchClock clock = clock(config());
        Timestamp next = Timestamp.from(clock.valid() && clock.open(now) ? now.plusSeconds(60) : now.plusSeconds(3600));
        store.setStatus(watchId, owner, status, status == PriceCodes.WATCH_PENDING ? "ACTIVE_LIMIT" : null, next);
        return Command.ok(watchId);
    }

    public Command manualRefresh(String owner, long watchId) {
        PriceWatchStore.WatchRow watch = owned(owner, watchId);
        if (watch == null) {
            return Command.fail("WATCH_NOT_FOUND", "WATCH_NOT_FOUND");
        }
        AppConfig.PriceWatchConfig config = config();
        PriceWatchClock clock = clock(config);
        Instant now = Instant.now();
        if (!clock.valid() || !clock.open(now)) {
            return Command.fail("WINDOW_CLOSED", "WINDOW_CLOSED");
        }
        if (riskGate.stopped(watch.getPlatform(), now)) {
            return Command.fail("RISK_STOP", "login or slider failures reached the daily limit");
        }
        if (store.visitCount(watch.getPlatform(), clock.businessDate(now).toString()) >= limit(config, watch.getPlatform())) {
            return Command.fail("DAILY_VISIT_LIMIT", limitMessage(watch.getPlatform(), config));
        }
        if (watch.getLastSuccessAt() != null && now.isBefore(watch.getLastSuccessAt().toInstant().plusSeconds(600))) {
            Command cached = Command.ok(watch.getId());
            cached.message = "CACHED";
            cached.cached = true;
            return cached;
        }
        if (queue == null) {
            return Command.fail("QUEUE_UNAVAILABLE", "QUEUE_UNAVAILABLE");
        }
        long taskId = enqueue(watch, false, now, CustomCrawlQueueService.FOREGROUND_PRIORITY);
        Command command = Command.ok(watch.getId());
        command.taskId = taskId;
        command.message = "QUEUED";
        return command;
    }

    public List<PriceWatchStore.WatchRow> list(String owner) {
        return store.listByOwner(owner);
    }

    public PriceWatchStore.WatchRow owned(String owner, long watchId) {
        PriceWatchStore.WatchRow watch = store.watch(watchId);
        if (watch == null || owner == null || !owner.equals(watch.getOwnerId())) {
            return null;
        }
        return watch;
    }

    public List<PriceWatchStore.SnapshotRow> snapshots(String owner, long watchId) {
        PriceWatchStore.WatchRow watch = owned(owner, watchId);
        if (watch == null) {
            return null;
        }
        return store.snapshots(watch.getProductId(), 100);
    }

    public Map<String, Object> visits(Instant now) {
        AppConfig.PriceWatchConfig config = config();
        PriceWatchClock clock = clock(config);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("businessDate", clock.valid() ? clock.businessDate(now).toString() : "");
        body.put("windowOpen", clock.valid() && clock.open(now));
        Map<String, Object> platforms = new LinkedHashMap<String, Object>();
        List<String> keys = new ArrayList<String>();
        keys.add("jd");
        keys.add("tb");
        if (config.getDailyMaxProductVisits() != null) {
            for (String key : config.getDailyMaxProductVisits().keySet()) {
                if (key != null && !keys.contains(key.toLowerCase())) {
                    keys.add(key.toLowerCase());
                }
            }
        }
        for (String key : keys) {
            int platform = PriceCodes.platform(key);
            if (platform == 0) {
                continue;
            }
            int cap = limit(config, platform);
            int used = clock.valid() ? store.visitCount(platform, clock.businessDate(now).toString()) : 0;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            int risks = riskGate.count(platform, now);
            item.put("used", used);
            item.put("limit", cap);
            item.put("remaining", Math.max(0, cap - used));
            item.put("riskFailures", risks);
            item.put("riskLimit", riskGate.limit());
            item.put("stopped", cap <= 0 || used >= cap || riskGate.stopped(platform, now));
            platforms.put(key, item);
        }
        body.put("platforms", platforms);
        return body;
    }

    private PriceTaskResult runPriced(String requestJson, long taskId, boolean resolve) {
        PriceTaskResult result = new PriceTaskResult();
        PriceWatchStore.WatchRow watch = watchOf(requestJson);
        if (watch == null) {
            result.setStatus(CustomCrawlStatus.FAILED);
            result.setMessage("WATCH_NOT_FOUND");
            return result;
        }
        result.setWatchId(watch.getId());
        if (watch.getCurrentTaskId() != null && watch.getCurrentTaskId() != taskId) {
            result.setMessage("SUPERSEDED");
            return result;
        }
        Instant now = Instant.now();
        AppConfig.PriceWatchConfig config = config();
        PriceWatchClock clock = clock(config);
        if (!clock.valid() || !clock.open(now)) {
            defer(watch, "WINDOW", now, clock);
            result.setMessage("WINDOW_CLOSED");
            return result;
        }
        if (riskGate.stopped(watch.getPlatform(), now)) {
            defer(watch, "RISK_STOP", now, clock);
            result.setMessage("RISK_STOP");
            return result;
        }
        String day = clock.businessDate(now).toString();
        if (store.reserveVisit(watch.getPlatform(), day, limit(config, watch.getPlatform())) < 0) {
            defer(watch, "DAILY_VISIT_LIMIT", now, clock);
            result.setMessage("DAILY_VISIT_LIMIT");
            return result;
        }
        store.noteAttempt(watch.getId(), Timestamp.from(now));
        store.schedule(watch.getId(), Timestamp.from(nextSlot(config, clock, watch, now, false)), null);
        try {
            if (resolve) {
                return finishResolve(config, watch, taskId, now, result);
            }
            return finishRefresh(watch, taskId, now, result);
        } catch (RuntimeException e) {
            store.clearTask(watch.getId(), taskId);
            throw e;
        }
    }

    private PriceTaskResult finishRefresh(PriceWatchStore.WatchRow watch, long taskId, Instant now, PriceTaskResult result) {
        UnionPriceProbe.Probe probed = probe.probe(watch.getPlatform(), watch.getUnionProductId(), watch.getProfileName());
        if (probed.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED) {
            result.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
            result.setMessage("LOGIN_REQUIRED");
            return result;
        }
        Timestamp stamp = Timestamp.from(now);
        int kind = PriceRules.kind(probed.getPriceField());
        BigDecimal amount = probed.isExactMatch() ? PriceRules.amount(probed.getRawPrice()) : null;
        boolean comparable = probed.isExactMatch() && PriceRules.comparable(kind, amount);
        PriceWatchStore.SnapshotWrite write = new PriceWatchStore.SnapshotWrite();
        write.setProductId(watch.getProductId());
        write.setAliasId(watch.getAliasId());
        write.setRawPrice(probed.getRawPrice());
        write.setPriceAmount(amount);
        write.setSource(PriceCodes.SOURCE_UNION);
        write.setSourceField(probed.getPriceField());
        write.setPriceKind(kind);
        write.setComparableKey(PriceRules.comparableKey(watch.getPlatform(), PriceCodes.SOURCE_UNION, kind,
                probed.getPriceField(), watch.getPlatformProductId(), ""));
        write.setComparable(comparable);
        write.setObservedAt(stamp);
        write.setTaskId(taskId);
        write.setItemIndex(0);
        write.setFetchStatus(probed.getStatus() != CustomCrawlStatus.SUCCESS ? PriceCodes.FETCH_RISK
                : probed.isExactMatch() ? PriceCodes.FETCH_OK : PriceCodes.FETCH_MISS);
        write.setQualityReason(probed.isExactMatch() ? null : probed.getMessage());
        write.setCreatedAt(stamp);
        if (probed.isExactMatch()) {
            store.upsertAlias(watch.getPlatform(), watch.getAccountContext(), watch.getUnionProductId(),
                    probed.getTitle(), probed.getShop(), probed.getImage(), watch.getPromotionUrl(), stamp);
        }
        PriceWatchStore.SnapshotSave saved = store.saveSnapshot(write);
        if (saved.isCreated() && comparable) {
            alerts.consider(store.watch(watch.getId()), store.snapshot(saved.getId()), now);
        }
        if (probed.isExactMatch() && probed.getStatus() == CustomCrawlStatus.SUCCESS) {
            store.noteSuccess(watch.getId(), stamp);
        }
        store.clearTask(watch.getId(), taskId);
        result.setStatus(probed.getStatus() == null ? CustomCrawlStatus.FAILED : probed.getStatus());
        result.setMessage(probed.isExactMatch() ? "REFRESHED" : empty(probed.getMessage()));
        if (result.getStatus() == CustomCrawlStatus.SUCCESS && !probed.isExactMatch()) {
            result.setMessage(empty(probed.getMessage()));
        }
        return result;
    }

    private PriceTaskResult finishResolve(AppConfig.PriceWatchConfig config, PriceWatchStore.WatchRow watch,
            long taskId, Instant now, PriceTaskResult result) {
        PromotionNavigator.Nav nav = navigator.open(watch.getPlatform(), watch.getProfileName(), watch.getPromotionUrl());
        if (nav.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED) {
            result.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
            result.setMessage("LOGIN_REQUIRED");
            return result;
        }
        Timestamp stamp = Timestamp.from(now);
        if (nav.getPageKind() == ProductIdentityParser.PageKind.PRODUCT && nav.getProductId() != null && !nav.getProductId().isBlank()
                && titleAgrees(watch.getTitle(), nav.getBodyText())) {
            store.updateIdentity(watch.getAliasId(), watch.getProductId(), nav.getFinalUrl(), nav.getProductId(),
                    PriceCodes.IDENTITY_VERIFIED, "final-url", stamp);
            result.setMessage("RESOLVED");
        } else {
            store.addResolveAttempt(watch.getAliasId());
            PriceWatchStore.AliasHit alias = store.alias(watch.getAliasId());
            int attempts = alias == null ? config.getMaxIdentityAttempts() : alias.getResolveAttempts();
            int identity = attempts >= config.getMaxIdentityAttempts() ? PriceCodes.IDENTITY_MANUAL : PriceCodes.IDENTITY_PENDING;
            store.updateIdentity(watch.getAliasId(), watch.getProductId(), nav.getFinalUrl(), null, identity,
                    nav.getPageKind().name(), stamp);
            result.setMessage(identity == PriceCodes.IDENTITY_MANUAL ? "IDENTITY_MANUAL" : "UNRESOLVED");
        }
        store.clearTask(watch.getId(), taskId);
        result.setStatus(nav.getStatus() == CustomCrawlStatus.FAILED ? CustomCrawlStatus.FAILED : CustomCrawlStatus.SUCCESS);
        return result;
    }

    private boolean titleAgrees(String title, String body) {
        if (title == null || title.length() < 8 || body == null || body.length() < 80) {
            return true;
        }
        return body.contains(title.substring(0, 8));
    }

    private void reconcile(Instant now, PriceWatchClock clock) {
        if (queue == null) {
            return;
        }
        for (PriceWatchStore.WatchRow watch : store.withTask()) {
            if (watch.getCurrentTaskId() == null) {
                continue;
            }
            String status = queue.taskStatus(watch.getCurrentTaskId());
            if (status == null || terminal(status)) {
                store.clearTask(watch.getId(), watch.getCurrentTaskId());
                if (watch.getNextCheckAt() == null || !watch.getNextCheckAt().after(Timestamp.from(now))) {
                    Instant next = clock.open(now) ? now.plus(Duration.ofMinutes(30))
                            : clock.nextOpen(now).plusSeconds((watch.getId() % 45) * 60);
                    store.schedule(watch.getId(), Timestamp.from(next), "RECONCILED");
                }
            }
        }
    }

    private long enqueue(PriceWatchStore.WatchRow watch, boolean resolve, Instant now) {
        int priority = CustomCrawlQueueService.BACKGROUND_PRIORITY;
        if (watch.getLastSuccessAt() != null && now.isAfter(watch.getLastSuccessAt().toInstant().plus(Duration.ofHours(6)))) {
            priority = CustomCrawlQueueService.BACKGROUND_AGING_PRIORITY;
        }
        return enqueue(watch, resolve, now, priority);
    }

    private long enqueue(PriceWatchStore.WatchRow watch, boolean resolve, Instant now, int priority) {
        long plan = watch.getPlanSeq() + 1;
        String action = resolve ? "resolveProductIdentity" : "priceRefresh";
        ObjectNode body = mapper.createObjectNode();
        body.put("watchId", watch.getId());
        body.put("planId", plan);
        body.put("platform", watch.getPlatform());
        body.put("unionProductId", watch.getUnionProductId());
        body.put("accountContext", watch.getAccountContext());
        body.put("profileName", watch.getProfileName() == null ? watch.getAccountContext() : watch.getProfileName());
        body.put("promotionUrl", watch.getPromotionUrl() == null ? "" : watch.getPromotionUrl());
        long taskId = queue.enqueueBackground("price-watch", action, body, watch.getId() + ":" + plan + ":" + action, priority);
        if (!store.attachTask(watch.getId(), plan, taskId)) {
            queue.deferTask(taskId, "SUPERSEDED");
        }
        return taskId;
    }

    private void defer(PriceWatchStore.WatchRow watch, String reason, Instant now, PriceWatchClock clock) {
        Instant next;
        long jitter = (watch.getId() % 45) * 60;
        if (!clock.valid()) {
            store.clearTask(watch.getId());
            return;
        }
        if ("DAILY_VISIT_LIMIT".equals(reason) || "RISK_STOP".equals(reason)) {
            next = clock.nextBusinessStart(now).plusSeconds(jitter);
        } else if ("WINDOW".equals(reason) || "CONFIG".equals(reason)) {
            next = clock.nextOpen(now).plusSeconds(jitter);
        } else {
            next = now.plus(Duration.ofMinutes(15));
        }
        store.clearTask(watch.getId());
        store.schedule(watch.getId(), Timestamp.from(next), reason);
    }

    private Instant nextSlot(AppConfig.PriceWatchConfig config, PriceWatchClock clock,
            PriceWatchStore.WatchRow watch, Instant now, boolean missed) {
        int hours = watch.getTier() == PriceCodes.TIER_FOCUS ? config.getFocusIntervalHours()
                : watch.getTier() == PriceCodes.TIER_COLD ? config.getColdIntervalHours() : config.getOrdinaryIntervalHours();
        if (missed) {
            hours = hours * 2;
        }
        Instant candidate = now.plus(Duration.ofHours(Math.max(1, hours)));
        if (!clock.open(candidate)) {
            return clock.nextOpen(candidate).plusSeconds((watch.getId() % 45) * 60);
        }
        return candidate;
    }

    private boolean needsResolve(AppConfig.PriceWatchConfig config, PriceWatchStore.WatchRow watch) {
        return watch.getIdentityStatus() != PriceCodes.IDENTITY_VERIFIED
                && watch.getPromotionUrl() != null && !watch.getPromotionUrl().isBlank()
                && watch.getResolveAttempts() < config.getMaxIdentityAttempts()
                && (watch.getPlatformProductId() == null || watch.getPlatformProductId().isBlank());
    }

    private PriceWatchStore.WatchRow watchOf(String requestJson) {
        try {
            JsonNode node = mapper.readTree(requestJson);
            long watchId = node.path("watchId").asLong(0);
            return watchId == 0 ? null : store.watch(watchId);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean terminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status) || "TIMED_OUT".equals(status);
    }

    private boolean unionAction(String action) {
        return "jdUnionSearchProducts".equals(action) || "tbUnionSearchProducts".equals(action)
                || "getPromotionUrl".equals(action) || "getPromotionUrls".equals(action)
                || "getTbPromotionUrl".equals(action) || "getTbPromotionUrls".equals(action);
    }

    private int limit(AppConfig.PriceWatchConfig config, int platform) {
        String key = PriceCodes.platformKey(platform);
        if (key.isEmpty() || config.getDailyMaxProductVisits() == null) {
            return 0;
        }
        for (Map.Entry<String, Integer> entry : config.getDailyMaxProductVisits().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                return Math.max(0, entry.getValue());
            }
        }
        return 0;
    }

    private String limitMessage(int platform, AppConfig.PriceWatchConfig config) {
        return PriceCodes.platformKey(platform) + " daily product visit limit " + limit(config, platform);
    }

    private boolean enabled() {
        return config().isEnabled();
    }

    private AppConfig.PriceWatchConfig config() {
        AppConfig.PriceWatchConfig config = appConfig.getCustom() == null ? null : appConfig.getCustom().getPriceWatch();
        return config == null ? new AppConfig.PriceWatchConfig() : config;
    }

    private PriceWatchClock clock(AppConfig.PriceWatchConfig config) {
        return PriceWatchClock.parse(config.getTimezone(), config.getWindowStart(), config.getWindowEnd());
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }

    @Getter
    public static class Command {
        private final boolean ok;
        private final String code;
        private String message;
        private final Long watchId;
        private Long taskId;
        private boolean cached;

        private Command(boolean ok, String code, String message, Long watchId) {
            this.ok = ok;
            this.code = code;
            this.message = message;
            this.watchId = watchId;
        }

        static Command ok(long watchId) {
            return new Command(true, "SUCCESS", "", watchId);
        }

        static Command fail(String code, String message) {
            return new Command(false, code, message, null);
        }
    }
}
