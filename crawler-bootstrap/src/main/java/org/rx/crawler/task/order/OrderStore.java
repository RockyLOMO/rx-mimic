package org.rx.crawler.task.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.io.EntityDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class OrderStore {
    private final EntityDatabase database;
    private final ObjectMapper mapper;

    @Autowired
    public OrderStore(EntityDatabase database, @Autowired(required = false) ObjectMapper mapper) {
        this.database = database;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        database.executeUpdate("create table if not exists promotion_order ("
                + "id bigint auto_increment primary key,"
                + "source_id varchar(64) not null,"
                + "platform varchar(32) not null,"
                + "platform_order_no varchar(64) not null,"
                + "main_order_no varchar(64),"
                + "order_status varchar(64),"
                + "normalized_status varchar(64),"
                + "order_time varchar(64),"
                + "finish_time varchar(64),"
                + "settle_time varchar(64),"
                + "item_count int not null default 0,"
                + "total_amount decimal(18,2),"
                + "total_commission decimal(18,2),"
                + "first_discovered_at timestamp not null,"
                + "last_synced_at timestamp not null,"
                + "latest_task_id bigint,"
                + "created_at timestamp not null,"
                + "updated_at timestamp not null)");
        database.executeUpdate("create unique index if not exists uk_order_source_no on promotion_order(source_id, platform_order_no)");
        database.executeUpdate("create index if not exists idx_order_source_time on promotion_order(source_id, order_time)");
        database.executeUpdate("create index if not exists idx_order_source_status on promotion_order(source_id, order_status)");

        database.executeUpdate("create table if not exists promotion_order_item ("
                + "id bigint auto_increment primary key,"
                + "order_id bigint not null,"
                + "source_id varchar(64) not null,"
                + "source_line_id varchar(128) not null,"
                + "sku_id varchar(64),"
                + "product_name varchar(500),"
                + "product_link varchar(1000),"
                + "product_price decimal(18,2),"
                + "store_name varchar(300),"
                + "promotion_position varchar(300),"
                + "order_status varchar(64),"
                + "quantity int not null default 1,"
                + "refund_quantity int not null default 0,"
                + "estimated_billing_amount decimal(18,2),"
                + "estimated_commission decimal(18,2),"
                + "actual_billing_amount decimal(18,2),"
                + "actual_commission decimal(18,2),"
                + "commission_rate varchar(32),"
                + "raw_json clob,"
                + "created_at timestamp not null,"
                + "updated_at timestamp not null)");
        database.executeUpdate("alter table promotion_order_item add column if not exists promotion_position varchar(300)");
        database.executeUpdate("create unique index if not exists uk_order_item_line on promotion_order_item(source_id, source_line_id)");
        database.executeUpdate("create index if not exists idx_order_item_order_id on promotion_order_item(order_id)");
        database.executeUpdate("create index if not exists idx_order_item_sku on promotion_order_item(sku_id)");

        database.executeUpdate("create table if not exists order_sync_state ("
                + "source_id varchar(64) primary key,"
                + "platform varchar(32) not null,"
                + "profile_name varchar(64) not null,"
                + "account_name varchar(128),"
                + "allowed_callers varchar(500),"
                + "initialized boolean not null default false,"
                + "sync_status varchar(32) not null default 'IDLE',"
                + "last_successful_sync_at timestamp,"
                + "last_successful_window_end varchar(32),"
                + "next_sync_at timestamp,"
                + "current_task_id bigint,"
                + "pending_ingest_task_id bigint,"
                + "last_error varchar(500),"
                + "created_at timestamp not null,"
                + "updated_at timestamp not null)");

        database.executeUpdate("create table if not exists order_event ("
                + "id bigint auto_increment primary key,"
                + "event_id varchar(64) not null,"
                + "type varchar(64) not null,"
                + "version int not null default 1,"
                + "order_id bigint not null,"
                + "source_id varchar(64) not null,"
                + "payload clob not null,"
                + "occurred_at timestamp not null,"
                + "created_at timestamp not null)");
        database.executeUpdate("create unique index if not exists uk_order_event_event_id on order_event(event_id)");
        database.executeUpdate("create unique index if not exists uk_order_event_order_type on order_event(order_id, type)");
        database.executeUpdate("create index if not exists idx_order_event_source on order_event(source_id, id)");
        database.executeUpdate("create index if not exists idx_order_event_created on order_event(created_at)");

        database.executeUpdate("create table if not exists order_event_delivery ("
                + "id bigint auto_increment primary key,"
                + "event_id bigint not null,"
                + "subscriber_id varchar(128) not null,"
                + "channel varchar(32) not null default 'webhook',"
                + "target_url varchar(1000) not null,"
                + "status int not null,"
                + "attempts int not null default 0,"
                + "next_attempt_at timestamp,"
                + "last_http_status int,"
                + "last_error varchar(500),"
                + "sent_at timestamp,"
                + "created_at timestamp not null,"
                + "updated_at timestamp not null)");
        database.executeUpdate("create unique index if not exists uk_order_delivery on order_event_delivery(event_id, subscriber_id, channel)");
        database.executeUpdate("create index if not exists idx_order_delivery_due on order_event_delivery(status, next_attempt_at)");
    }

    public static class IngestBatchResult {
        public int newOrdersCount;
        public int updatedOrdersCount;
        public int itemsCount;
    }

    public IngestBatchResult ingestOrders(String sourceId, String platform, Long taskId,
                                          String windowStart, String windowEnd, boolean complete,
                                          List<PromotionOrderItem> items, boolean bootstrapNotify,
                                          boolean isInitialBackfill,
                                          List<AppConfig.OrderSubscriberConfig> subscribers) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId is required");
        }
        if (items == null) {
            items = Collections.emptyList();
        }

        final List<PromotionOrderItem> orderItems = items;
        return database.withConnection(connection -> {
            boolean origAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Timestamp now = Timestamp.from(Instant.now());
                IngestBatchResult result = new IngestBatchResult();

                Map<String, List<PromotionOrderItem>> grouped = new LinkedHashMap<>();
                for (PromotionOrderItem item : orderItems) {
                    if (item == null
                            || item.getOrderNo() == null || item.getOrderNo().isBlank()
                            || item.getProductName() == null || item.getProductName().isBlank()
                            || item.getStoreName() == null || item.getStoreName().isBlank()
                            || item.getPromotionPosition() == null || item.getPromotionPosition().isBlank()) {
                        log.warn("Skipping invalid order item missing required fields (orderNo, productName, storeName, promotionPosition): {}",
                                item != null ? ("orderNo=" + item.getOrderNo() + ", product=" + item.getProductName()
                                        + ", store=" + item.getStoreName() + ", position=" + item.getPromotionPosition()) : "null");
                        continue;
                    }
                    grouped.computeIfAbsent(item.getOrderNo().trim(), k -> new ArrayList<>()).add(item);
                }

                List<Long> newlyCreatedOrderIds = new ArrayList<>();

                for (Map.Entry<String, List<PromotionOrderItem>> entry : grouped.entrySet()) {
                    String platformOrderNo = entry.getKey();
                    List<PromotionOrderItem> rows = entry.getValue();
                    PromotionOrderItem primary = rows.get(0);

                    Long orderId = findOrderId(connection, sourceId, platformOrderNo);
                    boolean isNew = (orderId == null);

                    BigDecimal totalAmount = BigDecimal.ZERO;
                    BigDecimal totalCommission = BigDecimal.ZERO;
                    int itemCount = 0;
                    for (PromotionOrderItem r : rows) {
                        BigDecimal bill = parseAmount(r.getActualBillingAmount() != null && !r.getActualBillingAmount().isBlank()
                                ? r.getActualBillingAmount() : r.getEstimatedBillingAmount());
                        if (bill != null) {
                            totalAmount = totalAmount.add(bill);
                        }
                        BigDecimal comm = parseAmount(r.getActualCommission() != null && !r.getActualCommission().isBlank()
                                ? r.getActualCommission() : r.getEstimatedCommission());
                        if (comm != null) {
                            totalCommission = totalCommission.add(comm);
                        }
                        int q = parseQuantity(r.getProductQuantity() != null && !r.getProductQuantity().isBlank()
                                ? r.getProductQuantity() : r.getQuantity());
                        itemCount += (q > 0 ? q : 1);
                    }

                    if (isNew) {
                        orderId = insertOrder(connection, sourceId, platform, platformOrderNo,
                                primary.getMainOrderNo(), primary.getOrderStatus(), primary.getOrderStatus(),
                                primary.getOrderTime(), primary.getFinishTime(), primary.getSettleTime(),
                                itemCount, totalAmount, totalCommission, taskId, now);
                        newlyCreatedOrderIds.add(orderId);
                        result.newOrdersCount++;
                    } else {
                        updateOrder(connection, orderId, primary.getMainOrderNo(), primary.getOrderStatus(),
                                primary.getOrderStatus(), primary.getOrderTime(), primary.getFinishTime(),
                                primary.getSettleTime(), itemCount, totalAmount, totalCommission, taskId, now);
                        result.updatedOrdersCount++;
                    }

                    for (PromotionOrderItem r : rows) {
                        String lineId = resolveLineId(r, platformOrderNo);
                        upsertOrderItem(connection, orderId, sourceId, lineId, r, now);
                        result.itemsCount++;
                    }
                }

                boolean shouldNotify = bootstrapNotify || !isInitialBackfill;
                if (shouldNotify && !newlyCreatedOrderIds.isEmpty()) {
                    for (Long createdOrderId : newlyCreatedOrderIds) {
                        OrderRecord rec = loadOrderRecord(connection, createdOrderId);
                        if (rec != null) {
                            createOrderEventAndDeliveries(connection, rec, isInitialBackfill, subscribers, now);
                        }
                    }
                }

                updateSyncStateWatermark(connection, sourceId, complete, windowEnd, now);

                connection.commit();
                return result;
            } catch (Exception e) {
                connection.rollback();
                throw new RuntimeException("Order batch ingestion failed: " + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(origAutoCommit);
            }
        });
    }

    private String resolveLineId(PromotionOrderItem item, String defaultOrderNo) {
        if (item.getSourceLineId() != null && !item.getSourceLineId().isBlank()) {
            return item.getSourceLineId().trim();
        }
        String sku = item.getSkuId() != null ? item.getSkuId().trim() : "";
        String orderNo = item.getOrderNo() != null ? item.getOrderNo().trim() : defaultOrderNo;
        return orderNo + ":" + sku;
    }

    private Long findOrderId(Connection conn, String sourceId, String platformOrderNo) throws SQLException {
        String sql = "select id from promotion_order where source_id = ? and platform_order_no = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sourceId);
            stmt.setString(2, platformOrderNo);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return null;
    }

    private long insertOrder(Connection conn, String sourceId, String platform, String platformOrderNo,
                             String mainOrderNo, String orderStatus, String normalizedStatus,
                             String orderTime, String finishTime, String settleTime,
                             int itemCount, BigDecimal totalAmount, BigDecimal totalCommission,
                             Long taskId, Timestamp now) throws SQLException {
        String sql = "insert into promotion_order (source_id, platform, platform_order_no, main_order_no, "
                + "order_status, normalized_status, order_time, finish_time, settle_time, "
                + "item_count, total_amount, total_commission, first_discovered_at, last_synced_at, "
                + "latest_task_id, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, sourceId);
            stmt.setString(2, platform);
            stmt.setString(3, platformOrderNo);
            stmt.setString(4, trim(mainOrderNo, 64));
            stmt.setString(5, trim(orderStatus, 64));
            stmt.setString(6, trim(normalizedStatus, 64));
            stmt.setString(7, trim(orderTime, 64));
            stmt.setString(8, trim(finishTime, 64));
            stmt.setString(9, trim(settleTime, 64));
            stmt.setInt(10, itemCount);
            stmt.setBigDecimal(11, totalAmount);
            stmt.setBigDecimal(12, totalCommission);
            stmt.setTimestamp(13, now);
            stmt.setTimestamp(14, now);
            if (taskId != null) stmt.setLong(15, taskId); else stmt.setNull(15, java.sql.Types.BIGINT);
            stmt.setTimestamp(16, now);
            stmt.setTimestamp(17, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to retrieve generated order id");
    }

    private void updateOrder(Connection conn, long orderId, String mainOrderNo, String orderStatus,
                             String normalizedStatus, String orderTime, String finishTime, String settleTime,
                             int itemCount, BigDecimal totalAmount, BigDecimal totalCommission,
                             Long taskId, Timestamp now) throws SQLException {
        String sql = "update promotion_order set main_order_no = coalesce(?, main_order_no), "
                + "order_status = coalesce(?, order_status), normalized_status = coalesce(?, normalized_status), "
                + "order_time = coalesce(?, order_time), finish_time = coalesce(?, finish_time), settle_time = coalesce(?, settle_time), "
                + "item_count = ?, total_amount = ?, total_commission = ?, "
                + "latest_task_id = coalesce(?, latest_task_id), last_synced_at = ?, updated_at = ? where id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, trim(mainOrderNo, 64));
            stmt.setString(2, trim(orderStatus, 64));
            stmt.setString(3, trim(normalizedStatus, 64));
            stmt.setString(4, trim(orderTime, 64));
            stmt.setString(5, trim(finishTime, 64));
            stmt.setString(6, trim(settleTime, 64));
            stmt.setInt(7, itemCount);
            stmt.setBigDecimal(8, totalAmount);
            stmt.setBigDecimal(9, totalCommission);
            if (taskId != null) stmt.setLong(10, taskId); else stmt.setNull(10, java.sql.Types.BIGINT);
            stmt.setTimestamp(11, now);
            stmt.setTimestamp(12, now);
            stmt.setLong(13, orderId);
            stmt.executeUpdate();
        }
    }

    private void upsertOrderItem(Connection conn, long orderId, String sourceId, String lineId,
                                 PromotionOrderItem item, Timestamp now) throws SQLException {
        String selectSql = "select id from promotion_order_item where source_id = ? and source_line_id = ?";
        Long existingItemId = null;
        try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
            stmt.setString(1, sourceId);
            stmt.setString(2, lineId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    existingItemId = rs.getLong(1);
                }
            }
        }

        BigDecimal price = parseAmount(item.getProductPrice());
        int quantity = parseQuantity(item.getProductQuantity() != null && !item.getProductQuantity().isBlank()
                ? item.getProductQuantity() : item.getQuantity());
        if (quantity <= 0) quantity = 1;
        int refundQuantity = parseQuantity(item.getReturnQuantity() != null && !item.getReturnQuantity().isBlank()
                ? item.getReturnQuantity() : item.getAfterSaleQuantity());
        BigDecimal estBill = parseAmount(item.getEstimatedBillingAmount());
        BigDecimal estComm = parseAmount(item.getEstimatedCommission());
        BigDecimal actBill = parseAmount(item.getActualBillingAmount());
        BigDecimal actComm = parseAmount(item.getActualCommission());

        if (existingItemId == null) {
            String insertSql = "insert into promotion_order_item (order_id, source_id, source_line_id, sku_id, "
                    + "product_name, product_link, product_price, store_name, promotion_position, order_status, quantity, refund_quantity, "
                    + "estimated_billing_amount, estimated_commission, actual_billing_amount, actual_commission, "
                    + "commission_rate, raw_json, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                stmt.setLong(1, orderId);
                stmt.setString(2, sourceId);
                stmt.setString(3, lineId);
                stmt.setString(4, trim(item.getSkuId(), 64));
                stmt.setString(5, trim(item.getProductName(), 500));
                stmt.setString(6, trim(item.getProductLink(), 1000));
                stmt.setBigDecimal(7, price);
                stmt.setString(8, trim(item.getStoreName(), 300));
                stmt.setString(9, trim(item.getPromotionPosition(), 300));
                stmt.setString(10, trim(item.getOrderStatus(), 64));
                stmt.setInt(11, quantity);
                stmt.setInt(12, refundQuantity);
                stmt.setBigDecimal(13, estBill);
                stmt.setBigDecimal(14, estComm);
                stmt.setBigDecimal(15, actBill);
                stmt.setBigDecimal(16, actComm);
                stmt.setString(17, trim(item.getCommissionRate(), 32));
                stmt.setString(18, item.getRawJson());
                stmt.setTimestamp(19, now);
                stmt.setTimestamp(20, now);
                stmt.executeUpdate();
            }
        } else {
            String updateSql = "update promotion_order_item set promotion_position = coalesce(?, promotion_position), "
                    + "order_status = coalesce(?, order_status), "
                    + "quantity = ?, refund_quantity = ?, actual_billing_amount = coalesce(?, actual_billing_amount), "
                    + "actual_commission = coalesce(?, actual_commission), estimated_billing_amount = coalesce(?, estimated_billing_amount), "
                    + "estimated_commission = coalesce(?, estimated_commission), raw_json = coalesce(?, raw_json), updated_at = ? where id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setString(1, trim(item.getPromotionPosition(), 300));
                stmt.setString(2, trim(item.getOrderStatus(), 64));
                stmt.setInt(3, quantity);
                stmt.setInt(4, refundQuantity);
                stmt.setBigDecimal(5, actBill);
                stmt.setBigDecimal(6, actComm);
                stmt.setBigDecimal(7, estBill);
                stmt.setBigDecimal(8, estComm);
                stmt.setString(9, item.getRawJson());
                stmt.setTimestamp(10, now);
                stmt.setLong(11, existingItemId);
                stmt.executeUpdate();
            }
        }
    }

    private void createOrderEventAndDeliveries(Connection conn, OrderRecord order, boolean isInitialBackfill,
                                               List<AppConfig.OrderSubscriberConfig> subscribers, Timestamp now) throws SQLException {
        String eventId = UUID.randomUUID().toString();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("eventId", eventId);
        payload.put("type", OrderCodes.EVENT_ORDER_CREATED);
        payload.put("version", 1);
        payload.put("occurredAt", DateTimeFormatter.ISO_INSTANT.format(now.toInstant()));

        ObjectNode data = payload.putObject("data");
        data.put("orderId", String.valueOf(order.getId()));
        data.put("sourceId", order.getSourceId());
        data.put("platform", order.getPlatform());
        data.put("orderNo", order.getPlatformOrderNo());
        data.put("orderTime", order.getOrderTime());
        data.put("discoveredAt", DateTimeFormatter.ISO_INSTANT.format(now.toInstant()));
        data.put("initialBackfill", isInitialBackfill);
        data.put("itemCount", order.getItemCount());
        data.put("orderPath", "/api/v1/orders/" + order.getId());

        String jsonPayload = payload.toString();

        long eventPk = 0;
        String insertEventSql = "insert into order_event (event_id, type, version, order_id, source_id, payload, occurred_at, created_at) "
                + "values (?,?,?,?,?,?,?,?)";
        try (PreparedStatement stmt = conn.prepareStatement(insertEventSql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, eventId);
            stmt.setString(2, OrderCodes.EVENT_ORDER_CREATED);
            stmt.setInt(3, 1);
            stmt.setLong(4, order.getId());
            stmt.setString(5, order.getSourceId());
            stmt.setString(6, jsonPayload);
            stmt.setTimestamp(7, now);
            stmt.setTimestamp(8, now);
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    eventPk = rs.getLong(1);
                }
            }
        }

        if (subscribers != null && eventPk > 0) {
            String insertDeliverySql = "insert into order_event_delivery (event_id, subscriber_id, channel, target_url, status, attempts, next_attempt_at, created_at, updated_at) "
                    + "values (?,?,?,?,?,?,?,?,?)";
            for (AppConfig.OrderSubscriberConfig sub : subscribers) {
                if (sub == null || !sub.matchesSource(order.getSourceId()) || sub.getUrl() == null || sub.getUrl().isBlank()) {
                    continue;
                }
                try (PreparedStatement stmt = conn.prepareStatement(insertDeliverySql)) {
                    stmt.setLong(1, eventPk);
                    stmt.setString(2, sub.getId() != null ? sub.getId() : "default");
                    stmt.setString(3, OrderCodes.CHANNEL_WEBHOOK);
                    stmt.setString(4, sub.getUrl().trim());
                    stmt.setInt(5, OrderCodes.DELIVERY_PENDING);
                    stmt.setInt(6, 0);
                    stmt.setTimestamp(7, now);
                    stmt.setTimestamp(8, now);
                    stmt.setTimestamp(9, now);
                    stmt.executeUpdate();
                }
            }
        }
    }

    private void updateSyncStateWatermark(Connection conn, String sourceId, boolean complete,
                                          String windowEnd, Timestamp now) throws SQLException {
        if (complete) {
            String sql = "update order_sync_state set initialized = true, last_successful_sync_at = ?, "
                    + "last_successful_window_end = coalesce(?, last_successful_window_end), "
                    + "pending_ingest_task_id = null, current_task_id = null, sync_status = 'IDLE', "
                    + "last_error = null, updated_at = ? where source_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setTimestamp(1, now);
                stmt.setString(2, windowEnd);
                stmt.setTimestamp(3, now);
                stmt.setString(4, sourceId);
                stmt.executeUpdate();
            }
        } else {
            String sql = "update order_sync_state set pending_ingest_task_id = null, current_task_id = null, "
                    + "sync_status = 'IDLE', updated_at = ? where source_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setTimestamp(1, now);
                stmt.setString(2, sourceId);
                stmt.executeUpdate();
            }
        }
    }

    public OrderRecord loadOrderRecord(Connection conn, long orderId) throws SQLException {
        String sql = "select id, source_id, platform, platform_order_no, main_order_no, order_status, "
                + "normalized_status, order_time, finish_time, settle_time, item_count, total_amount, "
                + "total_commission, first_discovered_at, last_synced_at, latest_task_id, created_at, updated_at "
                + "from promotion_order where id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    OrderRecord rec = mapOrderRow(rs);
                    return rec;
                }
            }
        }
        return null;
    }

    public OrderRecord getOrderWithItems(long orderId) {
        return database.withConnection(conn -> {
            OrderRecord order = loadOrderRecord(conn, orderId);
            if (order == null) {
                return null;
            }
            order.setItems(loadOrderItems(conn, orderId));
            return order;
        });
    }

    public List<OrderItemRecord> getOrderItems(long orderId) {
        return database.withConnection(conn -> {
            return loadOrderItems(conn, orderId);
        });
    }

    public List<OrderItemRecord> loadOrderItems(Connection conn, long orderId) throws SQLException {
        String sql = "select id, order_id, source_id, source_line_id, sku_id, product_name, product_link, "
                + "product_price, store_name, promotion_position, order_status, quantity, refund_quantity, estimated_billing_amount, "
                + "estimated_commission, actual_billing_amount, actual_commission, commission_rate, raw_json, "
                + "created_at, updated_at from promotion_order_item where order_id = ? order by id asc";
        List<OrderItemRecord> items = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    items.add(mapOrderItemRow(rs));
                }
            }
        }
        return items;
    }

    public Map<Long, List<OrderItemRecord>> loadOrderItemsBatch(Connection conn, List<Long> orderIds) throws SQLException {
        if (orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        StringBuilder sql = new StringBuilder("select id, order_id, source_id, source_line_id, sku_id, product_name, product_link, ")
                .append("product_price, store_name, promotion_position, order_status, quantity, refund_quantity, estimated_billing_amount, ")
                .append("estimated_commission, actual_billing_amount, actual_commission, commission_rate, raw_json, ")
                .append("created_at, updated_at from promotion_order_item where order_id in (");
        for (int i = 0; i < orderIds.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(") order by order_id asc, id asc");

        Map<Long, List<OrderItemRecord>> map = new LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < orderIds.size(); i++) {
                stmt.setLong(i + 1, orderIds.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    OrderItemRecord item = mapOrderItemRow(rs);
                    map.computeIfAbsent(item.getOrderId(), k -> new ArrayList<>()).add(item);
                }
            }
        }
        return map;
    }

    private OrderItemRecord mapOrderItemRow(ResultSet rs) throws SQLException {
        OrderItemRecord item = new OrderItemRecord();
        item.setId(rs.getLong("id"));
        item.setOrderId(rs.getLong("order_id"));
        item.setSourceId(rs.getString("source_id"));
        item.setSourceLineId(rs.getString("source_line_id"));
        item.setSkuId(rs.getString("sku_id"));
        item.setProductName(rs.getString("product_name"));
        item.setProductLink(rs.getString("product_link"));
        item.setProductPrice(rs.getBigDecimal("product_price"));
        item.setStoreName(rs.getString("store_name"));
        item.setPromotionPosition(rs.getString("promotion_position"));
        item.setOrderStatus(rs.getString("order_status"));
        item.setQuantity(rs.getInt("quantity"));
        item.setRefundQuantity(rs.getInt("refund_quantity"));
        item.setEstimatedBillingAmount(rs.getBigDecimal("estimated_billing_amount"));
        item.setEstimatedCommission(rs.getBigDecimal("estimated_commission"));
        item.setActualBillingAmount(rs.getBigDecimal("actual_billing_amount"));
        item.setActualCommission(rs.getBigDecimal("actual_commission"));
        item.setCommissionRate(rs.getString("commission_rate"));
        item.setRawJson(rs.getString("raw_json"));
        item.setCreatedAt(rs.getTimestamp("created_at"));
        item.setUpdatedAt(rs.getTimestamp("updated_at"));
        return item;
    }

    public OrderPage queryOrders(OrderQueryFilter filter) {
        return database.withConnection(conn -> {
            List<String> allowedSources = filter.getAuthorizedSources();
            if (allowedSources == null || allowedSources.isEmpty()) {
                return new OrderPage(Collections.emptyList(), 0, filter.getPage(), filter.getPageSize(), false, null, false);
            }

            StringBuilder where = new StringBuilder("where 1=1 ");
            List<Object> params = new ArrayList<>();

            if (filter.getSourceId() != null && !filter.getSourceId().isBlank()) {
                if (!allowedSources.contains(filter.getSourceId().trim())) {
                    return new OrderPage(Collections.emptyList(), 0, filter.getPage(), filter.getPageSize(), false, null, false);
                }
                where.append("and source_id = ? ");
                params.add(filter.getSourceId().trim());
            } else {
                where.append("and source_id in (");
                for (int i = 0; i < allowedSources.size(); i++) {
                    if (i > 0) where.append(",");
                    where.append("?");
                    params.add(allowedSources.get(i));
                }
                where.append(") ");
            }

            if (filter.getPlatform() != null && !filter.getPlatform().isBlank()) {
                where.append("and platform = ? ");
                params.add(filter.getPlatform().trim());
            }
            if (filter.getStartDate() != null && !filter.getStartDate().isBlank()) {
                where.append("and order_time >= ? ");
                params.add(filter.getStartDate().trim());
            }
            if (filter.getEndDate() != null && !filter.getEndDate().isBlank()) {
                where.append("and order_time <= ? ");
                params.add(filter.getEndDate().trim() + " 23:59:59");
            }
            if (filter.getOrderNo() != null && !filter.getOrderNo().isBlank()) {
                String no = filter.getOrderNo().trim();
                where.append("and (platform_order_no = ? or main_order_no = ?) ");
                params.add(no);
                params.add(no);
            }
            String pid = filter.getProductId() != null && !filter.getProductId().isBlank()
                    ? filter.getProductId().trim()
                    : (filter.getSkuId() != null && !filter.getSkuId().isBlank() ? filter.getSkuId().trim() : null);
            if (pid != null) {
                where.append("and exists (select 1 from promotion_order_item poi where poi.order_id = promotion_order.id and poi.sku_id = ?) ");
                params.add(pid);
            }
            if (filter.getProductName() != null && !filter.getProductName().isBlank()) {
                where.append("and exists (select 1 from promotion_order_item poi where poi.order_id = promotion_order.id and poi.product_name like ?) ");
                params.add("%" + filter.getProductName().trim() + "%");
            }
            if (filter.getOrderStatus() != null && !filter.getOrderStatus().isBlank()) {
                where.append("and order_status = ? ");
                params.add(filter.getOrderStatus().trim());
            }

            String countSql = "select count(*) from promotion_order " + where;
            long total = 0;
            try (PreparedStatement stmt = conn.prepareStatement(countSql)) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getLong(1);
                    }
                }
            }

            int page = Math.max(1, filter.getPage());
            int pageSize = Math.max(1, Math.min(200, filter.getPageSize()));
            int offset = (page - 1) * pageSize;

            String selectSql = "select id, source_id, platform, platform_order_no, main_order_no, order_status, "
                    + "normalized_status, order_time, finish_time, settle_time, item_count, total_amount, "
                    + "total_commission, first_discovered_at, last_synced_at, latest_task_id, created_at, updated_at "
                    + "from promotion_order " + where + " order by order_time desc, id desc limit ? offset ?";

            List<OrderRecord> list = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                int idx = 1;
                for (Object param : params) {
                    stmt.setObject(idx++, param);
                }
                stmt.setInt(idx++, pageSize);
                stmt.setInt(idx, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapOrderRow(rs));
                    }
                }
            }

            if (!list.isEmpty()) {
                List<Long> orderIds = new ArrayList<>();
                for (OrderRecord r : list) {
                    orderIds.add(r.getId());
                }
                Map<Long, List<OrderItemRecord>> itemMap = loadOrderItemsBatch(conn, orderIds);
                for (OrderRecord r : list) {
                    r.setItems(itemMap.getOrDefault(r.getId(), Collections.emptyList()));
                }
            }

            boolean initialized = true;
            Timestamp lastSync = null;
            for (String src : allowedSources) {
                OrderSyncState state = getSyncState(src);
                if (state == null || !state.isInitialized()) {
                    initialized = false;
                }
                if (state != null && state.getLastSuccessfulSyncAt() != null) {
                    if (lastSync == null || state.getLastSuccessfulSyncAt().after(lastSync)) {
                        lastSync = state.getLastSuccessfulSyncAt();
                    }
                }
            }

            boolean stale = false;
            if (lastSync == null || Instant.now().minusSeconds(1800).isAfter(lastSync.toInstant())) {
                stale = true;
            }

            return new OrderPage(list, total, page, pageSize, initialized, lastSync, stale);
        });
    }

    private OrderRecord mapOrderRow(ResultSet rs) throws SQLException {
        OrderRecord rec = new OrderRecord();
        rec.setId(rs.getLong("id"));
        rec.setSourceId(rs.getString("source_id"));
        rec.setPlatform(rs.getString("platform"));
        rec.setPlatformOrderNo(rs.getString("platform_order_no"));
        rec.setMainOrderNo(rs.getString("main_order_no"));
        rec.setOrderStatus(rs.getString("order_status"));
        rec.setNormalizedStatus(rs.getString("normalized_status"));
        rec.setOrderTime(rs.getString("order_time"));
        rec.setFinishTime(rs.getString("finish_time"));
        rec.setSettleTime(rs.getString("settle_time"));
        rec.setItemCount(rs.getInt("item_count"));
        rec.setTotalAmount(rs.getBigDecimal("total_amount"));
        rec.setTotalCommission(rs.getBigDecimal("total_commission"));
        rec.setFirstDiscoveredAt(rs.getTimestamp("first_discovered_at"));
        rec.setLastSyncedAt(rs.getTimestamp("last_synced_at"));
        long taskId = rs.getLong("latest_task_id");
        rec.setLatestTaskId(rs.wasNull() ? null : taskId);
        rec.setCreatedAt(rs.getTimestamp("created_at"));
        rec.setUpdatedAt(rs.getTimestamp("updated_at"));
        return rec;
    }

    public OrderSyncState getSyncState(String sourceId) {
        String sql = "select source_id, platform, profile_name, account_name, allowed_callers, initialized, "
                + "sync_status, last_successful_sync_at, last_successful_window_end, next_sync_at, current_task_id, "
                + "pending_ingest_task_id, last_error, created_at, updated_at from order_sync_state where source_id = ?";
        return database.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, sourceId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return mapSyncStateRow(rs);
                    }
                }
            }
            return null;
        });
    }

    public List<OrderSyncState> listSyncStates() {
        String sql = "select source_id, platform, profile_name, account_name, allowed_callers, initialized, "
                + "sync_status, last_successful_sync_at, last_successful_window_end, next_sync_at, current_task_id, "
                + "pending_ingest_task_id, last_error, created_at, updated_at from order_sync_state order by source_id asc";
        return database.withConnection(conn -> {
            List<OrderSyncState> list = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapSyncStateRow(rs));
                }
            }
            return list;
        });
    }

    public void upsertSyncStateRegistration(String sourceId, String platform, String profileName,
                                           String accountName, String allowedCallers) {
        String sql = "merge into order_sync_state (source_id, platform, profile_name, account_name, allowed_callers, created_at, updated_at) "
                + "key(source_id) values (?,?,?,?,?,current_timestamp,current_timestamp)";
        update(sql, sourceId, platform, profileName, accountName, allowedCallers);
    }

    public void updateSyncStateTask(String sourceId, Long taskId, String syncStatus) {
        String sql = "update order_sync_state set current_task_id = ?, sync_status = ?, updated_at = current_timestamp where source_id = ?";
        update(sql, taskId, syncStatus, sourceId);
    }

    public void updateSyncStatePendingIngest(String sourceId, Long taskId) {
        String sql = "update order_sync_state set pending_ingest_task_id = ?, updated_at = current_timestamp where source_id = ?";
        update(sql, taskId, sourceId);
    }

    public void updateSyncStateFailure(String sourceId, String error, Timestamp nextSyncAt) {
        String sql = "update order_sync_state set sync_status = 'ERROR', last_error = ?, current_task_id = null, "
                + "next_sync_at = ?, updated_at = current_timestamp where source_id = ?";
        update(sql, trim(error, 500), nextSyncAt, sourceId);
    }

    public void updateNextSyncAt(String sourceId, Timestamp nextSyncAt) {
        String sql = "update order_sync_state set next_sync_at = ?, updated_at = current_timestamp where source_id = ?";
        update(sql, nextSyncAt, sourceId);
    }

    private OrderSyncState mapSyncStateRow(ResultSet rs) throws SQLException {
        OrderSyncState s = new OrderSyncState();
        s.setSourceId(rs.getString("source_id"));
        s.setPlatform(rs.getString("platform"));
        s.setProfileName(rs.getString("profile_name"));
        s.setAccountName(rs.getString("account_name"));
        s.setAllowedCallers(rs.getString("allowed_callers"));
        s.setInitialized(rs.getBoolean("initialized"));
        s.setSyncStatus(rs.getString("sync_status"));
        s.setLastSuccessfulSyncAt(rs.getTimestamp("last_successful_sync_at"));
        s.setLastSuccessfulWindowEnd(rs.getString("last_successful_window_end"));
        s.setNextSyncAt(rs.getTimestamp("next_sync_at"));
        long currentTask = rs.getLong("current_task_id");
        s.setCurrentTaskId(rs.wasNull() ? null : currentTask);
        long pendingTask = rs.getLong("pending_ingest_task_id");
        s.setPendingIngestTaskId(rs.wasNull() ? null : pendingTask);
        s.setLastError(rs.getString("last_error"));
        s.setCreatedAt(rs.getTimestamp("created_at"));
        s.setUpdatedAt(rs.getTimestamp("updated_at"));
        return s;
    }

    public List<OrderEventDeliveryDto> dueDeliveries(Timestamp now, int limit) {
        String sql = "select d.id, d.event_id, d.subscriber_id, d.channel, d.target_url, d.status, d.attempts, "
                + "d.next_attempt_at, d.last_http_status, d.last_error, d.sent_at, d.created_at, d.updated_at, "
                + "e.event_id as event_public_id, e.payload "
                + "from order_event_delivery d join order_event e on d.event_id = e.id "
                + "where d.status in (?, ?) and (d.next_attempt_at is null or d.next_attempt_at <= ?) "
                + "order by d.id asc limit ?";
        return database.withConnection(conn -> {
            List<OrderEventDeliveryDto> list = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, OrderCodes.DELIVERY_PENDING);
                stmt.setInt(2, OrderCodes.DELIVERY_RETRY);
                stmt.setTimestamp(3, now);
                stmt.setInt(4, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        OrderEventDeliveryDto dto = new OrderEventDeliveryDto();
                        dto.setId(rs.getLong("id"));
                        dto.setEventId(rs.getLong("event_id"));
                        dto.setEventPublicId(rs.getString("event_public_id"));
                        dto.setSubscriberId(rs.getString("subscriber_id"));
                        dto.setChannel(rs.getString("channel"));
                        dto.setTargetUrl(rs.getString("target_url"));
                        dto.setStatus(rs.getInt("status"));
                        dto.setAttempts(rs.getInt("attempts"));
                        dto.setNextAttemptAt(rs.getTimestamp("next_attempt_at"));
                        int http = rs.getInt("last_http_status");
                        dto.setLastHttpStatus(rs.wasNull() ? null : http);
                        dto.setLastError(rs.getString("last_error"));
                        dto.setSentAt(rs.getTimestamp("sent_at"));
                        dto.setCreatedAt(rs.getTimestamp("created_at"));
                        dto.setUpdatedAt(rs.getTimestamp("updated_at"));
                        dto.setPayload(rs.getString("payload"));
                        list.add(dto);
                    }
                }
            }
            return list;
        });
    }

    public boolean claimDelivery(long deliveryId, Timestamp now) {
        String sql = "update order_event_delivery set status = ?, updated_at = ? where id = ? and status in (?, ?)";
        int updated = update(sql, OrderCodes.DELIVERY_IN_PROGRESS, now, deliveryId,
                OrderCodes.DELIVERY_PENDING, OrderCodes.DELIVERY_RETRY);
        return updated > 0;
    }

    public void updateDeliveryResult(long deliveryId, int status, int attempts, Timestamp nextAttemptAt,
                                     Integer lastHttpStatus, String lastError, Timestamp sentAt, Timestamp now) {
        String sql = "update order_event_delivery set status = ?, attempts = ?, next_attempt_at = ?, "
                + "last_http_status = ?, last_error = ?, sent_at = ?, updated_at = ? where id = ?";
        update(sql, status, attempts, nextAttemptAt, lastHttpStatus, trim(lastError, 500), sentAt, now, deliveryId);
    }

    public int recoverHangingDeliveries(Timestamp cutoff, Timestamp now) {
        String sql = "update order_event_delivery set status = ?, next_attempt_at = ?, updated_at = ? "
                + "where status = ? and updated_at < ?";
        return update(sql, OrderCodes.DELIVERY_RETRY, now, now,
                OrderCodes.DELIVERY_IN_PROGRESS, cutoff);
    }

    public int cleanExpiredEvents(Timestamp cutoff) {
        update("delete from order_event_delivery where created_at < ?", cutoff);
        return update("delete from order_event where created_at < ?", cutoff);
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

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.replaceAll("[^0-9.]", "").trim();
            if (cleaned.isEmpty()) return null;
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseQuantity(String raw) {
        if (raw == null || raw.isBlank()) return 1;
        try {
            String cleaned = raw.replaceAll("[^0-9]", "").trim();
            if (cleaned.isEmpty()) return 1;
            return Integer.parseInt(cleaned);
        } catch (Exception e) {
            return 1;
        }
    }

    private static String trim(String val, int max) {
        if (val == null) return null;
        val = val.trim();
        return val.length() > max ? val.substring(0, max) : val;
    }
}
