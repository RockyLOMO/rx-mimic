package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rx.crawler.config.AppConfig;

import java.sql.Timestamp;
import java.time.Instant;

/** 搜索只留快照。转链成功才建跟踪，重放同一任务不重复写。 */
public class PriceIngestService {
    private final PriceWatchStore store;
    private final AppConfig appConfig;
    private final ObjectMapper mapper;
    private final PriceAlertService alerts;

    public PriceIngestService(PriceWatchStore store, AppConfig appConfig, ObjectMapper mapper, PriceAlertService alerts) {
        this.store = store;
        this.appConfig = appConfig;
        this.mapper = mapper;
        this.alerts = alerts;
    }

    public void accept(String action, String owner, long taskId, String requestJson, String resultJson) {
        if (action == null || resultJson == null || resultJson.isBlank()) {
            return;
        }
        int platform = platform(action);
        if (platform == 0) {
            return;
        }
        try {
            JsonNode result = mapper.readTree(resultJson);
            JsonNode request = requestJson == null || requestJson.isBlank() ? null : mapper.readTree(requestJson);
            if (result.isArray()) {
                int index = 0;
                for (JsonNode item : result) {
                    if (item.has("productInfo") || item.has("promotionUrl") || item.has("keyword")) {
                        promotion(platform, owner, taskId, index, request, item);
                    }
                    index++;
                }
                return;
            }
            if (result.has("items")) {
                search(platform, taskId, result);
                return;
            }
            if (result.has("productInfo") || result.has("promotionUrl")) {
                promotion(platform, owner, taskId, 0, request, result);
            }
        } catch (Exception e) {
            throw new IllegalStateException("price ingest failed", e);
        }
    }

    private void search(int platform, long taskId, JsonNode result) {
        if (!"SUCCESS".equals(result.path("status").asText(""))) {
            return;
        }
        String account = account(result);
        Timestamp now = Timestamp.from(Instant.now());
        int index = 0;
        for (JsonNode item : result.path("items")) {
            String unionId = text(item, "skuId");
            if (unionId.isEmpty()) {
                unionId = text(item, "itemId");
            }
            if (!unionId.isEmpty()) {
                PriceWatchStore.AliasHit alias = store.upsertAlias(platform, account, unionId,
                        text(item, "productName"), text(item, "storeName"), text(item, "imageUrl"), "", now);
                saveObserved(alias, taskId, index, text(item, "price"), text(item, "priceField"), now);
            }
            index++;
        }
    }

    private void promotion(int platform, String owner, long taskId, int index, JsonNode request, JsonNode result) {
        if (!"SUCCESS".equals(result.path("status").asText(""))) {
            return;
        }
        String unionId = text(result, "keyword");
        if (unionId.isEmpty()) {
            unionId = keyword(request);
        }
        if (unionId.isEmpty()) {
            return;
        }
        JsonNode info = result.path("productInfo");
        String account = account(result);
        Timestamp now = Timestamp.from(Instant.now());
        String promotionUrl = text(result, "promotionUrl");
        PriceWatchStore.AliasHit alias = store.upsertAlias(platform, account, unionId,
                text(info, "productName"), text(info, "storeName"), text(info, "imageUrl"), promotionUrl, now);
        PriceWatchStore.SnapshotSave saved = saveObserved(alias, taskId, index, text(info, "price"), text(info, "priceField"), now);
        boolean createdRecord = store.savePromotion(ownerId(owner), alias.getProductId(), alias.getAliasId(), taskId, index,
                text(result, "adSiteName"), promotionUrl, saved.getId(), now);
        if (!createdRecord && !saved.isCreated()) {
            return;
        }
        AppConfig.PriceWatchConfig config = config();
        PriceWatchClock clock = clock(config);
        Instant instant = now.toInstant();
        int status = store.countActive() >= Math.max(1, config.getActiveWatchLimit())
                ? PriceCodes.WATCH_PENDING : PriceCodes.WATCH_ACTIVE;
        PriceWatchStore.WatchDraft draft = new PriceWatchStore.WatchDraft();
        draft.setOwnerId(ownerId(owner));
        draft.setProductId(alias.getProductId());
        draft.setAliasId(alias.getAliasId());
        draft.setWatchKey(draft.getOwnerId() + "|" + alias.getProductId() + "|" + alias.getAliasId());
        draft.setProfileName(account);
        draft.setDropAmount(config.getDropAmount());
        draft.setDropPercent(config.getDropPercent());
        draft.setTier(PriceCodes.TIER_NORMAL);
        draft.setStatus(status);
        draft.setPauseReason(status == PriceCodes.WATCH_PENDING ? "ACTIVE_LIMIT" : null);
        draft.setNow(now);
        draft.setNextCheckAt(Timestamp.from(firstCheck(clock, instant, alias.getAliasId())));
        PriceWatchStore.WatchRow watch = store.ensureWatch(draft);
        if (saved.isCreated() && watch.getStatus() == PriceCodes.WATCH_ACTIVE) {
            PriceWatchStore.SnapshotRow snapshot = store.snapshot(saved.getId());
            alerts.consider(store.watch(watch.getId()), snapshot, instant);
        }
    }

    private PriceWatchStore.SnapshotSave saveObserved(PriceWatchStore.AliasHit alias, long taskId, int index,
            String rawPrice, String field, Timestamp now) {
        int kind = PriceRules.kind(field);
        java.math.BigDecimal amount = PriceRules.amount(rawPrice);
        boolean comparable = PriceRules.comparable(kind, amount);
        PriceWatchStore.SnapshotWrite write = new PriceWatchStore.SnapshotWrite();
        write.setProductId(alias.getProductId());
        write.setAliasId(alias.getAliasId());
        write.setRawPrice(rawPrice);
        write.setPriceAmount(amount);
        write.setSource(PriceCodes.SOURCE_UNION);
        write.setSourceField(field);
        write.setPriceKind(kind);
        write.setComparableKey(PriceRules.comparableKey(alias.getPlatform(), PriceCodes.SOURCE_UNION, kind, field,
                alias.getPlatformProductId(), ""));
        write.setComparable(comparable);
        write.setObservedAt(now);
        write.setTaskId(taskId);
        write.setItemIndex(index);
        write.setFetchStatus(amount == null ? PriceCodes.FETCH_MISS : PriceCodes.FETCH_OK);
        write.setQualityReason(comparable ? null : "NOT_COMPARABLE");
        write.setCreatedAt(now);
        PriceWatchStore.SnapshotSave saved = store.saveSnapshot(write);
        if (saved.isCreated()) {
            Instant instant = now.toInstant();
            for (PriceWatchStore.WatchRow watch : store.watchesForAlias(alias.getAliasId())) {
                alerts.consider(watch, store.snapshot(saved.getId()), instant);
            }
        }
        return saved;
    }

    private Instant firstCheck(PriceWatchClock clock, Instant now, long seed) {
        if (!clock.valid()) {
            return now.plusSeconds(86400);
        }
        if (!clock.open(now)) {
            return clock.nextOpen(now).plusSeconds((seed % 45) * 60);
        }
        return now.plusSeconds(120 + (seed % 30));
    }

    private int platform(String action) {
        if (action.startsWith("jd") || "getPromotionUrl".equals(action) || "getPromotionUrls".equals(action)) {
            return PriceCodes.JD;
        }
        if (action.startsWith("tb") || action.startsWith("getTb")) {
            return PriceCodes.TB;
        }
        return 0;
    }

    private String account(JsonNode result) {
        String profile = result.path("profileName").asText("");
        if (!profile.isBlank()) {
            return profile;
        }
        String configured = appConfig.getCustom().getChrome().getDefaultProfileName();
        return configured == null || configured.isBlank() ? "common" : configured;
    }

    private String keyword(JsonNode request) {
        if (request == null) {
            return "";
        }
        if (request.isTextual()) {
            return request.asText("");
        }
        if (request.isArray() && request.size() > 0) {
            return request.get(0).asText("");
        }
        return request.path("keyword").asText("");
    }

    private String ownerId(String owner) {
        return owner == null || owner.isBlank() ? "legacy" : owner;
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        String value = node.path(field).asText("");
        return "null".equals(value) ? "" : value.trim();
    }

    private AppConfig.PriceWatchConfig config() {
        AppConfig.PriceWatchConfig config = appConfig.getCustom().getPriceWatch();
        return config == null ? new AppConfig.PriceWatchConfig() : config;
    }

    private PriceWatchClock clock(AppConfig.PriceWatchConfig config) {
        return PriceWatchClock.parse(config.getTimezone(), config.getWindowStart(), config.getWindowEnd());
    }
}
