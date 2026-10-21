package org.rx.crawler.task.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.jd.JdMallConfig;
import org.rx.crawler.task.jd.JdUnionConfig;
import org.rx.crawler.task.tb.TbMallConfig;
import org.rx.crawler.task.tb.TbPromotionConfig;

/**
 * 队列任务真正第一次打开的页面。商城搜索先打开首页再输入关键词，不是搜索结果地址。
 * 解析失败返回 null，调用方就跳过预加载。
 */
public final class TaskEntryUrls {
    private TaskEntryUrls() {
    }

    public static String resolve(String action, String requestJson, AppConfig appConfig, ObjectMapper mapper) {
        if (Strings.isEmpty(action) || appConfig == null || appConfig.getCustom() == null) {
            return null;
        }
        AppConfig.CustomTaskConfig custom = appConfig.getCustom();
        JsonNode node = read(requestJson, mapper);
        JdUnionConfig jdUnion = custom.getJdUnion();
        JdMallConfig jdMall = custom.getJdMall();
        TbPromotionConfig tbPromotion = custom.getTbPromotion();
        TbMallConfig tbMall = custom.getTbMall();
        if ("jdUnionSearchProducts".equals(action) || "getPromotionUrl".equals(action)
                || "getPromotionUrls".equals(action) || "loginCheck".equals(action)
                || "getPromotionOrders".equals(action)) {
            return jdUnion == null ? null : jdUnion.getEntireUrl();
        }
        if ("jdSearchProduct".equals(action)) {
            return jdMall == null ? null : jdMall.getHomeUrl();
        }
        if ("jdProductDetail".equals(action)) {
            return detailUrl(node, jdMall == null ? null : jdMall.getItemUrlTemplate(), "skuId");
        }
        if ("tbUnionSearchProducts".equals(action) || "getTbPromotionUrl".equals(action)
                || "getTbPromotionUrls".equals(action)) {
            return tbPromotion == null ? null : tbPromotion.getPromotionGoodsUrl();
        }
        if ("getTbPromotionOrders".equals(action)) {
            return tbPromotion == null ? null : tbPromotion.getOrderUrl();
        }
        if ("tbSearchProduct".equals(action)) {
            return tbMall == null ? null : tbMall.getHomeUrl();
        }
        if ("tbProductDetail".equals(action)) {
            return detailUrl(node, tbMall == null ? null : tbMall.getItemUrlTemplate(), "itemId");
        }
        return null;
    }

    private static String detailUrl(JsonNode node, String template, String idField) {
        String productUrl = text(node, "productUrl");
        if (productUrl.startsWith("http://") || productUrl.startsWith("https://")) {
            return productUrl;
        }
        String id = text(node, idField);
        if (Strings.isEmpty(template) || !id.matches("\\d+")) {
            return null;
        }
        String token = "skuId".equals(idField) ? "{skuId}" : "{itemId}";
        return template.replace(token, id);
    }

    private static JsonNode read(String requestJson, ObjectMapper mapper) {
        if (mapper == null || Strings.isEmpty(requestJson)) {
            return null;
        }
        try {
            return mapper.readTree(requestJson);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        String value = node.get(field).asText("");
        return value == null ? "" : value.trim();
    }
}
