package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.ProductInfoDto;
import org.rx.crawler.task.common.PromotionOrderItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在已打开的 pub.alimama.com 页面里调用页面自己的 unionpub 网关。
 * cookie 与 _tb_token_ 走浏览器，不另起 HTTP 客户端。
 */
@Slf4j
final class TbUnionPageApi {
    private static final String GATEWAY = "/openapi/param2/1/gateway.unionpub/";
    private static final int SEARCH_FLOOR_ID = 80674;
    private static final int PROMOTE_FLOOR_ID = 124176;
    private static final int ORDER_PAGE_SIZE = 20;
    private static final int ORDER_MAX_PAGES = 20;
    private static final String ITEM_URL_PREFIX = "https://item.taobao.com/item.htm?id=";
    private static final String SCRIPT = Browser.readResourceJs("tb-union-api.js");

    private final ObjectMapper objectMapper;
    private final java.util.function.Function<String, String> materialLookup;
    private final java.util.function.Predicate<Browser> pageReady;

    TbUnionPageApi(ObjectMapper objectMapper) {
        this(objectMapper, id -> null);
    }

    TbUnionPageApi(ObjectMapper objectMapper, java.util.function.Function<String, String> materialLookup) {
        this(objectMapper, materialLookup, browser -> true);
    }

    TbUnionPageApi(ObjectMapper objectMapper, java.util.function.Function<String, String> materialLookup,
            java.util.function.Predicate<Browser> pageReady) {
        this.objectMapper = objectMapper;
        this.materialLookup = materialLookup;
        this.pageReady = pageReady;
    }

    SearchHit searchProducts(Browser browser, String keyword, int maxResults) {
        int size = Math.max(1, Math.min(30, maxResults));
        ObjectNode variableMap = objectMapper.createObjectNode();
        variableMap.put("fn", "search");
        variableMap.put("resultCanBeEmpty", true);
        String material = keyword.matches("\\d+") ? materialLookup.apply(keyword) : null;
        variableMap.put("q", isMktId(material) ? material : keyword);
        variableMap.putObject("curSelected");
        variableMap.put("pubFloorId", SEARCH_FLOOR_ID);
        variableMap.put("sort", "default");
        variableMap.put("tk_navigator", "true");
        variableMap.put("lensScene", "PUB");
        variableMap.put("spmB", "_portal_v2_pages_promo_goods_index_htm");
        ObjectNode extras = objectMapper.createObjectNode();
        extras.put("sceneCode", "pub_selection_navigation");
        extras.put("floorId", SEARCH_FLOOR_ID);
        extras.put("pageNum", 0);
        extras.put("pageSize", String.valueOf(size));
        extras.put("pid", "mm_0_0_0");
        extras.set("variableMap", variableMap);
        Map<String, Object> form = new LinkedHashMap<String, Object>();
        form.put("siteCode", "selectionPlaza_site");
        form.put("appCode", "pub");
        form.put("extras", extras.toString());
        Call call = call(browser, "POST", "skyleap.distribution.site.json", null, form);
        if (!call.ok) {
            return SearchHit.failed(call.diagnostic);
        }
        JsonNode siteData = call.body.path("data").path("siteData");
        JsonNode rows = siteData.path("resultList");
        if (!rows.isArray()) {
            return SearchHit.failed("skyleap resultList missing " + call.diagnostic);
        }
        List<TbUnionProductItem> items = parseSearchItems(rows, size);
        SearchHit hit = SearchHit.ok(items, siteData.path("totalCount").asLong(rows.size()), call.diagnostic);
        hit.rawCount = rows.size();
        return hit;
    }

    PromotionHit promote(Browser browser, String keyword, String mediaName, String adSiteName, String configuredPid) {
        String mktId = keyword.trim();
        if (mktId.matches("\\d+")) {
            String cached = materialLookup.apply(mktId);
            if (isMktId(cached)) mktId = cached;
        }
        if (!isMktId(mktId)) {
            SearchHit found = searchProducts(browser, mktId, 10);
            if (!found.success) {
                return PromotionHit.failed("search before promote failed: " + found.diagnostic);
            }
            if (found.items.isEmpty()) {
                return PromotionHit.failed("search before promote returned no item");
            }
            mktId = found.items.get(0).getItemId();
        }
        Call zones = call(browser, "GET", "record.adzone.queryForPublish.json", null, null);
        JsonNode listed = zones.ok ? pickAdzone(zones.body.path("data"), mediaName, adSiteName, configuredPid) : null;
        JsonNode zone = listed;
        String zoneSource = "queryForPublish";
        if (zone == null) {
            Call def = call(browser, "GET", "record.adzone.defaultAdzone.json", null, null);
            JsonNode defaultZone = def.ok ? def.body.path("data").path("defaultAdzone") : null;
            zone = resolveZone(null, defaultZone, mediaName, adSiteName, configuredPid, objectMapper);
            if (zone != null && defaultZone != null && zone == defaultZone) {
                zoneSource = "defaultAdzone";
            } else if (zone != null) {
                zoneSource = "config";
            }
        }
        if (zone == null) {
            return PromotionHit.failed("adzone " + adSiteName + " not found (" + zones.diagnostic + ")");
        }
        String pid = text(zone, "pid");
        ObjectNode variableMap = objectMapper.createObjectNode();
        variableMap.put("itemId", mktId);
        variableMap.put("pubFloorId", SEARCH_FLOOR_ID);
        Map<String, Object> query = new LinkedHashMap<String, Object>();
        query.put("floorId", PROMOTE_FLOOR_ID);
        query.put("refpid", pid);
        query.put("variableMap", variableMap.toString());
        Call link = call(browser, "GET", "xt.entry.json", query, null);
        if (!link.ok) {
            return PromotionHit.failed("xt.entry " + link.diagnostic);
        }
        JsonNode rows = link.body.path("data").path("resultList");
        if (!rows.isArray() || rows.size() == 0) {
            return PromotionHit.failed("xt.entry resultList empty");
        }
        JsonNode row = rows.get(0);
        String url = pickPromotionUrl(row);
        if (Strings.isEmpty(url)) {
            return PromotionHit.failed("xt.entry returned no promotion url");
        }
        ProductInfoDto info = toProductInfo(row);
        String numericId = text(row, "itemId");
        // Alliance itemId is optional/opaque. Only a genuine numeric ID can contradict the request.
        // Missing identity is resolved by following the promotion URL at the material boundary.
        if (!numericId.matches("\\d+")) numericId = "";
        if (keyword.trim().matches("\\d+") && !numericId.isEmpty() && !keyword.trim().equals(numericId)) {
            return PromotionHit.failed("first search result is item " + numericId + ", not " + keyword.trim());
        }
        return PromotionHit.ok(url, info, numericId, mktId, "adzone=" + text(zone, "adzoneName")
                + ",zoneSource=" + zoneSource);
    }

    OrdersHit listOrders(Browser browser, String startTime, String endTime) {
        List<PromotionOrderItem> all = new ArrayList<PromotionOrderItem>();
        String positionIndex = "";
        String diagnostic = "";
        int pages = 0;
        for (int pageNo = 1; pageNo <= ORDER_MAX_PAGES; pageNo++) {
            Map<String, Object> query = new LinkedHashMap<String, Object>();
            query.put("pageNo", pageNo);
            query.put("pageSize", ORDER_PAGE_SIZE);
            query.put("startTime", startTime);
            query.put("endTime", endTime);
            query.put("payStatus", "");
            query.put("queryType", 2);
            query.put("jumpType", 0);
            query.put("tkTradeId", "");
            query.put("positionIndex", positionIndex);
            Call call = call(browser, "GET", "report.getTbkOrderDetails.json", query, null);
            diagnostic = call.diagnostic;
            if (!call.ok) {
                return OrdersHit.failed(diagnostic);
            }
            JsonNode data = call.body.path("data");
            JsonNode rows = data.path("result");
            if (!rows.isArray()) {
                if (data.isMissingNode() || data.isNull() || data.size() == 0) {
                    break;
                }
                return OrdersHit.failed("order result array missing " + diagnostic);
            }
            pages++;
            all.addAll(parseOrders(rows));
            positionIndex = text(data, "positionIndex");
            if (!data.path("hasNext").asBoolean(false) || rows.size() == 0) {
                break;
            }
        }
        return OrdersHit.ok(all, diagnostic + ",pages=" + pages + ",count=" + all.size());
    }

    private Call call(Browser browser, String method, String api, Map<String, Object> query, Map<String, Object> form) {
        if (!pageReady.test(browser)) return Call.fail(api, "TB_SLIDER_VERIFICATION_REQUIRED");
        ObjectNode spec = objectMapper.createObjectNode();
        spec.put("method", method);
        spec.put("path", GATEWAY + api);
        if (query != null) {
            spec.set("query", objectMapper.valueToTree(query));
        }
        if (form != null) {
            spec.set("form", objectMapper.valueToTree(form));
        }
        String raw = null;
        RuntimeException transportFailure = null;
        try {
            raw = browser.executeScript(SCRIPT, spec.toString());
        } catch (RuntimeException e) {
            log.info("TB union ajax {} failed: {}", api, e.getClass().getSimpleName());
            transportFailure = e;
        }
        if (!pageReady.test(browser)) return Call.fail(api, "TB_SLIDER_VERIFICATION_REQUIRED");
        if (transportFailure != null) return Call.fail(api, transportFailure.getClass().getSimpleName());
        if (Strings.isEmpty(raw)) {
            return Call.fail(api, "empty script result");
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (!node.path("ok").asBoolean(false)) {
                return Call.fail(api, "status=" + node.path("status").asText("") + " " + trim(node.path("error").asText("")));
            }
            return new Call(true, api + " ok", node.path("body"));
        } catch (Exception e) {
            return Call.fail(api, "bad json");
        }
    }

    static boolean isMktId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{24,}") && value.matches(".*[A-Za-z].*");
    }

    static List<TbUnionProductItem> parseSearchItems(JsonNode rows, int maxResults) {
        List<TbUnionProductItem> items = new ArrayList<TbUnionProductItem>();
        if (rows == null || !rows.isArray()) {
            return items;
        }
        for (JsonNode row : rows) {
            if (items.size() >= maxResults) {
                break;
            }
            String mktId = text(row, "outputMktId");
            String name = stripHtml(text(row, "itemName", "title"));
            if (Strings.isEmpty(mktId) || Strings.isEmpty(name)) {
                continue;
            }
            TbUnionProductItem item = new TbUnionProductItem();
            item.setItemId(mktId);
            item.setEncryptedProductId(mktId);
            item.setProductName(name);
            String link = httpUrl(text(row, "url"));
            item.setProductLink(Strings.isEmpty(link)
                    ? "https://pub.alimama.com/portal/v2/pages/promo/goods/detail.htm?itemId=" + mktId : link);
            String[] price = named(row, "unionPromotionPrice", "finalPromotionPrice", "zkFinalPrice", "price");
            item.setPrice(price[1]);
            item.setPriceField(price[0]);
            String rate = text(row, "unionCommissionRateDisplay");
            item.setCommissionRate(Strings.isEmpty(rate) ? percent(text(row, "maxCommissionRate")) : rate);
            item.setStoreName(text(row, "shopTitle", "nick"));
            item.setImageUrl(httpUrl(text(row, "pic", "whiteImage")));
            String coupon = text(row, "couponAmount");
            item.setCouponAmount(isPositive(coupon) ? coupon : "");
            item.setMonthSales(text(row, "monthPromotionCount"));
            items.add(item);
        }
        return items;
    }

    static JsonNode pickAdzone(JsonNode data, String mediaName, String adSiteName, String configuredPid) {
        if (data == null || !data.isArray()) {
            return null;
        }
        JsonNode nameOnly = null;
        JsonNode pidMatch = null;
        JsonNode pidOnly = null;
        for (JsonNode media : data) {
            for (JsonNode siteType : media.path("siteTypes")) {
                for (JsonNode site : siteType.path("sites")) {
                    String siteName = text(site, "siteName");
                    boolean mediaOk = Strings.isEmpty(mediaName) || mediaName.equals(siteName);
                    for (JsonNode zone : site.path("adzones")) {
                        String pid = text(zone, "pid");
                        if (Strings.isEmpty(pid)) {
                            continue;
                        }
                        boolean nameOk = Strings.isEmpty(adSiteName) || adSiteName.equals(text(zone, "adzoneName"));
                        boolean pidOk = !Strings.isEmpty(configuredPid) && configuredPid.equals(pid);
                        if (nameOk && mediaOk) {
                            return zone;
                        }
                        if (nameOk && nameOnly == null) {
                            nameOnly = zone;
                        }
                        if (pidOk && mediaOk && pidMatch == null) {
                            pidMatch = zone;
                        } else if (pidOk && pidOnly == null) {
                            pidOnly = zone;
                        }
                    }
                }
            }
        }
        if (pidMatch != null) {
            return pidMatch;
        }
        if (nameOnly != null) {
            return nameOnly;
        }
        return pidOnly;
    }

    /**
     * 列表里没有目标推广位时，先接受同名或同 pid 的默认推广位，再退回配置里的 pid。
     * 配置 pid 必须是 mm_数字_数字_数字，避免把空值或别的字符串拿去转链。
     */
    static JsonNode resolveZone(JsonNode data, JsonNode defaultZone, String mediaName, String adSiteName,
            String configuredPid, ObjectMapper mapper) {
        JsonNode zone = pickAdzone(data, mediaName, adSiteName, configuredPid);
        if (zone != null) {
            return zone;
        }
        if (defaultZone != null && !defaultZone.isMissingNode() && !defaultZone.isNull()
                && !Strings.isEmpty(text(defaultZone, "pid"))) {
            boolean nameOk = Strings.isEmpty(adSiteName) || adSiteName.equals(text(defaultZone, "adzoneName"));
            boolean pidOk = !Strings.isEmpty(configuredPid) && configuredPid.equals(text(defaultZone, "pid"));
            if (nameOk || pidOk) {
                return defaultZone;
            }
        }
        if (isTbPid(configuredPid)) {
            ObjectNode synthetic = mapper.createObjectNode();
            synthetic.put("pid", configuredPid);
            synthetic.put("adzoneName", adSiteName == null ? "" : adSiteName);
            return synthetic;
        }
        return null;
    }

    static boolean isTbPid(String value) {
        return value != null && value.matches("mm_\\d+_\\d+_\\d+");
    }

    static String pickPromotionUrl(JsonNode row) {
        String[] keys = {"couponShortLinkUrl", "shortLinkurl", "shortLinkUrl", "cpsShortLinkUrl", "url", "couponShareUrl"};
        for (String key : keys) {
            String url = httpUrl(text(row, key));
            if (!Strings.isEmpty(url)) {
                return url;
            }
        }
        return "";
    }

    static ProductInfoDto toProductInfo(JsonNode row) {
        ProductInfoDto info = new ProductInfoDto();
        info.setProductName(stripHtml(text(row, "itemName", "title")));
        String itemId = text(row, "itemId");
        info.setProductLink(itemId.matches("\\d+") ? ITEM_URL_PREFIX + itemId : httpUrl(text(row, "itemUrl")));
        String[] price = named(row, "unionPromotionPrice", "finalPromotionPrice", "promotionPrice", "price");
        info.setPrice(price[1]);
        info.setPriceField(price[0]);
        info.setCommissionRate(percent(text(row, "unionCommissionRate")));
        info.setStoreName(text(row, "shopTitle", "sellerNickName"));
        info.setImageUrl(httpUrl(text(row, "pic", "whiteImage")));
        return info;
    }

    static List<PromotionOrderItem> parseOrders(JsonNode rows) {
        List<PromotionOrderItem> items = new ArrayList<PromotionOrderItem>();
        for (JsonNode row : rows) {
            String orderNo = text(row, "tradeId", "tbTradeId");
            String name = text(row, "itemTitle", "itemName");
            if (Strings.isEmpty(orderNo) || Strings.isEmpty(name)) {
                continue;
            }
            PromotionOrderItem item = new PromotionOrderItem();
            item.setOrderNo(orderNo);
            item.setMainOrderNo(text(row, "tradeParentId", "tbTradeParentId"));
            item.setProductName(name);
            String itemId = text(row, "itemId");
            String link = httpUrl(text(row, "mktItemLink", "itemLink"));
            item.setProductLink(!Strings.isEmpty(link) ? link : itemId.matches("\\d+") ? ITEM_URL_PREFIX + itemId : "");
            item.setProductPrice(money(text(row, "itemPrice")));
            item.setStoreName(text(row, "sellerShopTitle", "sellerNick"));
            item.setOrderStatus(text(row, "tkStatusText", "tkStatusDesc"));
            item.setOrderTime(text(row, "tkCreateTime", "createTime"));
            item.setFinishTime(text(row, "tkConfirmTime"));
            item.setSettleTime(text(row, "tkEarningTime"));
            item.setTime(joinTime(item.getOrderTime(), text(row, "tkPaidTime"), item.getSettleTime()));
            item.setEstimatedBillingAmount(money(text(row, "alipayTotalPrice")));
            item.setEstimatedCommission(money(text(row, "pubSharePreFee")));
            item.setCommissionRate(percent(text(row, "totalCommissionRate")));
            item.setShareRate(percent(text(row, "incomeRate")));
            item.setActualBillingAmount(money(text(row, "payPrice")));
            item.setActualCommission(money(text(row, "pubShareFee")));
            item.setProductQuantity(text(row, "itemNum"));
            item.setQuantity(item.getProductQuantity());
            item.setPromotionPosition(text(row, "adzoneName"));
            String site = text(row, "siteName");
            item.setPromotionInfo(Strings.isEmpty(site) ? item.getPromotionPosition()
                    : site + (Strings.isEmpty(item.getPromotionPosition()) ? "" : "/" + item.getPromotionPosition()));
            items.add(item);
        }
        return items;
    }

    static String stripHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&nbsp;", " ").trim();
    }

    static String httpUrl(String raw) {
        if (Strings.isEmpty(raw)) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        return trimmed.startsWith("http://") || trimmed.startsWith("https://") ? trimmed : "";
    }

    private static boolean isPositive(String value) {
        try {
            return !Strings.isEmpty(value) && Double.parseDouble(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String percent(String raw) {
        if (Strings.isEmpty(raw)) {
            return "";
        }
        return raw.endsWith("%") ? raw : raw + "%";
    }

    private static String money(String raw) {
        if (Strings.isEmpty(raw)) {
            return "";
        }
        return raw.startsWith("￥") || raw.startsWith("¥") ? raw : "￥" + raw;
    }

    private static String joinTime(String createTime, String paidTime, String settleTime) {
        StringBuilder builder = new StringBuilder();
        appendTime(builder, "创建时间", createTime);
        appendTime(builder, "付款时间", paidTime);
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

    static String[] named(JsonNode node, String... names) {
        if (node != null) {
            for (String name : names) {
                JsonNode value = node.get(name);
                if (value == null || value.isNull() || value.isContainerNode()) {
                    continue;
                }
                String text = value.asText("").trim();
                if (!Strings.isEmpty(text) && !"null".equals(text)) {
                    return new String[]{name, text};
                }
            }
        }
        return new String[]{"", ""};
    }

    static String text(JsonNode node, String... names) {
        if (node == null) {
            return "";
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull() || value.isContainerNode()) {
                continue;
            }
            String text = value.asText("").trim();
            if (!Strings.isEmpty(text) && !"null".equals(text)) {
                return text;
            }
        }
        return "";
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replace('\n', ' ').replace('\r', ' ');
        return compact.length() <= 180 ? compact : compact.substring(0, 180);
    }

    static final class SearchHit {
        int rawCount;
        final boolean success;
        final List<TbUnionProductItem> items;
        final long totalCount;
        final String diagnostic;

        private SearchHit(boolean success, List<TbUnionProductItem> items, long totalCount, String diagnostic) {
            this.success = success;
            this.items = items;
            this.totalCount = totalCount;
            this.diagnostic = diagnostic;
            this.rawCount = items.size();
        }

        boolean uniqueMaterial() {
            return success && rawCount == 1 && totalCount == 1 && items.size() == 1 && isMktId(items.get(0).getItemId());
        }

        static SearchHit ok(List<TbUnionProductItem> items, long totalCount, String diagnostic) {
            return new SearchHit(true, items, totalCount, diagnostic);
        }

        static SearchHit failed(String diagnostic) {
            return new SearchHit(false, new ArrayList<TbUnionProductItem>(), 0, diagnostic);
        }
    }

    static final class PromotionHit {
        final boolean success;
        final String promotionUrl;
        final ProductInfoDto productInfo;
        final String itemId;
        final String mktId;
        final String diagnostic;

        private PromotionHit(boolean success, String promotionUrl, ProductInfoDto productInfo, String itemId,
                String mktId, String diagnostic) {
            this.success = success;
            this.promotionUrl = promotionUrl;
            this.productInfo = productInfo;
            this.itemId = itemId;
            this.mktId = mktId;
            this.diagnostic = diagnostic;
        }

        static PromotionHit ok(String promotionUrl, ProductInfoDto productInfo, String itemId, String mktId,
                String diagnostic) {
            return new PromotionHit(true, promotionUrl, productInfo, itemId, mktId, diagnostic);
        }

        static PromotionHit failed(String diagnostic) {
            return new PromotionHit(false, "", null, "", "", diagnostic);
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

    private static final class Call {
        final boolean ok;
        final String diagnostic;
        final JsonNode body;

        Call(boolean ok, String diagnostic, JsonNode body) {
            this.ok = ok;
            this.diagnostic = diagnostic;
            this.body = body;
        }

        static Call fail(String api, String error) {
            return new Call(false, api + " " + error, null);
        }
    }
}
