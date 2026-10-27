package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.rx.crawler.config.AppConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 同一比较序列先复核，再发信与广播。价格回到阈值内就取消，不重抓。 */
public class PriceAlertService {
    private final PriceWatchStore store;
    private final AppConfig appConfig;
    private final PriceAlertSender sender;
    private PriceDeliveryService deliveryService;
    private final ObjectMapper mapper;

    public PriceAlertService(PriceWatchStore store, AppConfig appConfig, PriceAlertSender sender) {
        this(store, appConfig, sender, null, new ObjectMapper());
    }

    public PriceAlertService(PriceWatchStore store, AppConfig appConfig, PriceAlertSender sender,
            PriceDeliveryService deliveryService, ObjectMapper mapper) {
        this.store = store;
        this.appConfig = appConfig;
        this.sender = sender;
        this.deliveryService = deliveryService;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public void setDeliveryService(PriceDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    public void consider(PriceWatchStore.WatchRow watch, PriceWatchStore.SnapshotRow snapshot, Instant now) {
        if (watch == null || snapshot == null || watch.getStatus() != PriceCodes.WATCH_ACTIVE || !snapshot.isComparable()) {
            return;
        }
        PriceWatchStore.SnapshotRow baseline = watch.getBaselineSnapshotId() == null
                ? null : store.snapshot(watch.getBaselineSnapshotId());
        if (baseline == null || !snapshot.getComparableKey().equals(baseline.getComparableKey())) {
            store.replaceBaseline(watch.getId(), snapshot.getId());
            return;
        }
        if (snapshot.getId() == baseline.getId()) {
            return;
        }
        boolean drop = PriceRules.alert(baseline.getPriceAmount(), watch.getLastNotifiedPrice(), snapshot.getPriceAmount(),
                watch.getTargetPrice(), watch.getDropAmount(), watch.getDropPercent());
        PriceWatchStore.AlertRow pending = store.pendingAlert(watch.getId());
        if (!drop) {
            if (pending != null) {
                store.updateAlert(pending.getId(), PriceCodes.ALERT_CANCEL, null, null,
                        Timestamp.from(now), null, pending.getAttempts());
            }
            return;
        }
        AppConfig.PriceWatchConfig config = config();
        String payload = body(watch, baseline, snapshot);
        Timestamp nowTs = Timestamp.from(now);
        if (pending == null) {
            Timestamp reviewAt = Timestamp.from(now.plusSeconds(Math.max(1, config.getReviewDelayMinutes()) * 60L));
            String eventKey = watch.getId() + ":" + snapshot.getComparableKey() + ":" + PriceRules.money(snapshot.getPriceAmount());
            store.insertAlert(watch.getId(), eventKey, baseline.getId(), snapshot.getId(), payload, reviewAt, nowTs);
            return;
        }
        boolean reviewDue = pending.getStatus() == PriceCodes.ALERT_REVIEW
                && pending.getNextAttemptAt() != null && !now.isBefore(pending.getNextAttemptAt().toInstant());
        int status = reviewDue ? PriceCodes.ALERT_READY : pending.getStatus();
        Timestamp nextAttempt = reviewDue ? Timestamp.from(now) : pending.getNextAttemptAt();
        store.updateAlert(pending.getId(), status, snapshot.getId(), payload, nextAttempt, null, pending.getAttempts());
    }

    public void flush(Instant now) {
        AppConfig.PriceWatchConfig config = config();
        PriceWatchClock clock = PriceWatchClock.parse(config.getTimezone(), config.getWindowStart(), config.getWindowEnd());
        boolean mailEnabled = config.isNotifyEnabled();
        boolean broadcastEnabled = config.getBroadcast() != null && config.getBroadcast().isEnabled();
        if (!mailEnabled && !broadcastEnabled) {
            return;
        }
        if (!clock.valid() || !clock.notifyHour(now, config.getNotifyStartHour(), config.getNotifyEndHour())) {
            return;
        }
        for (PriceWatchStore.AlertRow alert : store.dueAlerts(Timestamp.from(now))) {
            PriceWatchStore.WatchRow watch = store.watch(alert.getWatchId());
            if (watch == null) {
                store.updateAlert(alert.getId(), PriceCodes.ALERT_CANCEL, null, null, Timestamp.from(now), null, alert.getAttempts());
                continue;
            }
            if (alert.getStatus() == PriceCodes.ALERT_REVIEW) {
                // Review the stored daily observation; notification retries must not cause another crawl.
                store.updateAlert(alert.getId(), PriceCodes.ALERT_READY, null, null,
                        Timestamp.from(now), null, alert.getAttempts());
                alert.setStatus(PriceCodes.ALERT_READY);
            }
            Long confirmedId = alert.getConfirmedSnapshotId() == null ? alert.getCandidateSnapshotId() : alert.getConfirmedSnapshotId();
            PriceWatchStore.SnapshotRow confirmed = confirmedId == null ? null : store.snapshot(confirmedId);
            PriceWatchStore.SnapshotRow baseline = alert.getBaselineSnapshotId() == null ? null : store.snapshot(alert.getBaselineSnapshotId());
            if (confirmed == null || baseline == null || !confirmed.isComparable()) {
                store.updateAlert(alert.getId(), PriceCodes.ALERT_CANCEL, null, null, Timestamp.from(now), null, alert.getAttempts());
                continue;
            }
            long ageSeconds = Duration.between(confirmed.getObservedAt().toInstant(), now).getSeconds();
            // Daily observations remain usable through the notification quiet period without another visit.
            long freshSeconds = Math.max(Duration.ofDays(1).getSeconds(), Math.max(1, config.getSnapshotFreshMinutes()) * 60L);
            if (ageSeconds > freshSeconds) {
                store.updateAlert(alert.getId(), alert.getStatus(), null, null,
                        Timestamp.from(now.plus(Duration.ofMinutes(10))), null, alert.getAttempts());
                continue;
            }
            if (!PriceRules.alert(baseline.getPriceAmount(), null, confirmed.getPriceAmount(), watch.getTargetPrice(),
                    watch.getDropAmount(), watch.getDropPercent())) {
                store.updateAlert(alert.getId(), PriceCodes.ALERT_CANCEL, null, null, Timestamp.from(now), null, alert.getAttempts());
                continue;
            }

            PriceDropEvent event = buildDropEvent(watch, baseline, confirmed, now);
            List<PriceWatchStore.DeliveryDraft> drafts = new ArrayList<PriceWatchStore.DeliveryDraft>();
            if (broadcastEnabled && config.getBroadcast().getSubscribers() != null) {
                for (AppConfig.PriceSubscriberConfig sub : config.getBroadcast().getSubscribers()) {
                    if (sub != null && sub.matchesOwner(watch.getOwnerId())) {
                        PriceWatchStore.DeliveryDraft draft = new PriceWatchStore.DeliveryDraft();
                        draft.setChannel(PriceCodes.CHANNEL_WEBHOOK);
                        draft.setSubscriberId(sub.getId());
                        draft.setTargetUrl(sub.getUrl());
                        drafts.add(draft);
                    }
                }
            }
            if (mailEnabled) {
                PriceWatchStore.DeliveryDraft draft = new PriceWatchStore.DeliveryDraft();
                draft.setChannel(PriceCodes.CHANNEL_MAIL);
                draft.setSubscriberId("mail");
                draft.setTargetUrl("mail");
                drafts.add(draft);
            }

            long eventId = store.publishEventAndDeliveries(alert.getId(), watch.getOwnerId(), event, drafts,
                    Timestamp.from(now), confirmed.getPriceAmount(), watch.getId());

            if (deliveryService != null) {
                deliveryService.trigger(now);
            } else if (mailEnabled && sender != null) {
                try {
                    sender.send(alert.getPayload() == null ? "" : alert.getPayload());
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    public PriceDropEvent buildDropEvent(PriceWatchStore.WatchRow watch, PriceWatchStore.SnapshotRow baseline,
            PriceWatchStore.SnapshotRow confirmed, Instant now) {
        PriceDropEvent event = new PriceDropEvent();
        event.setType(PriceCodes.EVENT_PRICE_DROPPED);
        event.setVersion(1);
        event.setOccurredAt(DateTimeFormatter.ISO_INSTANT.format(now));

        PriceDropEvent.PriceEventData data = new PriceDropEvent.PriceEventData();
        data.setWatchId(String.valueOf(watch.getId()));
        data.setPlatform(PriceCodes.platformKey(watch.getPlatform()));
        data.setProductId(watch.getPlatformProductId());
        data.setTitle(empty(watch.getTitle()));
        data.setShopName(empty(watch.getShopName()));
        data.setProductUrl(watch.getResolvedUrl());
        data.setCurrency("CNY");
        data.setBaselinePrice(PriceRules.money(baseline.getPriceAmount()));
        data.setCurrentPrice(PriceRules.money(confirmed.getPriceAmount()));

        BigDecimal dropAmount = baseline.getPriceAmount().subtract(confirmed.getPriceAmount());
        data.setDropAmount(dropAmount.setScale(2, RoundingMode.HALF_UP).toPlainString());

        BigDecimal dropPercent = BigDecimal.ZERO;
        if (baseline.getPriceAmount().compareTo(BigDecimal.ZERO) > 0) {
            dropPercent = dropAmount.multiply(BigDecimal.valueOf(100))
                    .divide(baseline.getPriceAmount(), 2, RoundingMode.HALF_UP);
        }
        data.setDropPercent(dropPercent.toPlainString());

        data.setPriceKind(confirmed.getPriceKind());
        data.setPriceField(confirmed.getSourceField() != null ? confirmed.getSourceField() : "price");
        data.setObservedAt(DateTimeFormatter.ISO_INSTANT.format(confirmed.getObservedAt().toInstant()));

        event.setData(data);
        return event;
    }

    private String body(PriceWatchStore.WatchRow watch, PriceWatchStore.SnapshotRow baseline, PriceWatchStore.SnapshotRow current) {
        String direct = watch.getResolvedUrl() == null ? "" : watch.getResolvedUrl();
        return "平台: " + PriceCodes.platformKey(watch.getPlatform())
                + "\n商品: " + empty(watch.getTitle())
                + "\n店铺: " + empty(watch.getShopName())
                + "\n数字商品ID: " + empty(watch.getPlatformProductId())
                + "\n基线价: " + PriceRules.money(baseline.getPriceAmount()) + " 观察时间: " + baseline.getObservedAt()
                + "\n当前价: " + PriceRules.money(current.getPriceAmount()) + " 观察时间: " + current.getObservedAt()
                + "\n价格字段: " + empty(current.getSourceField())
                + "\n商品链接: " + direct
                + "\n说明: 这是同一比较序列的展示价下降。规格、优惠资格和最终结算价请再确认。"
                ;
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }

    private AppConfig.PriceWatchConfig config() {
        AppConfig.PriceWatchConfig config = appConfig.getCustom().getPriceWatch();
        return config == null ? new AppConfig.PriceWatchConfig() : config;
    }
}
