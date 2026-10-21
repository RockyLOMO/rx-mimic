package org.rx.crawler.task.price;

import org.rx.crawler.config.AppConfig;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/** 同一比较序列先复核，再发信。价格回到阈值内就取消，不重抓。 */
public class PriceAlertService {
    private final PriceWatchStore store;
    private final AppConfig appConfig;
    private final PriceAlertSender sender;

    public PriceAlertService(PriceWatchStore store, AppConfig appConfig, PriceAlertSender sender) {
        this.store = store;
        this.appConfig = appConfig;
        this.sender = sender;
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
            if (watch.getNextCheckAt() == null || watch.getNextCheckAt().after(reviewAt)) {
                store.schedule(watch.getId(), reviewAt, null);
            }
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
        if (!config.isNotifyEnabled() || !clock.valid() || !clock.notifyHour(now, config.getNotifyStartHour(), config.getNotifyEndHour())) {
            return;
        }
        for (PriceWatchStore.AlertRow alert : store.dueAlerts(Timestamp.from(now))) {
            PriceWatchStore.WatchRow watch = store.watch(alert.getWatchId());
            if (watch == null) {
                store.updateAlert(alert.getId(), PriceCodes.ALERT_CANCEL, null, null, Timestamp.from(now), null, alert.getAttempts());
                continue;
            }
            if (alert.getStatus() == PriceCodes.ALERT_REVIEW) {
                store.schedule(watch.getId(), Timestamp.from(now), null);
                store.updateAlert(alert.getId(), PriceCodes.ALERT_REVIEW, null, null,
                        Timestamp.from(now.plus(Duration.ofMinutes(10))), null, alert.getAttempts());
                continue;
            }
            Long confirmedId = alert.getConfirmedSnapshotId() == null ? alert.getCandidateSnapshotId() : alert.getConfirmedSnapshotId();
            PriceWatchStore.SnapshotRow confirmed = confirmedId == null ? null : store.snapshot(confirmedId);
            PriceWatchStore.SnapshotRow baseline = alert.getBaselineSnapshotId() == null ? null : store.snapshot(alert.getBaselineSnapshotId());
            if (confirmed == null || baseline == null || !confirmed.isComparable()) {
                store.updateAlert(alert.getId(), PriceCodes.ALERT_CANCEL, null, null, Timestamp.from(now), null, alert.getAttempts());
                continue;
            }
            long ageSeconds = Duration.between(confirmed.getObservedAt().toInstant(), now).getSeconds();
            if (ageSeconds > Math.max(1, config.getSnapshotFreshMinutes()) * 60L) {
                store.schedule(watch.getId(), Timestamp.from(now), null);
                store.updateAlert(alert.getId(), alert.getStatus(), null, null,
                        Timestamp.from(now.plus(Duration.ofMinutes(10))), null, alert.getAttempts());
                continue;
            }
            if (!PriceRules.alert(baseline.getPriceAmount(), null, confirmed.getPriceAmount(), watch.getTargetPrice(),
                    watch.getDropAmount(), watch.getDropPercent())) {
                store.updateAlert(alert.getId(), PriceCodes.ALERT_CANCEL, null, null, Timestamp.from(now), null, alert.getAttempts());
                continue;
            }
            boolean sent = false;
            try {
                sent = sender != null && sender.send(alert.getPayload() == null ? "" : alert.getPayload());
            } catch (RuntimeException e) {
                sent = false;
            }
            if (sent) {
                store.updateAlert(alert.getId(), PriceCodes.ALERT_SENT, confirmed.getId(), null, null, Timestamp.from(now), alert.getAttempts() + 1);
                store.setLastNotified(watch.getId(), confirmed.getPriceAmount());
            } else {
                store.updateAlert(alert.getId(), PriceCodes.ALERT_RETRY, confirmed.getId(), null,
                        Timestamp.from(now.plus(Duration.ofMinutes(10))), null, alert.getAttempts() + 1);
            }
        }
    }

    private String body(PriceWatchStore.WatchRow watch, PriceWatchStore.SnapshotRow baseline, PriceWatchStore.SnapshotRow current) {
        String direct = watch.getResolvedUrl() == null ? "" : watch.getResolvedUrl();
        String promotion = watch.getPromotionUrl() == null ? "" : watch.getPromotionUrl();
        return "平台: " + PriceCodes.platformKey(watch.getPlatform())
                + "\n商品: " + empty(watch.getTitle())
                + "\n店铺: " + empty(watch.getShopName())
                + "\n联盟ID: " + empty(watch.getUnionProductId())
                + "\n数字商品ID: " + empty(watch.getPlatformProductId())
                + "\n基线价: " + PriceRules.money(baseline.getPriceAmount()) + " 观察时间: " + baseline.getObservedAt()
                + "\n当前价: " + PriceRules.money(current.getPriceAmount()) + " 观察时间: " + current.getObservedAt()
                + "\n价格字段: " + empty(current.getSourceField())
                + "\n商品链接: " + direct
                + "\n推广链接: " + promotion
                + "\n说明: 这是同一比较序列的展示价下降。规格、优惠资格和最终结算价请再确认。"
                + "\n推广链接可能失效，生成后不会在查价时重建。";
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }

    private AppConfig.PriceWatchConfig config() {
        AppConfig.PriceWatchConfig config = appConfig.getCustom().getPriceWatch();
        return config == null ? new AppConfig.PriceWatchConfig() : config;
    }
}
