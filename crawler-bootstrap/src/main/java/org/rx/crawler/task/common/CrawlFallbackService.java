package org.rx.crawler.task.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.crawler.task.jd.JdImageInfo;
import org.rx.crawler.task.jd.JdProductDetailResult;
import org.rx.crawler.task.price.PriceCodes;
import org.rx.crawler.task.tb.TbImageInfo;
import org.rx.crawler.task.tb.TbProductDetailResult;
import org.rx.crawler.task.tb.TbProductSpecItem;
import org.rx.io.EntityDatabase;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 统一商品详情与转链兜底服务（淘宝、京东公共逻辑）。
 * 数据持久化于 H2 数据库，解决海量小 JSON 文件对文件系统与 IO 的性能压力，
 * 优先读取精准执行快照，若无快照则联动价格监控表（price_product / price_promotion_record / price_snapshot）提供降级兜底。
 */
@Slf4j
@Service
public class CrawlFallbackService {
    private static final String TYPE_PROMOTION = "PROMOTION";
    private static final String TYPE_DETAIL = "DETAIL";

    private final EntityDatabase database;
    private final ObjectMapper objectMapper;

    public CrawlFallbackService(EntityDatabase database, ObjectMapper objectMapper) {
        this.database = database;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (database == null) {
            return;
        }
        database.executeUpdate("create table if not exists crawl_fallback_record ("
                + "id bigint auto_increment primary key,"
                + "platform int not null,"
                + "record_type varchar(32) not null,"
                + "lookup_key varchar(256) not null,"
                + "payload_json clob not null,"
                + "updated_at timestamp not null)");
        database.executeUpdate("create unique index if not exists uk_crawl_fallback on crawl_fallback_record(platform, record_type, lookup_key)");
    }

    // -------------------------------------------------------------------------
    // 商品转链公共兜底（支持淘宝 PriceCodes.TB 与京东 PriceCodes.JD）
    // -------------------------------------------------------------------------

    public void finishPromotion(int platform, PromotionUrlResult result) {
        if (result == null) {
            return;
        }
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            savePromotion(platform, result);
            result.setFallback(null);
            return;
        }
        PromotionUrlResult previous = findPromotion(platform, result.getKeyword(), result.getItemId(), result.getMktItemId());
        clearPromotion(result);
        result.setFallback(previous);
    }

    private void savePromotion(int platform, PromotionUrlResult result) {
        if (database == null) {
            return;
        }
        PromotionUrlResult copy = copy(result, PromotionUrlResult.class);
        if (copy == null) {
            return;
        }
        copy.setFallback(null);
        copy.setMessage(null);
        copy.setCurrentUrl(null);
        copy.setProfileName(null);
        copy.setDiagnostics(new LinkedHashMap<String, Object>());

        String json = toJson(copy);
        if (json == null) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        saveFallbackRecord(platform, TYPE_PROMOTION, safeKey(result.getItemId()), json, now);
        saveFallbackRecord(platform, TYPE_PROMOTION, safeKey(result.getMktItemId()), json, now);
        saveFallbackRecord(platform, TYPE_PROMOTION, safeKey(result.getKeyword()), json, now);
    }

    private PromotionUrlResult findPromotion(int platform, String keyword, String itemId, String mktItemId) {
        if (database == null) {
            return null;
        }
        // 1. 优先从精准快照表中按 itemId, mktItemId, keyword 查找
        PromotionUrlResult cached = queryFallbackRecord(platform, TYPE_PROMOTION, safeKey(itemId), PromotionUrlResult.class);
        if (cached != null) {
            return cached;
        }
        cached = queryFallbackRecord(platform, TYPE_PROMOTION, safeKey(mktItemId), PromotionUrlResult.class);
        if (cached != null) {
            return cached;
        }
        cached = queryFallbackRecord(platform, TYPE_PROMOTION, safeKey(keyword), PromotionUrlResult.class);
        if (cached != null) {
            return cached;
        }

        // 2. 借助价格监控模块（price_product / price_product_alias / price_promotion_record / price_snapshot）兜底
        return findPromotionFromPriceWatch(platform, keyword, itemId, mktItemId);
    }

    private PromotionUrlResult findPromotionFromPriceWatch(int platform, String keyword, String itemId, String mktItemId) {
        String key1 = safeKey(itemId);
        String key2 = safeKey(mktItemId);
        String key3 = safeKey(keyword);
        if (key1.isEmpty() && key2.isEmpty() && key3.isEmpty()) {
            return null;
        }

        String sql = "select p.platform_product_id, p.title, p.shop_name, p.image_url, "
                + "a.union_product_id, a.promotion_url as alias_url, "
                + "r.promotion_url as record_url, r.ad_site_name, "
                + "s.raw_price "
                + "from price_product p "
                + "left join price_product_alias a on a.product_id = p.id "
                + "left join price_promotion_record r on r.product_id = p.id "
                + "left join price_snapshot s on s.product_id = p.id "
                + "where p.platform = ? and ("
                + "p.platform_product_id in (?, ?, ?) or a.union_product_id in (?, ?, ?)"
                + ") order by s.observed_at desc nulls last, r.created_at desc nulls last limit 1";

        try {
            return database.withConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setInt(1, platform);
                    stmt.setString(2, key1);
                    stmt.setString(3, key2);
                    stmt.setString(4, key3);
                    stmt.setString(5, key1);
                    stmt.setString(6, key2);
                    stmt.setString(7, key3);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            return null;
                        }
                        PromotionUrlResult fallback = new PromotionUrlResult();
                        fallback.setStatus(CustomCrawlStatus.SUCCESS);
                        fallback.setTaskType(platform == PriceCodes.JD ? "jdUnionPromotionUrl" : "getTbPromotionUrl");
                        fallback.setItemId(rs.getString("platform_product_id"));
                        String matchedUnionId = rs.getString("union_product_id");
                        fallback.setKeyword(!Strings.isEmpty(matchedUnionId) ? matchedUnionId : keyword);

                        String promoUrl = rs.getString("record_url");
                        if (Strings.isEmpty(promoUrl)) {
                            promoUrl = rs.getString("alias_url");
                        }
                        fallback.setPromotionUrl(promoUrl);
                        fallback.setAdSiteName(rs.getString("ad_site_name"));

                        ProductInfoDto info = new ProductInfoDto();
                        info.setProductName(rs.getString("title"));
                        info.setStoreName(rs.getString("shop_name"));
                        info.setImageUrl(rs.getString("image_url"));
                        info.setPrice(rs.getString("raw_price"));
                        info.setProductLink(promoUrl);
                        fallback.setProductInfo(info);
                        return fallback;
                    }
                }
            });
        } catch (Exception ignored) {
            // 价格监控表在极端测试场景下若未初始化，直接返回 null
            return null;
        }
    }

    private void clearPromotion(PromotionUrlResult result) {
        result.setItemId(null);
        result.setMktItemId(null);
        result.setProductInfo(null);
        result.setPromotionUrl(null);
    }

    // -------------------------------------------------------------------------
    // 淘宝商品详情兜底
    // -------------------------------------------------------------------------

    public void finishTbDetail(TbProductDetailResult result) {
        if (result == null) {
            return;
        }
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            saveTbDetail(result);
            result.setFallback(null);
            return;
        }
        TbProductDetailResult previous = findTbDetail(result.getItemId());
        clearTbDetail(result);
        result.setFallback(previous);
    }

    private void saveTbDetail(TbProductDetailResult result) {
        if (database == null) {
            return;
        }
        String key = safeKey(result.getItemId());
        if (key.isEmpty()) {
            return;
        }
        TbProductDetailResult copy = copy(result, TbProductDetailResult.class);
        if (copy == null) {
            return;
        }
        copy.setFallback(null);
        copy.setMessage(null);
        copy.setCurrentUrl(null);
        copy.setProfileName(null);
        copy.setDiagnostics(new LinkedHashMap<String, Object>());
        stripTbImages(copy.getMainImages());
        stripTbImages(copy.getDetailImages());

        String json = toJson(copy);
        if (json == null) {
            return;
        }
        saveFallbackRecord(PriceCodes.TB, TYPE_DETAIL, key, json, Timestamp.from(Instant.now()));
    }

    private TbProductDetailResult findTbDetail(String itemId) {
        if (database == null) {
            return null;
        }
        String key = safeKey(itemId);
        if (key.isEmpty()) {
            return null;
        }
        TbProductDetailResult cached = queryFallbackRecord(PriceCodes.TB, TYPE_DETAIL, key, TbProductDetailResult.class);
        if (cached != null) {
            return cached;
        }
        return findDetailFromPriceWatch(PriceCodes.TB, key, TbProductDetailResult.class);
    }

    private void clearTbDetail(TbProductDetailResult result) {
        result.setTitle("");
        result.setPrice("");
        result.setShopName("");
        result.setMainImages(new ArrayList<TbImageInfo>());
        result.setDetailImages(new ArrayList<TbImageInfo>());
        result.setSpecs(new ArrayList<TbProductSpecItem>());
    }

    // -------------------------------------------------------------------------
    // 京东商品详情兜底
    // -------------------------------------------------------------------------

    public void finishJdDetail(JdProductDetailResult result) {
        if (result == null) {
            return;
        }
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            saveJdDetail(result);
            result.setFallback(null);
            return;
        }
        JdProductDetailResult previous = findJdDetail(result.getSkuId());
        clearJdDetail(result);
        result.setFallback(previous);
    }

    private void saveJdDetail(JdProductDetailResult result) {
        if (database == null) {
            return;
        }
        String key = safeKey(result.getSkuId());
        if (key.isEmpty()) {
            return;
        }
        JdProductDetailResult copy = copy(result, JdProductDetailResult.class);
        if (copy == null) {
            return;
        }
        copy.setFallback(null);
        copy.setMessage(null);
        copy.setCurrentUrl(null);
        copy.setProfileName(null);
        copy.setDiagnostics(new LinkedHashMap<String, Object>());
        stripJdImages(copy.getMainImages());
        stripJdImages(copy.getDetailImages());

        String json = toJson(copy);
        if (json == null) {
            return;
        }
        saveFallbackRecord(PriceCodes.JD, TYPE_DETAIL, key, json, Timestamp.from(Instant.now()));
    }

    private JdProductDetailResult findJdDetail(String skuId) {
        if (database == null) {
            return null;
        }
        String key = safeKey(skuId);
        if (key.isEmpty()) {
            return null;
        }
        JdProductDetailResult cached = queryFallbackRecord(PriceCodes.JD, TYPE_DETAIL, key, JdProductDetailResult.class);
        if (cached != null) {
            return cached;
        }
        return findDetailFromPriceWatch(PriceCodes.JD, key, JdProductDetailResult.class);
    }

    private void clearJdDetail(JdProductDetailResult result) {
        result.setTitle("");
        result.setPrice("");
        result.setShopName("");
        result.setGoodRate("");
        result.setMainImages(new ArrayList<JdImageInfo>());
        result.setDetailImages(new ArrayList<JdImageInfo>());
        result.setSpecs(new ArrayList<org.rx.crawler.task.jd.JdProductSpecItem>());
    }

    // -------------------------------------------------------------------------
    // 价格监控表详情辅助兜底
    // -------------------------------------------------------------------------

    private <T> T findDetailFromPriceWatch(int platform, String productId, Class<T> targetClass) {
        String sql = "select p.platform_product_id, p.title, p.shop_name, p.image_url, s.raw_price "
                + "from price_product p "
                + "left join price_snapshot s on s.product_id = p.id "
                + "where p.platform = ? and p.platform_product_id = ? "
                + "order by s.observed_at desc nulls last limit 1";
        try {
            return database.withConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setInt(1, platform);
                    stmt.setString(2, productId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            return null;
                        }
                        if (targetClass == TbProductDetailResult.class) {
                            TbProductDetailResult r = new TbProductDetailResult();
                            r.setStatus(CustomCrawlStatus.SUCCESS);
                            r.setItemId(rs.getString("platform_product_id"));
                            r.setTitle(rs.getString("title"));
                            r.setShopName(rs.getString("shop_name"));
                            r.setPrice(rs.getString("raw_price"));
                            r.setProductUrl("https://item.taobao.com/item.htm?id=" + rs.getString("platform_product_id"));
                            String img = rs.getString("image_url");
                            if (!Strings.isEmpty(img)) {
                                TbImageInfo imageInfo = new TbImageInfo();
                                imageInfo.setUrl(img);
                                r.getMainImages().add(imageInfo);
                            }
                            return targetClass.cast(r);
                        } else if (targetClass == JdProductDetailResult.class) {
                            JdProductDetailResult r = new JdProductDetailResult();
                            r.setStatus(CustomCrawlStatus.SUCCESS);
                            r.setSkuId(rs.getString("platform_product_id"));
                            r.setTitle(rs.getString("title"));
                            r.setShopName(rs.getString("shop_name"));
                            r.setPrice(rs.getString("raw_price"));
                            r.setProductUrl("https://item.jd.com/" + rs.getString("platform_product_id") + ".html");
                            String img = rs.getString("image_url");
                            if (!Strings.isEmpty(img)) {
                                JdImageInfo imageInfo = new JdImageInfo();
                                imageInfo.setUrl(img);
                                r.getMainImages().add(imageInfo);
                            }
                            return targetClass.cast(r);
                        }
                        return null;
                    }
                }
            });
        } catch (Exception ignored) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // H2 基础读写工具
    // -------------------------------------------------------------------------

    private void saveFallbackRecord(int platform, String recordType, String lookupKey, String payloadJson, Timestamp now) {
        if (lookupKey.isEmpty() || payloadJson == null) {
            return;
        }
        String sql = "merge into crawl_fallback_record (platform, record_type, lookup_key, payload_json, updated_at) "
                + "key(platform, record_type, lookup_key) values (?, ?, ?, ?, ?)";
        try {
            database.withConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setInt(1, platform);
                    stmt.setString(2, recordType);
                    stmt.setString(3, lookupKey);
                    stmt.setString(4, payloadJson);
                    stmt.setTimestamp(5, now);
                    stmt.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Save crawl fallback record fail, platform={}, type={}, key={}, error={}",
                    platform, recordType, lookupKey, e.getMessage());
        }
    }

    private <T> T queryFallbackRecord(int platform, String recordType, String lookupKey, Class<T> targetClass) {
        if (lookupKey.isEmpty()) {
            return null;
        }
        String sql = "select payload_json from crawl_fallback_record where platform = ? and record_type = ? and lookup_key = ?";
        try {
            return database.withConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setInt(1, platform);
                    stmt.setString(2, recordType);
                    stmt.setString(3, lookupKey);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            String json = rs.getString("payload_json");
                            if (!Strings.isEmpty(json)) {
                                return objectMapper.readValue(json, targetClass);
                            }
                        }
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Query crawl fallback record fail, platform={}, type={}, key={}, error={}",
                    platform, recordType, lookupKey, e.getMessage());
            return null;
        }
    }

    private static String safeKey(String key) {
        if (key == null) {
            return "";
        }
        String trimmed = key.trim();
        return trimmed.length() > 250 ? trimmed.substring(0, 250) : trimmed;
    }

    private static void stripTbImages(List<TbImageInfo> images) {
        if (images == null) {
            return;
        }
        for (TbImageInfo image : images) {
            if (image != null) {
                image.setLocalPath(null);
                image.setFileName(null);
            }
        }
    }

    private static void stripJdImages(List<JdImageInfo> images) {
        if (images == null) {
            return;
        }
        for (JdImageInfo image : images) {
            if (image != null) {
                image.setLocalPath(null);
                image.setFileName(null);
            }
        }
    }

    private <T> T copy(Object value, Class<T> type) {
        try {
            return objectMapper.convertValue(value, type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
