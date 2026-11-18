package org.rx.crawler.task.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.rx.io.EntityDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 按 (caller_id, device_id) 持久化客户端返现转链队列。
 */
@Slf4j
@Component
public class ClientCashbackQueueStore {
    public static final String TABLE_NAME = "client_cashback_queue";
    public static final int MAX_QUEUE_SIZE = 100;
    public static final int MAX_DEVICE_ID_LENGTH = 128;
    public static final int MAX_PAYLOAD_CHARS = 2 * 1024 * 1024;

    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");

    private final EntityDatabase database;
    private final ObjectMapper mapper;

    @Autowired
    public ClientCashbackQueueStore(EntityDatabase database, @Autowired(required = false) ObjectMapper mapper) {
        this.database = database;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        database.executeUpdate("create table if not exists " + TABLE_NAME + " ("
                + "caller_id varchar(128) not null,"
                + "device_id varchar(128) not null,"
                + "queue_json clob not null,"
                + "item_count int not null default 0,"
                + "updated_at timestamp not null,"
                + "primary key (caller_id, device_id)"
                + ")");
    }

    public static String normalizeDeviceId(String rawDeviceId) {
        if (rawDeviceId == null) {
            throw new TaskApiException(400, "INVALID_DEVICE_ID", "Invalid or missing deviceId");
        }
        String trimmed = rawDeviceId.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_DEVICE_ID_LENGTH
                || !DEVICE_ID_PATTERN.matcher(trimmed).matches()) {
            throw new TaskApiException(400, "INVALID_DEVICE_ID", "Invalid or missing deviceId");
        }
        return trimmed;
    }

    public ArrayNode loadQueue(String callerId, String rawDeviceId) {
        String caller = requireCaller(callerId);
        String deviceId = normalizeDeviceId(rawDeviceId);
        String rawJson = database.withConnection(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "select queue_json from " + TABLE_NAME + " where caller_id=? and device_id=?")) {
                stmt.setString(1, caller);
                stmt.setString(2, deviceId);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
        if (rawJson == null || rawJson.isBlank()) {
            return mapper.createArrayNode();
        }
        try {
            JsonNode parsed = mapper.readTree(rawJson);
            if (!(parsed instanceof ArrayNode array)) {
                log.warn("Stored cashback queue is not a JSON array for caller={}, deviceId={}", caller, deviceId);
                return mapper.createArrayNode();
            }
            if (array.size() <= MAX_QUEUE_SIZE) {
                return array;
            }
            ArrayNode truncated = mapper.createArrayNode();
            for (int i = 0; i < MAX_QUEUE_SIZE; i++) {
                truncated.add(array.get(i));
            }
            return truncated;
        } catch (Exception e) {
            log.warn("Failed to parse stored cashback queue for caller={}, deviceId={}: {}", caller, deviceId, e.getMessage());
            return mapper.createArrayNode();
        }
    }

    public ArrayNode saveQueue(String callerId, String rawDeviceId, JsonNode payload) {
        String caller = requireCaller(callerId);
        String deviceId = normalizeDeviceId(rawDeviceId);
        ArrayNode sourceArray = extractArray(payload);
        ArrayNode normalized = mapper.createArrayNode();
        int limit = Math.min(sourceArray.size(), MAX_QUEUE_SIZE);
        for (int i = 0; i < limit; i++) {
            JsonNode item = sourceArray.get(i);
            if (!(item instanceof ObjectNode obj)
                    || !hasNonBlankText(obj, "id")
                    || !hasNonBlankText(obj, "rawContent")
                    || !hasNonBlankText(obj, "extractedUrl")
                    || !hasNonBlankText(obj, "platform")) {
                throw new TaskApiException(400, "INVALID_REQUEST", "Invalid cashback queue item");
            }
            normalized.add(obj);
        }
        String json = normalized.toString();
        if (json.length() > MAX_PAYLOAD_CHARS) {
            throw new TaskApiException(400, "INVALID_REQUEST", "Queue payload too large");
        }
        Timestamp now = Timestamp.from(Instant.now());
        database.withConnection(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "merge into " + TABLE_NAME
                            + "(caller_id, device_id, queue_json, item_count, updated_at) key(caller_id, device_id) values(?, ?, ?, ?, ?)")) {
                stmt.setString(1, caller);
                stmt.setString(2, deviceId);
                stmt.setString(3, json);
                stmt.setInt(4, normalized.size());
                stmt.setTimestamp(5, now);
                stmt.executeUpdate();
            }
            return null;
        });
        return normalized;
    }

    private ArrayNode extractArray(JsonNode payload) {
        if (payload instanceof ArrayNode array) {
            return array;
        }
        if (payload instanceof ObjectNode obj && obj.get("items") instanceof ArrayNode items) {
            return items;
        }
        throw new TaskApiException(400, "INVALID_REQUEST", "Queue payload must be a JSON array");
    }

    private static boolean hasNonBlankText(ObjectNode obj, String field) {
        JsonNode node = obj.get(field);
        return node != null && node.isTextual() && !node.asText().isBlank();
    }

    private static String requireCaller(String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new TaskApiException(401, "UNAUTHORIZED", "UNAUTHORIZED");
        }
        return callerId.trim();
    }
}
