package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.rx.io.EntityDatabase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 与价格监控使用同一个数据库、同一张商品主表。缓存过期不删除上次成功结果。 */
@Repository
@DependsOn("priceWatchStore")
public class ProductCacheStore {
    private final EntityDatabase database;
    private final ObjectMapper mapper;
    private final PriceWatchStore products;

    public ProductCacheStore(@Qualifier("priceDatabase") EntityDatabase database, ObjectMapper mapper, PriceWatchStore products) {
        this.database = database;
        this.mapper = mapper;
        this.products = products;
    }

    @PostConstruct
    public void init() {
        database.executeUpdate("create table if not exists product_search_cache ("
                + "cache_key varchar(64) primary key, platform int not null, search_type int not null,"
                + "keyword varchar(2000) not null, parameters_json clob not null, result_json clob not null,"
                + "cached_at timestamp not null, expires_at timestamp not null)");
        database.executeUpdate("create table if not exists product_promotion_cache ("
                + "product_id bigint not null references price_product(id), ad_site_name varchar(128) not null,"
                + "promotion_token varchar(2000), short_url varchar(4000), long_url varchar(8000),"
                + "result_json clob not null, cached_at timestamp not null, expires_at timestamp not null,"
                + "primary key(product_id, ad_site_name))");
    }

    public record Entry(JsonNode result, Instant cachedAt, Instant expiresAt) {
        public boolean fresh(Instant now) { return expiresAt.isAfter(now); }
        public ObjectNode response(boolean hit) {
            ObjectNode value = result.deepCopy();
            value.put("cacheHit", hit);
            value.put("cachedAt", cachedAt.toEpochMilli());
            value.put("cacheExpiresAt", expiresAt.toEpochMilli());
            return value;
        }
    }

    public Entry search(String key) { return load("select result_json,cached_at,expires_at from product_search_cache where cache_key=?", key); }
    public Entry detail(int platform, String id) {
        return load("select detail_result_json,detail_cached_at,detail_expires_at from price_product where platform=? and platform_product_id=?", platform, id);
    }
    public Entry promotion(int platform, String id, String adSite) {
        return load("select c.result_json,c.cached_at,c.expires_at from product_promotion_cache c join price_product p on p.id=c.product_id where p.platform=? and p.platform_product_id=? and c.ad_site_name=?", platform, id, adSite);
    }

    public void saveSearch(String key, int platform, int type, String keyword, JsonNode parameters,
            JsonNode result, Instant now, Instant expires) {
        update("merge into product_search_cache(cache_key,platform,search_type,keyword,parameters_json,result_json,cached_at,expires_at) key(cache_key) values(?,?,?,?,?,?,?,?)",
                key, platform, type, keyword, parameters.toString(), result.toString(), Timestamp.from(now), Timestamp.from(expires));
    }
    public void saveDetail(int platform, String id, JsonNode result, Instant now, Instant expires) {
        update("update price_product set detail_result_json=?,detail_cached_at=?,detail_expires_at=? where platform=? and platform_product_id=?",
                result.toString(), Timestamp.from(now), Timestamp.from(expires), platform, id);
    }
    public void savePromotion(int platform, String id, String adSite, JsonNode result, Instant now, Instant expires) {
        PriceWatchStore.ProductHit product = products.findProduct(platform, id);
        if (product == null) throw new IllegalStateException("Numeric product must exist before promotion caching");
        update("merge into product_promotion_cache(product_id,ad_site_name,promotion_token,short_url,long_url,result_json,cached_at,expires_at) key(product_id,ad_site_name) values(?,?,?,?,?,?,?,?)",
                product.getProductId(), adSite, result.path("promotionToken").asText(null), result.path("shortUrl").asText(null),
                result.path("longUrl").asText(null), result.toString(), Timestamp.from(now), Timestamp.from(expires));
    }

    /** source=basic/union/detail；空字段不擦除已有资料，联盟抓取不能清除主站详情。 */
    public void mergeProduct(int platform, String id, ObjectNode basic, ObjectNode source, String kind, Instant now) {
        if (!ProductIdentityParser.isNumeric(id)) throw new IllegalArgumentException("Numeric product ID required");
        var product = products.upsertProduct(platform, id, basic.path("productName").asText(""),
                basic.path("storeName").asText(""), basic.path("imageUrl").asText(""), Timestamp.from(now));
        if (!kind.equals("basic") && !kind.equals("union") && !kind.equals("detail")) throw new IllegalArgumentException("Invalid product source");
        database.withConnection(connection -> {
            try (var select = connection.prepareStatement("select basic_json," + kind + "_json from price_product where id=? for update")) {
                select.setLong(1, product.getProductId());
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) throw new IllegalStateException("Product missing");
                    ObjectNode mergedBasic = merge(read(row.getString(1)), basic);
                    ObjectNode mergedSource = merge(read(row.getString(2)), source);
                    String sql = kind.equals("basic") ? "update price_product set basic_json=? where id=?"
                            : "update price_product set basic_json=?," + kind + "_json=? where id=?";
                    try (var write = connection.prepareStatement(sql)) {
                        write.setString(1, mergedBasic.toString());
                        if (kind.equals("basic")) write.setLong(2, product.getProductId());
                        else { write.setString(2, mergedSource.toString()); write.setLong(3, product.getProductId()); }
                        write.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    public List<Map<String, Object>> snapshots(int platform, String id, int days, java.time.ZoneId zone, Instant now) {
        PriceWatchStore.ProductHit product = products.findProduct(platform, id);
        List<PriceWatchStore.SnapshotRow> rows = product == null ? List.of()
                : products.dailySnapshots(product.getProductId(), days, zone, now);
        List<Map<String, Object>> list = new ArrayList<>();
        for (PriceWatchStore.SnapshotRow row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", row.getId());
            map.put("date", row.getObservedAt().toInstant().atZone(zone).toLocalDate().toString());
            map.put("rawPrice", row.getRawPrice());
            map.put("priceAmount", row.getPriceAmount());
            map.put("sourceField", row.getSourceField());
            map.put("priceKind", row.getPriceKind());
            map.put("comparable", row.isComparable());
            map.put("observedAt", row.getObservedAt().toInstant().toString());
            map.put("fetchStatus", row.getFetchStatus());
            map.put("qualityReason", row.getQualityReason());
            list.add(map);
        }
        return list;
    }

    public JsonNode product(int platform, String id) {
        return database.withConnection(connection -> {
            try (var statement = connection.prepareStatement("select id,basic_json,union_json,detail_json,created_at,last_seen_at from price_product where platform=? and platform_product_id=?")) {
                statement.setInt(1, platform); statement.setString(2, id);
                try (var row = statement.executeQuery()) {
                    if (!row.next()) return null;
                    ObjectNode result = mapper.createObjectNode().put("id", row.getLong(1)).put("platform", platform).put("productId", id);
                    result.set("basic", read(row.getString(2))); result.set("rebate", read(row.getString(3))); result.set("detail", read(row.getString(4)));
                    result.put("createdAt", row.getTimestamp(5).toInstant().toString());
                    result.put("updatedAt", row.getTimestamp(6).toInstant().toString());
                    return result;
                }
            }
        });
    }

    private ObjectNode read(String json) {
        try { return json == null ? mapper.createObjectNode() : (ObjectNode) mapper.readTree(json); }
        catch (Exception e) { throw new IllegalStateException("Invalid product payload", e); }
    }
    private ObjectNode merge(ObjectNode old, ObjectNode fresh) {
        fresh.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            if (!value.isNull() && !(value.isTextual() && value.asText().isBlank()) && !(value.isContainerNode() && value.isEmpty()))
                old.set(field.getKey(), value.deepCopy());
        });
        return old;
    }
    private Entry load(String sql, Object... args) {
        return database.withConnection(connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                try (var row = statement.executeQuery()) {
                    if (!row.next() || row.getString(1) == null) return null;
                    try { return new Entry(mapper.readTree(row.getString(1)), row.getTimestamp(2).toInstant(), row.getTimestamp(3).toInstant()); }
                    catch (Exception e) { throw new IllegalStateException("Invalid cache payload", e); }
                }
            }
        });
    }
    private void update(String sql, Object... args) {
        database.withConnection(connection -> { try (var statement = connection.prepareStatement(sql)) { bind(statement, args); statement.executeUpdate(); } return null; });
    }
    private static void bind(PreparedStatement statement, Object... args) throws java.sql.SQLException {
        for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
    }
}
