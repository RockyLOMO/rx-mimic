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
        if (!UnionPersistencePolicy.unionAction(action)) return request;
        JsonNode node = mapper.valueToTree(request);
        int platform = platform(action);
        if (node.isArray()) {
            var normalized = mapper.createArrayNode();
            for (JsonNode input : node) normalized.add(resolver.resolve(platform, "common", input.asText()));
            return normalized;
        }
        ObjectNode object = (ObjectNode) node;
        String value = object.path("keyword").asText("").trim();
        boolean promotion = !action.endsWith("SearchProducts");
        if (promotion || ProductIdentityResolver.isEncrypted(value) || value.startsWith("http")) {
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
        if (original.has("promotionUrl")) object.set("promotionUrl", original.get("promotionUrl"));
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
    private static String taobaoFailureCode(JsonNode original) {
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
        String profile = node.path("profileName").asText("common");
        String input = node.path("promotionUrl").asText("");
        try {
            String id = resolver.resolve(platform, profile, input);
            String expected = node.path("keyword").asText("");
            if (ProductIdentityParser.isNumeric(expected) && !expected.equals(id)) {
                throw new TaskApiException(409, "PRODUCT_ID_MISMATCH", "PRODUCT_ID_MISMATCH");
            }
            ((ObjectNode) node).put("productId", id);
            ((ObjectNode) node).put("identityResolved", true);
            resolver.remember(platform, profile, node.path("keyword").asText(""), id);
            String encrypted = node.path("encryptedProductId").asText("");
            resolver.remember(platform, profile, encrypted, id);
        } catch (TaskApiException e) {
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
