package org.rx.crawler.task.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.config.ProductCacheProperties;
import org.rx.crawler.task.common.TaskApiException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 所有公开商品任务的缓存边界；内部身份解析和价格探测仍强制访问实时来源。 */
@Service
public class ProductCacheService {
    private static final Set<String> SEARCH = Set.of("jdSearchProduct", "tbSearchProduct", "jdUnionSearchProducts", "tbUnionSearchProducts");
    private static final Set<String> DETAIL = Set.of("jdProductDetail", "tbProductDetail");
    private static final Set<String> PROMOTION = Set.of("getPromotionUrl", "getTbPromotionUrl");
    private static final Set<String> STORED_FIELDS = Set.of("status","taskType","keyword","productId","itemId","skuId",
            "identityResolved","adSiteName","mediaName","profileName","productInfo","promotionUrl","promotionToken","shortUrl","longUrl",
            "productName","productLink","storeName","price","priceField","commissionRate","couponAmount","monthSales","imageUrl",
            "title","shopName","productUrl","goodRate","mainImages","mainVideos","detailImages","specs","url","fileName","name","value",
            "commentCount","soldCount","location","items","resultCount","totalCount","loginRequired","fingerprintPassed","createdAt",
            "cacheHit","cachedAt","cacheExpiresAt","errorCode");
    private final ProductCacheStore store;
    private final ObjectMapper mapper;
    private final ProductCacheProperties settings;
    private final ProductIdentityResolver identities;
    private final AppConfig config;

    public ProductCacheService(ProductCacheStore store, ObjectMapper mapper, ProductCacheProperties settings,
            ProductIdentityResolver identities, AppConfig config) {
        this.store=store; this.mapper=mapper; this.settings=settings; this.identities=identities; this.config=config;
    }
    public boolean supports(String action) { return SEARCH.contains(action) || DETAIL.contains(action) || PROMOTION.contains(action); }

    public JsonNode fresh(String action, JsonNode request) {
        if (!supports(action)) return null;
        if (request != null && request.has("useCache") && !request.path("useCache").asBoolean(true)) {
            return null;
        }
        var entry = previous(action, request);
        if (entry == null || !entry.fresh(Instant.now())) return null;
        if ("jdSearchProduct".equals(action) && !jdSearchSatisfied(request,entry.result())) return null;
        if (DETAIL.contains(action) && !detailSatisfies(platform(action),entry.result(), request)) return null;
        if ("getPromotionUrl".equals(action) && entry.result().path("productInfo").path("priceField").asText("").isBlank()
                && !entry.result().path("productInfo").path("price").asText("").isBlank()) return null;
        // Legacy link-only snapshots must be refreshed to obtain the native short command.
        if (PROMOTION.contains(action) && (entry.result().path("shortUrl").asText("").isBlank()
                || entry.result().path("promotionToken").asText("").isBlank())) return null;
        return response(entry, true);
    }

    /** 正常失败保留原状态与本次字段，只增加独立 fallback。 */
    public JsonNode complete(String action, JsonNode request, JsonNode input) {
        if (!supports(action) || !input.isObject()) return input;
        ObjectNode result=input.deepCopy();
        result.remove("fallback");
        if (!"SUCCESS".equals(result.path("status").asText())) {
            var previous=previous(action, request);
            if (previous != null) result.set("fallback", response(previous, false));
            return result;
        }
        int platform=platform(action);
        Instant now=Instant.now();
        if (SEARCH.contains(action)) {
            result.put("keyword",searchParameters(action,request).path("keyword").asText());
            boolean complete=true;
            for (JsonNode raw : result.path("items")) {
                if (!(raw instanceof ObjectNode item)) { complete=false; continue; }
                String id=numericId(platform, item);
                if (id.isEmpty() && action.contains("Union")) {
                    String opaque=item.path("encryptedProductId").asText(item.path(platform==PriceCodes.JD?"skuId":"itemId").asText(""));
                    try { id=identities.resolve(platform, "common", opaque); }
                    catch (TaskApiException e) { complete=false; continue; }
                }
                if (id.isEmpty()) { complete=false; continue; }
                normalize(item, platform, id, action.contains("Union"));
                saveProduct(platform,id,item,action.contains("Union")?"union":"basic",now);
            }
            // 不能把部分未识别结果保存为一个完整的命中缓存。
            ObjectNode snapshot=clean(result);
            if ("jdSearchProduct".equals(action) && result.path("diagnostics").path("searchExhausted").asBoolean(false))
                snapshot.put("_searchExhausted",true);
            if (complete && (!"jdSearchProduct".equals(action) || jdSearchSatisfied(request,snapshot))) store.saveSearch(searchKey(action,request),platform,action.contains("Union")?1:2,
                    searchParameters(action,request).path("keyword").asText(), searchParameters(action,request), snapshot, now,
                    now.plusSeconds(Math.max(0,settings.getSearchTtlSeconds())));
            else if (!complete) {
                result.put("status","PAGE_CHANGED").put("errorCode","PRODUCT_ID_UNRESOLVED");
                var previous=previous(action,request);
                if (previous!=null) result.set("fallback",response(previous,false));
            }
        } else if (DETAIL.contains(action)) {
            String id=detailId(platform, request);
            // 最终商品页 URL 是详情身份的唯一依据；不得把请求 ID 硬塞给不相符页面。
            var finalUrl=ProductIdentityParser.inspect(platform,result.path("currentUrl").asText(""));
            if (finalUrl.kind!=ProductIdentityParser.PageKind.PRODUCT || !id.equals(finalUrl.productId))
                throw new TaskApiException(409,"PRODUCT_ID_MISMATCH","Final product URL did not confirm numeric ID");
            result.put(platform==PriceCodes.JD?"skuId":"itemId",id);
            result.put("productUrl",ProductIdentityParser.canonicalUrl(platform,id));
            saveProduct(platform,id,result,"detail",now);
            ObjectNode snapshot=clean(result);
            snapshot.set("_options",detailOptions(platform,request));
            store.saveDetail(platform,id,snapshot,now,now.plusSeconds(Math.max(0,settings.getDetailTtlSeconds())));
        } else {
            String id=result.path("productId").asText("");
            if (!ProductIdentityParser.isNumeric(id) || !result.path("identityResolved").asBoolean(false))
                throw new TaskApiException(409,"PRODUCT_ID_UNRESOLVED","Numeric product identity was not confirmed");
            String expected=promotionId(platform,request);
            if (!expected.isEmpty() && !expected.equals(id)) throw new TaskApiException(409,"PRODUCT_ID_MISMATCH","PRODUCT_ID_MISMATCH");
            ObjectNode info=result.path("productInfo").isObject()?(ObjectNode)result.path("productInfo"):mapper.createObjectNode();
            saveProduct(platform,id,info,"union",now);
            classifyLink(result);
            store.savePromotion(platform,id,adSite(platform,request),clean(result),now,now.plusSeconds(Math.max(0,settings.getPromotionTtlSeconds())));
        }
        return result;
    }

    public JsonNode failure(String action, JsonNode request, String code) {
        ObjectNode result=mapper.createObjectNode().put("status","FAILED").put("taskType",action).put("errorCode",code);
        return complete(action,request,result);
    }
    public List<Map<String, Object>> snapshots(String platform, String id) {
        return snapshots(platform, id, 30);
    }
    public List<Map<String, Object>> snapshots(String platform, String id, int days) {
        int p = "jd".equalsIgnoreCase(platform) ? PriceCodes.JD : "tb".equalsIgnoreCase(platform) ? PriceCodes.TB : 0;
        if (p == 0 || !ProductIdentityParser.isNumeric(id)) throw new TaskApiException(400, "INVALID_PRODUCT_ID", "INVALID_PRODUCT_ID");
        if (days != 30 && days != 60 && days != 90) throw new TaskApiException(400, "INVALID_DAYS", "days must be 30, 60 or 90");
        return store.snapshots(p, id, days, java.time.ZoneId.of(config.getCustom().getPriceWatch().getTimezone()), Instant.now());
    }
    public JsonNode product(String platform, String id) {
        int p="jd".equals(platform)?PriceCodes.JD:"tb".equals(platform)?PriceCodes.TB:0;
        if (p==0 || !ProductIdentityParser.isNumeric(id)) throw new TaskApiException(400,"INVALID_PRODUCT_ID","INVALID_PRODUCT_ID");
        JsonNode product=store.product(p,id);
        if (product==null) throw new TaskApiException(404,"PRODUCT_NOT_FOUND","PRODUCT_NOT_FOUND");
        return product;
    }
    private ProductCacheStore.Entry previous(String action, JsonNode request) {
        int platform=platform(action);
        if (SEARCH.contains(action)) return store.search(searchKey(action,request));
        String id=DETAIL.contains(action)?detailId(platform,request):promotionId(platform,request);
        if (id.isEmpty()) return null;
        return DETAIL.contains(action)?store.detail(platform,id):store.promotion(platform,id,adSite(platform,request));
    }
    private ObjectNode response(ProductCacheStore.Entry entry, boolean hit) {
        ObjectNode node=entry.response(hit); node.remove("_options"); node.remove("_searchExhausted"); return node;
    }
    private boolean jdSearchSatisfied(JsonNode request, JsonNode result) {
        int wanted=request.path("maxResults").asInt(config.getCustom().getJdMall().getDefaultMaxResults());
        if(wanted<=0)wanted=config.getCustom().getJdMall().getDefaultMaxResults();
        int limit=config.getCustom().getJdMall().getMaxResultsLimit();
        wanted=Math.min(wanted,limit>0?limit:100);
        return result.path("items").size()>=wanted || result.path("_searchExhausted").asBoolean(false);
    }
    private String adSite(int platform, JsonNode request) {
        String value=request.path("adSiteName").asText("");
        if (!value.isBlank()) return value;
        value=platform==PriceCodes.TB?config.getCustom().getTbPromotion().getDefaultAdSiteName():config.getCustom().getJdUnion().getDefaultAdSiteName();
        return value==null || value.isBlank()?"0":value;
    }
    private String detailId(int platform, JsonNode request) {
        String id=request.path(platform==PriceCodes.JD?"skuId":"itemId").asText("");
        var url=ProductIdentityParser.inspect(platform,request.path("productUrl").asText(""));
        if (ProductIdentityParser.isNumeric(id)) {
            if (url.kind==ProductIdentityParser.PageKind.PRODUCT && !id.equals(url.productId))
                throw new TaskApiException(409,"PRODUCT_ID_MISMATCH","PRODUCT_ID_MISMATCH");
            return id;
        }
        return url.kind==ProductIdentityParser.PageKind.PRODUCT?url.productId:"";
    }
    private String promotionId(int platform, JsonNode request) {
        String input=request.path("keyword").asText("");
        if (ProductIdentityParser.isNumeric(input)) return input;
        var url=ProductIdentityParser.inspect(platform,input);
        return url.kind==ProductIdentityParser.PageKind.PRODUCT?url.productId:"";
    }
    private String numericId(int platform, JsonNode item) {
        String id=UnionPersistencePolicy.numericIdentity(item,platform);
        String link=item.path("productUrl").asText(item.path("productLink").asText(""));
        var url=ProductIdentityParser.inspect(platform,link);
        if (url.kind==ProductIdentityParser.PageKind.PRODUCT) {
            if (!id.isEmpty() && !id.equals(url.productId)) throw new TaskApiException(409,"PRODUCT_ID_MISMATCH","PRODUCT_ID_MISMATCH");
            return url.productId;
        }
        return id;
    }
    private void normalize(ObjectNode item, int platform, String id, boolean union) {
        item.put("productId",id); item.put(platform==PriceCodes.JD?"skuId":"itemId",id);
        item.remove("encryptedProductId");
        item.put(union?"productLink":"productUrl",ProductIdentityParser.canonicalUrl(platform,id));
        if (union) item.put("identityResolved",true);
    }
    private void saveProduct(int platform, String id, JsonNode raw, String source, Instant now) {
        ObjectNode basic=mapper.createObjectNode();
        for (String key:new String[]{"productName","storeName","price","priceField","imageUrl"}) if(raw.has(key)) basic.set(key,raw.get(key));
        if(raw.hasNonNull("title")) basic.set("productName",raw.get("title"));
        if(raw.hasNonNull("shopName")) basic.set("storeName",raw.get("shopName"));
        basic.put("productId",id); basic.put("productLink",ProductIdentityParser.canonicalUrl(platform,id));
        ObjectNode fields=clean(raw);
        // 主档只放商品资料；推广链接和临时材料属于转链缓存。
        fields.remove(java.util.List.of("promotionUrl","shortUrl","longUrl","promotionToken","keyword","items","resultCount",
                "cacheHit","cachedAt","cacheExpiresAt","status","taskType","profileName","createdAt","loginRequired","fingerprintPassed"));
        fields.remove("productUrl"); fields.put("productId",id); fields.put("productLink",ProductIdentityParser.canonicalUrl(platform,id));
        fields.remove("itemId"); fields.remove("skuId");
        store.mergeProduct(platform,id,basic,fields,source,now);
    }
    private ObjectNode clean(JsonNode input) {
        ObjectNode result=input.deepCopy();
        strip(result);
        return result;
    }
    private void strip(JsonNode node) {
        if (node.isObject()) {
            var object=(ObjectNode)node;
            var remove=new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(key -> { if(!STORED_FIELDS.contains(key)) remove.add(key); });
            object.remove(remove);
            object.elements().forEachRemaining(this::strip);
        } else if(node.isArray()) node.forEach(this::strip);
    }
    private ObjectNode searchParameters(String action, JsonNode request) {
        // 不把调试路径、登录等待等执行选项放入缓存键；影响结果完整性的参数必须区分。
        var parameters=new TreeMap<String,JsonNode>();
        request.fields().forEachRemaining(e -> { if(Set.of("keyword","maxResults","sort","page","pageSize","categoryId").contains(e.getKey())) parameters.put(e.getKey(),e.getValue()); });
        ObjectNode node=mapper.valueToTree(parameters);
        String keyword=request.path("keyword").asText().trim();
        if (org.rx.crawler.task.tb.TbUnionAnalysisPage.isProductInput(keyword) && !ProductIdentityParser.isNumeric(keyword))
            keyword=identities.resolve(platform(action),"common",keyword);
        node.put("keyword",keyword);
        return node;
    }
    private String searchKey(String action, JsonNode request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((action+"|"+searchParameters(action,request)).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private ObjectNode detailOptions(int platform, JsonNode request) {
        ObjectNode options=mapper.createObjectNode();
        options.put("includeSpecs",request.path("includeSpecs").asBoolean(true));
        options.put("downloadImages",request.path("downloadImages").asBoolean(platform==PriceCodes.JD));
        return options;
    }
    private boolean detailSatisfies(int platform, JsonNode cached, JsonNode request) {
        JsonNode have=cached.path("_options"), want=detailOptions(platform,request);
        return (!want.path("includeSpecs").asBoolean() || have.path("includeSpecs").asBoolean())
                && (!want.path("downloadImages").asBoolean() || have.path("downloadImages").asBoolean());
    }
    private void classifyLink(ObjectNode result) {
        String link=result.path("promotionUrl").asText("");
        try {
            String host=java.net.URI.create(link).getHost();
            boolean shortLink=Set.of("u.jd.com","3.cn","m.tb.cn","e.tb.cn","tb.cn","3.jd.hk","u.jd.hk").contains(host);
            if (!result.hasNonNull(shortLink?"shortUrl":"longUrl")) result.put(shortLink?"shortUrl":"longUrl",link);
        } catch(Exception ignored) { }
    }
    private int platform(String action) { return action.startsWith("tb") || action.startsWith("getTb")?PriceCodes.TB:PriceCodes.JD; }
}
