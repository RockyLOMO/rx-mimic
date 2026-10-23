package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import jakarta.annotation.PostConstruct;
import org.rx.io.EntityDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 价格业务表。专用 H2 数据库只记录已确认的数字商品身份。 */
@Component
public class PriceWatchStore {
    private final EntityDatabase database;
    private final ObjectMapper mapper;

    @Autowired
    public PriceWatchStore(@Qualifier("priceDatabase") EntityDatabase database, ObjectMapper mapper) {
        this.database = database;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public PriceWatchStore(EntityDatabase database) {
        this(database, new ObjectMapper());
    }

    @PostConstruct
    public void init() {
        database.executeUpdate("create table if not exists price_product ("
                + "id bigint auto_increment primary key,"
                + "platform int not null,"
                + "platform_product_id varchar(64) not null,"
                + "resolved_url varchar(1000) not null,"
                + "title varchar(500),"
                + "shop_name varchar(300),"
                + "image_url varchar(1000),"
                + "created_at timestamp not null,"
                + "last_seen_at timestamp not null)");
        database.executeUpdate("create unique index if not exists uk_price_product_pid on price_product(platform, platform_product_id)");
        // 唯一商品主表：永久资料与有时效的详情抓取快照分开。
        for (String column : new String[]{"basic_json clob", "union_json clob", "detail_json clob",
                "detail_result_json clob", "detail_cached_at timestamp", "detail_expires_at timestamp"}) {
            database.executeUpdate("alter table price_product add column if not exists " + column);
        }
        database.executeUpdate("create table if not exists price_promotion_record ("
                + "id bigint auto_increment primary key,"
                + "owner_id varchar(128) not null,"
                + "product_id bigint not null,"
                + "task_id bigint not null,"
                + "item_index int not null,"
                + "ad_site_name varchar(128),"
                + "snapshot_id bigint,"
                + "created_at timestamp not null)");
        database.executeUpdate("create unique index if not exists uk_price_promotion on price_promotion_record(task_id, item_index)");
        database.executeUpdate("create table if not exists price_watch ("
                + "id bigint auto_increment primary key,"
                + "owner_id varchar(128) not null,"
                + "product_id bigint not null,"
                + "watch_key varchar(220) not null,"
                + "profile_name varchar(64),"
                + "baseline_snapshot_id bigint,"
                + "target_price decimal(18,2),"
                + "drop_amount decimal(18,2) not null,"
                + "drop_percent decimal(8,2) not null,"
                + "tier int not null,"
                + "status int not null,"
                + "next_check_at timestamp,"
                + "last_attempt_at timestamp,"
                + "last_success_at timestamp,"
                + "last_notified_price decimal(18,2),"
                + "current_task_id bigint,"
                + "plan_seq bigint not null,"
                + "pause_reason varchar(300),"
                + "created_at timestamp not null,"
                + "updated_at timestamp not null)");
        database.executeUpdate("create unique index if not exists uk_price_watch on price_watch(watch_key)");
        database.executeUpdate("create index if not exists idx_price_watch_due on price_watch(status, next_check_at)");
        database.executeUpdate("create table if not exists price_snapshot ("
                + "id bigint auto_increment primary key,"
                + "product_id bigint not null,"
                + "raw_price varchar(64),"
                + "price_amount decimal(18,2),"
                + "source int not null,"
                + "source_field varchar(64),"
                + "price_kind int not null,"
                + "comparable_key varchar(300) not null,"
                + "comparable int not null,"
                + "observed_at timestamp not null,"
                + "task_id bigint not null,"
                + "item_index int not null,"
                + "fetch_status int not null,"
                + "quality_reason varchar(300),"
                + "created_at timestamp not null)");
        database.executeUpdate("create unique index if not exists uk_price_snapshot on price_snapshot(task_id, item_index, source)");
        database.executeUpdate("create index if not exists idx_price_snapshot_product on price_snapshot(product_id, observed_at)");
        database.executeUpdate("create table if not exists price_alert_outbox ("
                + "id bigint auto_increment primary key,"
                + "watch_id bigint not null,"
                + "event_key varchar(220) not null,"
                + "baseline_snapshot_id bigint,"
                + "candidate_snapshot_id bigint,"
                + "confirmed_snapshot_id bigint,"
                + "payload clob,"
                + "status int not null,"
                + "attempts int not null,"
                + "next_attempt_at timestamp,"
                + "sent_at timestamp,"
                + "created_at timestamp not null)");
        database.executeUpdate("create unique index if not exists uk_price_alert on price_alert_outbox(event_key)");
        database.executeUpdate("create index if not exists idx_price_alert_due on price_alert_outbox(status, next_attempt_at)");
        database.executeUpdate("create table if not exists price_visit_counter ("
                + "platform int not null,"
                + "business_date varchar(10) not null,"
                + "visit_count int not null,"
                + "risk_count int not null default 0,"
                + "primary key (platform, business_date))");
        database.executeUpdate("alter table price_visit_counter add column if not exists risk_count int default 0");

        database.executeUpdate("create table if not exists price_event ("
                + "id bigint auto_increment primary key,"
                + "alert_id bigint not null,"
                + "owner_id varchar(128) not null,"
                + "type varchar(64) not null,"
                + "version int not null,"
                + "payload clob not null,"
                + "occurred_at timestamp not null,"
                + "created_at timestamp not null)");
        database.executeUpdate("create unique index if not exists uk_price_event_alert on price_event(alert_id)");
        database.executeUpdate("create index if not exists idx_price_event_owner on price_event(owner_id, id)");
        database.executeUpdate("create index if not exists idx_price_event_created on price_event(created_at)");

        database.executeUpdate("create table if not exists price_event_delivery ("
                + "id bigint auto_increment primary key,"
                + "event_id bigint not null,"
                + "subscriber_id varchar(128) not null,"
                + "channel varchar(32) not null,"
                + "target_url varchar(1000),"
                + "status int not null,"
                + "attempts int not null default 0,"
                + "next_attempt_at timestamp,"
                + "last_http_status int,"
                + "last_error varchar(500),"
                + "sent_at timestamp,"
                + "created_at timestamp not null,"
                + "updated_at timestamp not null)");
        database.executeUpdate("create unique index if not exists uk_price_delivery on price_event_delivery(event_id, subscriber_id, channel)");
        database.executeUpdate("create index if not exists idx_price_delivery_due on price_event_delivery(status, next_attempt_at)");
    }

    public ProductHit upsertProduct(int platform, String numericId, String title, String shop,
            String image, Timestamp now) {
        validateProduct(platform, numericId);
        ProductHit existing = findProduct(platform, numericId);
        if (existing != null) {
            refreshProduct(existing.productId, title, shop, image, now);
            existing.title = blankToNull(title) == null ? existing.title : trim(title, 500);
            existing.shopName = blankToNull(shop) == null ? existing.shopName : trim(shop, 300);
            return existing;
        }
        try {
            long id = insert("insert into price_product(platform,platform_product_id,resolved_url,title,shop_name,image_url,created_at,last_seen_at) values (?,?,?,?,?,?,?,?)",
                    platform, numericId, canonicalUrl(platform, numericId), trim(title, 500), trim(shop, 300), trim(image, 1000), now, now);
            ProductHit created = product(id);
            created.created = true;
            return created;
        } catch (Exception e) {
            ProductHit concurrent = findProduct(platform, numericId);
            if (concurrent == null) throw new IllegalStateException("Product insert failed", e);
            refreshProduct(concurrent.productId, title, shop, image, now);
            concurrent.title = blankToNull(title) == null ? concurrent.title : trim(title, 500);
            concurrent.shopName = blankToNull(shop) == null ? concurrent.shopName : trim(shop, 300);
            return concurrent;
        }
    }

    private void refreshProduct(long id, String title, String shop, String image, Timestamp now) {
        update("update price_product set title=coalesce(?, title), shop_name=coalesce(?, shop_name), image_url=coalesce(?, image_url), last_seen_at=? where id=?",
                trim(title, 500), trim(shop, 300), trim(image, 1000), now, id);
    }

    public ProductHit findProduct(int platform, String numericId) {
        validateProduct(platform, numericId);
        List<ProductHit> rows = queryProduct("select id,platform,platform_product_id,title,shop_name from price_product where platform=? and platform_product_id=?", platform, numericId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ProductHit product(long id) {
        List<ProductHit> rows = queryProduct("select id,platform,platform_product_id,title,shop_name from price_product where id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void validateProduct(int platform, String numericId) {
        if (platform != PriceCodes.JD && platform != PriceCodes.TB) throw new IllegalArgumentException("Unsupported product platform");
        if (numericId == null || numericId.isEmpty() || numericId.length() > 64) throw new IllegalArgumentException("Numeric product ID required");
        for (int i = 0; i < numericId.length(); i++) {
            char c = numericId.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException("Numeric product ID required");
        }
    }

    private static String canonicalUrl(int platform, String numericId) {
        return platform == PriceCodes.JD ? "https://item.jd.com/" + numericId + ".html"
                : "https://item.taobao.com/item.htm?id=" + numericId;
    }

    /** 已存在则返回原行，不重置基线和状态。 */
    public SnapshotSave saveSnapshot(SnapshotWrite write) {
        Long existing = queryLong("select id from price_snapshot where task_id=? and item_index=? and source=?",
                write.taskId, write.itemIndex, write.source);
        SnapshotSave save = new SnapshotSave();
        if (existing != null) {
            save.id = existing;
            save.created = false;
            return save;
        }
        try {
            save.id = insert("insert into price_snapshot(product_id,raw_price,price_amount,source,source_field,price_kind,comparable_key,comparable,observed_at,task_id,item_index,fetch_status,quality_reason,created_at) "
                            + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    write.productId, trim(write.rawPrice, 64), write.priceAmount, write.source,
                    trim(write.sourceField, 64), write.priceKind, trim(write.comparableKey, 300), write.comparable ? 1 : 0,
                    write.observedAt, write.taskId, write.itemIndex, write.fetchStatus, trim(write.qualityReason, 300), write.createdAt);
            save.created = true;
            return save;
        } catch (RuntimeException e) {
            Long again = queryLong("select id from price_snapshot where task_id=? and item_index=? and source=?",
                    write.taskId, write.itemIndex, write.source);
            if (again == null) {
                throw e;
            }
            save.id = again;
            save.created = false;
            return save;
        }
    }

    public boolean savePromotion(String ownerId, long productId, long taskId,
            int itemIndex, String adSite, Long snapshotId, Timestamp now) {
        Long existing = queryLong("select id from price_promotion_record where task_id=? and item_index=?", taskId, itemIndex);
        if (existing != null) {
            return false;
        }
        try {
            insert("insert into price_promotion_record(owner_id,product_id,task_id,item_index,ad_site_name,snapshot_id,created_at) values (?,?,?,?,?,?,?)",
                    ownerId, productId, taskId, itemIndex, trim(adSite, 128), snapshotId, now);
            return true;
        } catch (RuntimeException e) {
            if (queryLong("select id from price_promotion_record where task_id=? and item_index=?", taskId, itemIndex) != null) {
                return false;
            }
            throw e;
        }
    }

    public WatchRow ensureWatch(WatchDraft draft) {
        WatchRow existing = findWatchByKey(draft.watchKey);
        if (existing != null) {
            return existing;
        }
        Timestamp now = draft.now;
        try {
            long id = insert("insert into price_watch(owner_id,product_id,watch_key,profile_name,target_price,drop_amount,drop_percent,tier,status,next_check_at,current_task_id,plan_seq,pause_reason,created_at,updated_at) "
                            + "values (?,?,?,?,?,?,?,?,?,?,null,0,?,?,?)",
                    draft.ownerId, draft.productId, draft.watchKey, draft.profileName, draft.targetPrice,
                    draft.dropAmount, draft.dropPercent, draft.tier, draft.status, draft.nextCheckAt, draft.pauseReason, now, now);
            return watch(id);
        } catch (RuntimeException e) {
            WatchRow again = findWatchByKey(draft.watchKey);
            if (again != null) {
                return again;
            }
            throw e;
        }
    }

    public WatchRow watch(long id) {
        List<WatchRow> rows = queryWatch("select " + WATCH_COLUMNS + " where w.id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public WatchRow findWatch(String ownerId, long productId) {
        return findWatchByKey(ownerId + "|" + productId);
    }

    public List<WatchRow> listByOwner(String ownerId) {
        return queryWatch("select " + WATCH_COLUMNS + " where w.owner_id=? order by w.id desc", ownerId);
    }

    public List<WatchRow> watchesForProduct(long productId) {
        return queryWatch("select " + WATCH_COLUMNS + " where w.product_id=? and w.status=?", productId, PriceCodes.WATCH_ACTIVE);
    }

    public List<WatchRow> due(Timestamp now, int limit) {
        return queryWatch("select " + WATCH_COLUMNS + " where w.status=? and w.current_task_id is null and w.next_check_at<=? order by w.tier asc, w.next_check_at asc limit ?",
                PriceCodes.WATCH_ACTIVE, now, limit);
    }

    public List<WatchRow> withTask() {
        return queryWatch("select " + WATCH_COLUMNS + " where w.current_task_id is not null");
    }

    public int countActive() {
        Long count = queryLong("select count(*) from price_watch where status=?", PriceCodes.WATCH_ACTIVE);
        return count == null ? 0 : count.intValue();
    }

    public boolean attachTask(long watchId, long planSeq, long taskId) {
        return update("update price_watch set current_task_id=?, plan_seq=?, updated_at=current_timestamp where id=? and current_task_id is null",
                taskId, planSeq, watchId) > 0;
    }

    public void clearTask(long watchId, long taskId) {
        update("update price_watch set current_task_id=null, updated_at=current_timestamp where id=? and current_task_id=?",
                watchId, taskId);
    }

    public void clearTask(long watchId) {
        update("update price_watch set current_task_id=null, updated_at=current_timestamp where id=?", watchId);
    }

    public void schedule(long watchId, Timestamp next, String reason) {
        update("update price_watch set next_check_at=?, pause_reason=?, updated_at=current_timestamp where id=?",
                next, reason, watchId);
    }

    public void noteAttempt(long watchId, Timestamp when) {
        update("update price_watch set last_attempt_at=?, updated_at=current_timestamp where id=?", when, watchId);
    }

    public void noteSuccess(long watchId, Timestamp when) {
        update("update price_watch set last_success_at=?, updated_at=current_timestamp where id=?", when, watchId);
    }

    public void setBaseline(long watchId, long snapshotId) {
        update("update price_watch set baseline_snapshot_id=?, updated_at=current_timestamp where id=? and baseline_snapshot_id is null",
                snapshotId, watchId);
    }

    public void replaceBaseline(long watchId, long snapshotId) {
        update("update price_watch set baseline_snapshot_id=?, last_notified_price=null, updated_at=current_timestamp where id=?",
                snapshotId, watchId);
    }

    public void setLastNotified(long watchId, BigDecimal price) {
        update("update price_watch set last_notified_price=?, updated_at=current_timestamp where id=?", price, watchId);
    }

    public boolean setStatus(long watchId, String ownerId, int status, String reason, Timestamp next) {
        return update("update price_watch set status=?, pause_reason=?, next_check_at=?, updated_at=current_timestamp where id=? and owner_id=?",
                status, reason, next, watchId, ownerId) > 0;
    }

    public void setTarget(long watchId, String ownerId, BigDecimal target, int tier) {
        update("update price_watch set target_price=?, tier=?, updated_at=current_timestamp where id=? and owner_id=?",
                target, tier, watchId, ownerId);
    }

    public SnapshotRow snapshot(long id) {
        List<SnapshotRow> rows = querySnapshot("select * from price_snapshot where id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<SnapshotRow> snapshots(long productId, int limit) {
        return querySnapshot("select * from price_snapshot where product_id=? order by observed_at desc, id desc limit ?", productId, limit);
    }

    public synchronized int reserveVisit(int platform, String businessDate, int limit) {
        if (limit <= 0) {
            return -1;
        }
        Long count = queryLong("select visit_count from price_visit_counter where platform=? and business_date=?", platform, businessDate);
        if (count == null) {
            try {
                update("insert into price_visit_counter(platform,business_date,visit_count) values (?,?,1)", platform, businessDate);
                return 1;
            } catch (RuntimeException e) {
                count = queryLong("select visit_count from price_visit_counter where platform=? and business_date=?", platform, businessDate);
                if (count == null) {
                    throw e;
                }
            }
        }
        if (count.intValue() >= limit) {
            return -1;
        }
        int updated = update("update price_visit_counter set visit_count=visit_count+1 where platform=? and business_date=? and visit_count<?",
                platform, businessDate, limit);
        if (updated == 0) {
            return -1;
        }
        Long after = queryLong("select visit_count from price_visit_counter where platform=? and business_date=?", platform, businessDate);
        return after == null ? count.intValue() + 1 : after.intValue();
    }

    public int visitCount(int platform, String businessDate) {
        Long count = queryLong("select visit_count from price_visit_counter where platform=? and business_date=?", platform, businessDate);
        return count == null ? 0 : count.intValue();
    }

    public synchronized int incrementRisk(int platform, String businessDate) {
        Long count = queryLong("select coalesce(risk_count,0) from price_visit_counter where platform=? and business_date=?", platform, businessDate);
        if (count == null) {
            try {
                update("insert into price_visit_counter(platform,business_date,visit_count,risk_count) values (?,?,0,1)", platform, businessDate);
                return 1;
            } catch (RuntimeException e) {
                count = queryLong("select coalesce(risk_count,0) from price_visit_counter where platform=? and business_date=?", platform, businessDate);
                if (count == null) {
                    throw e;
                }
            }
        }
        update("update price_visit_counter set risk_count=coalesce(risk_count,0)+1 where platform=? and business_date=?", platform, businessDate);
        Long after = queryLong("select coalesce(risk_count,0) from price_visit_counter where platform=? and business_date=?", platform, businessDate);
        return after == null ? count.intValue() + 1 : after.intValue();
    }

    public int riskCount(int platform, String businessDate) {
        Long count = queryLong("select coalesce(risk_count,0) from price_visit_counter where platform=? and business_date=?", platform, businessDate);
        return count == null ? 0 : count.intValue();
    }

    public AlertRow insertAlert(long watchId, String eventKey, long baselineId, long candidateId, String payload,
            Timestamp nextAttempt, Timestamp now) {
        AlertRow pending = pendingAlert(watchId);
        if (pending != null) {
            update("update price_alert_outbox set candidate_snapshot_id=?, payload=?, status=? where id=? and status in (?,?)",
                    candidateId, payload, pending.status, pending.id, PriceCodes.ALERT_REVIEW, PriceCodes.ALERT_READY);
            return alert(pending.id);
        }
        try {
            long id = insert("insert into price_alert_outbox(watch_id,event_key,baseline_snapshot_id,candidate_snapshot_id,payload,status,attempts,next_attempt_at,created_at) values (?,?,?,?,?,?,0,?,?)",
                    watchId, eventKey, baselineId, candidateId, payload, PriceCodes.ALERT_REVIEW, nextAttempt, now);
            return alert(id);
        } catch (RuntimeException e) {
            AlertRow again = alertByKey(eventKey);
            if (again != null) {
                return again;
            }
            throw e;
        }
    }

    public AlertRow pendingAlert(long watchId) {
        List<AlertRow> rows = queryAlert("select * from price_alert_outbox where watch_id=? and status in (?,?,?) order by id desc limit 1",
                watchId, PriceCodes.ALERT_REVIEW, PriceCodes.ALERT_READY, PriceCodes.ALERT_RETRY);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AlertRow alert(long id) {
        List<AlertRow> rows = queryAlert("select * from price_alert_outbox where id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AlertRow alertByKey(String eventKey) {
        List<AlertRow> rows = queryAlert("select * from price_alert_outbox where event_key=?", eventKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<AlertRow> dueAlerts(Timestamp now) {
        return queryAlert("select * from price_alert_outbox where status in (?,?,?) and (next_attempt_at is null or next_attempt_at<=?) order by id asc limit 20",
                PriceCodes.ALERT_REVIEW, PriceCodes.ALERT_READY, PriceCodes.ALERT_RETRY, now);
    }

    public void updateAlert(long id, int status, Long confirmedId, String payload, Timestamp next, Timestamp sent, int attempts) {
        update("update price_alert_outbox set status=?, confirmed_snapshot_id=coalesce(?, confirmed_snapshot_id), payload=coalesce(?, payload), next_attempt_at=?, sent_at=coalesce(?, sent_at), attempts=? where id=?",
                status, confirmedId, payload, next, sent, attempts, id);
    }

    private WatchRow findWatchByKey(String key) {
        List<WatchRow> rows = queryWatch("select " + WATCH_COLUMNS + " where w.watch_key=?", key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final String WATCH_COLUMNS = "w.id,w.owner_id,w.product_id,w.profile_name,w.baseline_snapshot_id,w.target_price,w.drop_amount,w.drop_percent,w.tier,w.status,w.next_check_at,w.last_attempt_at,w.last_success_at,w.last_notified_price,w.current_task_id,w.plan_seq,w.pause_reason,"
            + "p.platform,p.resolved_url,p.platform_product_id,p.title,p.shop_name "
            + "from price_watch w join price_product p on p.id=w.product_id";

    private List<WatchRow> queryWatch(String sql, Object... args) {
        return database.withConnection(connection -> {
            List<WatchRow> items = new ArrayList<WatchRow>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        WatchRow row = new WatchRow();
                        row.id = rs.getLong("id");
                        row.ownerId = rs.getString("owner_id");
                        row.productId = rs.getLong("product_id");
                        row.profileName = rs.getString("profile_name");
                        row.baselineSnapshotId = (Long) rs.getObject("baseline_snapshot_id");
                        row.targetPrice = rs.getBigDecimal("target_price");
                        row.dropAmount = rs.getBigDecimal("drop_amount");
                        row.dropPercent = rs.getBigDecimal("drop_percent");
                        row.tier = rs.getInt("tier");
                        row.status = rs.getInt("status");
                        row.nextCheckAt = rs.getTimestamp("next_check_at");
                        row.lastAttemptAt = rs.getTimestamp("last_attempt_at");
                        row.lastSuccessAt = rs.getTimestamp("last_success_at");
                        row.lastNotifiedPrice = rs.getBigDecimal("last_notified_price");
                        row.currentTaskId = (Long) rs.getObject("current_task_id");
                        row.planSeq = rs.getLong("plan_seq");
                        row.pauseReason = rs.getString("pause_reason");
                        row.platform = rs.getInt("platform");
                        row.resolvedUrl = rs.getString("resolved_url");
                        row.platformProductId = rs.getString("platform_product_id");
                        row.title = rs.getString("title");
                        row.shopName = rs.getString("shop_name");
                        items.add(row);
                    }
                }
            }
            return items;
        });
    }

    private List<ProductHit> queryProduct(String sql, Object... args) {
        return database.withConnection(connection -> {
            List<ProductHit> items = new ArrayList<ProductHit>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        ProductHit row = new ProductHit();
                        row.productId = rs.getLong("id");
                        row.platform = rs.getInt("platform");
                        row.platformProductId = rs.getString("platform_product_id");
                        row.title = rs.getString("title");
                        row.shopName = rs.getString("shop_name");
                        items.add(row);
                    }
                }
            }
            return items;
        });
    }

    private List<SnapshotRow> querySnapshot(String sql, Object... args) {
        return database.withConnection(connection -> {
            List<SnapshotRow> items = new ArrayList<SnapshotRow>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        SnapshotRow row = new SnapshotRow();
                        row.id = rs.getLong("id");
                        row.productId = rs.getLong("product_id");
                        row.rawPrice = rs.getString("raw_price");
                        row.priceAmount = rs.getBigDecimal("price_amount");
                        row.source = rs.getInt("source");
                        row.sourceField = rs.getString("source_field");
                        row.priceKind = rs.getInt("price_kind");
                        row.comparableKey = rs.getString("comparable_key");
                        row.comparable = rs.getInt("comparable") == 1;
                        row.observedAt = rs.getTimestamp("observed_at");
                        row.taskId = rs.getLong("task_id");
                        row.itemIndex = rs.getInt("item_index");
                        row.fetchStatus = rs.getInt("fetch_status");
                        row.qualityReason = rs.getString("quality_reason");
                        items.add(row);
                    }
                }
            }
            return items;
        });
    }

    private List<AlertRow> queryAlert(String sql, Object... args) {
        return database.withConnection(connection -> {
            List<AlertRow> items = new ArrayList<AlertRow>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        AlertRow row = new AlertRow();
                        row.id = rs.getLong("id");
                        row.watchId = rs.getLong("watch_id");
                        row.eventKey = rs.getString("event_key");
                        row.baselineSnapshotId = (Long) rs.getObject("baseline_snapshot_id");
                        row.candidateSnapshotId = (Long) rs.getObject("candidate_snapshot_id");
                        row.confirmedSnapshotId = (Long) rs.getObject("confirmed_snapshot_id");
                        row.payload = rs.getString("payload");
                        row.status = rs.getInt("status");
                        row.attempts = rs.getInt("attempts");
                        row.nextAttemptAt = rs.getTimestamp("next_attempt_at");
                        row.sentAt = rs.getTimestamp("sent_at");
                        items.add(row);
                    }
                }
            }
            return items;
        });
    }

    private Long queryLong(String sql, Object... args) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    long value = rs.getLong(1);
                    return rs.wasNull() ? null : value;
                }
            }
        });
    }

    private long insert(String sql, Object... args) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, args);
                if (statement.executeUpdate() <= 0) {
                    throw new IllegalStateException("insert failed");
                }
                try (ResultSet rs = statement.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
                throw new IllegalStateException("generated id missing");
            }
        });
    }

    private int update(String sql, Object... args) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                return statement.executeUpdate();
            }
        });
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    @Data
    public static class ProductHit {
        private long productId;
        private int platform;
        private String platformProductId;
        private String title;
        private String shopName;
        private boolean created;
    }

    @Data
    public static class SnapshotWrite {
        private long productId;
        private String rawPrice;
        private BigDecimal priceAmount;
        private int source;
        private String sourceField;
        private int priceKind;
        private String comparableKey;
        private boolean comparable;
        private Timestamp observedAt;
        private long taskId;
        private int itemIndex;
        private int fetchStatus;
        private String qualityReason;
        private Timestamp createdAt;
    }

    @Data
    public static class SnapshotSave {
        private long id;
        private boolean created;
    }

    @Data
    public static class SnapshotRow {
        private long id;
        private long productId;
        private String rawPrice;
        private BigDecimal priceAmount;
        private int source;
        private String sourceField;
        private int priceKind;
        private String comparableKey;
        private boolean comparable;
        private Timestamp observedAt;
        private long taskId;
        private int itemIndex;
        private int fetchStatus;
        private String qualityReason;
    }

    @Data
    public static class WatchDraft {
        private String ownerId;
        private long productId;
        private String watchKey;
        private String profileName;
        private BigDecimal targetPrice;
        private BigDecimal dropAmount;
        private BigDecimal dropPercent;
        private int tier;
        private int status;
        private Timestamp nextCheckAt;
        private String pauseReason;
        private Timestamp now;
    }

    @Data
    public static class WatchRow {
        private long id;
        private String ownerId;
        private long productId;
        private String profileName;
        private Long baselineSnapshotId;
        private BigDecimal targetPrice;
        private BigDecimal dropAmount;
        private BigDecimal dropPercent;
        private int tier;
        private int status;
        private Timestamp nextCheckAt;
        private Timestamp lastAttemptAt;
        private Timestamp lastSuccessAt;
        private BigDecimal lastNotifiedPrice;
        private Long currentTaskId;
        private long planSeq;
        private String pauseReason;
        private int platform;
        private String resolvedUrl;
        private String platformProductId;
        private String title;
        private String shopName;
    }

    @Data
    public static class AlertRow {
        private long id;
        private long watchId;
        private String eventKey;
        private Long baselineSnapshotId;
        private Long candidateSnapshotId;
        private Long confirmedSnapshotId;
        private String payload;
        private int status;
        private int attempts;
        private Timestamp nextAttemptAt;
        private Timestamp sentAt;
    }

    public long publishEventAndDeliveries(long alertId, String ownerId, PriceDropEvent event, List<DeliveryDraft> drafts,
            Timestamp now, BigDecimal confirmedPrice, long watchId) {
        return database.transInvoke(java.sql.Connection.TRANSACTION_READ_COMMITTED, () -> {
            long eventId = insert("insert into price_event(alert_id,owner_id,type,version,payload,occurred_at,created_at) values (?,?,?,?,?,?,?)",
                    alertId, ownerId, event.getType(), event.getVersion(), "",
                    Timestamp.from(Instant.parse(event.getOccurredAt())), now);
            event.setEventId(String.valueOf(eventId));
            String payloadJson;
            try {
                payloadJson = mapper.writeValueAsString(event);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize PriceDropEvent: " + e.getMessage(), e);
            }
            update("update price_event set payload=? where id=?", payloadJson, eventId);

            if (drafts != null) {
                for (DeliveryDraft draft : drafts) {
                    insert("insert into price_event_delivery(event_id,subscriber_id,channel,target_url,status,attempts,next_attempt_at,created_at,updated_at) values (?,?,?,?,?,?,?,?,?)",
                            eventId, draft.getSubscriberId(), draft.getChannel(), draft.getTargetUrl(),
                            PriceCodes.DELIVERY_PENDING, 0, now, now, now);
                }
            }

            update("update price_alert_outbox set status=?, sent_at=?, attempts=attempts+1 where id=?",
                    PriceCodes.ALERT_SENT, now, alertId);

            update("update price_watch set last_notified_price=?, updated_at=? where id=?",
                    confirmedPrice, now, watchId);

            return eventId;
        });
    }

    public Long getMinEventId() {
        return queryLong("select min(id) from price_event");
    }

    public List<EventRow> listEvents(String ownerId, Long afterId, int limit) {
        String sql = "select id, alert_id, owner_id, type, version, payload, occurred_at, created_at "
                + "from price_event where id > ? and owner_id = ? order by id asc limit ?";
        return database.withConnection(connection -> {
            List<EventRow> items = new ArrayList<EventRow>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, afterId == null ? 0L : afterId, ownerId, limit);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        EventRow row = new EventRow();
                        row.setId(rs.getLong("id"));
                        row.setAlertId(rs.getLong("alert_id"));
                        row.setOwnerId(rs.getString("owner_id"));
                        row.setType(rs.getString("type"));
                        row.setVersion(rs.getInt("version"));
                        row.setPayload(rs.getString("payload"));
                        row.setOccurredAt(rs.getTimestamp("occurred_at"));
                        row.setCreatedAt(rs.getTimestamp("created_at"));
                        items.add(row);
                    }
                }
            }
            return items;
        });
    }

    public List<DeliveryRow> dueDeliveries(Timestamp now, int limit) {
        String sql = "select d.id, d.event_id, d.subscriber_id, d.channel, d.target_url, d.status, d.attempts, "
                + "d.next_attempt_at, d.last_http_status, d.last_error, d.sent_at, d.created_at, d.updated_at, "
                + "e.payload as event_payload, e.owner_id "
                + "from price_event_delivery d join price_event e on d.event_id = e.id "
                + "where d.status in (?, ?) and (d.next_attempt_at is null or d.next_attempt_at <= ?) "
                + "order by d.id asc limit ?";
        return database.withConnection(connection -> {
            List<DeliveryRow> items = new ArrayList<DeliveryRow>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, PriceCodes.DELIVERY_PENDING, PriceCodes.DELIVERY_RETRY, now, limit);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        DeliveryRow row = new DeliveryRow();
                        row.setId(rs.getLong("id"));
                        row.setEventId(rs.getLong("event_id"));
                        row.setSubscriberId(rs.getString("subscriber_id"));
                        row.setChannel(rs.getString("channel"));
                        row.setTargetUrl(rs.getString("target_url"));
                        row.setStatus(rs.getInt("status"));
                        row.setAttempts(rs.getInt("attempts"));
                        row.setNextAttemptAt(rs.getTimestamp("next_attempt_at"));
                        int httpStatus = rs.getInt("last_http_status");
                        row.setLastHttpStatus(rs.wasNull() ? null : httpStatus);
                        row.setLastError(rs.getString("last_error"));
                        row.setSentAt(rs.getTimestamp("sent_at"));
                        row.setCreatedAt(rs.getTimestamp("created_at"));
                        row.setUpdatedAt(rs.getTimestamp("updated_at"));
                        row.setEventPayload(rs.getString("event_payload"));
                        row.setOwnerId(rs.getString("owner_id"));
                        items.add(row);
                    }
                }
            }
            return items;
        });
    }

    public boolean claimDelivery(long deliveryId, Timestamp now) {
        int updated = update("update price_event_delivery set status=?, updated_at=? where id=? and status in (?, ?)",
                PriceCodes.DELIVERY_IN_PROGRESS, now, deliveryId, PriceCodes.DELIVERY_PENDING, PriceCodes.DELIVERY_RETRY);
        return updated > 0;
    }

    public void updateDeliveryResult(long deliveryId, int status, int attempts, Timestamp nextAttemptAt,
            Integer lastHttpStatus, String lastError, Timestamp sentAt, Timestamp now) {
        update("update price_event_delivery set status=?, attempts=?, next_attempt_at=?, last_http_status=?, last_error=?, sent_at=?, updated_at=? where id=?",
                status, attempts, nextAttemptAt, lastHttpStatus, trim(lastError, 500), sentAt, now, deliveryId);
    }

    public int recoverHangingDeliveries(Timestamp cutoff, Timestamp now) {
        return update("update price_event_delivery set status=?, next_attempt_at=?, updated_at=? where status=? and updated_at < ?",
                PriceCodes.DELIVERY_RETRY, now, now, PriceCodes.DELIVERY_IN_PROGRESS, cutoff);
    }

    public int cleanExpiredEvents(Timestamp cutoff) {
        update("delete from price_event_delivery where created_at < ?", cutoff);
        return update("delete from price_event where created_at < ?", cutoff);
    }

    @Data
    public static class DeliveryDraft {
        private String subscriberId;
        private String channel;
        private String targetUrl;
    }

    @Data
    public static class EventRow {
        private long id;
        private long alertId;
        private String ownerId;
        private String type;
        private int version;
        private String payload;
        private Timestamp occurredAt;
        private Timestamp createdAt;
    }

    @Data
    public static class DeliveryRow {
        private long id;
        private long eventId;
        private String subscriberId;
        private String channel;
        private String targetUrl;
        private int status;
        private int attempts;
        private Timestamp nextAttemptAt;
        private Integer lastHttpStatus;
        private String lastError;
        private Timestamp sentAt;
        private Timestamp createdAt;
        private Timestamp updatedAt;
        private String eventPayload;
        private String ownerId;
    }
}
