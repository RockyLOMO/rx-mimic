package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 淘宝主站数据解析：搜索页 / 详情页自己发出的 mtop 响应（被动截获）和详情页内嵌的 SSR 数据。
 */
@Slf4j
final class TbMallPageApi {
    static final String SEARCH_API = "mtop.relationrecommend.wirelessrecommend.recommend";
    static final String DETAIL_ADJUST_API = "mtop.taobao.pcdetail.data.adjust";
    static final String DESC_API = "getdesc";
    private static final String ITEM_URL_PREFIX = "https://item.taobao.com/item.htm?id=";
    private static final String DETAIL_DATA_SCRIPT = Browser.readResourceJs("tb-detail-data.js");
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile(
            "(?:https?:)?//(?:img|gw|g-search\\d?)\\.alicdn\\.com/[^\"'\\s()<>\\\\]+?\\.(?:jpg|jpeg|png|webp|gif)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ICON_PATTERN = Pattern.compile("-tps-\\d{1,3}-\\d{1,3}\\.|/tfs/|/tps/", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    TbMallPageApi(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode readDetailData(Browser browser) {
        try {
            String raw = browser.executeScript(DETAIL_DATA_SCRIPT);
            return Strings.isEmpty(raw) ? null : objectMapper.readTree(raw);
        } catch (Exception e) {
            log.info("Read TB detail SSR data failed: {}", e.getMessage());
            return null;
        }
    }

    JsonNode parseMtop(String body) {
        String json = stripJsonp(body);
        if (Strings.isEmpty(json)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode ret = node.path("ret");
            if (ret.isArray() && ret.size() > 0 && !ret.get(0).asText("").startsWith("SUCCESS")) {
                return null;
            }
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    static String stripJsonp(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        int start = trimmed.indexOf('(');
        int end = trimmed.lastIndexOf(')');
        if (start < 0 || end <= start) {
            return "";
        }
        return trimmed.substring(start + 1, end).trim();
    }

    /**
     * 返回 null 表示不是搜索结果响应（同名接口也承载推荐位）。
     */
    static List<TbSearchItem> parseSearchItems(JsonNode mtop, int maxResults) {
        JsonNode rows = mtop == null ? null : mtop.path("data").path("itemsArray");
        if (rows == null || !rows.isArray()) {
            return null;
        }
        List<TbSearchItem> items = new ArrayList<TbSearchItem>();
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode row : rows) {
            if (items.size() >= maxResults) {
                break;
            }
            String itemId = text(row, "item_id", "itemId", "nid");
            if (Strings.isEmpty(itemId) || !seen.add(itemId)) {
                continue;
            }
            TbSearchItem item = new TbSearchItem();
            item.setItemId(itemId);
            item.setProductId(itemId);
            item.setTitle(stripHtml(text(row, "title", "raw_title")));
            item.setPrice(pickPrice(text(row.path("priceShow"), "price"), text(row.path("utLogMap"), "price"),
                    text(row, "price")));
            item.setImageUrl(httpUrl(text(row, "pic_path", "pic", "picUrl")));
            String shop = text(row.path("shopInfo"), "title");
            item.setShopName(Strings.isEmpty(shop) ? text(row, "nick", "shopName") : shop);
            item.setProductUrl(productUrl(text(row, "auctionURL", "detail_url"), itemId));
            item.setSoldCount(text(row, "realSales", "view_sales"));
            item.setLocation(text(row, "procity", "item_loc"));
            items.add(item);
        }
        return items;
    }

    static DetailData parseDetail(JsonNode res, List<JsonNode> adjusts) {
        DetailData data = new DetailData();
        if (res != null) {
            JsonNode item = res.path("item");
            data.itemId = text(item, "itemId");
            data.title = text(item, "title");
            if (Strings.isEmpty(data.title)) {
                data.title = text(res.path("componentsVO").path("titleVO").path("title"), "title");
            }
            data.shopName = text(res.path("seller"), "shopName", "sellerNick");
            addImages(data.mainImages, item.path("images"));
            addImages(data.mainImages, res.path("componentsVO").path("headImageVO").path("images"));
            collectSpecs(res, data.specs);
        }
        List<JsonNode> priceSources = new ArrayList<JsonNode>();
        if (adjusts != null) {
            for (JsonNode adjust : adjusts) {
                JsonNode adjustData = adjust == null ? null : adjust.path("data");
                if (adjustData != null && adjustData.isObject()) {
                    priceSources.add(adjustData);
                    if (data.specs.isEmpty()) {
                        collectSpecs(adjustData, data.specs);
                    }
                }
            }
        }
        if (res != null) {
            priceSources.add(res);
        }
        for (JsonNode source : priceSources) {
            JsonNode priceVO = source.path("componentsVO").path("priceVO");
            String[][] candidates = {
                    {text(priceVO.path("extraPrice"), "priceText"), text(priceVO.path("extraPrice"), "priceTitle")},
                    {text(priceVO.path("price"), "priceText"), text(priceVO.path("price"), "priceTitle")}};
            for (String[] candidate : candidates) {
                if (Strings.isEmpty(candidate[0])) {
                    continue;
                }
                if (TbResultChecks.isCleanPrice(candidate[0])) {
                    if (Strings.isEmpty(data.price)) {
                        data.price = candidate[0];
                        data.priceTitle = candidate[1];
                    }
                } else {
                    data.priceMasked = true;
                }
            }
        }
        return data;
    }

    static List<String> extractImageUrls(String body) {
        List<String> urls = new ArrayList<String>();
        if (Strings.isEmpty(body)) {
            return urls;
        }
        Matcher matcher = IMAGE_URL_PATTERN.matcher(body.replace("\\/", "/"));
        Set<String> seen = new LinkedHashSet<String>();
        while (matcher.find()) {
            String url = httpUrl(matcher.group());
            if (ICON_PATTERN.matcher(url).find()) {
                continue;
            }
            if (seen.add(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    private static void collectSpecs(JsonNode root, List<TbProductSpecItem> specs) {
        JsonNode infos = root.path("componentsVO").path("extensionInfoVO").path("infos");
        for (JsonNode info : infos) {
            String type = text(info, "type");
            if (type.startsWith("GUARANTEE")) {
                continue;
            }
            for (JsonNode entry : info.path("items")) {
                String name = text(entry, "title", "name");
                String value = joinText(entry.path("text"));
                if (Strings.isEmpty(value)) {
                    value = text(entry, "value");
                }
                addSpec(specs, name, value);
            }
        }
        if (specs.isEmpty()) {
            findPropPairs(root, specs, 0);
        }
        if (specs.isEmpty()) {
            for (JsonNode prop : root.path("skuBase").path("props")) {
                List<String> names = new ArrayList<String>();
                for (JsonNode value : prop.path("values")) {
                    String name = text(value, "name");
                    if (!Strings.isEmpty(name)) {
                        names.add(name);
                    }
                }
                addSpec(specs, text(prop, "name"), String.join("、", names));
            }
        }
    }

    private static void findPropPairs(JsonNode node, List<TbProductSpecItem> specs, int depth) {
        if (node == null || depth > 6 || !node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey().toLowerCase();
            JsonNode value = field.getValue();
            if (value.isArray() && (key.contains("props") || key.contains("param") || key.contains("attr"))
                    && !key.contains("sku")) {
                for (JsonNode row : value) {
                    addSpec(specs, text(row, "name", "key", "title", "propName"),
                            text(row, "value", "valueName", "propValue"));
                }
                if (!specs.isEmpty()) {
                    return;
                }
            } else if (value.isObject()) {
                findPropPairs(value, specs, depth + 1);
                if (!specs.isEmpty()) {
                    return;
                }
            }
        }
    }

    private static void addSpec(List<TbProductSpecItem> specs, String name, String value) {
        if (Strings.isEmpty(name) || Strings.isEmpty(value)) {
            return;
        }
        TbProductSpecItem spec = new TbProductSpecItem();
        spec.setName(name.trim());
        spec.setValue(value.trim());
        specs.add(spec);
    }

    private static String joinText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (!node.isArray()) {
            return node.isContainerNode() ? "" : node.asText("");
        }
        List<String> parts = new ArrayList<String>();
        for (JsonNode part : node) {
            if (!part.isContainerNode() && !Strings.isEmpty(part.asText(""))) {
                parts.add(part.asText(""));
            }
        }
        return String.join(" ", parts);
    }

    private static void addImages(List<String> target, JsonNode images) {
        if (images == null || !images.isArray()) {
            return;
        }
        for (JsonNode image : images) {
            String url = httpUrl(image.isTextual() ? image.asText() : text(image, "url", "image"));
            if (!Strings.isEmpty(url) && !target.contains(url)) {
                target.add(url);
            }
        }
    }

    private static String pickPrice(String... values) {
        String first = "";
        for (String value : values) {
            if (Strings.isEmpty(value)) {
                continue;
            }
            if (TbResultChecks.isCleanPrice(value)) {
                return value;
            }
            if (Strings.isEmpty(first)) {
                first = value;
            }
        }
        return first;
    }

    static String productUrl(String auctionUrl, String itemId) {
        String url = httpUrl(auctionUrl);
        if (!Strings.isEmpty(url) && url.contains("id=" + itemId)
                && (url.contains("item.taobao.com") || url.contains("detail.tmall.com"))) {
            return url;
        }
        return ITEM_URL_PREFIX + itemId;
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

    static final class DetailData {
        String itemId = "";
        String title = "";
        String price = "";
        String priceTitle = "";
        boolean priceMasked;
        String shopName = "";
        final List<String> mainImages = new ArrayList<String>();
        final List<TbProductSpecItem> specs = new ArrayList<TbProductSpecItem>();
    }
}
