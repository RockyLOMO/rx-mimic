package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.rx.crawler.task.common.TaskApiException;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Normalize before the durable queue; promotional URLs exist only in bounded live responses. */
@Service
@RequiredArgsConstructor
public class UnionMaterialBoundary {
    private final ObjectMapper mapper;
    private final ProductIdentityResolver resolver;
    private final BoundedMemoryCache<JsonNode> live = new BoundedMemoryCache<>(500, 2 * 1024 * 1024);

    public Object request(String action, Object request) {
        boolean detail = "jdProductDetail".equals(action) || "tbProductDetail".equals(action);
        boolean search = "jdSearchProduct".equals(action) || "tbSearchProduct".equals(action);
        if (!UnionPersistencePolicy.unionAction(action) && !detail && !search) return request;
        JsonNode node = mapper.valueToTree(request);
        int platform = platform(action);
        if (detail) {
            ObjectNode object = (ObjectNode) node;
            String key = platform == PriceCodes.JD ? "skuId" : "itemId";
            String id = object.path(key).asText("").trim();
            String url = object.path("productUrl").asText("").trim();
            String profile = object.path("profileName").asText("common");
            String numeric = resolver.resolve(platform,profile,id.isEmpty()?url:id);
            if (!url.isEmpty() && !numeric.equals(resolver.resolve(platform,profile,url)))
                throw new TaskApiException(409,"PRODUCT_ID_MISMATCH","PRODUCT_ID_MISMATCH");
            object.put(key,numeric);
            object.put("productUrl",ProductIdentityParser.canonicalUrl(platform,numeric));
            return object;
        }
        // Promotion keywords stay on the queued task. Resolving them here opens Chrome
        // before POST /tasks can return 202, and the public gateway closes at 30 seconds.
        if (promotionAction(action)) return node;
        if (node.isArray()) {
            var normalized = mapper.createArrayNode();
            for (JsonNode input : node) {
                String value = input.asText("").trim();
                normalized.add(org.rx.crawler.task.tb.TbUnionAnalysisPage.isProductInput(value)
                        ? resolver.resolve(platform, "common", value) : value);
            }
            return normalized;
        }
        ObjectNode object = (ObjectNode) node;
        String value = object.path("keyword").asText("").trim();
        if (org.rx.crawler.task.tb.TbUnionAnalysisPage.isProductInput(value)) {
            String id = resolver.resolve(platform, object.path("profileName").asText("common"), value);
            object.put("keyword", id);
        }
        // No arbitrary extension fields: only the known request DTO is serialized by the caller.
        return object;
    }

    public JsonNode result(String action, Object result, long taskId) {
        JsonNode node = mapper.valueToTree(result);
        if (!UnionPersistencePolicy.unionAction(action)) return node;
        int platform = platform(action);
        resolvePromotion(node, platform);
        JsonNode persistent = UnionPersistencePolicy.project(node, platform);
        JsonNode response = persistent.deepCopy();
        appendEphemeral(node, response, platform);
        live.put(Long.toString(taskId), response, Duration.ofHours(6), response.toString().length() * 2);
        return persistent;
    }

    private void appendEphemeral(JsonNode original, JsonNode response, int platform) {
        if (original.isArray()) {
            for (int i = 0; i < original.size(); i++) appendEphemeral(original.get(i), response.get(i), platform);
            return;
        }
        if (!response.isObject()) return;
        ObjectNode object = (ObjectNode) response;
        if (platform == PriceCodes.TB && !"SUCCESS".equals(original.path("status").asText())) {
            object.put("failureCode", taobaoFailureCode(original));
        }
        for (String field : new String[]{"promotionUrl", "shortUrl", "promotionToken"}) {
            if (original.has(field)) object.set(field, original.get(field));
        }
        if (original.has("items")) {
            var items = mapper.createArrayNode();
            for (JsonNode raw : original.path("items")) {
                String id = UnionPersistencePolicy.numericIdentity(raw, platform);
                if (!id.isEmpty()) {
                    ObjectNode wrapper = mapper.createObjectNode();
                    wrapper.putArray("items").add(raw);
                    items.add(UnionPersistencePolicy.project(wrapper, platform).path("items").get(0));
                } else {
                    ObjectNode item = mapper.createObjectNode();
                    // Explicitly transient: never part of the persisted result projection.
                    item.put("encryptedProductId", raw.path(platform == PriceCodes.JD ? "skuId" : "itemId").asText(""));
                    for (String key : new String[]{"productName", "storeName", "imageUrl", "price", "priceField", "commissionRate"})
                        if (raw.has(key)) item.set(key, raw.get(key));
                    item.put("identityResolved", false);
                    items.add(item);
                }
            }
            object.set("items", items);
            object.put("resultCount", items.size());
        }
    }

    /** Constant diagnostics only, in the bounded live response; raw gateway messages never escape. */
    private static boolean promotionAction(String action) {
        return "getPromotionUrl".equals(action) || "getTbPromotionUrl".equals(action)
                || "getPromotionUrls".equals(action) || "getTbPromotionUrls".equals(action);
    }

    private static String taobaoFailureCode(JsonNode original) {
        if ("NO_REBATE_PRODUCT".equals(original.path("errorCode").asText())) return "NO_REBATE_PRODUCT";
        JsonNode diagnostics = original.path("diagnostics");
        String ajax = diagnostics.path("ajaxPromotion").asText("");
        if (ajax.startsWith("first search result is item ")) return "TB_GATEWAY_NUMERIC_MISMATCH";
        if (ajax.startsWith("search before promote returned no item")) return "TB_NUMERIC_SEARCH_EMPTY";
        if (ajax.startsWith("search before promote failed")) return "TB_NUMERIC_SEARCH_FAILED";
        if (ajax.startsWith("xt.entry resultList empty")) return "TB_PROMOTION_RESULT_EMPTY";
        if (ajax.startsWith("xt.entry returned no promotion url")) return "TB_PROMOTION_LINK_MISSING";
        if (ajax.startsWith("xt.entry ")) return "TB_PROMOTION_GATEWAY_FAILED";
        if (ajax.startsWith("adzone ")) return "TB_ADZONE_UNAVAILABLE";
        if (diagnostics.has("ajaxPromotionValidation")) return "TB_PROMOTION_FIELDS_INVALID";
        return "TB_PROMOTION_FAILED";
    }

    private void resolvePromotion(JsonNode node, int platform) {
        if (node.isArray()) { node.forEach(item -> resolvePromotion(item, platform)); return; }
        if (!node.isObject() || !node.has("promotionUrl") || !"SUCCESS".equals(node.path("status").asText())) return;
        // 只有服务端成功缓存会带此标记；重启后命中数据库缓存不再打开推广落地页。
        if (node.path("cacheHit").asBoolean(false) && node.path("identityResolved").asBoolean(false)
                && ProductIdentityParser.isNumeric(node.path("productId").asText(""))) return;
        String profile = node.path("profileName").asText("common");
        String input = node.path("promotionUrl").asText("");
        // The coupon short link remains the public output. Verify the ordinary product promotion
        // from the same generated dialog instead of navigating to the coupon login/activity page.
        String identityInput = platform == PriceCodes.JD
                ? node.path("diagnostics").path("identityPromotionUrl").asText("") : "";
        if (identityInput.isBlank()) identityInput = input;
        try {
            String id = resolver.resolve(platform, profile, identityInput);
            String expected = node.path("keyword").asText("");
            if (ProductIdentityParser.isNumeric(expected) && !expected.equals(id)) {
                throw new TaskApiException(409, "PRODUCT_ID_MISMATCH", "PRODUCT_ID_MISMATCH");
            }
            ((ObjectNode) node).put("productId", id);
            ((ObjectNode) node).put("identityResolved", true);
            if (!input.equals(identityInput)) resolver.remember(platform, profile, input, id);
            resolver.remember(platform, profile, node.path("keyword").asText(""), id);
            String encrypted = node.path("encryptedProductId").asText("");
            resolver.remember(platform, profile, encrypted, id);
        } catch (TaskApiException e) {
            if ("LOGIN_REQUIRED".equals(e.getCode())) {
                ((ObjectNode) node).put("status", "LOGIN_REQUIRED").put("loginRequired", true)
                        .put("errorCode", "LOGIN_REQUIRED");
            }
            ((ObjectNode) node).put("identityResolved", false);
            // The link generation succeeded; do not claim that identity/price tracking succeeded as well.
            ((ObjectNode) node).remove("productId");
            ((ObjectNode) node).remove("itemId");
            ((ObjectNode) node).put("keyword", "");
            ((ObjectNode) node).remove("productInfo");
        }
    }

    public JsonNode liveResult(long id) {
        JsonNode value = live.get(Long.toString(id));
        return value == null ? null : value.deepCopy();
    }

    public void cacheBatch(long parentId, JsonNode durable) {
        JsonNode response = durable.deepCopy();
        for (JsonNode child : response) {
            JsonNode cached = liveResult(child.path("taskId").asLong());
            if (cached != null && child.isObject()) ((ObjectNode) child).set("result", cached);
        }
        live.put(Long.toString(parentId), response, Duration.ofHours(6), response.toString().length() * 2);
    }

    public static int platform(String action) {
        return action.startsWith("tb") || action.startsWith("getTb") ? PriceCodes.TB : PriceCodes.JD;
    }
}
