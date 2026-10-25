package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.ProductInfoDto;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.crawler.task.common.PromotionSlot;
import org.rx.crawler.task.common.PromotionSlots;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析页面自然响应中的淘宝联盟字段；旧主动请求入口仅保留禁用兼容壳。
 */
@Slf4j
final class TbUnionPageApi {
    private static final String ITEM_URL_PREFIX = "https://item.taobao.com/item.htm?id=";

    private final ObjectMapper objectMapper;

    TbUnionPageApi(ObjectMapper objectMapper) {
        this(objectMapper, id -> null);
    }

    TbUnionPageApi(ObjectMapper objectMapper, java.util.function.Function<String, String> materialLookup) {
        this(objectMapper, materialLookup, browser -> true);
    }

    TbUnionPageApi(ObjectMapper objectMapper, java.util.function.Function<String, String> materialLookup,
            java.util.function.Predicate<Browser> pageReady) {
        this.objectMapper = objectMapper;
    }

    // Compatibility guards: only page interactions may initiate business requests.
    SearchHit searchProducts(Browser browser, String keyword, int maxResults) {
        return SearchHit.failed("ACTIVE_PAGE_REQUEST_DISABLED");
    }

    PromotionHit promote(Browser browser, String keyword, String mediaName, String adSiteName, String configuredPid) {
        return PromotionHit.failed("ACTIVE_PAGE_REQUEST_DISABLED");
    }

    OrdersHit listOrders(Browser browser, String startTime, String endTime) {
        return OrdersHit.failed("ACTIVE_PAGE_REQUEST_DISABLED");
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
        return parseOrders(rows, PromotionSlots.taobaoDefaults());
    }

    static List<PromotionOrderItem> parseOrders(JsonNode rows, List<PromotionSlot> slots) {
        List<PromotionOrderItem> items = new ArrayList<PromotionOrderItem>();
        if (rows == null || !rows.isArray()) return items;
        for (JsonNode row : rows) {
            String orderNo = text(row, "tradeId", "tbTradeId");
            String name = text(row, "itemTitle", "itemName");
            if (Strings.isEmpty(orderNo) || Strings.isEmpty(name)) {
                continue;
            }
            PromotionOrderItem item = new PromotionOrderItem();
            item.setOrderNo(orderNo);
            String mainOrderNo = text(row, "tradeParentId", "tbTradeParentId");
            item.setMainOrderNo(mainOrderNo);
            item.setProductName(name);
            String itemId = text(row, "itemId");
            if (!itemId.matches("\\d+")) itemId = "";
            item.setSkuId(itemId);
            item.setSourceLineId(orderNo);
            item.setRawJson(row.toString());
            String link = httpUrl(text(row, "mktItemLink", "itemLink"));
            item.setProductLink(!Strings.isEmpty(link) ? link : itemId.matches("\\d+") ? ITEM_URL_PREFIX + itemId : "");
            item.setProductPrice(money(text(row, "itemPrice")));
            item.setStoreName(text(row, "sellerShopTitle", "sellerNick"));
            item.setOrderStatus(text(row, "tkStatusText", "tkStatusDesc"));
            item.setOrderTime(text(row, "tkCreateTime", "createTime"));
            item.setPaidTime(text(row, "tbPaidTime", "tkPaidTime"));
            item.setFinishTime(text(row, "tkEarningTime", "tkConfirmTime"));
            item.setSettleTime(org.rx.crawler.task.common.OrderTimeFields.nextMonthSettlementDate(item.getFinishTime()));
            item.setTime(joinTime(item.getOrderTime(), item.getPaidTime(), item.getFinishTime()));
            item.setEstimatedBillingAmount(money(text(row, "alipayTotalPrice")));
            item.setEstimatedCommission(money(text(row, "pubSharePreFee")));
            item.setCommissionRate(percent(text(row, "tkTotalRateForCommissionAndSdy", "totalCommissionRate", "tkTotalRate")));
            item.setShareRate(percent(text(row, "pubShareRate")));
            item.setActualBillingAmount(money(text(row, "payPrice")));
            item.setActualCommission(money(text(row, "pubShareFee")));
            item.setProductQuantity(text(row, "itemNum"));
            item.setQuantity(item.getProductQuantity());
            Map<String, Object> extensions = new LinkedHashMap<String, Object>();
            extensions.put("parentOrderNo", mainOrderNo);
            for (String key : new String[]{"siteName", "siteId", "adzoneId", "adzoneName", "refundTag", "mktId",
                    "tkStatus", "orderType", "tbPaidTime", "tkPaidTime", "tkTotalRate", "incomeRate"}) {
                if (row.hasNonNull(key)) extensions.put(key, row.get(key).asText());
            }
            extensions.put("rawOrderStatus", item.getOrderStatus());
            extensions.put("rawEarningTime", text(row, "tkEarningTime"));
            if (!item.getSettleTime().isEmpty()) extensions.put("settlementDateSource", "receipt-next-month-20");
            item.setExtensions(extensions);
            normalizeOrderPromotionPosition(item, slots);
            item.setNormalizedStatus(org.rx.crawler.task.order.OrderFields.normalizedStatus("tb", item.getOrderStatus(), extensions));
            items.add(item);
        }
        return items;
    }

    /** Map the order's adzone ID to its configured display name; siteName identifies the media site. */
    static void normalizeOrderPromotionPosition(PromotionOrderItem item, List<PromotionSlot> slots) {
        if (item == null) return;
        Map<String, Object> extensions = item.getExtensions();
        if (extensions == null) {
            extensions = new LinkedHashMap<String, Object>();
            item.setExtensions(extensions);
        }
        String raw = item.getPromotionPosition() == null ? "" : item.getPromotionPosition().trim();
        String id = extensionText(extensions, "adzoneId");
        if (id.isEmpty()) id = extensionText(extensions, "promotionPositionId");
        String name = extensionText(extensions, "adzoneName");
        if (!raw.isEmpty()) extensions.putIfAbsent("rawPromotionPosition", raw);
        if (!id.isEmpty()) extensions.put("promotionPositionId", id);
        if (!name.isEmpty()) extensions.putIfAbsent("rawPromotionPositionName", name);
        String resolved = PromotionSlots.nameById(slots, id.isEmpty() ? raw : id);
        if (resolved.isEmpty()) resolved = !name.isEmpty() ? name : !id.isEmpty() ? id : raw;
        item.setPromotionPosition(resolved);
        item.setPromotionInfo(resolved);
    }

    private static String extensionText(Map<String, Object> extensions, String key) {
        Object value = extensions.get(key);
        return value == null ? "" : value.toString().trim();
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

    // Read only fields already returned by the promotion response; absent values remain null.
    private static String promotionValue(JsonNode node, boolean link, String... keys) {
        if (node == null || node.isNull()) return null;
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || !value.isTextual() || value.asText().isBlank()) continue;
            String text = value.asText().trim();
            if (text.startsWith("//")) text = "https:" + text;
            boolean url = text.startsWith("https://") || text.startsWith("http://");
            if (link == url) return text;
        }
        for (JsonNode child : node) {
            if (!child.isContainerNode()) continue;
            String found = promotionValue(child, link, keys);
            if (found != null) return found;
        }
        return null;
    }

    static final class PromotionHit {
        final boolean success;
        final String promotionUrl;
        final String promotionToken;
        final String shortUrl;
        final String longUrl;
        final ProductInfoDto productInfo;
        final String itemId;
        final String mktId;
        final String diagnostic;

        private PromotionHit(boolean success, String promotionUrl, ProductInfoDto productInfo, String itemId,
                String mktId, String diagnostic, JsonNode payload) {
            this.success = success;
            this.promotionUrl = promotionUrl;
            this.promotionToken = promotionValue(payload, false, "promotionToken", "tpwd", "taoToken", "tkl", "couponLinkTaoToken", "taoPassword");
            this.shortUrl = promotionValue(payload, true, "couponShortLinkUrl", "shortLinkurl", "shortLinkUrl", "cpsShortLinkUrl", "shortUrl");
            this.longUrl = promotionValue(payload, true, "longLinkUrl", "longUrl", "clickUrl", "couponShareUrl", "url", "couponLinkUrl", "cpsLinkUrl");
            this.productInfo = productInfo;
            this.itemId = itemId;
            this.mktId = mktId;
            this.diagnostic = diagnostic;
        }

        static PromotionHit ok(String promotionUrl, ProductInfoDto productInfo, String itemId, String mktId,
                String diagnostic, JsonNode payload) {
            return new PromotionHit(true, promotionUrl, productInfo, itemId, mktId, diagnostic, payload);
        }

        static PromotionHit failed(String diagnostic) {
            return new PromotionHit(false, "", null, "", "", diagnostic, null);
        }
    }

    static final class OrdersHit {
        final boolean success;
        final List<PromotionOrderItem> orders;
        final String diagnostic;
        final boolean complete;
        final boolean hasMore;
        final int pagesFetched;
        final String incompleteReason;

        private OrdersHit(boolean success, List<PromotionOrderItem> orders, String diagnostic,
                boolean complete, boolean hasMore, int pagesFetched, String incompleteReason) {
            this.success = success;
            this.orders = orders;
            this.diagnostic = diagnostic;
            this.complete = complete;
            this.hasMore = hasMore;
            this.pagesFetched = pagesFetched;
            this.incompleteReason = incompleteReason;
        }

        static OrdersHit ok(List<PromotionOrderItem> orders, String diagnostic,
                boolean complete, boolean hasMore, int pagesFetched, String incompleteReason) {
            return new OrdersHit(true, orders, diagnostic, complete, hasMore, pagesFetched, incompleteReason);
        }

        static OrdersHit failed(String diagnostic) {
            return new OrdersHit(false, new ArrayList<PromotionOrderItem>(), diagnostic, false, false, 0, diagnostic);
        }
    }

}
