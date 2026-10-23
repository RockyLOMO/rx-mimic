package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Whitelist for durable union results. No material URL, encrypted ID or raw diagnostics survive. */
public final class UnionPersistencePolicy {
    private UnionPersistencePolicy() {}
    public static boolean unionAction(String action) {
        return action != null && (action.equals("jdUnionSearchProducts") || action.equals("tbUnionSearchProducts")
                || action.equals("getPromotionUrl") || action.equals("getTbPromotionUrl")
                || action.equals("getPromotionUrls") || action.equals("getTbPromotionUrls"));
    }

    public static JsonNode project(JsonNode node, int platform) {
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> array.add(project(item, platform)));
            return array;
        }
        ObjectNode clean = JsonNodeFactory.instance.objectNode();
        copy(node, clean, "status", "taskType", "profileName", "adSiteName", "mediaName", "createdAt",
                "loginRequired", "fingerprintPassed", "identityResolved", "cacheHit", "cachedAt", "cacheExpiresAt", "errorCode");
        if (node.path("fallback").isObject()) clean.set("fallback", project(node.path("fallback"), platform));
        if (node.has("items")) {
            ArrayNode items = clean.putArray("items");
            for (JsonNode item : node.path("items")) {
                String id = numericIdentity(item, platform);
                if (!id.isEmpty()) items.add(product(item, platform, id));
            }
            clean.put("resultCount", items.size());
            if (platform == PriceCodes.TB && node.has("totalCount")) clean.set("totalCount", node.get("totalCount"));
        } else if (!node.path("identityResolved").isBoolean() || node.path("identityResolved").asBoolean()) {
            String id = numericIdentity(node, platform);
            JsonNode info = node.path("productInfo");
            if (id.isEmpty()) id = numericIdentity(info, platform);
            if (!id.isEmpty()) {
                copy(node, clean, "promotionUrl", "promotionToken", "shortUrl", "longUrl");
                clean.put("productId", id);
                clean.put("keyword", id);
                ObjectNode productInfo = product(info, platform, id);
                productInfo.remove("skuId");
                productInfo.remove("itemId");
                clean.set("productInfo", productInfo);
            }
        }
        return clean;
    }

    public static String numericIdentity(JsonNode node, int platform) {
        if (node == null || !node.isObject()) return "";
        for (String key : new String[]{"productId", platform == PriceCodes.JD ? "skuId" : "itemId"}) {
            String value = node.path(key).asText("");
            if (ProductIdentityParser.isNumeric(value)) return value;
        }
        ProductIdentityParser.Hit url = ProductIdentityParser.inspect(platform, node.path("productLink").asText(""));
        return url.kind == ProductIdentityParser.PageKind.PRODUCT ? url.productId : "";
    }

    private static ObjectNode product(JsonNode item, int platform, String id) {
        ObjectNode clean = JsonNodeFactory.instance.objectNode();
        copy(item, clean, "productName", "storeName", "imageUrl", "price", "priceField", "commissionRate", "couponAmount", "monthSales", "identityResolved");
        clean.put("productId", id);
        clean.put(platform == PriceCodes.JD ? "skuId" : "itemId", id);
        clean.put("productLink", ProductIdentityParser.canonicalUrl(platform, id));
        return clean;
    }
    private static void copy(JsonNode from, ObjectNode to, String... keys) {
        for (String key : keys) if (from.has(key) && from.path(key).isValueNode()) to.set(key, from.get(key));
    }
}
