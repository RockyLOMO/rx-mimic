package org.rx.crawler.task.jd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.ProductInfoDto;
import org.rx.crawler.task.common.PromotionOrderItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 在已打开的京东联盟页面里调用页面自己的 color 网关。
 * 签名用页面上的 ParamsSign，cookie 走浏览器，不另起 HTTP 客户端。
 */
@Slf4j
final class JdUnionPageApi {
    private static final int GUIDE_MEDIA_TYPE = 3;
    private static final int ORDER_PAGE_SIZE = 20;
    private static final int ORDER_MAX_PAGES = 10;

    private final ObjectMapper objectMapper;

    JdUnionPageApi(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    SearchHit searchProducts(Browser browser, String keyword, int maxResults) {
        int size = Math.max(1, Math.min(40, maxResults));
        ObjectNode param = objectMapper.createObjectNode();
        param.put("keyword", keyword);
        param.put("requestScene", 0);
        param.putArray("requestExtFields").add("shopInfo").add("orientations");
        param.put("source", 20310);
        ObjectNode page = objectMapper.createObjectNode();
        page.put("pageNo", 1);
        page.put("pageSize", size);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("funName", "search");
        params.set("page", page);
        params.set("param", param);
        ColorCall call = color(browser, "unionSearchGoods", "GET", "unionpc", params);
        if (!call.ok) {
            return SearchHit.failed(call.diagnostic);
        }
        List<JdUnionProductItem> items = parseSearchItems(unwrap(call.body), size);
        if (items == null) {
            return SearchHit.failed("search response shape not recognized " + call.diagnostic + " " + outline(call.body));
        }
        return SearchHit.ok(items, call.diagnostic);
    }

    PromotionHit promote(Browser browser, String sku, String mediaName, String adSiteName, String siteId,
            String mediaId, String mediaTypeLabel) {
        int mediaType = mediaTypeOf(mediaTypeLabel);
        SearchHit found = searchProducts(browser, sku, 8);
        JdUnionProductItem matched = null;
        if (found.success) {
            for (JdUnionProductItem item : found.items) {
                if (sku.equals(item.getSkuId())) {
                    matched = item;
                    break;
                }
            }
        }
        if (matched == null) {
            return PromotionHit.failed("ajax search did not return sku " + sku
                    + (found.success ? "" : ": " + found.diagnostic));
        }
        JsonNode medias = listMedias(browser, mediaType);
        JsonNode media = pickMedia(medias, mediaName, mediaId);
        if (media == null) {
            return PromotionHit.failed("guide media list empty");
        }
        String resolvedMediaId = text(media, "id");
        JsonNode sites = listSites(browser, resolvedMediaId, sku, mediaType);
        JsonNode listed = pickSite(sites, adSiteName, siteId);
        JsonNode site = listed != null ? listed : resolveSite(null, adSiteName, siteId, objectMapper);
        if (site == null) {
            return PromotionHit.failed("promotion site " + adSiteName + " not found for media "
                    + text(media, "mediaName", "name"));
        }
        String siteSource = listed != null ? "list" : "config";
        ObjectNode codeParam = objectMapper.createObjectNode();
        codeParam.put("materialId", sku);
        codeParam.put("materialType", 1);
        codeParam.put("promotionId", text(site, "id"));
        codeParam.put("promotionType", mediaType);
        codeParam.put("promotionTypeId", resolvedMediaId);
        codeParam.put("receiveType", "cps");
        codeParam.put("wareUrl", "http://item.jd.com/" + sku + ".html");
        codeParam.put("ext1", "618%7Cpc%7Citem");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("funName", "getCode");
        params.set("param", codeParam);
        ColorCall call = color(browser, "unionPromoteLinkService", "POST", "unionpc", params);
        if (!call.ok) {
            return PromotionHit.failed("getCode " + call.diagnostic);
        }
        String url = findPromotionUrl(unwrap(call.body));
        if (Strings.isEmpty(url)) {
            url = findPromotionUrl(call.body);
        }
        if (Strings.isEmpty(url)) {
            return PromotionHit.failed("getCode returned no promotion url");
        }
        ProductInfoDto info = toProductInfo(matched);
        return PromotionHit.ok(url, info, "media=" + text(media, "mediaName", "name")
                + ",site=" + text(site, "promotionName", "name")
                + ",siteSource=" + siteSource);
    }

    OrdersHit listOrders(Browser browser, String startTime, String endTime) {
        List<PromotionOrderItem> all = new ArrayList<PromotionOrderItem>();
        boolean recognized = false;
        String diagnostic = "";
        for (int pageNo = 1; pageNo <= ORDER_MAX_PAGES; pageNo++) {
            ObjectNode param = objectMapper.createObjectNode();
            param.put("unionRole", 1);
            param.put("startTime", orderQueryTime(startTime, false));
            param.put("endTime", orderQueryTime(endTime, true));
            param.put("orderStatus", 0);
            param.put("optType", 1);
            ObjectNode page = objectMapper.createObjectNode();
            page.put("pageNo", pageNo);
            page.put("pageSize", ORDER_PAGE_SIZE);
            ObjectNode params = objectMapper.createObjectNode();
            params.put("funName", "listOrderSku");
            params.set("page", page);
            params.set("param", param);
            ColorCall call = color(browser, "listOrderSku", "GET", "unionpc", params);
            diagnostic = call.diagnostic;
            if (!call.ok) {
                return OrdersHit.failed(diagnostic);
            }
            List<PromotionOrderItem> pageRows = parseOrderPage(call.body);
            if (pageRows == null) {
                return OrdersHit.failed("order response shape not recognized");
            }
            recognized = true;
            all.addAll(pageRows);
            if (!truthy(unwrap(call.body), "hasNext")) {
                break;
            }
        }
        if (!recognized) {
            return OrdersHit.failed(diagnostic);
        }
        return OrdersHit.ok(all, diagnostic + ",count=" + all.size());
    }

    private JsonNode listMedias(Browser browser, int mediaType) {
        ObjectNode inner = objectMapper.createObjectNode();
        inner.put("mediaType", mediaType);
        inner.put("pageNo", 1);
        inner.put("pageSize", 20);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("funName", "getMediaList");
        params.set("param", inner);
        ColorCall call = color(browser, "unionMedia", "GET", "unionpc", params);
        if (!call.ok) {
            return null;
        }
        return firstArray(unwrap(call.body), "result", "promotionLists", "mediaList");
    }

    private JsonNode listSites(Browser browser, String mediaId, String sku, int mediaType) {
        ObjectNode inner = objectMapper.createObjectNode();
        inner.put("pageNo", 1);
        inner.put("pageSize", 20);
        inner.put("id", mediaId);
        inner.put("materialType", 1);
        inner.put("opType", 2);
        inner.put("promotionType", mediaType);
        inner.put("materialId", sku);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("funName", "listPromotionSite");
        params.set("param", inner);
        ColorCall call = color(browser, "unionPromotion", "GET", "u", params);
        if (!call.ok) {
            return null;
        }
        return firstArray(unwrap(call.body), "promotionLists", "result", "siteList");
    }

    private ColorCall color(Browser browser, String functionId, String method, String appid, ObjectNode params) {
        ObjectNode spec = objectMapper.createObjectNode();
        spec.put("functionId", functionId);
        spec.put("method", method);
        spec.put("appid", appid);
        spec.set("params", params);
        String raw;
        try {
            raw = browser.executeScript(COLOR_SCRIPT, spec.toString());
        } catch (RuntimeException e) {
            log.info("JD union ajax {} failed: {}", functionId, e.getMessage());
            return ColorCall.fail(functionId, trim(e.getMessage()));
        }
        if (Strings.isEmpty(raw)) {
            return ColorCall.fail(functionId, "empty script result");
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            boolean ok = node.path("ok").asBoolean(false);
            String code = node.path("code").asText("");
            String error = node.path("error").asText("");
            JsonNode body = node.get("body");
            if (!ok) {
                return ColorCall.fail(functionId, code + " " + trim(error));
            }
            return new ColorCall(true, functionId + " code=" + code, body == null ? objectMapper.createObjectNode() : body);
        } catch (Exception e) {
            return ColorCall.fail(functionId, "bad json");
        }
    }

    static List<JdUnionProductItem> parseSearchItems(JsonNode data, int maxResults) {
        JsonNode rows = locateGoods(data);
        if (rows == null) {
            return null;
        }
        List<JdUnionProductItem> items = new ArrayList<JdUnionProductItem>();
        for (JsonNode row : rows) {
            if (items.size() >= maxResults) {
                break;
            }
            JdUnionProductItem item = toProductItem(row);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    static String findPromotionUrl(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return preferLink(node.asText());
        }
        if (node.isObject()) {
            String direct = firstText(node, "shortUrl", "shortURL", "clickUrl", "clickURL", "promotionUrl",
                    "couponShortUrl", "jShortCommand", "shortLink");
            if (!Strings.isEmpty(preferLink(direct))) {
                return preferLink(direct);
            }
            String best = "";
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                String found = findPromotionUrl(fields.next().getValue());
                if (rankLink(found) > rankLink(best)) {
                    best = found;
                }
            }
            return best;
        }
        if (node.isArray()) {
            String best = "";
            for (JsonNode child : node) {
                String found = findPromotionUrl(child);
                if (rankLink(found) > rankLink(best)) {
                    best = found;
                }
            }
            return best;
        }
        return "";
    }

    static JsonNode pickMedia(JsonNode medias, String mediaName) {
        return pickMedia(medias, mediaName, "");
    }

    static JsonNode pickMedia(JsonNode medias, String mediaName, String mediaId) {
        if (medias == null || !medias.isArray() || medias.isEmpty()) {
            return null;
        }
        JsonNode fallback = null;
        JsonNode nameMatch = null;
        for (JsonNode media : medias) {
            String name = text(media, "mediaName", "name", "promotionName");
            String id = text(media, "id");
            if ("-1".equals(id) || Strings.isEmpty(name) || name.contains("请选择") || name.contains("新增")) {
                continue;
            }
            if (!Strings.isEmpty(mediaId) && mediaId.equals(id)) {
                return media;
            }
            if (fallback == null) {
                fallback = media;
            }
            if (nameMatch == null && !Strings.isEmpty(mediaName)
                    && (name.equals(mediaName) || name.contains(mediaName))) {
                nameMatch = media;
            }
        }
        if (nameMatch != null) {
            return nameMatch;
        }
        return Strings.isEmpty(mediaId) ? fallback : null;
    }

    static JsonNode pickSite(JsonNode sites, String adSiteName, String siteId) {
        if (sites == null || !sites.isArray() || sites.isEmpty()) {
            return null;
        }
        JsonNode byId = null;
        for (JsonNode site : sites) {
            String name = text(site, "promotionName", "name", "spaceName");
            String id = text(site, "id");
            if (Strings.isEmpty(name) || name.contains("请选择") || name.contains("新增")) {
                continue;
            }
            if (!Strings.isEmpty(adSiteName) && name.equals(adSiteName)) {
                return site;
            }
            if (byId == null && !Strings.isEmpty(siteId) && siteId.equals(id)) {
                byId = site;
            }
        }
        return byId;
    }

    /**
     * 页面列表没有目标名称时，用配置里的推广位 ID 调 getCode。不回退到列表第一项，避免命中 4–9。
     */
    static JsonNode resolveSite(JsonNode sites, String adSiteName, String siteId, ObjectMapper mapper) {
        JsonNode site = pickSite(sites, adSiteName, siteId);
        if (site != null) {
            return site;
        }
        if (siteId != null && siteId.matches("\\d+")) {
            ObjectNode synthetic = mapper.createObjectNode();
            synthetic.put("id", siteId);
            synthetic.put("promotionName", adSiteName == null ? "" : adSiteName);
            return synthetic;
        }
        return null;
    }

    private static JdUnionProductItem toProductItem(JsonNode row) {
        String sku = text(row, "skuId", "sku");
        String name = text(row, "skuName", "name", "productName");
        if (Strings.isEmpty(sku) || Strings.isEmpty(name)) {
            return null;
        }
        JdUnionProductItem item = new JdUnionProductItem();
        item.setSkuId(sku);
        item.setProductName(name);
        item.setProductLink("https://item.jd.com/" + sku + ".html");
        item.setPrice(text(row, "finalPrice", "wlPrice", "price", "purchasePrice"));
        JsonNode purchase = row.path("purchasePriceInfo");
        if (Strings.isEmpty(item.getPrice()) && purchase.isObject()) {
            item.setPrice(text(purchase, "purchasePrice"));
        }
        item.setCommissionRate(percent(text(row, "wlCommissionRatio", "wlCommissionShare", "commissionShare", "commissionRate")));
        item.setStoreName(shopName(row));
        item.setImageUrl(imageUrl(text(row, "imageUrl", "imgUrl", "materialUrl")));
        return item;
    }

    private static ProductInfoDto toProductInfo(JdUnionProductItem item) {
        ProductInfoDto info = new ProductInfoDto();
        info.setProductName(item.getProductName());
        info.setProductLink(item.getProductLink());
        info.setCommissionRate(item.getCommissionRate());
        info.setStoreName(item.getStoreName());
        info.setPrice(item.getPrice());
        info.setImageUrl(item.getImageUrl());
        return info;
    }

    static List<PromotionOrderItem> parseOrderPage(JsonNode body) {
        JsonNode data = unwrap(body);
        JsonNode rows = firstArray(data, "result", "orderList", "orders", "data");
        if (rows == null && data != null && data.isArray()) {
            rows = data;
        }
        if (rows == null) {
            return null;
        }
        List<PromotionOrderItem> items = new ArrayList<PromotionOrderItem>();
        for (JsonNode row : rows) {
            PromotionOrderItem item = toOrder(row);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private static PromotionOrderItem toOrder(JsonNode row) {
        String orderNo = text(row, "orderId", "orderNo");
        String name = text(row, "skuName", "productName", "name");
        if (Strings.isEmpty(orderNo) || Strings.isEmpty(name)) {
            return null;
        }
        PromotionOrderItem item = new PromotionOrderItem();
        item.setOrderNo(orderNo);
        item.setMainOrderNo(text(row, "parentId", "mainOrderId"));
        item.setProductName(name);
        String sku = text(row, "skuId");
        if (!Strings.isEmpty(sku)) {
            item.setProductLink("https://item.jd.com/" + sku + ".html");
        }
        item.setStoreName(text(row, "skuShopName", "shopName", "popName", "venderName", "storeName"));
        String status = text(row, "validCodeStr", "orderStatusStr", "orderStatus");
        if (Strings.isEmpty(status)) {
            status = text(row, "validCode");
        }
        item.setOrderStatus(status);
        item.setOrderTime(text(row, "orderTimeStr", "orderTime"));
        item.setFinishTime(text(row, "finishTimeStr", "finishTime"));
        item.setSettleTime(text(row, "payMonth", "settleTime"));
        item.setTime(joinTime(item.getOrderTime(), item.getFinishTime(), item.getSettleTime()));
        item.setEstimatedBillingAmount(money(text(row, "estimateCosPrice")));
        item.setEstimatedCommission(money(text(row, "estimateFeeOrder", "estimateFee")));
        item.setCommissionRate(percent(text(row, "commissionRate", "finalRate", "subSideRate")));
        item.setActualBillingAmount(money(text(row, "actualCosPrice", "actualFee")));
        item.setActualCommission(money(text(row, "actualFeeOrder", "actualFee")));
        item.setProductQuantity(text(row, "skuNum", "skuCount", "quantity"));
        item.setQuantity(item.getProductQuantity());
        item.setPromotionPosition(text(row, "spName", "promotionName", "positionName"));
        item.setPromotionInfo(item.getPromotionPosition());
        return item;
    }

    private static JsonNode locateGoods(JsonNode data) {
        if (data != null && data.isTextual()) {
            String text = data.asText("");
            if (text.startsWith("{") || text.startsWith("[")) {
                try {
                    data = new ObjectMapper().readTree(text);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        JsonNode rows = firstArray(data, "goodsSynopsisList", "skuList", "goodsList", "result");
        if (rows == null && data != null && data.path("result").isObject()) {
            rows = firstArray(data.path("result"), "goodsSynopsisList", "skuList", "goodsList");
        }
        if (rows == null) {
            rows = findSkuArray(data, 0);
        }
        return rows;
    }

    private static JsonNode findSkuArray(JsonNode node, int depth) {
        if (node == null || node.isNull() || depth > 6) {
            return null;
        }
        if (node.isArray()) {
            if (node.size() > 0 && hasSku(node.get(0))) {
                return node;
            }
            for (JsonNode child : node) {
                JsonNode found = findSkuArray(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            JsonNode found = findSkuArray(fields.next().getValue(), depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean hasSku(JsonNode row) {
        return row != null && row.isObject()
                && !Strings.isEmpty(text(row, "skuId", "sku"))
                && !Strings.isEmpty(text(row, "skuName", "name", "productName"));
    }

    private static String outline(JsonNode node) {
        if (node == null || node.isNull()) {
            return "body=null";
        }
        StringBuilder builder = new StringBuilder();
        appendKeys(node, builder, 0);
        String message = firstText(node, "message", "msg", "echo");
        JsonNode data = node.get("data");
        if (Strings.isEmpty(message) && data != null && data.isObject()) {
            message = firstText(data, "message", "msg", "echo");
        }
        if (!Strings.isEmpty(message)) {
            builder.append(" msg=").append(message.replace('\n', ' '));
        }
        return trim(builder.toString());
    }

    private static void appendKeys(JsonNode node, StringBuilder builder, int depth) {
        if (node == null || !node.isObject() || depth > 2) {
            return;
        }
        builder.append('{');
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        boolean first = true;
        int count = 0;
        while (fields.hasNext() && count < 8) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!first) {
                builder.append(',');
            }
            first = false;
            count++;
            builder.append(field.getKey());
            JsonNode value = field.getValue();
            if (value != null && value.isArray()) {
                builder.append('[').append(value.size()).append(']');
            } else if (value != null && value.isObject() && depth < 2) {
                appendKeys(value, builder, depth + 1);
            }
        }
        builder.append('}');
    }

    private static JsonNode unwrap(JsonNode body) {
        if (body == null || body.isNull()) {
            return body;
        }
        JsonNode data = body.get("data");
        if (data != null && (data.isObject() || data.isArray())) {
            return data;
        }
        return body;
    }

    private static JsonNode firstArray(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode child = node.get(name);
            if (child != null && child.isArray()) {
                return child;
            }
        }
        return null;
    }

    private static boolean truthy(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText("");
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static String shopName(JsonNode row) {
        String direct = text(row, "shopName", "storeName", "venderName");
        if (!Strings.isEmpty(direct)) {
            return direct;
        }
        JsonNode shop = row.path("shopInfo");
        if (shop.isObject()) {
            return text(shop, "shopName", "name");
        }
        return "";
    }

    private static String imageUrl(String raw) {
        if (Strings.isEmpty(raw) || raw.startsWith("data:")) {
            return "";
        }
        if (raw.startsWith("//")) {
            return "https:" + raw;
        }
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw;
        }
        if (raw.startsWith("jfs/")) {
            return "https://img14.360buyimg.com/n1/" + raw;
        }
        return raw;
    }

    private static String percent(String raw) {
        if (Strings.isEmpty(raw)) {
            return "";
        }
        return raw.contains("%") ? raw : raw + "%";
    }

    private static String money(String raw) {
        if (Strings.isEmpty(raw)) {
            return "";
        }
        return raw.startsWith("￥") || raw.startsWith("¥") ? raw : "￥" + raw;
    }

    private static String joinTime(String orderTime, String finishTime, String settleTime) {
        StringBuilder builder = new StringBuilder();
        appendTime(builder, "下单时间", orderTime);
        appendTime(builder, "完成时间", finishTime);
        appendTime(builder, "结算时间", settleTime);
        return builder.toString();
    }

    private static void appendTime(StringBuilder builder, String label, String value) {
        if (Strings.isEmpty(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(label).append('：').append(value);
    }

    private static String text(JsonNode node, String... names) {
        if (node == null) {
            return "";
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull() || value.isContainerNode()) {
                continue;
            }
            String text = value.asText("");
            if (!Strings.isEmpty(text) && !"null".equals(text)) {
                return text;
            }
        }
        return "";
    }

    private static String firstText(JsonNode node, String... names) {
        return text(node, names);
    }

    private static String preferLink(String value) {
        if (Strings.isEmpty(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        return trimmed;
    }

    private static int rankLink(String value) {
        if (Strings.isEmpty(value)) {
            return 0;
        }
        if (value.contains("u.jd.com") || value.contains("union-click")) {
            return 3;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return 2;
        }
        return 1;
    }

    /**
     * 订单页传给 listOrderSku 的时间。页面默认是「7 天前 00:00:00」到「今天 23:59:59」。
     * 任务入参仍是 yyyy-MM-dd，这里补上时分秒。已经带时间的原样使用。
     */
    static String orderQueryTime(String value, boolean endOfDay) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return trimmed + (endOfDay ? " 23:59:59" : " 00:00:00");
        }
        return trimmed;
    }

    static int mediaTypeOf(String label) {
        if (Strings.isEmpty(label) || label.contains("导购")) {
            return GUIDE_MEDIA_TYPE;
        }
        if (label.contains("网站")) {
            return 1;
        }
        if (label.contains("APP") || label.contains("App")) {
            return 2;
        }
        if (label.contains("流量")) {
            return 6;
        }
        return GUIDE_MEDIA_TYPE;
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replace('\n', ' ').replace('\r', ' ');
        return compact.length() <= 180 ? compact : compact.substring(0, 180);
    }

    static final class SearchHit {
        final boolean success;
        final List<JdUnionProductItem> items;
        final String diagnostic;

        private SearchHit(boolean success, List<JdUnionProductItem> items, String diagnostic) {
            this.success = success;
            this.items = items;
            this.diagnostic = diagnostic;
        }

        static SearchHit ok(List<JdUnionProductItem> items, String diagnostic) {
            return new SearchHit(true, items, diagnostic);
        }

        static SearchHit failed(String diagnostic) {
            return new SearchHit(false, new ArrayList<JdUnionProductItem>(), diagnostic);
        }
    }

    static final class PromotionHit {
        final boolean success;
        final String promotionUrl;
        final ProductInfoDto productInfo;
        final String diagnostic;

        private PromotionHit(boolean success, String promotionUrl, ProductInfoDto productInfo, String diagnostic) {
            this.success = success;
            this.promotionUrl = promotionUrl;
            this.productInfo = productInfo;
            this.diagnostic = diagnostic;
        }

        static PromotionHit ok(String promotionUrl, ProductInfoDto productInfo, String diagnostic) {
            return new PromotionHit(true, promotionUrl, productInfo, diagnostic);
        }

        static PromotionHit failed(String diagnostic) {
            return new PromotionHit(false, "", null, diagnostic);
        }
    }

    static final class OrdersHit {
        final boolean success;
        final List<PromotionOrderItem> orders;
        final String diagnostic;

        private OrdersHit(boolean success, List<PromotionOrderItem> orders, String diagnostic) {
            this.success = success;
            this.orders = orders;
            this.diagnostic = diagnostic;
        }

        static OrdersHit ok(List<PromotionOrderItem> orders, String diagnostic) {
            return new OrdersHit(true, orders, diagnostic);
        }

        static OrdersHit failed(String diagnostic) {
            return new OrdersHit(false, new ArrayList<PromotionOrderItem>(), diagnostic);
        }
    }

    private static final class ColorCall {
        final boolean ok;
        final String diagnostic;
        final JsonNode body;

        ColorCall(boolean ok, String diagnostic, JsonNode body) {
            this.ok = ok;
            this.diagnostic = diagnostic;
            this.body = body;
        }

        static ColorCall fail(String functionId, String error) {
            return new ColorCall(false, functionId + " " + error, null);
        }
    }

    private static final String COLOR_SCRIPT = loadColorScript();

    private static String loadColorScript() {
        try (InputStream in = JdUnionPageApi.class.getClassLoader().getResourceAsStream("jd-union-color.js")) {
            if (in == null) {
                throw new IllegalStateException("jd-union-color.js missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("jd-union-color.js unreadable", e);
        }
    }
}
