package org.rx.crawler.task.jd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.DebugSnapshots;
import org.rx.crawler.task.common.HumanPageNoise;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.CrawlEntryResult;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.CustomCrawlTask;
import org.rx.crawler.task.common.KeepAliveUrlStore;
import org.rx.crawler.task.common.LoginNotificationContext;
import org.rx.crawler.task.common.ProductInfoDto;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.crawler.task.common.PromotionSlots;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.price.PriceCodes;
import org.rx.exception.InvalidException;
import org.rx.util.BeanMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.rx.core.Extends.tryClose;

@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class JdUnionPromotionTask  implements CustomCrawlTask<PromotionUrlRequest, PromotionUrlResult> {
    private static final String TASK_TYPE = "getPromotionUrl";
    private static final String SEARCH_TASK_TYPE = "jdUnionSearchProducts";
    private static final String ORDERS_TASK_TYPE = "getPromotionOrders";
    private static final String PRIMARY_PROMOTE_SELECTOR = "[data-rx-jd-primary-promote='1']";
    private static final Pattern SEARCH_RESULT_COUNT_PATTERN = Pattern.compile("所有结果\\s*共\\s*(\\d+)\\s*件商品");
    private static final DateTimeFormatter DEBUG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AppConfig appConfig;
    private final BrowserProfileManager profileManager;
    private final CrawlEntryService entryService;
    private final ObjectMapper objectMapper;
    private final KeepAliveUrlStore keepAliveUrlStore;
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private org.rx.crawler.task.price.ProductIdentityResolver identities;

    private void normalizeIdentity(PromotionUrlRequest request) {
        if (identities != null && request != null) {
            String value = request.getKeyword();
            if (org.rx.crawler.task.price.ProductIdentityResolver.isEncrypted(value) || (value != null && value.startsWith("http")))
                request.setKeyword(identities.resolve(org.rx.crawler.task.price.PriceCodes.JD, request.getProfileName(), value));
        }
    }

    @Autowired(required = false)
    private org.rx.crawler.task.price.ProductVisitBudget visitBudget;
    private void reserveProductVisit() {
        if (visitBudget != null) visitBudget.reserve(org.rx.crawler.task.price.PriceCodes.JD,
                org.rx.crawler.task.price.ProductVisitBudget.isBackground());
    }

    private final ThreadLocal<Long> taskDeadlineHolder = new ThreadLocal<Long>();

    public JdUnionPromotionTask(AppConfig appConfig, BrowserProfileManager profileManager, CrawlEntryService entryService,
            ObjectMapper objectMapper) {
        this(appConfig, profileManager, entryService, objectMapper, KeepAliveUrlStore.NOOP);
    }

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public PromotionUrlResult execute(PromotionUrlRequest request) {
        return getPromotionUrl(request);
    }

    public PromotionUrlResult getPromotionUrl(PromotionUrlRequest request) {
        normalizeIdentity(request);
        try (var scope = org.rx.crawler.task.common.SensitiveCrawlScope.open()) {
            return executeInternal(request, true);
        }
    }

    public JdUnionPromotionOrdersResult getPromotionOrders(JdUnionPromotionOrdersRequest request) {
        return executeOrdersInternal(request);
    }

    public PromotionUrlResult loginCheck(PromotionUrlRequest request) {
        return executeInternal(request, false);
    }

    public boolean closeProfile(String profileName) {
        return profileManager.closeSession(profileName);
    }

    public List<PromotionUrlResult> getPromotionUrls(List<String> keywords) {
        try (var scope = org.rx.crawler.task.common.SensitiveCrawlScope.open()) {
            return executeBatchInternal(keywords);
        }
    }

    public JdUnionProductSearchResult searchProducts(JdUnionProductSearchRequest request) {
        if (identities != null && request != null) {
            String value = request.getKeyword();
            if (org.rx.crawler.task.price.ProductIdentityResolver.isEncrypted(value) || (value != null && value.startsWith("http")))
                request.setKeyword(identities.resolve(org.rx.crawler.task.price.PriceCodes.JD, request.getProfileName(), value));
        }
        try (var scope = org.rx.crawler.task.common.SensitiveCrawlScope.open()) {
            return executeSearchInternal(request);
        }
    }

    private JdUnionPromotionOrdersResult executeOrdersInternal(JdUnionPromotionOrdersRequest rawRequest) {
        JdUnionConfig jdConfig = appConfig.getCustom().getJdUnion();
        JdUnionPromotionOrdersRequest request = normalizeOrdersRequest(rawRequest, jdConfig);
        JdUnionPromotionOrdersResult result = createOrdersResult(request);
        PromotionUrlRequest debugRequest = toDebugRequest(request);
        DebugRecorder debug = new DebugRecorder(debugRequest, jdConfig, objectMapper, ORDERS_TASK_TYPE);
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }
        BrowserProfileManager.ProfileLease lease = null;
        taskDeadlineHolder.set(System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(Math.max(1, appConfig.getCustom().getMaxTaskMinutes())));
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(debugRequest, jdConfig));
            Browser browser = lease.getBrowser();

            CrawlEntryResult entry = entryService.enter(browser, lease,
                    createEntryOptions(debugRequest, jdConfig, jdConfig.getEntireUrl(), ORDERS_TASK_TYPE));
            applyEntryResult(result, entry);
            debug.snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", result.getMessage());
                return result;
            }
            runOrdersFlow(browser, request, jdConfig, result, debug);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("JD union promotion orders fail, startTime={}, endTime={}, profile={}, error={}",
                    request.getStartTime(), request.getEndTime(), request.getProfileName(), e.getMessage(), e);
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private PromotionUrlResult executeInternal(PromotionUrlRequest rawRequest, boolean doPromotion) {
        if (doPromotion) reserveProductVisit();
        JdUnionConfig jdConfig = appConfig.getCustom().getJdUnion();
        PromotionUrlRequest request = normalizeRequest(rawRequest, jdConfig);
        if (doPromotion && Strings.isEmpty(request.getKeyword())) {
            throw new InvalidException("keyword is required");
        }
        PromotionUrlResult result = createResult(request);
        DebugRecorder debug = new DebugRecorder(request, jdConfig, objectMapper);
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }
        BrowserProfileManager.ProfileLease lease = null;
        taskDeadlineHolder.set(System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(Math.max(1, appConfig.getCustom().getMaxTaskMinutes())));
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(request, jdConfig));
            Browser browser = lease.getBrowser();

            CrawlEntryResult entry = entryService.enter(browser, lease, createEntryOptions(request, jdConfig));
            applyEntryResult(result, entry);
            debug.snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", result.getMessage());
                return result;
            }
            if (!doPromotion) {
                result.setStatus(CustomCrawlStatus.SUCCESS);
                result.setMessage("JD Union login state is valid");
                keepAliveUrlStore.collect("jd", browser, result.getDiagnostics());
                return result;
            }
            runPromotionFlow(browser, request, jdConfig, result, debug);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("JD union promotion fail, keyword={}, profile={}, error={}",
                    "[product]", request.getProfileName(), e.getClass().getSimpleName());
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private JdUnionProductSearchResult executeSearchInternal(JdUnionProductSearchRequest rawRequest) {
        reserveProductVisit();
        JdUnionConfig jdConfig = appConfig.getCustom().getJdUnion();
        JdUnionProductSearchRequest request = normalizeSearchRequest(rawRequest, jdConfig);
        if (Strings.isEmpty(request.getKeyword())) {
            throw new InvalidException("keyword is required");
        }
        JdUnionProductSearchResult result = createSearchResult(request);
        PromotionUrlRequest entryRequest = toPromotionRequest(request, jdConfig);
        DebugRecorder debug = new DebugRecorder(entryRequest, jdConfig, objectMapper, SEARCH_TASK_TYPE);
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }
        BrowserProfileManager.ProfileLease lease = null;
        taskDeadlineHolder.set(System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(Math.max(1, appConfig.getCustom().getMaxTaskMinutes())));
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(entryRequest, jdConfig));
            Browser browser = lease.getBrowser();
            CrawlEntryResult entry = entryService.enter(browser, lease,
                    createEntryOptions(entryRequest, jdConfig, jdConfig.getEntireUrl(), SEARCH_TASK_TYPE));
            PromotionUrlResult gate = createResult(entryRequest);
            applyEntryResult(gate, entry);
            copyGate(result, gate);
            debug.snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", result.getMessage());
                return result;
            }
            if (!preparePromotionWorkbench(browser, jdConfig, gate, debug)) {
                copyGate(result, gate);
                return result;
            }
            runProductSearchFlow(browser, request, jdConfig, result, debug);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("JD union product search fail, keyword={}, profile={}, error={}",
                    "[product]", request.getProfileName(), e.getClass().getSimpleName());
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private void runProductSearchFlow(Browser browser, JdUnionProductSearchRequest request, JdUnionConfig config,
            JdUnionProductSearchResult result, DebugRecorder debug) throws TimeoutException {
        JdUnionPageApi.SearchHit ajax = new JdUnionPageApi(objectMapper)
                .searchProducts(browser, request.getKeyword(), request.getMaxResults());
        result.getDiagnostics().put("ajaxSearch", ajax.diagnostic);
        if (ajax.success) {
            if (ajax.items.isEmpty()) {
                fail(result, CustomCrawlStatus.NOT_FOUND, "JD Union product not found");
                result.getDiagnostics().put("channel", "ajax");
                debug.snapshot(browser, "06-search-no-result");
                return;
            }
            if (publishSearchItems(result, ajax.items, "ajax")) {
                result.setCurrentUrl(browser.getCurrentUrl());
                keepAliveUrlStore.collect("jd", browser, result.getDiagnostics());
                debug.snapshot(browser, "06-search-cards-collected");
                return;
            }
            result.getDiagnostics().put("ajaxSearchValidation", result.getDiagnostics().get("validationErrors"));
        }
        result.getDiagnostics().put("channel", "page");
        if (!nativeSetSearchValue(browser, request.getKeyword())) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union search input not found or value not applied");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "03-search-input-missing");
            return;
        }
        debug.snapshot(browser, "03-search-input-ready");
        if (!nativeClickSearchButton(browser)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union search button not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "04-search-button-missing");
            return;
        }
        String searchBody = waitPromotionCardBody(browser, config, 20);
        debug.snapshot(browser, "04-search-clicked");
        result.setCurrentUrl(browser.getCurrentUrl());
        if (containsAny(searchBody, "抱歉，没有找到相关商品", "没有找到相关商品", "暂无相关商品")) {
            fail(result, CustomCrawlStatus.NOT_FOUND, "JD Union product not found");
            result.getDiagnostics().put("body", searchBody);
            debug.snapshot(browser, "05-search-no-result");
            return;
        }
        List<JdUnionProductItem> items = readPromotionCards(browser, request.getMaxResults());
        if (items.isEmpty()) {
            Extends.sleep(Math.max(300, config.nextStepDelayMillis() / 2));
            items = readPromotionCards(browser, request.getMaxResults());
        }
        result.getDiagnostics().put("searchResultCount", readSearchResultCount(searchBody));
        if (items.isEmpty()) {
            fail(result, CustomCrawlStatus.NOT_FOUND, "JD Union promotion cards not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "06-search-cards-missing");
            return;
        }
        if (!publishSearchItems(result, items, "page")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union search cards missing required fields");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "06-search-cards-invalid");
            return;
        }
        keepAliveUrlStore.collect("jd", browser, result.getDiagnostics());
        debug.snapshot(browser, "06-search-cards-collected");
    }

    private boolean publishSearchItems(JdUnionProductSearchResult result, List<JdUnionProductItem> items, String channel) {
        List<String> errors = new ArrayList<String>();
        List<JdUnionProductItem> complete = JdResultChecks.completeSearchItems(items, errors);
        if (complete.isEmpty()) {
            result.getDiagnostics().put("validationErrors", errors);
            return false;
        }
        if (!errors.isEmpty()) {
            result.getDiagnostics().put("droppedIncompleteItems", errors.size());
        }
        result.setItems(complete);
        result.setResultCount(complete.size());
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("");
        result.setLoginRequired(false);
        result.getDiagnostics().put("channel", channel);
        result.getDiagnostics().put("searchResultCount", complete.size());
        return true;
    }

    private boolean publishPromotion(PromotionUrlResult result, String promotionUrl, ProductInfoDto info,
            String keyword, String channel) {
        List<String> errors = JdResultChecks.promotion(promotionUrl, info, keyword);
        if (!errors.isEmpty()) {
            result.getDiagnostics().put("validationErrors", errors);
            return false;
        }
        result.setProductInfo(info);
        result.setPromotionUrl(promotionUrl);
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("");
        result.getDiagnostics().put("channel", channel);
        return true;
    }

    private String waitPromotionCardBody(Browser browser, JdUnionConfig config, int maxSeconds) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, maxSeconds) * 1000L;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("jdUnionSearchProducts.waitCards");
            body = bodySnippet(browser);
            if (containsAny(body, "抱歉，没有找到相关商品", "没有找到相关商品", "暂无相关商品")) {
                return body;
            }
            if (!readPromotionCards(browser, 1).isEmpty()) {
                return body;
            }
            int count = readSearchResultCount(body);
            if (count == 0) {
                return body;
            }
            Extends.sleep(Math.max(400, config.nextStepDelayMillis()));
        }
        return body;
    }

    private List<JdUnionProductItem> readPromotionCards(Browser browser, int maxResults) {
        int limit = Math.max(1, maxResults);
        String script = "var limit=arguments[0];" +
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){if(!el){return false;}var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function text(el){return norm(el.innerText||el.textContent||'');}" +
                "function hrefOf(a){var href=a.href||a.getAttribute('href')||'';" +
                "return href.indexOf('//')===0?location.protocol+href:href;}" +
                "function skuOf(href){var m=String(href||'').match(/item\\.jd\\.com\\/(\\d+)/);return m?m[1]:'';}" +
                "var cards=Array.prototype.slice.call(document.querySelectorAll('.card.search-card'));" +
                "var out=[];var seen={};" +
                "for(var i=0;i<cards.length&&out.length<limit;i++){var card=cards[i];" +
                "if(card.closest&&card.closest('.recommend-goods,[class*=\"recommend\"]')){continue;}" +
                "var raw=text(card);if(raw.indexOf('我要推广')<0&&raw.indexOf('一键领链')<0){continue;}" +
                "var anchors=Array.prototype.slice.call(card.querySelectorAll('a[href*=\"item.jd.com\"]'));" +
                "var link='',name='',sku='';" +
                "for(var a=0;a<anchors.length;a++){var node=anchors[a];if(!visible(node)){continue;}" +
                "var href=hrefOf(node),id=skuOf(href);if(!id){continue;}" +
                "var title=norm(node.getAttribute('title')||node.innerText||'');" +
                "if(/我要推广|一键领链|查看全部|更多|清空|奖励活动|去报名/.test(title)){continue;}" +
                "if(!sku||title.length>name.length){link=href;sku=id;if(title.length>4){name=title;}}}" +
                "if(!sku||seen[sku]){continue;}seen[sku]=1;" +
                "if(!name){var titled=card.querySelector('p.two a[title],p[class*=two] a[title]');" +
                "if(titled){name=norm(titled.getAttribute('title')||titled.innerText||'');}}" +
                "var priceM=raw.match(/到手价\\s*[￥¥]?\\s*([0-9.]+)/)||raw.match(/[￥¥]\\s*([0-9.]+)/);" +
                "var rateM=raw.match(/佣金比例[:：]?\\s*([0-9.]+%)/);" +
                "var img=card.querySelector('img');" +
                "var image=img?(img.currentSrc||img.getAttribute('data-src')||img.src||''):'';" +
                "var store='';var nodes=card.querySelectorAll('a,span');" +
                "for(var s=0;s<nodes.length;s++){var st=text(nodes[s]);" +
                "if(st&&st.length<=50&&/旗舰店|专卖店|官方|店铺|专区|店$/.test(st)&&st.indexOf('我要推广')<0){store=st;break;}}" +
                "out.push({skuId:sku,productName:name,productLink:link,price:priceM?priceM[1]:''," +
                "commissionRate:rateM?rateM[1]:'',storeName:store,imageUrl:image});}" +
                "return out;";
        try {
            Object raw = browser.executeScript(script, limit);
            if (raw == null) {
                return new ArrayList<JdUnionProductItem>();
            }
            List<JdUnionProductItem> items = objectMapper.convertValue(raw,
                    new TypeReference<List<JdUnionProductItem>>() {
                    });
            List<JdUnionProductItem> ready = new ArrayList<JdUnionProductItem>();
            if (items == null) {
                return ready;
            }
            for (JdUnionProductItem item : items) {
                if (item == null || Strings.isEmpty(item.getSkuId()) || !item.getSkuId().matches("\\d+")) {
                    continue;
                }
                if (Strings.isEmpty(item.getProductLink())) {
                    item.setProductLink("https://item.jd.com/" + item.getSkuId() + ".html");
                } else if (item.getProductLink().startsWith("//")) {
                    item.setProductLink("https:" + item.getProductLink());
                }
                item.setImageUrl(normalizeHttpUrl(item.getImageUrl()));
                String linkedSku = JdLoginState.skuIdFromLink(item.getProductLink());
                if (!item.getSkuId().equals(linkedSku) && !Strings.isEmpty(linkedSku)) {
                    item.setSkuId(linkedSku);
                }
                ready.add(item);
            }
            return ready;
        } catch (Exception e) {
            log.warn("Read JD union promotion cards fail, error={}", e.getMessage());
            return new ArrayList<JdUnionProductItem>();
        }
    }

    private JdUnionProductSearchRequest normalizeSearchRequest(JdUnionProductSearchRequest request, JdUnionConfig config) {
        if (request == null) {
            request = new JdUnionProductSearchRequest();
        }
        if (request.getMaxResults() == null || request.getMaxResults() <= 0) {
            request.setMaxResults(20);
        }
        if (request.getMaxResults() > 40) {
            request.setMaxResults(40);
        }
        if (Strings.isEmpty(request.getProfileName())) {
            request.setProfileName(Strings.isEmpty(config.getProfileName())
                    ? profileManager.defaultProfileName() : config.getProfileName());
        }
        request.setProfileName(profileManager.normalizeProfileName(request.getProfileName()));
        return request;
    }

    private PromotionUrlRequest toPromotionRequest(JdUnionProductSearchRequest request, JdUnionConfig config) {
        PromotionUrlRequest entryRequest = new PromotionUrlRequest();
        entryRequest.setKeyword(request.getKeyword());
        entryRequest.setAdSiteName(config.getDefaultAdSiteName());
        entryRequest.setMediaName(config.getDefaultMediaName());
        entryRequest.setProfileName(request.getProfileName());
        entryRequest.setForcePreflight(request.getForcePreflight());
        entryRequest.setKeepBrowserOpenOnLoginRequired(request.getKeepBrowserOpenOnLoginRequired());
        entryRequest.setDebugEnabled(request.getDebugEnabled());
        entryRequest.setDebugOutputDir(request.getDebugOutputDir());
        return normalizeRequest(entryRequest, config);
    }

    private JdUnionProductSearchResult createSearchResult(JdUnionProductSearchRequest request) {
        JdUnionProductSearchResult result = new JdUnionProductSearchResult();
        result.setTaskType(SEARCH_TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setKeyword(request.getKeyword());
        result.setProfileName(request.getProfileName());
        return result;
    }

    private void copyGate(JdUnionProductSearchResult result, PromotionUrlResult gate) {
        result.setCurrentUrl(gate.getCurrentUrl());
        result.setLoginRequired(gate.isLoginRequired());
        result.setFingerprintPassed(gate.isFingerprintPassed());
        result.getDiagnostics().putAll(gate.getDiagnostics());
        if (gate.getStatus() != null && gate.getStatus() != CustomCrawlStatus.FAILED) {
            result.setStatus(gate.getStatus());
        }
        if (!Strings.isEmpty(gate.getMessage())) {
            result.setMessage(gate.getMessage());
        }
    }

    private void fail(JdUnionProductSearchResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message == null ? "" : message);
        if (status == CustomCrawlStatus.LOGIN_REQUIRED) {
            result.setLoginRequired(true);
        }
    }

    private List<PromotionUrlResult> executeBatchInternal(List<String> keywords) {
        JdUnionConfig jdConfig = appConfig.getCustom().getJdUnion();
        List<PromotionUrlRequest> requests = createBatchRequests(keywords, jdConfig);
        List<PromotionUrlResult> results = new ArrayList<PromotionUrlResult>();
        List<DebugRecorder> debugs = new ArrayList<DebugRecorder>();
        for (PromotionUrlRequest request : requests) {
            PromotionUrlResult result = createResult(request);
            DebugRecorder debug = new DebugRecorder(request, jdConfig, objectMapper);
            if (debug.enabled()) {
                result.getDiagnostics().put("debugEnabled", true);
                result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
            }
            results.add(result);
            debugs.add(debug);
        }
        if (requests.isEmpty()) {
            return results;
        }

        BrowserProfileManager.ProfileLease lease = null;
        taskDeadlineHolder.set(System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(Math.max(1, appConfig.getCustom().getMaxTaskMinutes())
                * Math.max(1, requests.size())));
        try {
            lease = profileManager.acquire(requests.get(0).getProfileName(), createBrowserConfig(requests.get(0), jdConfig));
            Browser browser = lease.getBrowser();

            CrawlEntryResult entry = entryService.enter(browser, lease, createEntryOptions(requests.get(0), jdConfig));
            for (PromotionUrlResult result : results) {
                applyEntryResult(result, entry);
            }
            debugs.get(0).snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                for (DebugRecorder debug : debugs) {
                    debug.snapshotText("01-entry-failed", entry.getMessage());
                }
                return results;
            }

            if (!preparePromotionWorkbench(browser, jdConfig, results.get(0), debugs.get(0))) {
                copyFailureToRemaining(results);
                return results;
            }

            for (int i = 0; i < requests.size(); i++) {
                PromotionUrlRequest request = requests.get(i);
                PromotionUrlResult result = results.get(i);
                DebugRecorder debug = debugs.get(i);
                try {
                    reserveProductVisit();
                    if (i > 0 && !ensurePromotionWorkbenchForNextJdKeyword(browser, jdConfig, result, debug)) {
                        continue;
                    }
                    runPromotionKeywordFlow(browser, request, jdConfig, result, debug);
                } catch (TimeoutException e) {
                    fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
                    debug.snapshotText("99-timeout", result.getMessage());
                    failRemaining(results, i + 1, CustomCrawlStatus.TIMEOUT, e.getMessage());
                    return results;
                } catch (Exception e) {
                    log.warn("JD union batch promotion fail, keyword={}, profile={}, error={}",
                            "[product]", request.getProfileName(), e.getClass().getSimpleName());
                    fail(result, CustomCrawlStatus.FAILED, e.getMessage());
                    debug.snapshotText("99-failed", result.getMessage());
                }
            }
            return results;
        } catch (TimeoutException e) {
            failRemaining(results, 0, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debugs.get(0).snapshotText("99-timeout", e.getMessage());
            return results;
        } catch (Exception e) {
            log.warn("JD union batch promotion fail, profile={}, error={}",
                    requests.get(0).getProfileName(), e.getMessage(), e);
            failRemaining(results, 0, CustomCrawlStatus.FAILED, e.getMessage());
            debugs.get(0).snapshotText("99-failed", e.getMessage());
            return results;
        } finally {
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private List<PromotionUrlRequest> createBatchRequests(List<String> keywords, JdUnionConfig config) {
        List<PromotionUrlRequest> requests = new ArrayList<PromotionUrlRequest>();
        if (keywords == null) {
            return requests;
        }
        for (String keyword : keywords) {
            if (Strings.isEmpty(keyword)) {
                throw new InvalidException("keyword is required");
            }
            PromotionUrlRequest request = new PromotionUrlRequest();
            request.setKeyword(keyword);
            request.setAdSiteName(config.getDefaultAdSiteName());
            requests.add(normalizeRequest(request, config));
        }
        return requests;
    }

    private void copyFailureToRemaining(List<PromotionUrlResult> results) {
        if (results.isEmpty()) {
            return;
        }
        PromotionUrlResult source = results.get(0);
        for (int i = 1; i < results.size(); i++) {
            PromotionUrlResult result = results.get(i);
            result.setCurrentUrl(source.getCurrentUrl());
            result.setLoginRequired(source.isLoginRequired());
            result.setFingerprintPassed(source.isFingerprintPassed());
            result.getDiagnostics().putAll(source.getDiagnostics());
            fail(result, source.getStatus(), source.getMessage());
        }
    }

    private void failRemaining(List<PromotionUrlResult> results, int startIndex, CustomCrawlStatus status, String message) {
        for (int i = Math.max(0, startIndex); i < results.size(); i++) {
            PromotionUrlResult result = results.get(i);
            if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
                continue;
            }
            fail(result, status, message);
        }
    }

    private CrawlEntryOptions createEntryOptions(PromotionUrlRequest request, JdUnionConfig config) {
        return createEntryOptions(request, config, config.getEntireUrl(), TASK_TYPE);
    }

    private CrawlEntryOptions createEntryOptions(PromotionUrlRequest request, JdUnionConfig config, String initialUrl) {
        return createEntryOptions(request, config, initialUrl, TASK_TYPE);
    }

    private CrawlEntryOptions createEntryOptions(PromotionUrlRequest request, JdUnionConfig config, String initialUrl,
            String taskType) {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setTaskType(taskType);
        options.setProfileName(request.getProfileName());
        options.setPreflightEnabled(config.isPreflightEnabled());
        options.setPreflightUrl(config.getPreflightUrl());
        options.setPreflightStrict(config.isPreflightStrict());
        options.setPreflightCacheMinutes(config.getPreflightCacheMinutes());
        options.setForcePreflight(request.getForcePreflight() == null ? config.isForcePreflight() : request.getForcePreflight());
        options.setInitialUrl(Strings.isEmpty(initialUrl) ? config.getOverviewUrl() : initialUrl);
        options.setLoginUrlPrefix(config.getLoginUrlPrefix());
        options.setInitialPageTimeoutSeconds(config.getInitialPageTimeoutSeconds());
        options.setLoginWaitSeconds(config.getLoginWaitSeconds());
        options.setStepDelayMillis(config.getStepDelayMillis());
        options.setStepDelayRandomMillis(config.getStepDelayRandomMillis());
        options.setKeepBrowserOpenOnLoginRequired(request.getKeepBrowserOpenOnLoginRequired() == null
                ? config.isKeepBrowserOpenOnLoginRequired() : request.getKeepBrowserOpenOnLoginRequired());
        options.setKeepBrowserOpenSecondsOnLoginRequired(config.getKeepBrowserOpenSecondsOnLoginRequired());
        options.setLoginRequiredUrlMatcher(url -> isLoginRequired(url, config));
        // body 判登录：解决 overview/promanager URL 上仍显示登录页、却不进人工等待的问题
        options.setLoginRequiredBodyMatcher(this::looksLikeJdLoginBody);
        options.setLoggedInUrlMatcher(url -> isLoggedInUrl(url) || isOverviewUrl(url, config));
        options.setLoggedInBodyMatcher(this::looksLikeJdLoggedInBody);
        return options;
    }

    private void applyEntryResult(PromotionUrlResult result, CrawlEntryResult entry) {
        result.setFingerprintPassed(entry.isFingerprintPassed());
        result.setCurrentUrl(entry.getCurrentUrl());
        result.setLoginRequired(entry.isLoginRequired());
        result.getDiagnostics().putAll(entry.getDiagnostics());
        if (!entry.isPassed()) {
            fail(result, entry.getStatus(), entry.getMessage());
        }
    }

    private void applyEntryResult(JdUnionPromotionOrdersResult result, CrawlEntryResult entry) {
        result.setFingerprintPassed(entry.isFingerprintPassed());
        result.setCurrentUrl(entry.getCurrentUrl());
        result.setLoginRequired(entry.isLoginRequired());
        result.getDiagnostics().putAll(entry.getDiagnostics());
        if (!entry.isPassed()) {
            fail(result, entry.getStatus(), entry.getMessage());
        }
    }

    private void runPromotionFlow(Browser browser, PromotionUrlRequest request, JdUnionConfig config,
            PromotionUrlResult result, DebugRecorder debug) throws TimeoutException {
        if (!preparePromotionWorkbench(browser, config, result, debug)) {
            return;
        }
        runPromotionKeywordFlow(browser, request, config, result, debug);
    }

    private boolean preparePromotionWorkbench(Browser browser, JdUnionConfig config, PromotionUrlResult result,
            DebugRecorder debug) throws TimeoutException {
        if (!enterPromotionWorkbench(browser, config, result, debug)) {
            return false;
        }
        result.setCurrentUrl(browser.getCurrentUrl());
        debug.snapshot(browser, "02-workbench-ready");
        return true;
    }

    private void runPromotionKeywordFlow(Browser browser, PromotionUrlRequest request, JdUnionConfig config,
            PromotionUrlResult result, DebugRecorder debug) throws TimeoutException {
        JdUnionPageApi.PromotionHit ajax = new JdUnionPageApi(objectMapper).promote(browser, request.getKeyword(),
                request.getMediaName(), request.getAdSiteName(),
                PromotionSlots.idOf(config.getAdSites(), request.getAdSiteName()),
                config.getDefaultMediaId(), config.getDefaultMediaType());
        result.getDiagnostics().put("ajaxPromotion", ajax.diagnostic);
        if (ajax.success && publishPromotion(result, ajax.promotionUrl, ajax.productInfo, request.getKeyword(), "ajax")) {
            result.setPromotionToken(ajax.promotionToken);
            result.setShortUrl(ajax.shortUrl);
            result.setLongUrl(ajax.longUrl);
            result.setCurrentUrl(browser.getCurrentUrl());
            keepAliveUrlStore.collect("jd", browser, result.getDiagnostics());
            debug.snapshot(browser, "14-promotion-link-ready");
            return;
        }
        if (ajax.success) {
            result.getDiagnostics().put("ajaxPromotionValidation", result.getDiagnostics().get("validationErrors"));
        }
        result.getDiagnostics().put("channel", "page");
        if (!nativeSetSearchValue(browser, request.getKeyword())) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union search input not found or value not applied");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "03-search-input-missing");
            return;
        }
        debug.snapshot(browser, "03-search-input-ready");
        if (!nativeClickSearchButton(browser)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union search button not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "04-search-button-missing");
            return;
        }
        // 必须等到「所有结果 共N件商品」或明确无结果；禁止用页面泛化的「共/件商品」提前退出
        String searchBody = waitSearchResultBody(browser, config, request.getKeyword(), 15);
        debug.snapshot(browser, "04-search-clicked");

        if (containsAny(searchBody, "抱歉，没有找到相关商品", "没有找到相关商品", "暂无相关商品")) {
            fail(result, CustomCrawlStatus.NOT_FOUND, "JD Union product not found");
            result.getDiagnostics().put("body", searchBody);
            debug.snapshot(browser, "05-search-no-result");
            return;
        }

        int searchResultCount = readSearchResultCount(searchBody);
        result.getDiagnostics().put("searchResultCount", searchResultCount);
        if (searchResultCount < 0) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union search result not ready");
            result.getDiagnostics().put("body", searchBody);
            debug.snapshot(browser, "06-search-result-not-ready");
            return;
        }
        if (searchResultCount == 0) {
            fail(result, CustomCrawlStatus.NOT_FOUND, "JD Union product not found");
            result.getDiagnostics().put("body", searchBody);
            debug.snapshot(browser, "06-search-result-count-zero");
            return;
        }
        if (searchResultCount > 1) {
            fail(result, CustomCrawlStatus.MULTIPLE_MATCHED, "JD Union search result is not unique");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "06-search-result-not-unique");
            return;
        }
        ProductInfoDto productInfo = readProductInfo(browser, request.getKeyword());
        if (productInfo == null) {
            // 结果区刚渲染时再采一次
            Extends.sleep(Math.max(300, config.nextStepDelayMillis() / 2));
            productInfo = readProductInfo(browser, request.getKeyword());
        }
        if (productInfo == null) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union product info not found on search card");
            result.getDiagnostics().put("productInfoMissing", true);
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "06-product-info-missing");
            return;
        }
        result.setProductInfo(productInfo);
        if (!jdProductMatchesKeyword(productInfo, request.getKeyword())) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union search result does not match keyword");
            result.getDiagnostics().put("body", bodySnippet(browser));
            result.getDiagnostics().put("productName", productInfo.getProductName());
            result.getDiagnostics().put("productLink", productInfo.getProductLink());
            debug.snapshot(browser, "06-product-info-not-matched");
            return;
        }
        debug.snapshot(browser, "06-product-info-collected");
        // 滚动+点击合并：避免多次 mouse wheel 假滚动像乱点
        if (!nativeClickPrimaryPromoteButton(browser, request.getKeyword())) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union primary promotion entry not found");
            result.getDiagnostics().put("primaryPromoteScroll", readPrimaryPromoteScrollMetrics(browser));
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "07-primary-promote-missing");
            return;
        }
        result.getDiagnostics().put("primaryPromoteScroll", readPrimaryPromoteScrollMetrics(browser));
        debug.snapshot(browser, "07-primary-promote-clicked");
        // 权益弹层可选：仅文案出现时点，避免模糊匹配「继续推广」误点其它按钮
        if (pageTextContains(browser, "已获取权益，继续推广")) {
            waitAndClickText(browser, "已获取权益，继续推广", true, config, 4);
        } else if (pageTextContains(browser, "继续推广") && pageTextContains(browser, "权益")) {
            waitAndClickText(browser, "继续推广", true, config, 3);
        }
        debug.snapshot(browser, "08-rights-confirmed");
        String promotionType = config.getDefaultMediaType();
        if (!waitTextVisible(browser, promotionType, config, 12)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union media type option not visible");
            result.getDiagnostics().put("promotionDialog", collectPromotionDialogDiagnostics(browser));
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "09-media-type-not-visible");
            return;
        }

        if (!clickByText(browser, promotionType, true)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union media type option not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "09-media-type-missing");
            return;
        }
        humanStepPause(config);
        debug.snapshot(browser, "09-media-type-selected");
        if (!selectGuideMedia(browser, request.getMediaName(), config)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union guide media option not found");
            result.getDiagnostics().put("promotionDialog", collectPromotionDialogDiagnostics(browser));
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "10-guide-media-missing");
            return;
        }
        humanStepPause(config);
        debug.snapshot(browser, "10-guide-media-selected");
        if (!preparePromotionSlot(browser, request.getAdSiteName(), config)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union promotion slot name input not found");
            result.getDiagnostics().put("promotionDialog", collectPromotionDialogDiagnostics(browser));
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "12-slot-name-missing");
            return;
        }
        humanStepPause(config);
        debug.snapshot(browser, "12-slot-name-selected");
        HumanPageNoise.applyRandomNoise(browser, "jd-promotion");
        if (!waitAndClickDialogButton(browser, "获取推广链接", config, 6)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union get promotion link button not found");
            result.getDiagnostics().put("promotionDialog", collectPromotionDialogDiagnostics(browser));
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "13-generate-link-missing");
            return;
        }
        debug.snapshot(browser, "13-generate-link-clicked");

        String promotionUrl = "";
        for (int i = 0; i < 10; i++) {
            ensureTaskDeadline("getPromotionUrl.waitPromotionUrl");
            promotionUrl = readPromotionUrl(browser);
            if (!Strings.isEmpty(promotionUrl)) {
                break;
            }
            Extends.sleep(Math.max(180, Math.min(350, config.nextStepDelayMillis() / 2)));
        }
        // 链接已拿到则不必再点复制（减少多余点击）
        if (Strings.isEmpty(promotionUrl)) {
            waitAndClickDialogButton(browser, "复制", config, 3);
            promotionUrl = readPromotionUrl(browser);
        }
        if (Strings.isEmpty(promotionUrl)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union promotion link not found");
            result.getDiagnostics().put("promotionDialog", collectPromotionDialogDiagnostics(browser));
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "14-promotion-link-missing");
            return;
        }
        if (!publishPromotion(result, promotionUrl, result.getProductInfo(), request.getKeyword(), "page")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union product info validation failed");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "14-product-info-invalid");
            return;
        }
        keepAliveUrlStore.collect("jd", browser, result.getDiagnostics());
        debug.snapshot(browser, "14-promotion-link-ready");
    }

    private void runOrdersFlow(Browser browser, JdUnionPromotionOrdersRequest request, JdUnionConfig config,
            JdUnionPromotionOrdersResult result, DebugRecorder debug) throws TimeoutException {
        HumanPageNoise.applyRandomNoise(browser, "jd-orders");
        JdUnionPageApi.OrdersHit ajax = new JdUnionPageApi(objectMapper)
                .listOrders(browser, request.getStartTime(), request.getEndTime());
        result.getDiagnostics().put("ajaxOrders", ajax.diagnostic);
        if (ajax.success) {
            result.setOrders(ajax.orders);
            result.setComplete(ajax.complete);
            result.setHasMore(ajax.hasMore);
            result.setPagesFetched(ajax.pagesFetched);
            result.setTotalCount(ajax.orders.size());
            result.setIncompleteReason(ajax.incompleteReason);
            List<String> validationErrors = JdResultChecks.orders(result.getOrders());
            if (!validationErrors.isEmpty()) {
                result.getDiagnostics().put("ajaxOrderValidation", validationErrors);
            } else {
                result.setStatus(CustomCrawlStatus.SUCCESS);
                result.setMessage("");
                result.setCurrentUrl(browser.getCurrentUrl());
                result.getDiagnostics().put("channel", "ajax");
                result.getDiagnostics().put("orderCount", ajax.orders.size());
                keepAliveUrlStore.collect("jd", browser, result.getDiagnostics());
                debug.snapshot(browser, "05-orders-ajax");
                return;
            }
        }
        result.getDiagnostics().put("channel", "page");
        if (!enterOrderPage(browser, config, result, debug)) {
            return;
        }
        collapseMessageNotice(browser, config);
        debug.snapshot(browser, "02-order-page-ready");
        HumanPageNoise.applyRandomNoise(browser, "jd-orders-page");

        LocalDate startDate = LocalDate.parse(request.getStartTime(), DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(request.getEndTime(), DATE_FORMATTER);
        if (!selectOrderDateRange(browser, startDate, endDate, config)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union order date range picker not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "03-date-range-missing");
            return;
        }
        collapseMessageNotice(browser, config);
        debug.snapshot(browser, "03-date-range-selected");

        if (!nativeClickOrderSearchButton(browser)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union order search button not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "04-search-button-missing");
            return;
        }
        Extends.sleep(config.nextStepDelayMillis() * 2L);
        scrollOrderPageBottom(browser, config);
        debug.snapshot(browser, "04-search-clicked");

        LinkedHashMap<String, PromotionOrderItem> orders = new LinkedHashMap<String, PromotionOrderItem>();
        List<String> previousPageKeys = Collections.emptyList();
        for (int pageNo = 1; pageNo <= 200; pageNo++) {
            ensureTaskDeadline("getPromotionOrders.pageLoop");
            List<PromotionOrderItem> pageOrders = readOrderRowsByScrolling(browser, config);
            List<String> pageKeys = new ArrayList<String>();
            int beforeSize = orders.size();
            for (PromotionOrderItem item : pageOrders) {
                String key = rowKey(item);
                pageKeys.add(key);
                if (!orders.containsKey(key)) {
                    orders.put(key, item);
                }
            }
            result.getDiagnostics().put("lastPageNo", pageNo);
            result.getDiagnostics().put("lastPageSize", pageOrders.size());
            result.getDiagnostics().put("lastPageNewSize", orders.size() - beforeSize);
            result.getDiagnostics().put("orderCount", orders.size());
            result.setOrders(new ArrayList<PromotionOrderItem>(orders.values()));
            debug.snapshot(browser, String.format("05-page-%03d-collected", pageNo));
            if (pageNo > 1 && (!pageKeys.isEmpty() && pageKeys.equals(previousPageKeys) || orders.size() == beforeSize)) {
                result.getDiagnostics().put("duplicatePageStop", true);
                break;
            }
            previousPageKeys = pageKeys;
            scrollOrderPageBottom(browser, config);
            if (!hasNextOrderPage(browser)) {
                break;
            }
            if (!nativeClickNextOrderPage(browser)) {
                break;
            }
            Extends.sleep(config.nextStepDelayMillis() * 2L);
        }

        result.setOrders(new ArrayList<PromotionOrderItem>(orders.values()));
        List<String> validationErrors = JdResultChecks.orders(result.getOrders());
        if (!validationErrors.isEmpty()) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union order item validation failed");
            result.getDiagnostics().put("validationErrors", validationErrors);
            result.getDiagnostics().put("orderCount", orders.size());
            debug.snapshot(browser, "05-orders-invalid");
            return;
        }
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("");
        result.setComplete(true);
        result.setHasMore(false);
        result.setTotalCount(orders.size());
        result.getDiagnostics().put("orderCount", orders.size());
        keepAliveUrlStore.collect("jd", browser, result.getDiagnostics());
    }

    private List<PromotionOrderItem> readOrderRowsByScrolling(Browser browser, JdUnionConfig config) throws TimeoutException {
        LinkedHashMap<String, PromotionOrderItem> rows = new LinkedHashMap<String, PromotionOrderItem>();
        scrollOrderTableTop(browser);
        Extends.sleep(Math.max(300, config.nextStepDelayMillis() / 2));
        for (int i = 0; i < 80; i++) {
            ensureTaskDeadline("getPromotionOrders.readOrderRows");
            List<PromotionOrderItem> visibleRows = readOrderRows(browser);
            for (PromotionOrderItem row : visibleRows) {
                ensureTaskDeadline("getPromotionOrders.productIdentity");
                if (!rows.containsKey(rowKey(row))) {
                    org.rx.crawler.task.common.OrderProductLinkReader.resolve(browser, row,
                            org.rx.crawler.task.price.PriceCodes.JD, Math.min(8, config.getPageTimeoutSeconds()));
                }
                rows.putIfAbsent(rowKey(row), row);
            }
            if (!scrollOrderRowsDown(browser)) {
                break;
            }
            Extends.sleep(Math.max(250, config.nextStepDelayMillis() / 3));
        }
        scrollOrderPageBottom(browser, config);
        return new ArrayList<PromotionOrderItem>(rows.values());
    }

    private String rowKey(PromotionOrderItem row) {
        return String.valueOf(row.getOrderNo()) + "|" + row.getOrderStatus() + "|" + row.getTime() + "|" + row.getEstimatedBillingAmount() + "|"
                + row.getEstimatedCommission() + "|" + row.getActualCommission() + "|" + row.getPromotionInfo() + "|"
                ;
    }

    private void scrollOrderTableTop(Browser browser) {
        browser.executeScript("var detail=document.querySelector('.order-detail-wrap')||document.querySelector('.order-list-wrap');" +
                "if(detail){detail.scrollIntoView({block:'start',inline:'nearest'});}" +
                "Array.prototype.slice.call(document.querySelectorAll('.el-table__body-wrapper,.order-list-wrap')).forEach(function(e){" +
                "try{e.scrollTop=0;}catch(ex){}" +
                "});");
    }

    private boolean scrollOrderRowsDown(Browser browser) {
        Boolean moved = browser.executeScript("function maxRoot(){return document.scrollingElement||document.documentElement||document.body;}" +
                "var table=document.querySelector('.order-list-wrap')||document.querySelector('.order-detail-wrap');" +
                "var root=maxRoot(), target=root;" +
                "if(table){var p=table;while(p){if(p.scrollHeight&&p.clientHeight&&p.scrollHeight>p.clientHeight+8){target=p;break;}p=p.parentElement;}}" +
                "var before=target.scrollTop||window.pageYOffset||0;" +
                "var max=(target.scrollHeight||document.documentElement.scrollHeight)-(target.clientHeight||window.innerHeight);" +
                "var next=Math.min(max,before+Math.max(420,Math.floor(window.innerHeight*0.75)));" +
                "if(target===root){window.scrollTo(0,next);target.scrollTop=next;}else{target.scrollTop=next;}" +
                "return next>before+5;");
        return Boolean.TRUE.equals(moved);
    }

    private void scrollOrderPageBottom(Browser browser, JdUnionConfig config) throws TimeoutException {
        for (int i = 0; i < 3; i++) {
            ensureTaskDeadline("getPromotionOrders.scrollPageBottom");
            browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                    "function canScroll(e){return e&&e.scrollHeight&&e.clientHeight&&e.scrollHeight>e.clientHeight+8;}" +
                    "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                    "var roots=[document.scrollingElement,document.documentElement,document.body];" +
                    "Array.prototype.slice.call(document.querySelectorAll('*')).forEach(function(e){if(canScroll(e)){roots.push(e);}});" +
                    "roots.forEach(function(e){try{e.scrollTop=e.scrollHeight;}catch(ex){}});" +
                    "window.scrollTo(0,Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));" +
                    "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,li,span,div'));" +
                    "var target=null;for(var j=nodes.length-1;j>=0;j--){var e=nodes[j],t=norm(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title);" +
                    "if(visible(e)&&(t==='下一页'||t.indexOf('下一页')>=0)){target=e.closest('button,a,li,.pagination-wrap')||e;break;}}" +
                    "if(target){var p=target.parentElement;while(p){try{if(canScroll(p)){p.scrollTop=p.scrollHeight;}}catch(ex){}p=p.parentElement;}" +
                    "target.scrollIntoView({block:'center',inline:'center'});}");
            Extends.sleep(Math.max(300, config.nextStepDelayMillis()));
        }
    }

    private boolean enterOrderPage(Browser browser, JdUnionConfig config, JdUnionPromotionOrdersResult result,
            DebugRecorder debug) throws TimeoutException {
        browser.navigateUrl(config.getEntireUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        Extends.sleep(config.nextStepDelayMillis());
        result.setCurrentUrl(browser.getCurrentUrl());
        debug.snapshot(browser, "00-entire-loaded");
        if (isLoginRequired(result.getCurrentUrl(), config)) {
            fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                    "JD Union login expired before entering entire page. Finish login in the opened Chrome profile, then retry.");
            debug.snapshot(browser, "00-entire-login-required");
            return false;
        }

        if (nativeClickByText(browser, "订单明细", true) || clickByText(browser, "订单明细", false)) {
            Extends.sleep(config.nextStepDelayMillis());
            debug.snapshot(browser, "00-order-menu-opened");
            if (nativeClickByText(browser, "推客推广订单明细", true) || clickByText(browser, "推客推广订单明细", false)) {
                Extends.sleep(config.nextStepDelayMillis() * 2L);
            }
        }
        if (!waitOrderPageReady(browser, config)) {
            browser.navigateUrl(config.getOrderUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
            Extends.sleep(config.nextStepDelayMillis() * 2L);
        }
        result.setCurrentUrl(browser.getCurrentUrl());
        if (isLoginRequired(result.getCurrentUrl(), config)) {
            fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                    "JD Union login expired before entering order page. Finish login in the opened Chrome profile, then retry.");
            debug.snapshot(browser, "00-order-login-required");
            return false;
        }
        if (waitOrderPageReady(browser, config)) {
            return true;
        }
        fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union promotion order page not found");
        result.getDiagnostics().put("body", bodySnippet(browser));
        debug.snapshot(browser, "00-order-page-not-found");
        return false;
    }

    private boolean waitOrderPageReady(Browser browser, JdUnionConfig config) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, config.getInitialPageTimeoutSeconds()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getPromotionOrders.waitOrderPageReady");
            Extends.sleep(Math.max(1000, config.nextStepDelayMillis()));
            String body = bodySnippet(browser);
            if (containsAny(body, "时间范围", "查找订单", "订单状态", "预估佣金", "推客推广订单明细")) {
                return true;
            }
        }
        return false;
    }

    private boolean selectOrderDateRange(Browser browser, LocalDate startDate, LocalDate endDate, JdUnionConfig config) throws TimeoutException {
        if (!nativeClickOrderDateRange(browser)) {
            return false;
        }
        Extends.sleep(Math.max(1000, config.nextStepDelayMillis()));
        YearMonth startMonth = YearMonth.from(startDate);
        YearMonth endMonth = YearMonth.from(endDate);
        boolean startMonthReady = false;
        for (int i = 0; i < 36; i++) {
            ensureTaskDeadline("getPromotionOrders.selectStartMonth");
            List<String> months = readDatePickerMonths(browser);
            if (months.isEmpty()) {
                return false;
            }
            if (startMonth.equals(parseYearMonthText(months.get(0)))) {
                startMonthReady = true;
                break;
            }
            YearMonth left = parseYearMonthText(months.get(0));
            boolean clicked;
            if (left != null && left.isAfter(startMonth)) {
                clicked = nativeClickDatePickerNav(browser, false);
            } else {
                clicked = nativeClickDatePickerNav(browser, true);
            }
            if (!clicked) {
                return false;
            }
            Extends.sleep(config.nextStepDelayMillis());
        }
        if (!startMonthReady) {
            return false;
        }
        if (!nativeClickDateInPicker(browser, startDate, true)) {
            return false;
        }
        Extends.sleep(config.nextStepDelayMillis());
        List<String> months = readDatePickerMonths(browser);
        YearMonth left = months.isEmpty() ? null : parseYearMonthText(months.get(0));
        if (endMonth.equals(left)) {
            return nativeClickDateInPicker(browser, endDate, true);
        }
        boolean endMonthReady = false;
        for (int i = 0; i < 36; i++) {
            ensureTaskDeadline("getPromotionOrders.selectEndMonth");
            months = readDatePickerMonths(browser);
            if (months.isEmpty()) {
                return false;
            }
            YearMonth right = months.size() > 1 ? parseYearMonthText(months.get(1)) : null;
            if (endMonth.equals(right)) {
                endMonthReady = true;
                break;
            }
            boolean clicked;
            if (right != null && right.isAfter(endMonth)) {
                clicked = nativeClickDatePickerNav(browser, false);
            } else {
                clicked = nativeClickDatePickerNav(browser, true);
            }
            if (!clicked) {
                return false;
            }
            Extends.sleep(config.nextStepDelayMillis());
        }
        if (!endMonthReady) {
            return false;
        }
        return nativeClickDateInPicker(browser, endDate, false);
    }

    private void collapseMessageNotice(Browser browser, JdUnionConfig config) {
        Boolean clicked = browser.executeScript("function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var pops=Array.prototype.slice.call(document.querySelectorAll('.popover-msg-pop,.el-popover'));" +
                "for(var i=0;i<pops.length;i++){var pop=pops[i];if(!visible(pop)||norm(pop.innerText).indexOf('消息公告')<0){continue;}" +
                "var nodes=Array.prototype.slice.call(pop.querySelectorAll('button,a,span,div'));" +
                "for(var j=0;j<nodes.length;j++){var e=nodes[j];if(visible(e)&&norm(e.innerText||e.textContent)==='收起'){e.click();return true;}}" +
                "}" +
                "return false;");
        if (Boolean.TRUE.equals(clicked)) {
            Extends.sleep(Math.max(300, config.nextStepDelayMillis()));
        }
    }

    private boolean nativeClickOrderDateRange(Browser browser) {
        String selector = "[data-rx-jd-order-date-range='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-order-date-range';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function rect(el){return el.getBoundingClientRect();}" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p'));" +
                "var label=null;for(var i=0;i<labels.length;i++){var t=norm(labels[i].innerText||labels[i].textContent);" +
                "if(visible(labels[i])&&t.indexOf('时间范围')>=0&&t.length<=10){label=labels[i];break;}}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,.el-date-editor,.ant-picker,.date-picker,[role=\"combobox\"]'));" +
                "var best=null,bestScore=999999;" +
                "for(var j=0;j<nodes.length;j++){var e=nodes[j];if(!visible(e)){continue;}var meta=norm(e.innerText||e.value||e.getAttribute('placeholder')||e.className||'');" +
                "var er=rect(e),score=999999;if(label){var lr=rect(label),dy=Math.abs((er.top+er.bottom)/2-(lr.top+lr.bottom)/2),dx=er.left-lr.right;if(dx>=-30&&dy<60){score=dy*20+Math.max(0,dx)+er.width/10;}}" +
                "if(score===999999&&(/日期|时间|开始|结束|date|range/i).test(meta)){score=er.top+er.left/10;}" +
                "if(score<bestScore){best=e;bestScore=score;}}" +
                "if(!best){return false;}var target=best.closest('.el-date-editor,.ant-picker,[role=\"combobox\"]')||best;" +
                "target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> readDatePickerMonths(Browser browser) {
        List<String> months = browser.executeScript("function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var picker=Array.prototype.slice.call(document.querySelectorAll('.el-date-range-picker,.ant-picker-dropdown')).filter(visible)[0];" +
                "if(!picker){return [];}" +
                "var nodes=Array.prototype.slice.call(picker.querySelectorAll('.el-date-range-picker__header div,.ant-picker-header-view'));" +
                "var items=[];for(var i=0;i<nodes.length;i++){var e=nodes[i],t=norm(e.innerText||e.textContent);" +
                "if(visible(e)&&/^\\d{4}年\\d{1,2}月$/.test(t)){var r=e.getBoundingClientRect();items.push({text:t,top:r.top,left:r.left});}}" +
                "items.sort(function(a,b){return Math.abs(a.top-b.top)>20?a.top-b.top:a.left-b.left;});" +
                "var out=[];for(var j=0;j<items.length;j++){if(out.indexOf(items[j].text)<0){out.push(items[j].text);}}" +
                "return out;");
        return months == null ? Collections.emptyList() : months;
    }

    private YearMonth parseYearMonthText(String text) {
        if (Strings.isEmpty(text)) {
            return null;
        }
        Matcher matcher = Pattern.compile("(\\d{4})年(\\d{1,2})月").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    private boolean nativeClickDatePickerNav(Browser browser, boolean next) {
        String selector = "[data-rx-jd-picker-nav='1']";
        Boolean marked = browser.executeScript("var next=arguments[0],attr='data-rx-jd-picker-nav';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "var picker=Array.prototype.slice.call(document.querySelectorAll('.el-date-range-picker,.ant-picker-dropdown')).filter(visible)[0];" +
                "if(!picker){return false;}" +
                "var nodes=next?picker.querySelectorAll('.el-icon-arrow-right,.ant-picker-header-next-btn'):picker.querySelectorAll('.el-icon-arrow-left,.ant-picker-header-prev-btn');" +
                "var best=null,bestScore=999999;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var r=e.getBoundingClientRect();" +
                "var score=next?(window.innerWidth-r.left):r.left;if(score<bestScore){best=e;bestScore=score;}}" +
                "if(!best){return false;}best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});return true;", next);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nativeClickDateInPicker(Browser browser, LocalDate date, boolean leftPanel) {
        String selector = "[data-rx-jd-picker-day='1']";
        Boolean marked = browser.executeScript("var day=String(arguments[0]),leftPanel=arguments[1],attr='data-rx-jd-picker-day';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function badClass(el){var c=((el.className||'')+'').toLowerCase();return /disabled|prev-month|next-month|off/.test(c);}" +
                "var picker=Array.prototype.slice.call(document.querySelectorAll('.el-date-range-picker,.ant-picker-dropdown')).filter(visible)[0];" +
                "if(!picker){return false;}" +
                "var panel=picker.querySelector(leftPanel?'.el-date-range-picker__content.is-left':'.el-date-range-picker__content.is-right')||picker;" +
                "var cells=Array.prototype.slice.call(panel.querySelectorAll('td,button,span,div'));" +
                "var candidates=[];for(var i=0;i<cells.length;i++){var e=cells[i];if(!visible(e)||badClass(e)){continue;}var t=norm(e.innerText||e.textContent||e.getAttribute('aria-label'));" +
                "if(t!==day){continue;}var r=e.getBoundingClientRect();if(r.width>80||r.height>80||r.top<80){continue;}candidates.push({el:e,left:r.left,top:r.top,area:r.width*r.height});}" +
                "if(candidates.length===0){return false;}candidates.sort(function(a,b){return leftPanel?(a.left-b.left):(b.left-a.left);});" +
                "var target=candidates[0].el.closest('td,button')||candidates[0].el;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;", date.getDayOfMonth(), leftPanel);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nativeClickOrderSearchButton(Browser browser) {
        return nativeClickByText(browser, "查找订单", true) || clickByText(browser, "查询", true) || clickByText(browser, "搜索", true);
    }

    private List<PromotionOrderItem> readOrderRows(Browser browser) {
        String tableHtml = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function cellText(el){return norm(el.innerText||el.textContent||el.value||'');}" +
                "var bodies=Array.prototype.slice.call(document.querySelectorAll('table.el-table__body'));" +
                "for(var i=0;i<bodies.length;i++){var t=bodies[i];if(visible(t)&&t.querySelectorAll('tr').length>0){return t.outerHTML;}}" +
                "var tables=Array.prototype.slice.call(document.querySelectorAll('table'));" +
                "var best=null,bestScore=-1;for(var i=0;i<tables.length;i++){var t=tables[i];if(!visible(t)){continue;}var tx=cellText(t),score=0;" +
                "['订单状态','预估计佣金额','预估佣金','佣金比例','分成比例','实际计佣金额','实际佣金','推广信息','订单类型'].forEach(function(k){if(tx.indexOf(k)>=0){score++;}});" +
                "if(score>bestScore){best=t;bestScore=score;}}" +
                "return best&&bestScore>0?best.outerHTML:'';");
        List<PromotionOrderItem> items = parseOrderRowsFromHtml(tableHtml);
        if (!items.isEmpty()) {
            return items;
        }

        List<Map<String, Object>> rows = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function cellText(el){return norm(el.innerText||el.textContent||el.value||'');}" +
                "var bodies=Array.prototype.slice.call(document.querySelectorAll('table.el-table__body')).filter(visible);" +
                "var out=[];for(var b=0;b<bodies.length;b++){var trs=Array.prototype.slice.call(bodies[b].querySelectorAll('tr')).filter(visible);" +
                "for(var r=0;r<trs.length;r++){var cells=Array.prototype.slice.call(trs[r].querySelectorAll('td'));" +
                "if(cells.length<3){continue;}var all=cellText(trs[r]);if(!all||all.indexOf('暂无')>=0||all.indexOf('没有')>=0){continue;}" +
                "var product=cellText(cells[0]),order=cellText(cells[1]),time=cellText(cells[3]),qty=cellText(cells[10]),promo=cellText(cells[11]);" +
                "out.push({productName:product,orderNo:(order.match(/\\b\\d{10,24}\\b/)||[''])[0],orderStatus:cellText(cells[2]),time:time,orderTime:time,estimatedBillingAmount:cellText(cells[4])," +
                "estimatedCommission:cellText(cells[5]),commissionRate:cellText(cells[6]),shareRate:cellText(cells[7]),actualBillingAmount:cellText(cells[8])," +
                "actualCommission:cellText(cells[9]),quantity:qty,promotionInfo:promo,promotionPosition:promo,orderType:cellText(cells[12])});}}" +
                "if(out.length>0){return out;}" +
                "var tables=Array.prototype.slice.call(document.querySelectorAll('table'));" +
                "var best=null,bestScore=-1;for(var i=0;i<tables.length;i++){var t=tables[i];if(!visible(t)){continue;}var tx=cellText(t),score=0;" +
                "['订单状态','预估计佣金额','预估佣金','佣金比例','分成比例','实际计佣金额','实际佣金','推广信息','订单类型'].forEach(function(k){if(tx.indexOf(k)>=0){score++;}});" +
                "if(score>bestScore){best=t;bestScore=score;}}" +
                "if(!best||bestScore<=0){return [];}" +
                "var trs=Array.prototype.slice.call(best.querySelectorAll('tr')).filter(visible);if(trs.length===0){return [];}" +
                "for(var r=0;r<trs.length;r++){var cells=Array.prototype.slice.call(trs[r].querySelectorAll('td'));if(cells.length<3){continue;}" +
                "var all=cellText(trs[r]);if(!all||all.indexOf('暂无')>=0||all.indexOf('没有')>=0){continue;}" +
                "var product=cellText(cells[0]),order=cellText(cells[1]),time=cellText(cells[3]),qty=cellText(cells[10]),promo=cellText(cells[11]);" +
                "out.push({productName:product,orderNo:(order.match(/\\b\\d{10,24}\\b/)||[''])[0],orderStatus:cellText(cells[2]),time:time,orderTime:time,estimatedBillingAmount:cellText(cells[4])," +
                "estimatedCommission:cellText(cells[5]),commissionRate:cellText(cells[6]),shareRate:cellText(cells[7]),actualBillingAmount:cellText(cells[8])," +
                "actualCommission:cellText(cells[9]),quantity:qty,promotionInfo:promo,promotionPosition:promo,orderType:cellText(cells[12])});}" +
                "return out;");
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<PromotionOrderItem>();
        }
        List<PromotionOrderItem> fallback = new ArrayList<PromotionOrderItem>();
        for (Map<String, Object> row : rows) {
            PromotionOrderItem item = objectMapper.convertValue(row, PromotionOrderItem.class);
            fillOrderNumbers(item, String.valueOf(row.get("orderNo")));
            fillTimeInfo(item, item.getTime());
            fillQuantityInfo(item, item.getQuantity());
            fillPromotionInfo(item, item.getPromotionInfo());
            fallback.add(item);
        }
        return fallback;
    }

    private List<PromotionOrderItem> parseOrderRowsFromHtml(String html) {
        if (Strings.isEmpty(html)) {
            return new ArrayList<PromotionOrderItem>();
        }
        try {
            Document document = Jsoup.parse(html);
            Element table = document.selectFirst("table.el-table__body");
            if (table == null) {
                table = document.selectFirst("table");
            }
            if (table == null) {
                return new ArrayList<PromotionOrderItem>();
            }

            Elements trs = table.select("tr");
            List<PromotionOrderItem> items = new ArrayList<PromotionOrderItem>();
            for (Element tr : trs) {
                Elements cells = tr.select("td");
                if (cells.size() < 3) {
                    continue;
                }
                String rowText = normalizeOrderCellText(tr.text());
                if (Strings.isEmpty(rowText) || containsAny(rowText, "暂无", "没有", "无数据")) {
                    continue;
                }

                PromotionOrderItem item = new PromotionOrderItem();
                fillProductInfo(item, cells.get(0));
                fillOrderNumbers(item, cellTextAt(cells, 1));
                item.setOrderStatus(cellTextAt(cells, 2));
                fillTimeInfo(item, cellTextAt(cells, 3));
                item.setEstimatedBillingAmount(cellTextAt(cells, 4));
                item.setEstimatedCommission(cellTextAt(cells, 5));
                item.setCommissionRate(cellTextAt(cells, 6));
                item.setShareRate(cellTextAt(cells, 7));
                item.setActualBillingAmount(cellTextAt(cells, 8));
                item.setActualCommission(cellTextAt(cells, 9));
                fillQuantityInfo(item, cellTextAt(cells, 10));
                fillPromotionInfo(item, cellTextAt(cells, 11));
                if (Strings.isEmpty(item.getOrderNo())) {
                    continue;
                }
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.debug("Parse JD union order rows from html fail, error={}", e.getMessage());
            return new ArrayList<PromotionOrderItem>();
        }
    }

    private void fillProductInfo(PromotionOrderItem item, Element cell) {
        item.setProductName(elementText(cell, ".sku-name"));
        item.setProductLink(elementAttr(cell, "a[href]", "href"));
        for (Element link : cell.select("a[href]")) {
            if (!Strings.isEmpty(JdLoginState.skuIdFromLink(link.attr("href")))) {
                item.setProductLink(link.attr("href"));
                break;
            }
        }
        item.setSkuId(JdLoginState.skuIdFromLink(item.getProductLink()));
        item.setProductPrice(elementText(cell, ".price"));
        item.setStoreName(elementText(cell, ".shop-name"));
        if (Strings.isEmpty(item.getProductName())) {
            item.setProductName(cellTextAt(new Elements(cell), 0));
        }
    }

    private void fillOrderNumbers(PromotionOrderItem item, String orderText) {
        Matcher matcher = Pattern.compile("\\b\\d{10,24}\\b").matcher(orderText == null ? "" : orderText);
        if (matcher.find()) {
            item.setOrderNo(matcher.group());
        }
        Matcher mainMatcher = Pattern.compile("主单号[:：]?\\s*(\\d{10,24})").matcher(orderText == null ? "" : orderText);
        if (mainMatcher.find()) {
            item.setMainOrderNo(mainMatcher.group(1));
        }
    }

    private void fillTimeInfo(PromotionOrderItem item, String timeText) {
        item.setTime(timeText);
        item.setOrderTime(extractLabeledValue(timeText, "下单时间", "完成时间", "结算时间"));
        item.setFinishTime(extractLabeledValue(timeText, "完成时间", "结算时间"));
        if ("--".equals(item.getFinishTime())) {
            item.setFinishTime(null);
        }
        item.getExtensions().put("rawPlatformSettleTime", extractLabeledValue(timeText, "结算时间"));
        if (Strings.isEmpty(item.getOrderTime())) {
            Matcher clock = Pattern.compile("\\d{4}-\\d{2}-\\d{2}(?:\\s+\\d{2}:\\d{2}:\\d{2})?")
                    .matcher(timeText == null ? "" : timeText);
            if (clock.find()) {
                item.setOrderTime(clock.group());
            }
        }
        org.rx.crawler.task.order.OrderFields.applyTimeSemantics(item, "jd");
    }

    private void fillQuantityInfo(PromotionOrderItem item, String quantityText) {
        item.setQuantity(quantityText);
        item.setProductQuantity(quantityValue(quantityText, "商品数量"));
        item.setAfterSaleQuantity(quantityValue(quantityText, "售后数量"));
        item.setReturnQuantity(quantityValue(quantityText, "退货数量"));
        item.getExtensions().put("rawQuantity", quantityText);
        item.getExtensions().put("afterSaleQuantity", item.getAfterSaleQuantity());
        item.getExtensions().put("returnQuantity", item.getReturnQuantity());
        item.getExtensions().put("rawOrderStatus", item.getOrderStatus());
        if (positiveQuantity(item.getAfterSaleQuantity()) || positiveQuantity(item.getReturnQuantity())) {
            item.setNormalizedStatus("INVALID");
        } else {
            item.setNormalizedStatus(org.rx.crawler.task.order.OrderFields.normalizedStatus("jd", item.getOrderStatus(), item.getExtensions()));
        }
    }

    private String quantityValue(String text, String label) {
        Matcher matcher = Pattern.compile(Pattern.quote(label) + "[:：]?\\s*(\\d+)(?!\\d)")
                .matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean positiveQuantity(String value) {
        return value != null && value.matches("\\d+") && new java.math.BigInteger(value).signum() > 0;
    }

    private void fillPromotionInfo(PromotionOrderItem item, String promotionText) {
        item.setPromotionInfo(promotionText);
        Matcher position = Pattern.compile("推广位\\s*id[:：]?\\s*([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE)
                .matcher(promotionText == null ? "" : promotionText);
        String pos = position.find() ? position.group(1) : "";
        if (Strings.isEmpty(pos)) {
            pos = "";
        }
        item.setPromotionPosition(pos);
    }

    private String elementText(Element root, String selector) {
        if (root == null) {
            return "";
        }
        Element element = root.selectFirst(selector);
        return element == null ? "" : normalizeOrderCellText(element.hasAttr("title") ? element.attr("title") : element.text());
    }

    private String elementAttr(Element root, String selector, String attr) {
        if (root == null) {
            return "";
        }
        Element element = root.selectFirst(selector);
        return element == null ? "" : normalizeOrderCellText(element.attr(attr));
    }

    private String extractLabeledValue(String text, String label, String... nextLabels) {
        if (Strings.isEmpty(text)) {
            return "";
        }
        String escapedLabel = Pattern.quote(label);
        StringBuilder pattern = new StringBuilder(escapedLabel).append("[:：]?\\s*(.*?)");
        if (nextLabels == null || nextLabels.length == 0) {
            pattern.append("$");
        } else {
            pattern.append("(?=");
            for (int i = 0; i < nextLabels.length; i++) {
                if (i > 0) {
                    pattern.append("|");
                }
                pattern.append(Pattern.quote(nextLabels[i])).append("[:：]?");
            }
            pattern.append("|$)");
        }
        Matcher matcher = Pattern.compile(pattern.toString()).matcher(text);
        return matcher.find() ? normalizeOrderCellText(matcher.group(1)) : "";
    }

    private String cellTextAt(Elements cells, int index) {
        if (cells == null || index < 0 || index >= cells.size()) {
            return "";
        }
        return normalizeOrderCellText(cells.get(index).text());
    }

    private String normalizeOrderCellText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private boolean hasNextOrderPage(Browser browser) {
        Boolean ok = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function disabled(el){var c=((el.className||'')+'').toLowerCase();return !!el.disabled||el.getAttribute('aria-disabled')==='true'||/disabled|stop-prev-next|is-disabled/.test(c);}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.pagination-wrap .next,.pagination-wrap [class*=next]'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=norm(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title);" +
                "if(t==='下一页'||/下一页/.test(t)||/next/.test(((e.className||'')+'').toLowerCase())){var target=e.closest('button,a,li,span')||e;if(!disabled(target)&&!disabled(e)){return true;}}}" +
                "return false;");
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickNextOrderPage(Browser browser) {
        String selector = "[data-rx-jd-next-page='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-next-page';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function disabled(el){var c=((el.className||'')+'').toLowerCase();return !!el.disabled||el.getAttribute('aria-disabled')==='true'||/disabled|stop-prev-next|is-disabled/.test(c);}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.pagination-wrap .next,.pagination-wrap [class*=next]'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=norm(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title);" +
                "if(t==='下一页'||/下一页/.test(t)||/next/.test(((e.className||'')+'').toLowerCase())){var target=e.closest('button,a,li,span')||e;if(!disabled(target)&&!disabled(e)){target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;}}}" +
                "return false;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean enterPromotionWorkbench(Browser browser, JdUnionConfig config, PromotionUrlResult result,
            DebugRecorder debug)
            throws TimeoutException {
        // entry 已打开 overview 时避免重复 navigate（减少无意义整页跳转/刷新感）
        if (!isAlreadyOnJdOverview(browser, config)) {
            browser.navigateUrl(config.getOverviewUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
            Extends.sleep(Math.max(250, config.nextStepDelayMillis() / 2));
            debug.snapshot(browser, "00-overview-loaded");
        } else {
            result.getDiagnostics().put("overviewNavigateSkipped", true);
            debug.snapshot(browser, "00-overview-reused");
        }
        result.setCurrentUrl(browser.getCurrentUrl());
        if (!ensureLoggedInOrWait(browser, config, result, debug, "00-overview-login-required",
                "JD Union login required on overview. Finish login in the opened Chrome profile.")) {
            return false;
        }

        if (!nativeClickByText(browser, "我要推广", true) && !clickByText(browser, "我要推广", false)) {
            result.getDiagnostics().put("leftMenuMissing", true);
            result.getDiagnostics().put("overviewBody", bodySnippet(browser));
            debug.snapshot(browser, "00-left-menu-missing");
            // 未登录时禁止再跳 proManager（会触发站点登录页/刷新，且 URL 仍可能像工作台）
            if (looksLikeJdLogin(browser, config)) {
                if (!ensureLoggedInOrWait(browser, config, result, debug, "00-left-menu-login-required",
                        "JD Union login required (left menu missing). Finish login in the opened Chrome profile.")) {
                    return false;
                }
                // 登录完成后重试一次菜单
                if (nativeClickByText(browser, "我要推广", true) || clickByText(browser, "我要推广", false)) {
                    Extends.sleep(config.nextStepDelayMillis());
                    debug.snapshot(browser, "00-left-menu-opened-after-login");
                } else {
                    fail(result, CustomCrawlStatus.PAGE_CHANGED,
                            "JD Union left menu still missing after login");
                    result.getDiagnostics().put("body", bodySnippet(browser));
                    debug.snapshot(browser, "00-left-menu-still-missing");
                    return false;
                }
            } else {
                // 已登录但菜单结构变化时，才允许直达工作台 URL
                browser.navigateUrl(config.getWorkbenchUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
                Extends.sleep(config.nextStepDelayMillis());
                result.setCurrentUrl(browser.getCurrentUrl());
                debug.snapshot(browser, "00-workbench-loaded");
                if (!ensureLoggedInOrWait(browser, config, result, debug, "00-workbench-login-required",
                        "JD Union login required on workbench. Finish login in the opened Chrome profile.")) {
                    return false;
                }
                if (waitPromotionWorkbenchReady(browser, config, result)) {
                    debug.snapshot(browser, "00-workbench-ready");
                    return true;
                }
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union promotion workbench not found");
                result.getDiagnostics().put("body", bodySnippet(browser));
                debug.snapshot(browser, "00-workbench-not-found");
                return false;
            }
        } else {
            Extends.sleep(Math.max(200, config.nextStepDelayMillis() / 2));
            debug.snapshot(browser, "00-left-menu-opened");
        }

        if (!nativeClickByText(browser, "商品推广", true) && !clickByText(browser, "商品推广", false)) {
            if (looksLikeJdLogin(browser, config)) {
                if (!ensureLoggedInOrWait(browser, config, result, debug, "00-product-menu-login-required",
                        "JD Union login required before product promotion menu.")) {
                    return false;
                }
                if (!nativeClickByText(browser, "商品推广", true) && !clickByText(browser, "商品推广", false)) {
                    fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union menu 商品推广 not found after login");
                    result.getDiagnostics().put("body", bodySnippet(browser));
                    debug.snapshot(browser, "00-product-menu-missing-after-login");
                    return false;
                }
            } else {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union menu 商品推广 not found");
                result.getDiagnostics().put("body", bodySnippet(browser));
                debug.snapshot(browser, "00-product-menu-missing");
                return false;
            }
        }
        debug.snapshot(browser, "00-product-menu-clicked");

        for (int i = 0; i < 12; i++) {
            ensureTaskDeadline("getPromotionUrl.waitWorkbenchPage");
            result.setCurrentUrl(browser.getCurrentUrl());
            if (looksLikeJdLogin(browser, config)) {
                if (!ensureLoggedInOrWait(browser, config, result, debug, "00-product-page-login-required",
                        "JD Union login required on product promotion page.")) {
                    return false;
                }
            }
            if (isPromotionWorkbenchReady(browser)) {
                debug.snapshot(browser, "00-product-page-ready");
                return true;
            }
            Extends.sleep(Math.max(250, Math.min(500, config.nextStepDelayMillis() / 2)));
        }

        fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union 商品推广 page not reached");
        result.getDiagnostics().put("body", bodySnippet(browser));
        debug.snapshot(browser, "00-product-page-not-reached");
        return false;
    }

    private boolean ensurePromotionWorkbenchForNextJdKeyword(Browser browser, JdUnionConfig config,
            PromotionUrlResult result, DebugRecorder debug) throws TimeoutException {
        closeJdPromotionDialogIfNeeded(browser, config);
        if (looksLikeJdLogin(browser, config)) {
            if (!ensureLoggedInOrWait(browser, config, result, debug, "02-workbench-login-required-batch",
                    "JD Union login required before batch keyword search.")) {
                return false;
            }
        }
        // 已在商品推广工作台则不强制刷新
        if (isPromotionWorkbenchReady(browser)) {
            debug.snapshot(browser, "02-workbench-ready-batch-reused");
            return true;
        }
        browser.navigateUrl(config.getWorkbenchUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        Extends.sleep(config.nextStepDelayMillis());
        result.setCurrentUrl(browser.getCurrentUrl());
        if (!ensureLoggedInOrWait(browser, config, result, debug, "02-workbench-login-required-batch",
                "JD Union login expired before batch keyword search. Finish login in the opened Chrome profile, then retry.")) {
            return false;
        }
        if (waitPromotionWorkbenchReady(browser, config, result)) {
            debug.snapshot(browser, "02-workbench-ready-batch");
            return true;
        }
        fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union promotion workbench not ready for next keyword");
        result.getDiagnostics().put("body", bodySnippet(browser));
        debug.snapshot(browser, "02-workbench-not-ready-batch");
        return false;
    }

    private void closeJdPromotionDialogIfNeeded(Browser browser, JdUnionConfig config) {
        for (int i = 0; i < 2; i++) {
            String selector = "[data-rx-jd-dialog-close='1']";
            Boolean marked = browser.executeScript("var attr='data-rx-jd-dialog-close';" +
                    "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                    "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                    "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                    "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                    "var dialogs=Array.prototype.slice.call(document.querySelectorAll('[role=\"dialog\"],.el-dialog,.ant-modal,.modal,div'));" +
                    "var scope=null,bestTop=-999999;for(var d=0;d<dialogs.length;d++){var box=dialogs[d];if(!visible(box)){continue;}var t=text(box);if(t.indexOf('推广链接')<0&&t.indexOf('生成推广')<0&&t.indexOf('获取推广')<0){continue;}var r=box.getBoundingClientRect();if(r.top>bestTop){scope=box;bestTop=r.top;}}" +
                    "if(!scope){return false;}" +
                    "var nodes=Array.prototype.slice.call(scope.querySelectorAll('button,a,[role=\"button\"],i,span'));" +
                    "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=text(e),cls=String(e.className||'').toLowerCase();" +
                    "var closeText=t==='关闭'||t==='取消'||t==='×'||t==='x'||/close|el-dialog__close|icon-close/.test(cls);if(!closeText){continue;}" +
                    "var target=e.closest('button,a,[role=\"button\"]')||e,r=target.getBoundingClientRect(),score=(t==='关闭'||t==='×'||t==='x'?0:100)+r.width*r.height/1000+Math.abs(r.top-scope.getBoundingClientRect().top);" +
                    "if(score<bestScore){best=target;bestScore=score;}}" +
                    "if(!best){return false;}best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});return true;");
            if (!Boolean.TRUE.equals(marked)) {
                return;
            }
            try {
                browser.elementClick(selector, false);
                Extends.sleep(Math.max(500, config.nextStepDelayMillis()));
            } catch (Exception e) {
                return;
            }
        }
    }

    @SneakyThrows
    private WebBrowserConfig createBrowserConfig(PromotionUrlRequest request, JdUnionConfig jdConfig) {
        WebBrowserConfig config = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
        config.setProfileDataPath(profileManager.resolveProfileDataPath(request.getProfileName()));
        config.setHeadless(jdConfig.isHeadless());
        config.setFingerprintEnabled(jdConfig.isFingerprintEnabled());
        config.setFingerprintHeadless(jdConfig.isHeadless());
        if (Strings.isEmpty(config.getConfigureScriptExecutorType())) {
            config.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        }
        return config;
    }

    private PromotionUrlRequest normalizeRequest(PromotionUrlRequest request, JdUnionConfig config) {
        if (request == null) {
            request = new PromotionUrlRequest();
        }
        if (Strings.isEmpty(request.getAdSiteName())) {
            request.setAdSiteName(config.getDefaultAdSiteName());
        }
        if (Strings.isEmpty(request.getAdSiteName())) {
            throw new InvalidException("adSiteName is required");
        }
        PromotionSlots.require(config.getAdSites(), request.getAdSiteName());
        if (Strings.isEmpty(request.getProfileName())) {
            request.setProfileName(Strings.isEmpty(config.getProfileName()) ? profileManager.defaultProfileName() : config.getProfileName());
        }
        request.setProfileName(profileManager.normalizeProfileName(request.getProfileName()));
        if (Strings.isEmpty(request.getMediaName())) {
            request.setMediaName(config.getDefaultMediaName());
        }
        return request;
    }

    private JdUnionPromotionOrdersRequest normalizeOrdersRequest(JdUnionPromotionOrdersRequest request, JdUnionConfig config) {
        if (request == null) {
            request = new JdUnionPromotionOrdersRequest();
        }
        if (Strings.isEmpty(request.getProfileName())) {
            request.setProfileName(Strings.isEmpty(config.getProfileName()) ? profileManager.defaultProfileName() : config.getProfileName());
        }
        request.setProfileName(profileManager.normalizeProfileName(request.getProfileName()));
        LocalDate startDate = LocalDate.parse(request.getStartTime(), DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(request.getEndTime(), DATE_FORMATTER);
        if (startDate.isAfter(endDate)) {
            throw new InvalidException("startTime must be less than or equal to endTime");
        }
        return request;
    }

    private PromotionUrlRequest toDebugRequest(JdUnionPromotionOrdersRequest request) {
        PromotionUrlRequest debugRequest = new PromotionUrlRequest();
        debugRequest.setKeyword("orders-" + request.getStartTime() + "-" + request.getEndTime());
        debugRequest.setAdSiteName("0");
        debugRequest.setProfileName(request.getProfileName());
        debugRequest.setForcePreflight(request.getForcePreflight());
        debugRequest.setKeepBrowserOpenOnLoginRequired(request.getKeepBrowserOpenOnLoginRequired());
        debugRequest.setDebugEnabled(request.getDebugEnabled());
        debugRequest.setDebugOutputDir(request.getDebugOutputDir());
        return debugRequest;
    }

    private PromotionUrlResult createResult(PromotionUrlRequest request) {
        PromotionUrlResult result = new PromotionUrlResult();
        result.setTaskType(TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setKeyword(request.getKeyword());
        result.setAdSiteName(request.getAdSiteName());
        result.setMediaName(request.getMediaName());
        result.setProfileName(request.getProfileName());
        return result;
    }

    private JdUnionPromotionOrdersResult createOrdersResult(JdUnionPromotionOrdersRequest request) {
        JdUnionPromotionOrdersResult result = new JdUnionPromotionOrdersResult();
        result.setTaskType(ORDERS_TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setStartTime(request.getStartTime());
        result.setEndTime(request.getEndTime());
        result.setProfileName(request.getProfileName());
        result.setSourceId(request.getSourceId());
        return result;
    }

    private boolean isLoginRequired(String currentUrl, JdUnionConfig config) {
        return JdLoginState.unionLoginWallUrl(currentUrl, config.getLoginUrlPrefix());
    }

    /**
     * 页面正文是否像登录页（URL 可能仍是 overview/promanager）。
     */
    public boolean looksLikeJdLoginBody(String body) {
        return JdLoginState.looksLikeUnionLoginBody(body);
    }

    public boolean looksLikeJdLoggedInBody(String body) {
        return JdLoginState.looksLikeUnionLoggedInBody(body);
    }

    private boolean looksLikeJdLogin(Browser browser, JdUnionConfig config) {
        String url = browser.getCurrentUrl();
        String body = bodySnippet(browser);
        return isLoginRequired(url, config) || looksLikeJdLoginBody(body);
    }

    private boolean isAlreadyOnJdOverview(Browser browser, JdUnionConfig config) {
        String url = browser.getCurrentUrl();
        if (Strings.isEmpty(url)) {
            return false;
        }
        return isOverviewUrl(url, config) || isLoggedInUrl(url);
    }

    /**
     * 若当前为登录页：通知 + 等待人工登录；超时则 LOGIN_REQUIRED。
     * @return true 表示已可继续业务
     */
    private boolean ensureLoggedInOrWait(Browser browser, JdUnionConfig config, PromotionUrlResult result,
            DebugRecorder debug, String snapshotName, String message) {
        if (!looksLikeJdLogin(browser, config)) {
            return true;
        }
        result.setCurrentUrl(browser.getCurrentUrl());
        result.getDiagnostics().put("loginBodyDetected", true);
        result.getDiagnostics().put("loginBodySnippet", bodySnippet(browser));
        debug.snapshot(browser, snapshotName);

        CrawlEntryOptions waitOptions = createEntryOptions(toRequestFromResult(result), config);
        CrawlEntryResult waitResult = new CrawlEntryResult();
        waitResult.setCurrentUrl(browser.getCurrentUrl());
        entryService.notifyLoginRequired(waitOptions, browser.getCurrentUrl(), message);
        result.getDiagnostics().put("loginNotificationAttempted", true);
        result.getDiagnostics().put("loginWaitSeconds", config.getLoginWaitSeconds());

        if (config.isKeepBrowserOpenOnLoginRequired()
                && entryService.waitLoginCompleted(browser, waitOptions, waitResult)) {
            result.setCurrentUrl(browser.getCurrentUrl());
            result.setLoginRequired(false);
            result.getDiagnostics().put("loginWaitCompleted", true);
            debug.snapshot(browser, snapshotName + "-after-login");
            return true;
        }

        fail(result, CustomCrawlStatus.LOGIN_REQUIRED, message);
        result.setCurrentUrl(browser.getCurrentUrl());
        return false;
    }

    private PromotionUrlRequest toRequestFromResult(PromotionUrlResult result) {
        PromotionUrlRequest request = new PromotionUrlRequest();
        request.setKeyword(Strings.isEmpty(result.getKeyword()) ? "login-wait" : result.getKeyword());
        request.setAdSiteName(Strings.isEmpty(result.getAdSiteName()) ? PromotionSlots.DEFAULT_NAME : result.getAdSiteName());
        request.setMediaName(result.getMediaName());
        request.setProfileName(result.getProfileName());
        return request;
    }

    private boolean isLoggedInUrl(String currentUrl) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        return lower.contains("union.jd.com/overview")
                || lower.contains("union.jd.com/entire")
                || lower.contains("union.jd.com/order")
                || lower.contains("union.jd.com/promanager")
                || lower.contains("union.jd.com/manager");
    }

    private boolean isOverviewUrl(String currentUrl, JdUnionConfig config) {
        if (Strings.isEmpty(currentUrl) || Strings.isEmpty(config.getOverviewUrl())) {
            return false;
        }
        return currentUrl.startsWith(config.getOverviewUrl());
    }

    private boolean waitPromotionWorkbenchReady(Browser browser, JdUnionConfig config, PromotionUrlResult result) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, config.getInitialPageTimeoutSeconds()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getPromotionUrl.waitWorkbenchReady");
            Extends.sleep(Math.max(1000, config.nextStepDelayMillis()));
            result.setCurrentUrl(browser.getCurrentUrl());
            if (isPromotionWorkbenchReady(browser)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPromotionWorkbenchReady(Browser browser) {
        Boolean ready = browser.executeScript("function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function text(el){return ((el.innerText||el.value||el.getAttribute('title')||el.getAttribute('placeholder')||'')+'').replace(/\\s+/g,'');}" +
                "var inputs=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var hasInput=false;for(var i=0;i<inputs.length;i++){var e=inputs[i],meta=text(e)+(e.name||'')+(e.id||'')+(e.className||'');" +
                "var box=e;for(var d=0;box&&d<5;d++,box=box.parentElement){meta+=text(box);}" +
                "if(visible(e)&&(/商品|sku|关键词|keyword|search/i).test(meta)){hasInput=true;break;}}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,span,div'));" +
                "var hasButton=false;for(var j=0;j<nodes.length;j++){var t=text(nodes[j]);" +
                "if(visible(nodes[j])&&(t==='搜索'||t==='搜索全部商品')){hasButton=true;break;}}" +
                "return hasInput&&hasButton;");
        return Boolean.TRUE.equals(ready);
    }

    private void fail(PromotionUrlResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message == null ? "" : message);
        if (status == CustomCrawlStatus.LOGIN_REQUIRED) {
            result.setLoginRequired(true);
            notifyLoginRequired(result.getDiagnostics(), TASK_TYPE, result.getProfileName(), result.getCurrentUrl(),
                    result.getMessage());
        }
    }

    private void fail(JdUnionPromotionOrdersResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message == null ? "" : message);
        if (status == CustomCrawlStatus.LOGIN_REQUIRED) {
            result.setLoginRequired(true);
            notifyLoginRequired(result.getDiagnostics(), ORDERS_TASK_TYPE, result.getProfileName(),
                    result.getCurrentUrl(), result.getMessage());
        }
    }

    private void notifyLoginRequired(Map<String, Object> diagnostics, String taskType, String profileName,
            String currentUrl, String message) {
        if (Boolean.TRUE.equals(diagnostics.get("loginNotificationAttempted"))) {
            return;
        }
        diagnostics.put("loginNotificationAttempted", true);
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType(taskType);
        context.setProfileName(profileName);
        context.setInitialUrl(appConfig.getCustom().getJdUnion().getOverviewUrl());
        context.setCurrentUrl(currentUrl);
        context.setMessage(message);
        context.setLoginWaitSeconds(appConfig.getCustom().getJdUnion().getLoginWaitSeconds());
        context.setKeepBrowserOpenSeconds(appConfig.getCustom().getJdUnion().getKeepBrowserOpenSecondsOnLoginRequired());
        entryService.notifyLoginRequired(context);
    }

    private boolean nativeSetSearchValue(Browser browser, String value) {
        String selector = "[data-rx-jd-search-input='1']";
        // 优先 placeholder 含「商品名称或商品ID」的主搜索框，避免点到侧栏其它 input
        Boolean marked = browser.executeScript("var attr='data-rx-jd-search-input';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function ph(el){return norm(el.getAttribute('placeholder')||el.getAttribute('aria-label')||'');}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var chosen=null;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var p=ph(e);if(p.indexOf('商品名称或商品ID')>=0||p.indexOf('商品ID')>=0){chosen=e;break;}}" +
                "if(!chosen){for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n)){continue;}" +
                "var m=ph(n)+(n.name||'')+(n.id||'')+(n.className||'');" +
                "if(/商品|sku|关键词|搜索|keyword|search/i.test(m)&&n.getBoundingClientRect().width>=160){chosen=n;break;}}}" +
                "if(!chosen){return false;}" +
                "chosen.setAttribute(attr,'1');chosen.scrollIntoView({block:'center',inline:'center'});chosen.focus();return true;");
        if (!Boolean.TRUE.equals(marked)) {
            if (!setSearchValueByScript(browser, value)) {
                return false;
            }
            return searchInputEquals(browser, value);
        }
        // 纯数字 SKU：聚焦后直接写入（像粘贴），避免逐字打 12 位浪费 5~7s
        if (value != null && value.matches("\\d{6,}")) {
            try {
                browser.elementClick(selector, false);
            } catch (Exception ignored) {
            }
            return setSearchValueByScript(browser, value) && searchInputEquals(browser, value);
        }
        try {
            browser.elementPress(selector, value, false);
        } catch (Exception e) {
            setSearchValueByScript(browser, value);
        }
        if (searchInputEquals(browser, value)) {
            return true;
        }
        // 键盘输入未进 Vue 模型时强制 script 写入并校验
        return setSearchValueByScript(browser, value) && searchInputEquals(browser, value);
    }

    private boolean setSearchValueByScript(Browser browser, String value) {
        Boolean ok = browser.executeScript("var value=String(arguments[0]||'');" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function ph(el){return norm(el.getAttribute('placeholder')||el.getAttribute('aria-label')||'');}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var chosen=document.querySelector('[data-rx-jd-search-input=\"1\"]');" +
                "if(!chosen||!visible(chosen)){chosen=null;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var p=ph(e);if(p.indexOf('商品名称或商品ID')>=0||p.indexOf('商品ID')>=0){chosen=e;break;}}}" +
                "if(!chosen){return false;}" +
                "chosen.focus();" +
                "var proto=chosen.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
                "var setter=Object.getOwnPropertyDescriptor(proto,'value');" +
                "if(setter&&setter.set){setter.set.call(chosen,value);}else{chosen.value=value;}" +
                "chosen.dispatchEvent(new Event('input',{bubbles:true}));" +
                "chosen.dispatchEvent(new Event('change',{bubbles:true}));" +
                "try{chosen.dispatchEvent(new InputEvent('input',{bubbles:true,data:value,inputType:'insertText'}));}catch(ex){}" +
                "return true;", value);
        return Boolean.TRUE.equals(ok);
    }

    private boolean searchInputEquals(Browser browser, String expected) {
        Boolean ok = browser.executeScript("var exp=String(arguments[0]||'');" +
                "var el=document.querySelector('[data-rx-jd-search-input=\"1\"]');" +
                "if(!el){var nodes=document.querySelectorAll('input,textarea');" +
                "for(var i=0;i<nodes.length;i++){var p=(nodes[i].getAttribute('placeholder')||'');" +
                "if(p.indexOf('商品名称或商品ID')>=0||p.indexOf('商品ID')>=0){el=nodes[i];break;}}}" +
                "return !!el && String(el.value||'')===exp;", expected);
        return Boolean.TRUE.equals(ok);
    }

    private boolean jdProductMatchesKeyword(ProductInfoDto productInfo, String keyword) {
        if (productInfo == null || Strings.isEmpty(keyword)) {
            return true;
        }
        // 数字 SKU：链接必须含 skuId
        if (keyword.matches("\\d+")) {
            String link = productInfo.getProductLink();
            if (!Strings.isEmpty(link) && link.contains(keyword)) {
                return true;
            }
            // 有的卡片链接不含 sku，名称旁 HTML 已校验过；名称侧无法含纯数字时看 diagnostics 页
            return false;
        }
        String name = productInfo.getProductName();
        if (Strings.isEmpty(name)) {
            return false;
        }
        String n = name.replaceAll("\\s+", "");
        String k = keyword.replaceAll("\\s+", "");
        return n.contains(k) || k.contains(n) || n.contains(k.substring(0, Math.min(6, k.length())));
    }

    private boolean nativeClickSearchButton(Browser browser) {
        String selector = "[data-rx-jd-search-button='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-search-button';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function mark(root){if(!root){return false;}var nodes=Array.prototype.slice.call(root.querySelectorAll('button,a,span,div'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],t=text(e);if(visible(e)&&(t==='搜索全部商品'||t==='搜索')){e.setAttribute(attr,'1');e.scrollIntoView({block:'center',inline:'center'});return true;}}return false;}" +
                "var input=document.querySelector('[data-rx-jd-search-input=\"1\"]');" +
                "var p=input;for(var d=0;p&&d<7;d++,p=p.parentElement){if(mark(p)){return true;}}" +
                "return mark(document.body);");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nativeClickPrimaryPromoteButton(Browser browser) {
        return nativeClickPrimaryPromoteButton(browser, null);
    }

    private boolean nativeClickPrimaryPromoteButton(Browser browser, String keyword) {
        if (!markPrimaryPromoteButton(browser, keyword)) {
            return false;
        }
        // 仅用 JS scrollIntoView 对齐可视区，避免 scrollPage 拟人鼠标乱晃像“点到别处”
        ensurePrimaryPromoteInView(browser);
        Extends.sleep(350);
        try {
            browser.elementClick(PRIMARY_PROMOTE_SELECTOR, false);
            return true;
        } catch (Exception e) {
            // 原生点击被遮挡时，兜底触发页面绑定的 click 事件。
        }

        Boolean clicked = browser.executeScript("var target=document.querySelector('[data-rx-jd-primary-promote=\"1\"]');" +
                "if(!target){return false;}" +
                "function fire(el,type){var r=el.getBoundingClientRect();" +
                "el.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,view:window,clientX:r.left+r.width/2,clientY:r.top+r.height/2,button:0}));}" +
                "fire(target,'mouseover');fire(target,'mousemove');fire(target,'mousedown');fire(target,'mouseup');fire(target,'click');" +
                "if(typeof target.click==='function'){target.click();}" +
                "return true;");
        return Boolean.TRUE.equals(clicked);
    }

    private boolean markPrimaryPromoteButton(Browser browser) {
        return markPrimaryPromoteButton(browser, null);
    }

    private boolean markPrimaryPromoteButton(Browser browser, String keyword) {
        Boolean marked = browser.executeScript("var kw=arguments[0]||'';var attr='data-rx-jd-primary-promote';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function productBox(el){var p=el;for(var d=0;p&&d<8&&p&&p!==document.body&&p!==document.documentElement;d++,p=p.parentElement){var t=norm(p.innerText);" +
                "if(t.indexOf('为您推荐以下相似商品')>=0){return null;}" +
                "if(t.length<2500&&t.indexOf('佣金比例')>=0&&t.indexOf('预估收益')>=0&&t.indexOf('到手价')>=0&&t.indexOf('一键领链')>=0){return p;}}return null;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,span,div'));" +
                "var firstValid=null;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],button=e.closest('button');" +
                "if(!button||!visible(button)||text(button)!=='我要推广'||(button.closest&&button.closest('.recommend-goods,[class*=\"recommend\"]'))){continue;}" +
                "var box=productBox(button);" +
                "if(box){" +
                "if(kw&&(norm(box.innerText).indexOf(kw)>=0||box.querySelector('a[href*=\"'+kw+'\"]'))){button.setAttribute(attr,'1');return true;}" +
                "if(!firstValid){firstValid=button;}" +
                "}}" +
                "if(firstValid){firstValid.setAttribute(attr,'1');return true;}" +
                "return false;", keyword);
        return Boolean.TRUE.equals(marked);
    }

    /** 仅 DOM 滚动到按钮，不用 Mouse.wheel，减少“乱点”观感。 */
    private void ensurePrimaryPromoteInView(Browser browser) {
        browser.executeScript("var t=document.querySelector('[data-rx-jd-primary-promote=\"1\"]');" +
                "if(!t){return false;}" +
                "try{t.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});}catch(e){" +
                "try{t.scrollIntoView(true);}catch(e2){}}" +
                "return true;");
        // 再微调一次内部滚动容器（无 mouse）
        adjustPrimaryPromoteScroll(browser, true);
    }

    private Map<String, Object> readPrimaryPromoteScrollMetrics(Browser browser) {
        return adjustPrimaryPromoteScroll(browser, false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> adjustPrimaryPromoteScroll(Browser browser, boolean scroll) {
        Object value = browser.executeScript("var doScroll=arguments[0]===true;" +
                "var target=document.querySelector('[data-rx-jd-primary-promote=\"1\"]');" +
                "function vh(){return window.innerHeight||document.documentElement.clientHeight||800;}" +
                "function vw(){return window.innerWidth||document.documentElement.clientWidth||1200;}" +
                "function pageY(){return window.pageYOffset||document.documentElement.scrollTop||document.body.scrollTop||0;}" +
                "function metric(){if(!target){return {found:false};}" +
                "var r=target.getBoundingClientRect(),h=vh(),w=vw(),safeTop=90,safeBottom=Math.max(180,h-135),center=r.top+r.height/2,targetY=h*0.45;" +
                "return {found:true,top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height,innerHeight:h,pageYOffset:pageY()," +
                "rootScrollTop:(document.scrollingElement||document.documentElement||document.body).scrollTop||0,bodyScrollTop:document.body?document.body.scrollTop||0:0," +
                "safeTop:safeTop,safeBottom:safeBottom,delta:center-targetY,clickable:r.top>=safeTop&&r.bottom<=safeBottom&&r.left>=0&&r.right<=w};}" +
                "function add(list,node){if(node&&list.indexOf(node)<0){list.push(node);}}" +
                "function roots(){var list=[];add(list,document.scrollingElement);add(list,document.documentElement);add(list,document.body);return list;}" +
                "function ancestors(){var list=[];for(var p=target&&target.parentElement;p;p=p.parentElement){if(p.scrollHeight>p.clientHeight+20){add(list,p);}}return list;}" +
                "function allScrollables(){var list=roots(),all=Array.prototype.slice.call(document.querySelectorAll('*'));" +
                "for(var i=0;i<all.length;i++){var n=all[i];if(n.scrollHeight>n.clientHeight+20){add(list,n);}}return list;}" +
                "function fireWheel(dy){try{var r=target.getBoundingClientRect();target.dispatchEvent(new WheelEvent('wheel',{bubbles:true,cancelable:true,deltaY:dy,deltaMode:0,clientX:Math.max(1,Math.min(vw()-2,r.left+r.width/2)),clientY:Math.max(1,Math.min(vh()-2,r.top+r.height/2))}));}catch(e){}}" +
                "function move(list,dy){var before=target.getBoundingClientRect().top;" +
                "try{window.scrollTo(window.pageXOffset||0,pageY()+dy);}catch(e){}" +
                "for(var i=0;i<list.length;i++){try{list[i].scrollTop=(list[i].scrollTop||0)+dy;}catch(e){}}" +
                "fireWheel(dy);return Math.abs(target.getBoundingClientRect().top-before);}" +
                "if(target&&doScroll){try{target.scrollIntoView({block:'center',inline:'nearest',behavior:'auto'});}catch(e){}" +
                "for(var step=0;step<10;step++){var m=metric();if(m.clickable){break;}var dy=m.delta;" +
                "if(Math.abs(dy)<30){dy=m.bottom>m.safeBottom?260:-220;}dy=Math.max(-780,Math.min(780,dy));" +
                "var moved=move(roots().concat(ancestors()),dy);if(moved<1){move(allScrollables(),dy>0?420:-320);}}}" +
                "return metric();", scroll);
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    private double metricDouble(Map<String, Object> metrics, String key) {
        Object value = metrics.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : 0D;
    }

    private boolean selectGuideMedia(Browser browser, String mediaName, JdUnionConfig config) {
        if (Strings.isEmpty(mediaName)) {
            return true;
        }
        if (selectedGuideMediaEquals(browser, mediaName)) {
            return true;
        }
        String current = readGuideMediaText(browser);
        if (!nativeClickGuideMediaDropdown(browser)) {
            if (isRealGuideMedia(current)) {
                log.info("JD union guide media '{}' not opened, keep current '{}'", mediaName, current);
                return true;
            }
            return false;
        }
        Extends.sleep(Math.max(200, Math.min(400, config.nextStepDelayMillis() / 2)));
        if (nativeClickElementSelectOption(browser, mediaName) || nativeClickDropdownOption(browser, mediaName, "data-rx-jd-media-dropdown")) {
            humanStepPause(config);
            return true;
        }
        nativeOpenGuideMediaDropdownByEvent(browser);
        Extends.sleep(Math.max(200, Math.min(400, config.nextStepDelayMillis() / 2)));
        if (nativeClickElementSelectOption(browser, mediaName) || nativeClickDropdownOption(browser, mediaName, "data-rx-jd-media-dropdown")) {
            humanStepPause(config);
            return true;
        }
        if (selectedGuideMediaEquals(browser, mediaName)) {
            return true;
        }
        if (isRealGuideMedia(current)) {
            log.info("JD union guide media '{}' not in the list, keep current '{}'", mediaName, current);
            closeGuideMediaDropdown(browser);
            return true;
        }
        if (nativeClickFirstGuideMedia(browser)) {
            log.info("JD union guide media '{}' not in the list, selected the first available option", mediaName);
            humanStepPause(config);
            return true;
        }
        return false;
    }

    private String readGuideMediaText(Browser browser) {
        String value = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var marked=document.querySelector('[data-rx-jd-media-dropdown=\"1\"],[data-rx-jd-media-input=\"1\"]');" +
                "if(marked){return norm(marked.value||marked.innerText||marked.getAttribute('title'));}" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p'));" +
                "var label=null;for(var i=0;i<labels.length;i++){var t=norm(labels[i].innerText||labels[i].textContent);" +
                "if(visible(labels[i])&&t.indexOf('所属导购媒体')>=0&&t.length<=14){label=labels[i];break;}}" +
                "if(!label){return '';}var lr=label.getBoundingClientRect(),best='',bestScore=999999;" +
                "var inputs=Array.prototype.slice.call(document.querySelectorAll('input,.el-input__inner,.el-select'));" +
                "for(var j=0;j<inputs.length;j++){var e=inputs[j];if(!visible(e)){continue;}var r=e.getBoundingClientRect();" +
                "var dy=Math.abs((r.top+r.bottom)/2-(lr.top+lr.bottom)/2),dx=r.left-lr.right;" +
                "if(dx<-20||dy>50){continue;}var score=dy*20+Math.max(0,dx);if(score<bestScore){bestScore=score;best=norm(e.value||e.innerText||e.getAttribute('title'));}}" +
                "return best;");
        return value == null ? "" : value;
    }

    private boolean isRealGuideMedia(String value) {
        if (Strings.isEmpty(value)) {
            return false;
        }
        String compact = value.replace(" ", "");
        return !compact.contains("请选择") && !compact.contains("新增导购媒体") && !"所属导购媒体".equals(compact);
    }

    private void closeGuideMediaDropdown(Browser browser) {
        // 不能向 document 发 Escape，Element 对话框会一起关掉。
        browser.executeScript("var input=document.querySelector('[data-rx-jd-media-dropdown=\"1\"],[data-rx-jd-media-input=\"1\"]');" +
                "if(input){input.click();return;}" +
                "var title=document.querySelector('.el-dialog__wrapper.promote .el-dialog__title');" +
                "if(title){title.click();}");
    }

    private boolean nativeClickFirstGuideMedia(Browser browser) {
        String selector = "[data-rx-jd-dropdown-option='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-dropdown-option';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.el-select-dropdown__item,[role=\"option\"],li'));" +
                "var best=null,bestTop=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var text=norm(e.innerText||e.textContent||e.getAttribute('title'));" +
                "if(!text||text.indexOf('请选择')>=0||text.indexOf('新增导购媒体')>=0||text.indexOf('所属导购媒体')>=0){continue;}" +
                "var r=e.getBoundingClientRect();if(r.width<12||r.height<12||r.width*r.height>60000){continue;}" +
                "if(r.top<bestTop){best=e;bestTop=r.top;}}" +
                "if(!best){return false;}best.setAttribute(attr,'1');best.scrollIntoView({block:'nearest',inline:'center'});return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nativeClickGuideMediaDropdown(Browser browser) {
        String selector = "[data-rx-jd-media-dropdown='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-media-dropdown';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+'],[data-rx-jd-media-input]')).forEach(function(e){e.removeAttribute(attr);e.removeAttribute('data-rx-jd-media-input');});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function cls(el){return ((el.className||'')+' '+(el.id||'')+' '+(el.getAttribute('role')||'')).toLowerCase();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "function rect(el){return el.getBoundingClientRect();}" +
                "var inputs=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var input=null;for(var i=0;i<inputs.length;i++){var e=inputs[i],meta=norm(e.getAttribute('placeholder')||e.value||e.getAttribute('aria-label'));" +
                "if(visible(e)&&meta.indexOf('导购媒体')>=0){input=e;break;}}" +
                "if(!input){var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p')),label=null;" +
                "for(var l=0;l<labels.length;l++){var lt=norm(labels[l].innerText||labels[l].textContent);if(visible(labels[l])&&lt.indexOf('所属导购媒体')>=0&&lt.length<=14){label=labels[l];break;}}" +
                "if(label){var lr=rect(label),best=null,bestScore=999999;for(var j=0;j<inputs.length;j++){var n=inputs[j];if(!visible(n)){continue;}var nr=rect(n),dy=Math.abs((nr.top+nr.bottom)/2-(lr.top+lr.bottom)/2),dx=nr.left-lr.right;" +
                "if(dx>=-20&&dy<50){var score=dy*20+Math.max(0,dx);if(score<bestScore){best=n;bestScore=score;}}}input=best;}}" +
                "if(!input){return false;}input.setAttribute('data-rx-jd-media-input','1');" +
                "var target=input;" +
                "target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nativeOpenGuideMediaDropdownByEvent(Browser browser) {
        Boolean ok = browser.executeScript("var input=document.querySelector('[data-rx-jd-media-input=\"1\"]');" +
                "if(!input){return false;}var select=input.closest('.el-select')||input.parentElement||input;" +
                "function fire(el,type){var e=new MouseEvent(type,{bubbles:true,cancelable:true,view:window});el.dispatchEvent(e);}" +
                "input.scrollIntoView({block:'center',inline:'center'});input.focus();" +
                "fire(input,'mousedown');fire(input,'mouseup');fire(input,'click');" +
                "fire(select,'mousedown');fire(select,'mouseup');fire(select,'click');return true;");
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickElementSelectOption(Browser browser, String mediaName) {
        String selector = "[data-rx-jd-el-select-option='1']";
        Boolean marked = browser.executeScript("var target=norm(arguments[0]),attr='data-rx-jd-el-select-option';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.el-select-dropdown__item, .el-select-dropdown li, [role=\"option\"]'));" +
                "var best=null,bestScore=999999;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var text=norm(e.innerText||e.textContent||e.getAttribute('title'));if(text!==target){continue;}" +
                "var r=e.getBoundingClientRect(),score=r.width*r.height/100+Math.abs(r.top-250);" +
                "if(score<bestScore){best=e;bestScore=score;}}" +
                "if(!best){return false;}" +
                "best.setAttribute(attr,'1');best.scrollIntoView({block:'nearest',inline:'center'});return true;", mediaName);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean selectedGuideMediaEquals(Browser browser, String mediaName) {
        Boolean ok = browser.executeScript("var target=norm(arguments[0]);" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var trigger=document.querySelector('[data-rx-jd-media-dropdown=\"1\"]');" +
                "if(!trigger||!visible(trigger)){return false;}" +
                "return norm(trigger.innerText||trigger.value||trigger.getAttribute('title'))===target;", mediaName);
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickLabelControl(Browser browser, String labelText, String attr) {
        String selector = "[" + attr + "='1']";
        Boolean marked = browser.executeScript("var labelText=norm(arguments[0]),attr=arguments[1];" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "function rect(el){return el.getBoundingClientRect();}" +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('placeholder'));}" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p'));" +
                "var label=null;for(var i=0;i<labels.length;i++){var lt=text(labels[i]);" +
                "if(visible(labels[i])&&lt.indexOf(labelText)>=0&&lt.length<=labelText.length+8){label=labels[i];break;}}" +
                "if(!label){return false;}var lr=rect(label),nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,input,textarea,div,span'));" +
                "var best=null,bestScore=999999;" +
                "for(var j=0;j<nodes.length;j++){var e=nodes[j];if(!visible(e)||e===label||label.contains(e)||e.contains(label)){continue;}" +
                "var t=text(e);if(t.indexOf(labelText)>=0||t.indexOf('新增导购媒体')>=0){continue;}" +
                "var er=rect(e),dy=Math.abs((er.top+er.bottom)/2-(lr.top+lr.bottom)/2),dx=er.left-lr.right,area=er.width*er.height;" +
                "if(dx<-20||dy>50||er.width<60||er.height<20||area>50000){continue;}" +
                "var score=dy*20+Math.max(0,dx)+area/500;" +
                "if(score<bestScore){best=e;bestScore=score;}}" +
                "if(!best){return false;}" +
                "var target=best.closest('[role=\"combobox\"],button,a')||best;" +
                "target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;", labelText, attr);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nativeClickDropdownOption(Browser browser, String optionText, String triggerAttr) {
        String selector = "[data-rx-jd-dropdown-option='1']";
        Boolean marked = browser.executeScript("var target=norm(arguments[0]),triggerAttr=arguments[1],attr='data-rx-jd-dropdown-option';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function cls(el){return ((el.className||'')+' '+(el.id||'')+' '+(el.getAttribute('role')||'')).toLowerCase();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "function popupScore(el){for(var p=el;p&&p!==document.body;p=p.parentElement){if(/dropdown|popover|popper|popup|menu|select|option|list|combobox/.test(cls(p))){return 0;}}return 120;}" +
                "function clickTarget(el){var direct=el.closest('li,button,a,[role=\"option\"],[role=\"menuitem\"]');if(direct){return direct;}" +
                "var best=el;for(var p=el.parentElement,d=0;p&&p!==document.body&&d<4;d++,p=p.parentElement){var r=p.getBoundingClientRect(),t=norm(p.innerText||p.value||p.getAttribute('title'));" +
                "if(t.indexOf(target)>=0&&r.width<=420&&r.height<=80){best=p;}}return best;}" +
                "var trigger=document.querySelector('['+triggerAttr+'=\"1\"]'),tr=trigger?trigger.getBoundingClientRect():null;" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('li,div,span,p,a,button,[role=\"option\"],[role=\"menuitem\"]'));" +
                "var best=null,bestScore=999999;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var text=norm(e.innerText||e.value||e.getAttribute('title'));if(text!==target&&text.indexOf(target)<0){continue;}" +
                "if(text.indexOf('所属导购媒体')>=0||text.indexOf('新增导购媒体')>=0||text.indexOf('请选择导购媒体')>=0){continue;}" +
                "if(trigger&&(trigger.contains(e)||e.contains(trigger))){continue;}" +
                "var r=e.getBoundingClientRect(),area=r.width*r.height;" +
                "if(r.width<12||r.height<12||area>60000){continue;}" +
                "var score=(text===target?0:60)+popupScore(e)+area/80;" +
                "if(tr){if(r.top<tr.bottom-25){score+=220;}score+=Math.abs(r.left-tr.left)*0.7+Math.abs(r.top-tr.bottom);}" +
                "if(score<bestScore){best=e;bestScore=score;}}" +
                "if(!best){return false;}" +
                "var targetNode=clickTarget(best);" +
                "targetNode.setAttribute(attr,'1');targetNode.scrollIntoView({block:'nearest',inline:'center'});return true;", optionText, triggerAttr);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean preparePromotionSlot(Browser browser, String slotName, JdUnionConfig config) throws TimeoutException {
        if (!waitTextVisible(browser, "投放推广位", config, 8) && !waitTextVisible(browser, "推广位名称", config, 3)) {
            return false;
        }
        if (promotionSlotChoiceEnabled(browser)) {
            if (!clickByText(browser, "选择推广位", true)) {
                return false;
            }
            humanStepPause(config);
            return selectPromotionSlotName(browser, slotName, config);
        }
        return nativeSetPromotionSlotName(browser, slotName);
    }

    private boolean promotionSlotChoiceEnabled(Browser browser) {
        Boolean enabled = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('label.el-radio,[role=\"radio\"]'));" +
                "for(var i=0;i<labels.length;i++){var e=labels[i];if(!visible(e)){continue;}" +
                "var t=norm(e.innerText||e.textContent);if(t!=='选择推广位'){continue;}" +
                "if(e.classList.contains('is-disabled')||e.getAttribute('aria-disabled')==='true'){return false;}" +
                "var input=e.querySelector('input');if(input&&input.disabled){return false;}" +
                "return true;}return false;");
        return Boolean.TRUE.equals(enabled);
    }

    private boolean selectPromotionSlotName(Browser browser, String slotName, JdUnionConfig config) {
        if (Strings.isEmpty(slotName)) {
            return false;
        }
        if (selectedPromotionSlotEquals(browser, slotName)) {
            return true;
        }
        if (nativeClickPromotionSlotDropdown(browser)) {
            Extends.sleep(Math.max(180, Math.min(350, config.nextStepDelayMillis() / 2)));
            if (nativeClickElementSelectOption(browser, slotName) || nativeClickDropdownOption(browser, slotName, "data-rx-jd-slot-dropdown")) {
                Extends.sleep(Math.max(150, Math.min(300, config.nextStepDelayMillis() / 2)));
                return true;
            }
        }
        return nativeSetPromotionSlotName(browser, slotName);
    }

    private boolean nativeClickPromotionSlotDropdown(Browser browser) {
        String selector = "[data-rx-jd-slot-dropdown='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-slot-dropdown';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "function rect(el){return el.getBoundingClientRect();}" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p'));" +
                "var label=null;for(var i=0;i<labels.length;i++){var lt=norm(labels[i].innerText||labels[i].textContent);" +
                "if(visible(labels[i])&&lt.indexOf('推广位名称')>=0&&lt.length<=10){label=labels[i];break;}}" +
                "if(!label){return false;}var lr=rect(label),nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var best=null,bestScore=999999;" +
                "for(var j=0;j<nodes.length;j++){var e=nodes[j];if(!visible(e)){continue;}var er=rect(e);" +
                "var dy=Math.abs((er.top+er.bottom)/2-(lr.top+lr.bottom)/2),dx=er.left-lr.right;" +
                "if(dx>=-20&&dy<50){var score=dy*20+Math.max(0,dx);if(score<bestScore){best=e;bestScore=score;}}}" +
                "if(!best){return false;}best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});best.focus();return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean selectedPromotionSlotEquals(Browser browser, String slotName) {
        Boolean ok = browser.executeScript("var target=norm(arguments[0]);" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var input=document.querySelector('[data-rx-jd-slot-dropdown=\"1\"],[data-rx-jd-slot-name-input=\"1\"]');" +
                "return !!input&&norm(input.value||input.innerText||input.getAttribute('title'))===target;", slotName);
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeSetPromotionSlotName(Browser browser, String value) {
        String selector = "[data-rx-jd-slot-name-input='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-slot-name-input';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function rect(el){return el.getBoundingClientRect();}" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p'));" +
                "var label=null;for(var i=0;i<labels.length;i++){var t=norm(labels[i].innerText||labels[i].textContent);" +
                "if(visible(labels[i])&&t.indexOf('推广位名称')>=0&&t.length<=8){label=labels[i];break;}}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "if(label){var lr=rect(label),best=null,bestScore=999999;" +
                "for(var j=0;j<nodes.length;j++){var e=nodes[j];if(!visible(e)){continue;}var er=rect(e);" +
                "var dy=Math.abs((er.top+er.bottom)/2-(lr.top+lr.bottom)/2),dx=er.left-lr.right;" +
                "if(dx>=-20&&dy<45){var score=dy*10+Math.max(0,dx);if(score<bestScore){best=e;bestScore=score;}}}" +
                "if(best){best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});best.focus();return true;}}" +
                "var fallback=null,fallbackTop=-1;" +
                "for(var k=0;k<nodes.length;k++){var input=nodes[k],ir=rect(input);if(visible(input)&&!(input.value||'').trim()&&ir.top>fallbackTop){fallback=input;fallbackTop=ir.top;}}" +
                "if(fallback){fallback.setAttribute(attr,'1');fallback.scrollIntoView({block:'center',inline:'center'});fallback.focus();return true;}" +
                "return false;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementPress(selector, value, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nativeClickByText(Browser browser, String text, boolean exact) {
        String selector = "[data-rx-jd-click-target='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-click-target', target=norm(arguments[0]), exact=arguments[1];" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,li,span,div,p'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var txt=norm(e.innerText||e.value||e.getAttribute('title'));" +
                "if(!txt){continue;}var matched=exact?txt===target:txt.indexOf(target)>=0;" +
                "if(matched){e.setAttribute(attr,'1');e.scrollIntoView({block:'center',inline:'center'});return true;}}" +
                "return false;", text, exact);
        if (!Boolean.TRUE.equals(marked)) {
            return clickByText(browser, text, exact);
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return clickByText(browser, text, exact);
        }
    }

    private boolean clickByText(Browser browser, String text, boolean exact) {
        if (Strings.isEmpty(text)) {
            return false;
        }
        String selector = "[data-rx-jd-click-target='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-click-target', target=norm(arguments[0]), exact=arguments[1];" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,li,span,div,p,input,textarea'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var txt=norm(e.innerText||e.value||e.getAttribute('title')||e.getAttribute('placeholder'));" +
                "if(!txt){continue;}var matched=exact?txt===target:txt.indexOf(target)>=0;" +
                "if(matched){e.setAttribute(attr,'1');e.scrollIntoView({block:'center',inline:'center'});return true;}}" +
                "return false;", text, exact);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nativeClickDialogButton(Browser browser, String text) {
        if (Strings.isEmpty(text)) {
            return false;
        }
        String selector = "[data-rx-jd-dialog-button='1']";
        Boolean marked = browser.executeScript("var target=norm(arguments[0]),attr='data-rx-jd-dialog-button';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function inDialog(el){for(var p=el;p&&p!==document.body;p=p.parentElement){var t=norm(p.innerText);" +
                "if(t.indexOf('生成推广链接')>=0){return true;}}return false;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,[role=\"button\"],span,div'));" +
                "var best=null,bestScore=999999;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var t=text(e);if(!t){continue;}" +
                "var exact=t===target,contains=t.indexOf(target)>=0&&t.indexOf('取消')<0;" +
                "if(!exact&&!contains){continue;}" +
                "var button=e.closest('button,a,[role=\"button\"]')||e;if(!visible(button)){continue;}" +
                "var bt=text(button);if(bt.indexOf('取消')>=0&&target.indexOf('取消')<0){continue;}" +
                "var r=button.getBoundingClientRect(),score=(exact?0:100)+(inDialog(button)?0:200)+r.width*r.height/1000;" +
                "if(score<bestScore){best=button;bestScore=score;}}" +
                "if(!best){return false;}" +
                "best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});return true;", text);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int readSearchResultCount(String body) {
        if (Strings.isEmpty(body)) {
            return -1;
        }
        Matcher matcher = SEARCH_RESULT_COUNT_PATTERN.matcher(body);
        if (!matcher.find()) {
            return -1;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private boolean waitTextVisible(Browser browser, String text, JdUnionConfig config) throws TimeoutException {
        return waitTextVisible(browser, text, config, Math.max(8, config.getInitialPageTimeoutSeconds()));
    }

    private boolean waitTextVisible(Browser browser, String text, JdUnionConfig config, int maxSeconds) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, maxSeconds) * 1000L;
        long poll = Math.max(180, Math.min(350, config.nextStepDelayMillis() / 2));
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getPromotionUrl.waitTextVisible");
            // 先检查再 sleep，避免就绪后仍白等一轮
            if (pageTextContains(browser, text)) {
                return true;
            }
            Extends.sleep(poll);
        }
        return false;
    }

    private boolean waitAndClickText(Browser browser, String text, boolean exact, JdUnionConfig config, int maxSeconds) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, maxSeconds) * 1000L;
        long poll = Math.max(180, Math.min(350, config.nextStepDelayMillis() / 2));
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getPromotionUrl.waitAndClickText");
            if (clickByText(browser, text, exact)) {
                humanStepPause(config);
                return true;
            }
            Extends.sleep(poll);
        }
        return false;
    }

    private boolean waitAndClickDialogButton(Browser browser, String text, JdUnionConfig config, int maxSeconds) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, maxSeconds) * 1000L;
        long poll = Math.max(180, Math.min(350, config.nextStepDelayMillis() / 2));
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getPromotionUrl.waitAndClickDialogButton");
            if (nativeClickDialogButton(browser, text)) {
                humanStepPause(config);
                return true;
            }
            Extends.sleep(poll);
        }
        return false;
    }

    private String waitSearchResultBody(Browser browser, JdUnionConfig config, int maxSeconds) throws TimeoutException {
        return waitSearchResultBody(browser, config, null, maxSeconds);
    }

    /**
     * 等搜索结果就绪。必须命中「所有结果 共N件商品」或明确无结果；
     * 数字 keyword 时还要求页面出现该 SKU（item.jd.com/{sku} 或正文含 sku）。
     */
    private String waitSearchResultBody(Browser browser, JdUnionConfig config, String keyword, int maxSeconds)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, maxSeconds) * 1000L;
        long poll = Math.max(200, Math.min(400, config.nextStepDelayMillis() / 2));
        String body = bodySnippet(browser);
        boolean numericSku = !Strings.isEmpty(keyword) && keyword.matches("\\d+");
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getPromotionUrl.waitSearchResult");
            body = bodySnippet(browser);
            if (containsAny(body, "抱歉，没有找到相关商品", "没有找到相关商品", "暂无相关商品")) {
                return body;
            }
            int count = readSearchResultCount(body);
            if (count >= 0) {
                if (!numericSku || body.contains(keyword) || pageContainsSkuLink(browser, keyword)) {
                    return body;
                }
                // 计数出来了但还不是本次 SKU 结果（列表未刷新完）→ 继续等
            }
            Extends.sleep(poll);
        }
        return body;
    }

    private boolean pageContainsSkuLink(Browser browser, String sku) {
        if (Strings.isEmpty(sku)) {
            return false;
        }
        Boolean ok = browser.executeScript(
                "var sku=String(arguments[0]||'');" +
                "var as=document.querySelectorAll('a[href]');" +
                "for(var i=0;i<as.length;i++){var h=as[i].href||as[i].getAttribute('href')||'';" +
                "if(h.indexOf(sku)>=0){return true;}}return false;", sku);
        return Boolean.TRUE.equals(ok);
    }

    private boolean pageTextContains(Browser browser, String text) {
        if (Strings.isEmpty(text)) {
            return false;
        }
        return Boolean.TRUE.equals(browser.executeScript(
                "return (document.body && document.body.innerText || '').indexOf(arguments[0]) >= 0;", text));
    }

    /** 步骤间短暂停顿（拟人），比 nextStepDelay 全量更省时。 */
    private void humanStepPause(JdUnionConfig config) {
        Extends.sleep(Math.max(120, Math.min(320, config.nextStepDelayMillis() / 2)));
    }

    private String readPromotionUrl(Browser browser) {
        return browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function pick(v){if(!v){return '';}var m=String(v).match(/https?:\\/\\/[^\\s\"'<>]+/);return m?m[0]:'';}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function score(el){var t=norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('placeholder'));" +
                "var p=el;for(var i=0;i<5&&p;i++,p=p.parentElement){t+=norm(p.innerText||p.getAttribute('title'));}" +
                "var s=0;if(/优惠券|券|coupon/.test(t)){s+=1000;}if(/推广链接|短链接/.test(t)){s+=100;}return s;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var best='';var bestScore=-1;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var v=pick(e.value||e.innerText);if(!v){continue;}" +
                "var sc=score(e);if(sc>bestScore){best=v;bestScore=sc;}}" +
                "if(best){return best;}" +
                "var body=document.body?document.body.innerText:'';" +
                "var coupon=body.match(/https?:\\/\\/[^\\s\"'<>]+/g)||[];" +
                "for(var j=0;j<coupon.length;j++){if(/u\\.jd\\.com/i.test(coupon[j])){return coupon[j];}}" +
                "return coupon[0]||'';");
    }

    private ProductInfoDto readProductInfo(Browser browser) {
        return readProductInfo(browser, null);
    }

    private ProductInfoDto readProductInfo(Browser browser, String keyword) {
        // 以「我要推广」按钮向上找商品卡；尺寸放宽；佣金+到手价+我要推广 即可（一键领链可选）
        String script = "var kw=String(arguments[0]||'');" +
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function isRecommend(el){return !!(el.closest && el.closest('.recommend-goods,[class*=\"recommend\"]'));}" +
                "function cardScore(el){if(isRecommend(el)){return 999999;}var r=el.getBoundingClientRect(),t=text(el);" +
                "if(r.width<160||r.width>720||r.height<180||r.height>1200){return 999999;}" +
                "if(t.indexOf('佣金比例')<0&&t.indexOf('佣金')<0){return 999999;}" +
                "if(t.indexOf('到手价')<0&&t.indexOf('￥')<0){return 999999;}" +
                "if(t.indexOf('我要推广')<0&&t.indexOf('一键领链')<0){return 999999;}" +
                "var imgs=el.querySelectorAll('img').length,as=el.querySelectorAll('a[href]').length;" +
                "var sc=r.width*r.height - imgs*8000 - as*800;" +
                "if(el.classList&&el.classList.contains('search-card')){sc-=200000;}" +
                "if(kw&&(t.indexOf(kw)>=0||el.querySelector('a[href*=\"'+kw+'\"]'))){sc-=500000;}" +
                // 含 item.jd.com 标题链的卡优先
                "if(/item\\.jd\\.com/.test(t)||el.querySelector('a[href*=\"item.jd.com\"]')){sc-=50000;}" +
                "return sc;}" +
                "function hrefOf(a){var href=a.href||a.getAttribute('href')||'';return href.indexOf('//')===0?location.protocol+href:href;}" +
                "function anchorName(a){return norm(a.getAttribute('title')||a.innerText||a.textContent);}" +
                "function productAnchor(card){var selectors=['p.two a[href][title]','p[class*=two] a[href][title]','a[href][title]','a[href*=\"item.jd.com\"]'];" +
                "if(kw){for(var si=0;si<selectors.length;si++){var as=Array.prototype.slice.call(card.querySelectorAll(selectors[si]));" +
                "for(var i=0;i<as.length;i++){var a=as[i],name=anchorName(a),href=hrefOf(a);" +
                "if(!visible(a)||!name||/我要推广|一键领链|查看全部|更多|清空|奖励活动|去报名/.test(name)){continue;}" +
                "if(/item\\.jd\\.com|\\.jd\\.com/.test(href)&&!/proManager/.test(href)&&href.indexOf(kw)>=0){return {name:name,href:href};}}}}" +
                "for(var si=0;si<selectors.length;si++){var as=Array.prototype.slice.call(card.querySelectorAll(selectors[si]));" +
                "for(var i=0;i<as.length;i++){var a=as[i],name=anchorName(a),href=hrefOf(a);" +
                "if(!visible(a)||!name||/我要推广|一键领链|查看全部|更多|清空|奖励活动|去报名/.test(name)){continue;}" +
                "if(/item\\.jd\\.com|\\.jd\\.com/.test(href)&&!/proManager/.test(href)){return {name:name,href:href};}}}" +
                "return null;}" +
                "function bestLink(card){var as=Array.prototype.slice.call(card.querySelectorAll('a[href]'));" +
                "var best='';var bestScore=-1;" +
                "for(var i=0;i<as.length;i++){var a=as[i];if(!visible(a)){continue;}var t=anchorName(a)||text(a);var href=hrefOf(a);" +
                "if(/我要推广|一键领链|查看全部|更多|清空|奖励活动|去报名/.test(t||'')){continue;}" +
                "var score=0;if(/item\\.jd\\.com/.test(href)){score+=200;}else if(/\\.jd\\.com/.test(href)&&!/proManager/.test(href)){score+=80;}" +
                "if(kw&&href.indexOf(kw)>=0){score+=1000;}" +
                "score+=(t||'').length;if(score>bestScore){best=href;bestScore=score;}}" +
                "return best;}" +
                "function bestImage(card){var imgs=Array.prototype.slice.call(card.querySelectorAll('img'));" +
                "for(var i=0;i<imgs.length;i++){var img=imgs[i];if(!visible(img)){continue;}return img.currentSrc||img.src||img.getAttribute('data-src')||img.getAttribute('src')||'';}" +
                "return '';}" +
                "function extract(card, re){var m=text(card).match(re);return m?m[1]||m[0]:'';}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,span,div'));var card=null,bestCardScore=999999;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)||text(e)!=='我要推广'){continue;}" +
                "if(isRecommend(e)){continue;}" +
                "var p=e;for(var d=0;p&&d<12;d++,p=p.parentElement){var sc=cardScore(p);if(sc<bestCardScore){card=p;bestCardScore=sc;}}}" +
                "if(!card){return null;}" +
                "var titleAnchor=productAnchor(card),anchors=Array.prototype.slice.call(card.querySelectorAll('a[href]'));" +
                "var name=titleAnchor?titleAnchor.name:'',nameScore=name?999999:-1,link=titleAnchor?titleAnchor.href:'';" +
                "for(var n=0;n<anchors.length;n++){var a=anchors[n],t=anchorName(a)||text(a),href=hrefOf(a);" +
                "if(!visible(a)||!t||/我要推广|一键领链|查看全部|更多|清空|奖励活动|去报名/.test(t)){continue;}" +
                "if(/旗舰店|专卖店|店铺|自营|京配|促销|券/.test(t)&&t.length<=40){continue;}" +
                "var score=t.length;if(/item\\.jd\\.com|\\.jd\\.com/.test(href)&&!/proManager/.test(href)){score+=80;}" +
                "if(kw&&href.indexOf(kw)>=0){score+=1000;}" +
                "if(score>nameScore){name=t;nameScore=score;link=href;}}" +
                "var store='';var storeNodes=Array.prototype.slice.call(card.querySelectorAll('a,span,div'));for(var s=0;s<storeNodes.length;s++){var se=storeNodes[s],st=text(se);" +
                "if(!visible(se)||!st||st==='我要推广'||st==='一键领链'){continue;}" +
                "if(/旗舰店|专卖店|店$|店铺|官方|专区/.test(st)&&st.length<=50){store=st;}}" +
                "return {" +
                "imageUrl: bestImage(card)," +
                "productName: name || extract(card,/([\\u4e00-\\u9fa5A-Za-z0-9【】\\(\\)\\[\\]\\-_.+·\\s]{6,120})/)," +
                "productLink: link || bestLink(card)," +
                "commissionRate: extract(card,/佣金比例[:：]?\\s*([0-9.]+%)/)||extract(card,/\\|\\s*佣金比例\\s*([0-9.]+%)/)," +
                "price: extract(card,/到手价\\s*[￥¥]?\\s*([0-9.]+(?:\\.[0-9]+)?(?:[万千]?)?)/)||extract(card,/[￥¥]\\s*([0-9.]+)/)," +
                "storeName: store" +
                "};";
        Map<String, Object> raw = Strings.isEmpty(keyword)
                ? browser.executeScript(script)
                : browser.executeScript(script, keyword);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        ProductInfoDto dto = objectMapper.convertValue(raw, ProductInfoDto.class);
        if (dto != null) {
            if (dto.getProductName() != null) {
                dto.setProductName(dto.getProductName().trim());
            }
            if (dto.getProductLink() != null && dto.getProductLink().startsWith("//")) {
                dto.setProductLink("https:" + dto.getProductLink());
            }
            dto.setImageUrl(normalizeHttpUrl(dto.getImageUrl()));
        }
        return dto;
    }

    private static String normalizeHttpUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("jfs/")) {
            return "https://img14.360buyimg.com/n1/" + trimmed;
        }
        return trimmed;
    }

    private Map<String, Object> collectPromotionDialogDiagnostics(Browser browser) {
        return browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function parents(el){var list=[];for(var p=el.parentElement;p&&p!==document.body&&list.length<5;p=p.parentElement){list.push({tag:p.tagName,role:p.getAttribute('role')||'',className:norm(p.className).substring(0,120),text:norm(p.innerText).substring(0,80)});}return list;}" +
                "function item(el){var r=el.getBoundingClientRect();return {tag:el.tagName,role:el.getAttribute('role')||'',className:norm(el.className).substring(0,120),text:norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('placeholder')).substring(0,80)," +
                "value:norm(el.value).substring(0,120),placeholder:norm(el.getAttribute('placeholder')).substring(0,80),disabled:!!el.disabled,readOnly:!!el.readOnly,top:Math.round(r.top),left:Math.round(r.left),width:Math.round(r.width),height:Math.round(r.height),parents:parents(el)};}" +
                "return {url:location.href,inputs:Array.prototype.slice.call(document.querySelectorAll('input,textarea')).filter(visible).map(item)," +
                "buttons:Array.prototype.slice.call(document.querySelectorAll('button,a,[role=\"button\"],[role=\"option\"],li')).filter(visible).map(item)," +
                "body:norm(document.body?document.body.innerText:'').substring(0,1200)};");
    }

    private String bodySnippet(Browser browser) {
        String body = "";
        for (int i = 0; i < 3; i++) {
            try {
                body = browser.executeScript("return document.body ? document.body.innerText : '';");
                break;
            } catch (Exception e) {
                String message = e.getMessage();
                if (message != null && (message.contains("Execution context was destroyed") || message.contains("because of a navigation"))) {
                    log.debug("Read JD union body while navigating, retry={}", i + 1);
                    Extends.sleep(500L * (i + 1));
                    continue;
                }
                log.warn("Read JD union body fail, currentUrl={}, error={}", browser.getCurrentUrl(), message);
                return "";
            }
        }
        if (Strings.isEmpty(body) || body.length() <= 2000) {
            return body;
        }
        return body.substring(0, 2000);
    }

    private boolean containsAny(String value, String... keys) {
        if (Strings.isEmpty(value)) {
            return false;
        }
        for (String key : keys) {
            if (value.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDebugEnabled(PromotionUrlRequest request) {
        if (request != null && request.getDebugEnabled() != null) {
            return request.getDebugEnabled();
        }
        return appConfig.getCustom().isDebugEnabled();
    }

    private void ensureTaskDeadline(String stage) throws TimeoutException {
        Long deadline = taskDeadlineHolder.get();
        if (deadline != null && System.currentTimeMillis() > deadline) {
            throw new TimeoutException("JD union task timeout at " + stage);
        }
    }

    private String resolveDebugOutputDir(PromotionUrlRequest request, JdUnionConfig config) {
        if (request != null && !Strings.isEmpty(request.getDebugOutputDir())) {
            return request.getDebugOutputDir();
        }
        return config == null ? null : config.getDebugOutputDir();
    }

    private final class DebugRecorder {
        private final boolean enabled;
        private final Path taskDir;
        private int stepIndex;

        private DebugRecorder(PromotionUrlRequest request, JdUnionConfig config, ObjectMapper objectMapper) {
            this(request, config, objectMapper, TASK_TYPE);
        }

        private DebugRecorder(PromotionUrlRequest request, JdUnionConfig config, ObjectMapper objectMapper, String taskType) {
            this.enabled = isDebugEnabled(request);
            if (!enabled) {
                this.taskDir = null;
                return;
            }
            String baseDir = resolveDebugOutputDir(request, config);
            String profileName = Strings.isEmpty(request.getProfileName()) ? "default" : request.getProfileName();
            String keyword = Strings.isEmpty(request.getKeyword()) ? "unknown" : request.getKeyword();
            String time = LocalDateTime.now().format(DEBUG_TIME_FORMATTER);
            this.taskDir = Paths.get(baseDir, profileName, keyword + "-" + time);
            try {
                Files.createDirectories(this.taskDir);
                DebugSnapshots.writeUtf8(this.taskDir.resolve("_task.txt"),
                        "taskType=" + taskType + System.lineSeparator()
                                + "profileName=" + profileName + System.lineSeparator()
                                + "keyword=" + keyword + System.lineSeparator());
            } catch (Exception e) {
                log.warn("JD union debug directory init fail, path={}, error={}", this.taskDir, e.getMessage(), e);
            }
        }

        private boolean enabled() {
            return enabled;
        }

        private Path getTaskDir() {
            return taskDir;
        }

        private void snapshot(Browser browser, String step) {
            if (!enabled || browser == null || taskDir == null) {
                return;
            }
            String safeStep = step == null ? "step" : step.replaceAll("[^a-zA-Z0-9._-]+", "_");
            int index = ++stepIndex;
            String html = "";
            try {
                html = browser.executeScript("return document.documentElement ? document.documentElement.outerHTML : '';");
            } catch (Exception e) {
                log.warn("JD union debug snapshot fail, step={}, error={}", step, e.getMessage(), e);
            }
            DebugSnapshots.writeUtf8(taskDir.resolve(String.format("%02d_%s.html", index, safeStep)), html);
            String stem = String.format("%02d_%s", index, safeStep);
            DebugSnapshots.writeViewport(taskDir, stem, browser);
            DebugSnapshots.writeDesktop(taskDir, stem);
        }

        private void snapshotText(String step, String text) {
            if (!enabled || taskDir == null) {
                return;
            }
            String safeStep = step == null ? "step" : step.replaceAll("[^a-zA-Z0-9._-]+", "_");
            int index = ++stepIndex;
            String body = text == null ? "" : text;
            DebugSnapshots.writeUtf8(taskDir.resolve(String.format("%02d_%s.html", index, safeStep)),
                    "<html><body><pre>" + escapeHtml(body) + "</pre></body></html>");
        }

        private String escapeHtml(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
