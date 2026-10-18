package org.rx.crawler.task.tb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.CrawlEntryResult;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.CustomCrawlTask;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.exception.InvalidException;
import org.rx.util.BeanMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.rx.core.Extends.tryClose;

/**
 * 淘宝/天猫主站：关键字搜索（tbSearchProduct）与商品详情（tbProductDetail）。
 * 数据优先取页面自己发出的 mtop 响应（被动截获）和详情页内嵌 SSR 数据，缺字段时读 DOM 兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class TbMallTask implements CustomCrawlTask<TbProductDetailRequest, TbProductDetailResult> {
    public static final String DETAIL_TASK_TYPE = "tbProductDetail";
    public static final String SEARCH_TASK_TYPE = "tbSearchProduct";
    private static final DateTimeFormatter DEBUG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final Pattern ITEM_ID_PATTERN = Pattern.compile("[?&]id=(\\d+)");
    private static final String SEARCH_INPUT_SELECTOR = "[data-rx-tb-search-input=\"1\"]";
    private static final String RISK_TEXT = "系统检测到当前访问存在异常";

    private final AppConfig appConfig;
    private final BrowserProfileManager profileManager;
    private final CrawlEntryService entryService;
    private final ResultWriter resultWriter;
    private final ObjectMapper objectMapper;
    private final SliderVerifyHandler sliderVerifyHandler = new SliderVerifyHandler();
    private final ThreadLocal<Long> taskDeadlineHolder = new ThreadLocal<Long>();

    @Override
    public String taskType() {
        return DETAIL_TASK_TYPE;
    }

    @Override
    public TbProductDetailResult execute(TbProductDetailRequest request) {
        return productDetail(request);
    }

    public boolean closeProfile(String profileName) {
        return profileManager.closeSession(profileName);
    }

    // ==================== 搜索 ====================

    public TbSearchResult search(TbSearchRequest rawRequest) {
        TbMallConfig config = appConfig.getCustom().getTbMall();
        TbSearchRequest request = normalizeSearchRequest(rawRequest, config);
        TbSearchResult result = new TbSearchResult();
        result.setTaskType(SEARCH_TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setKeyword(request.getKeyword());
        result.setProfileName(request.getProfileName());
        DebugRecorder debug = new DebugRecorder("search-" + request.getKeyword(), request.getProfileName(),
                Boolean.TRUE.equals(request.getDebugEnabled()), request.getDebugOutputDir(), config.getDebugOutputDir());
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }
        BrowserProfileManager.ProfileLease lease = null;
        Browser browser = null;
        taskDeadlineHolder.set(System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(Math.max(1, appConfig.getCustom().getMaxTaskMinutes())));
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(config));
            browser = lease.getBrowser();
            browser.maximize();
            browser.startResponseCapture(TbMallPageApi.SEARCH_API);
            CrawlEntryOptions options = createEntryOptions(request.getProfileName(), request.getForcePreflight(),
                    request.getKeepBrowserOpenOnLoginRequired(), null, config.getHomeUrl(), SEARCH_TASK_TYPE, config);
            humanNavigationPause(config);
            CrawlEntryResult entry = entryService.enter(browser, lease, options);
            result.setCurrentUrl(entry.getCurrentUrl());
            result.setLoginRequired(entry.isLoginRequired());
            result.setFingerprintPassed(entry.isFingerprintPassed());
            result.getDiagnostics().putAll(entry.getDiagnostics());
            debug.snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                result.setStatus(entry.getStatus());
                result.setMessage(entry.getMessage());
                return result;
            }
            runSearchFlow(browser, request, config, result, debug);
            return result;
        } catch (TimeoutException e) {
            result.setStatus(CustomCrawlStatus.TIMEOUT);
            result.setMessage(e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("TB mall search fail, keyword={}, profile={}, error={}",
                    request.getKeyword(), request.getProfileName(), e.getMessage(), e);
            result.setStatus(CustomCrawlStatus.FAILED);
            result.setMessage(e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            if (browser != null) {
                browser.stopResponseCapture(TbMallPageApi.SEARCH_API);
            }
            resultWriter.appendJsonLine(request.getOutputPath(), result);
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private void runSearchFlow(Browser browser, TbSearchRequest request, TbMallConfig config, TbSearchResult result,
            DebugRecorder debug) throws TimeoutException {
        if (!clearSlider(browser, config, debug, "02-home-slider")) {
            result.setMessage("TB slider verification not cleared on home page");
            return;
        }
        String keyword = request.getKeyword();
        boolean submitted = false;
        if (nativeSetSearchValue(browser, keyword)) {
            debug.snapshot(browser, "03-search-input-ready");
            browser.pressKey("Enter");
            submitted = waitSearchPageReached(browser, 8);
        }
        if (!submitted) {
            result.getDiagnostics().put("searchNavigateFallback", true);
            String url = config.getSearchUrlTemplate().replace("{keyword}",
                    java.net.URLEncoder.encode(keyword, StandardCharsets.UTF_8));
            browser.navigateUrl(url, Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        }
        List<TbSearchItem> captured = waitSearchResults(browser, config, result, debug, request.getMaxResults());
        result.setCurrentUrl(browser.getCurrentUrl());
        debug.snapshot(browser, "04-search-results");
        if (result.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED || result.getStatus() == CustomCrawlStatus.NOT_FOUND) {
            return;
        }
        if (captured != null) {
            result.getDiagnostics().put("ajaxSearchCount", captured.size());
            if (publishSearchItems(result, captured, "ajax")) {
                return;
            }
            result.getDiagnostics().put("ajaxSearchValidation", result.getDiagnostics().remove("validationErrors"));
        } else {
            result.getDiagnostics().put("ajaxSearch", "search mtop response not captured");
        }
        browser.scrollPage(0, 420 + ThreadLocalRandom.current().nextInt(260));
        Extends.sleep(Math.max(500, config.nextStepDelayMillis()));
        List<TbSearchItem> items = readSearchCards(browser, request.getMaxResults());
        result.getDiagnostics().put("pageSearchCount", items.size());
        if (items.isEmpty()) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB search cards not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        if (!publishSearchItems(result, items, "page")) {
            boolean masked = isRiskLimited(browser) || anyMaskedPrice(items) || anyMaskedPrice(captured);
            result.getDiagnostics().put("priceMasked", masked);
            fail(result, CustomCrawlStatus.PAGE_CHANGED, masked
                    ? "TB search prices are masked by risk control (e.g. 2?), retry later"
                    : "TB search cards missing required fields");
            debug.snapshot(browser, "05-search-items-invalid");
        }
    }

    private List<TbSearchItem> waitSearchResults(Browser browser, TbMallConfig config, TbSearchResult result,
            DebugRecorder debug, int maxResults) throws TimeoutException {
        long start = System.currentTimeMillis();
        long deadline = start + config.getPageTimeoutSeconds() * 1000L;
        List<TbSearchItem> captured = null;
        TbMallPageApi api = new TbMallPageApi(objectMapper);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("search-results-wait");
            for (String body : browser.drainResponseCapture(TbMallPageApi.SEARCH_API)) {
                List<TbSearchItem> parsed = TbMallPageApi.parseSearchItems(api.parseMtop(body), maxResults);
                if (parsed != null && captured == null) {
                    captured = parsed;
                }
            }
            if (isLoginRequiredPage(browser)) {
                result.setLoginRequired(true);
                fail(result, CustomCrawlStatus.LOGIN_REQUIRED, "TB search requires login");
                debug.snapshot(browser, "04-search-login-required");
                return captured;
            }
            clearSlider(browser, config, debug, "04-search-slider");
            if (captured != null && countSearchCards(browser) > 0) {
                return captured;
            }
            String body = bodySnippet(browser);
            if (body.contains("没有找到相关的宝贝") || body.contains("没有找到与")) {
                fail(result, CustomCrawlStatus.NOT_FOUND, "No products found for keyword: " + result.getKeyword());
                return captured;
            }
            if (countSearchCards(browser) > 0 && System.currentTimeMillis() - start > 6000L) {
                return captured;
            }
            Extends.sleep(500);
        }
        return captured;
    }

    private boolean publishSearchItems(TbSearchResult result, List<TbSearchItem> items, String channel) {
        List<String> errors = new ArrayList<String>();
        List<TbSearchItem> complete = TbResultChecks.completeMallItems(items, errors);
        if (complete.isEmpty()) {
            result.getDiagnostics().put("validationErrors", errors);
            return false;
        }
        if (!errors.isEmpty()) {
            result.getDiagnostics().put("droppedIncompleteItems", errors.size());
            result.getDiagnostics().put("droppedSample", errors.subList(0, Math.min(6, errors.size())));
        }
        result.setItems(complete);
        result.setResultCount(complete.size());
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("Found " + complete.size() + " products for keyword: " + result.getKeyword());
        result.getDiagnostics().put("channel", channel);
        return true;
    }

    private boolean anyMaskedPrice(List<TbSearchItem> items) {
        if (items == null) {
            return false;
        }
        for (TbSearchItem item : items) {
            if (item != null && item.getPrice() != null && item.getPrice().contains("?")) {
                return true;
            }
        }
        return false;
    }

    private boolean waitSearchPageReached(Browser browser, int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String url = browser.getCurrentUrl();
            if (url != null && url.contains("s.taobao.com/search")) {
                return true;
            }
            Extends.sleep(400);
        }
        return false;
    }

    private int countSearchCards(Browser browser) {
        try {
            Object count = browser.executeScript("return document.querySelectorAll('a[id^=\"item_id_\"]').length;");
            return count instanceof Number ? ((Number) count).intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private List<TbSearchItem> readSearchCards(Browser browser, int maxResults) {
        String script = "var limit=arguments[0];" +
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function q(root,s){return root.querySelector(s);}" +
                "function txt(el){return el?norm(el.innerText||el.textContent||''):'';}" +
                "var cards=document.querySelectorAll('a[id^=\"item_id_\"]');var out=[],seen={};" +
                "for(var i=0;i<cards.length&&out.length<limit;i++){var a=cards[i],id=a.id.replace('item_id_','');" +
                "if(!/^\\d+$/.test(id)||seen[id]){continue;}seen[id]=1;" +
                "var t=q(a,'[class*=\"title--\"]');var img=q(a,'img[class*=\"mainPic--\"]')||q(a,'img');" +
                "var pi=txt(q(a,'[class*=\"priceInt--\"]')),pf=txt(q(a,'[class*=\"priceFloat--\"]'));" +
                "var locs=[];a.querySelectorAll('[class*=\"procity--\"]').forEach(function(e){locs.push(txt(e));});" +
                "out.push({itemId:id,title:t?norm(t.getAttribute('title')||txt(t)):'',price:pi?pi+pf:''," +
                "imageUrl:img?(img.currentSrc||img.getAttribute('src')||''):'',shopName:txt(q(a,'[class*=\"shopNameText--\"]'))," +
                "productUrl:a.href||'',soldCount:txt(q(a,'[class*=\"realSales--\"]')),location:locs.join(' ')});}" +
                "return out;";
        try {
            Object raw = browser.executeScript(script, Math.max(1, maxResults));
            if (raw == null) {
                return new ArrayList<TbSearchItem>();
            }
            List<TbSearchItem> items = objectMapper.convertValue(raw, new TypeReference<List<TbSearchItem>>() {
            });
            for (TbSearchItem item : items) {
                item.setImageUrl(TbMallPageApi.httpUrl(item.getImageUrl()));
                item.setProductUrl(TbMallPageApi.productUrl(item.getProductUrl(), item.getItemId()));
            }
            return items;
        } catch (Exception e) {
            log.warn("Read TB search cards fail, error={}", e.getMessage());
            return new ArrayList<TbSearchItem>();
        }
    }

    private boolean nativeSetSearchValue(Browser browser, String value) {
        Boolean marked = browser.executeScript("var attr='data-rx-tb-search-input';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "var el=document.getElementById('q');" +
                "if(!el||!visible(el)){el=null;var nodes=document.querySelectorAll('input[name=\"q\"],input[type=\"search\"]');" +
                "for(var i=0;i<nodes.length;i++){if(visible(nodes[i])){el=nodes[i];break;}}}" +
                "if(!el){return false;}el.setAttribute(attr,'1');el.scrollIntoView({block:'center'});return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(SEARCH_INPUT_SELECTOR, false);
            Extends.sleep(200 + ThreadLocalRandom.current().nextInt(300));
            browser.elementPress(SEARCH_INPUT_SELECTOR, value, false);
        } catch (Exception e) {
            log.warn("Native type TB search keyword fail, error={}", e.getMessage());
            return false;
        }
        Boolean ok = browser.executeScript("var el=document.querySelector('" + SEARCH_INPUT_SELECTOR.replace("\"", "\\\"")
                + "');return !!el&&String(el.value||'')===String(arguments[0]||'');", value);
        return Boolean.TRUE.equals(ok);
    }

    private TbSearchRequest normalizeSearchRequest(TbSearchRequest request, TbMallConfig config) {
        if (request == null || Strings.isEmpty(request.getKeyword())) {
            throw new InvalidException("keyword is required");
        }
        request.setKeyword(request.getKeyword().trim());
        if (request.getMaxResults() == null || request.getMaxResults() <= 0) {
            request.setMaxResults(20);
        }
        if (request.getMaxResults() > 48) {
            request.setMaxResults(48);
        }
        if (Strings.isEmpty(request.getProfileName())) {
            request.setProfileName(profileManager.defaultProfileName());
        }
        request.setProfileName(profileManager.normalizeProfileName(request.getProfileName()));
        if (Strings.isEmpty(request.getOutputPath())) {
            request.setOutputPath(config.getDefaultOutputPath());
        }
        if (request.getDebugEnabled() == null) {
            request.setDebugEnabled(appConfig.getCustom().isDebugEnabled());
        }
        if (Strings.isEmpty(request.getDebugOutputDir())) {
            request.setDebugOutputDir(config.getDebugOutputDir());
        }
        return request;
    }

    // ==================== 详情 ====================

    public TbProductDetailResult productDetail(TbProductDetailRequest rawRequest) {
        TbMallConfig config = appConfig.getCustom().getTbMall();
        TbProductDetailRequest request = normalizeDetailRequest(rawRequest, config);
        TbProductDetailResult result = createDetailResult(request);
        DebugRecorder debug = new DebugRecorder(request.getItemId(), request.getProfileName(),
                Boolean.TRUE.equals(request.getDebugEnabled()), request.getDebugOutputDir(), config.getDebugOutputDir());
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }

        BrowserProfileManager.ProfileLease lease = null;
        Browser browser = null;
        int deadlineMinutes = Math.max(1, appConfig.getCustom().getMaxTaskMinutes())
                * (Boolean.TRUE.equals(request.getDownloadImages()) ? 2 : 1);
        taskDeadlineHolder.set(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(deadlineMinutes));

        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(config));
            browser = lease.getBrowser();
            browser.maximize();
            browser.startResponseCapture(TbMallPageApi.DETAIL_ADJUST_API);
            browser.startResponseCapture(TbMallPageApi.DESC_API);

            CrawlEntryOptions options = createEntryOptions(request.getProfileName(), request.getForcePreflight(),
                    request.getKeepBrowserOpenOnLoginRequired(), request.getLoginWaitSeconds(),
                    request.getProductUrl(), DETAIL_TASK_TYPE, config);
            humanNavigationPause(config);

            CrawlEntryResult entry = entryService.enter(browser, lease, options);
            applyEntryResult(result, entry);
            debug.snapshot(browser, "01-entry");

            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", result.getMessage());
                return result;
            }

            if (isLoginRequiredPage(browser)) {
                result.setLoginRequired(true);
                if (options.isKeepBrowserOpenOnLoginRequired()
                        && entryService.waitLoginCompleted(browser, options, entry)) {
                    result.setLoginRequired(false);
                    result.setCurrentUrl(browser.getCurrentUrl());
                } else {
                    fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                            "TB login wait timeout after " + options.getLoginWaitSeconds() + "s. Task stopped.");
                    debug.snapshot(browser, "01-login-timeout");
                    return result;
                }
            }

            runDetailFlow(browser, request, config, result, debug);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("TB mall product detail fail, itemId={}, profile={}, error={}",
                    request.getItemId(), request.getProfileName(), e.getMessage(), e);
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            if (browser != null) {
                browser.stopResponseCapture(TbMallPageApi.DETAIL_ADJUST_API);
                browser.stopResponseCapture(TbMallPageApi.DESC_API);
            }
            resultWriter.appendJsonLine(request.getOutputPath(), result);
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private void runDetailFlow(Browser browser, TbProductDetailRequest request, TbMallConfig config,
            TbProductDetailResult result, DebugRecorder debug) throws TimeoutException {
        if (!clearSlider(browser, config, debug, "02-product-slider")) {
            fail(result, CustomCrawlStatus.FAILED, "TB slider verification not cleared on product page");
            return;
        }
        if (!waitItemPageReady(browser, config, result, debug)) {
            return;
        }
        Extends.sleep(600 + ThreadLocalRandom.current().nextInt(600));

        TbMallPageApi api = new TbMallPageApi(objectMapper);
        JsonNode ssr = api.readDetailData(browser);
        List<JsonNode> adjusts = waitAdjustResponses(browser, api, 6);
        TbMallPageApi.DetailData data = TbMallPageApi.parseDetail(ssr, adjusts);
        result.getDiagnostics().put("ssrData", ssr != null);
        result.getDiagnostics().put("adjustResponses", adjusts.size());
        result.getDiagnostics().put("priceTitle", data.priceTitle);
        if (data.priceMasked) {
            result.getDiagnostics().put("priceMasked", true);
        }

        Map<String, Object> dom = readProductBase(browser);
        boolean ajaxComplete = !Strings.isEmpty(data.title) && TbResultChecks.isCleanPrice(data.price)
                && !Strings.isEmpty(data.shopName) && !data.mainImages.isEmpty();
        result.setTitle(firstNonEmpty(data.title, asString(dom.get("title"))));
        result.setPrice(TbResultChecks.isCleanPrice(data.price) ? data.price : asString(dom.get("price")));
        result.setShopName(firstNonEmpty(data.shopName, asString(dom.get("shopName"))));
        if (!Strings.isEmpty(data.itemId) && data.itemId.matches("\\d+")) {
            result.setItemId(data.itemId);
        }
        List<String> mainImageUrls = data.mainImages.isEmpty() ? asStringList(dom.get("mainImages")) : data.mainImages;
        debug.snapshot(browser, "03-item-base-collected");

        List<String> detailImageUrls = scrollDetailAndCollect(browser, config);
        List<String> descImages = new ArrayList<String>();
        for (String body : browser.drainResponseCapture(TbMallPageApi.DESC_API)) {
            descImages = mergeUniqueUrls(descImages, TbMallPageApi.extractImageUrls(body));
        }
        result.getDiagnostics().put("descApiImages", descImages.size());
        detailImageUrls = mergeUniqueUrls(descImages, detailImageUrls);
        debug.snapshot(browser, "04-detail-scrolled");

        if (Boolean.TRUE.equals(request.getIncludeSpecs())) {
            List<TbProductSpecItem> specs = data.specs.isEmpty() ? readProductSpecs(browser) : data.specs;
            result.setSpecs(specs);
            result.getDiagnostics().put("specsSource", data.specs.isEmpty() ? "page" : "ajax");
        }
        detailImageUrls = dedupeDetailImages(detailImageUrls, mainImageUrls, config.getMaxDetailImages());
        result.setCurrentUrl(browser.getCurrentUrl());

        if (Boolean.TRUE.equals(request.getDownloadImages())) {
            result.setMainImages(downloadImages(browser, result.getItemId(), mainImageUrls, "main", config));
            result.setDetailImages(downloadImages(browser, result.getItemId(), detailImageUrls, "detail", config));
        } else {
            result.setMainImages(toImageInfos(mainImageUrls));
            result.setDetailImages(toImageInfos(detailImageUrls));
        }
        result.getDiagnostics().put("channel", ajaxComplete ? "ajax" : "page");

        boolean riskLimited = isRiskLimited(browser);
        if (riskLimited) {
            result.getDiagnostics().put("riskLimited", true);
        }
        List<String> errors = TbResultChecks.detail(result, Boolean.TRUE.equals(request.getIncludeSpecs()));
        if (!errors.isEmpty()) {
            result.getDiagnostics().put("validationErrors", errors);
            fail(result, CustomCrawlStatus.PAGE_CHANGED, riskLimited
                    ? "TB item page degraded by risk control (访问异常: details/specs hidden, price masked), retry later"
                    : "TB item detail missing required fields");
            debug.snapshot(browser, "05-detail-invalid");
            return;
        }
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("Product detail captured, mainImages=" + result.getMainImages().size()
                + ", detailImages=" + result.getDetailImages().size() + ", specs=" + result.getSpecs().size());
    }

    private List<JsonNode> waitAdjustResponses(Browser browser, TbMallPageApi api, int seconds) {
        List<JsonNode> out = new ArrayList<JsonNode>();
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (true) {
            for (String body : browser.drainResponseCapture(TbMallPageApi.DETAIL_ADJUST_API)) {
                JsonNode node = api.parseMtop(body);
                if (node != null) {
                    out.add(node);
                }
            }
            if (!out.isEmpty() || System.currentTimeMillis() > deadline) {
                return out;
            }
            Extends.sleep(400);
        }
    }

    private boolean waitItemPageReady(Browser browser, TbMallConfig config,
            TbProductDetailResult result, DebugRecorder debug) throws TimeoutException {
        long deadline = System.currentTimeMillis() + config.getPageTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("waitItemPageReady");
            if (isLoginRequiredPage(browser)) {
                result.setLoginRequired(true);
                fail(result, CustomCrawlStatus.LOGIN_REQUIRED, "TB item page requires login");
                debug.snapshot(browser, "02-item-login-required");
                return false;
            }
            clearSlider(browser, config, debug, "02-item-wait-slider");
            String url = browser.getCurrentUrl();
            if (url != null && url.contains("noitem")) {
                fail(result, CustomCrawlStatus.NOT_FOUND, "TB item not found or off shelf: " + result.getItemId());
                return false;
            }
            Boolean ready = browser.executeScript(
                    "function has(s){return !!document.querySelector(s);}" +
                    "return !!window.__ICE_APP_CONTEXT__ || has('[class*=\"mainTitle--\"]') || has('.tb-main-title') || has('[class*=\"ItemHeader\"]') || has('[class*=\"priceText--\"]');"
            );
            if (Boolean.TRUE.equals(ready)) {
                return true;
            }
            Extends.sleep(400);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readProductBase(Browser browser) {
        String script = "return (function() {" +
                "function text(el){return el?(el.innerText||el.textContent||'').trim():'';}" +
                "function attr(el, name){return el?(el.getAttribute(name)||'').trim():'';}" +
                "var title = '';" +
                "var titleEl = document.querySelector('[class*=\"mainTitle--\"], .tb-main-title, [class*=\"ItemHeader\"] h1, h1[data-title], meta[property=\"og:title\"]');" +
                "if(titleEl){ title = titleEl.getAttribute('content') || text(titleEl); }" +
                "var price = '';" +
                "var priceEl = document.querySelector('[class*=\"highlightPrice--\"] [class*=\"text--\"], [class*=\"Price--priceText--\"], [class*=\"priceText--\"], .tb-rmb-num, .tm-price');" +
                "if(priceEl){ price = text(priceEl).replace(/[￥¥\\s]/g, ''); }" +
                "var shopName = '';" +
                "var shopEl = document.querySelector('[class*=\"ShopHeader--title--\"], [class*=\"shopName--\"], .tb-shop-name, .shop-name');" +
                "if(shopEl){ shopName = text(shopEl); }" +
                "var mainImages = [];" +
                "var imgEls = document.querySelectorAll('[class*=\"thumbnailPic--\"], [class*=\"PicGallery--thumbnails--\"] img, [class*=\"mainPic--\"] img, #J_UlThumb img');" +
                "for(var i=0; i<imgEls.length; i++){" +
                "  var src = attr(imgEls[i], 'src') || attr(imgEls[i], 'data-src');" +
                "  if(src && src.indexOf('alicdn.com')>=0){" +
                "    if(src.indexOf('//') === 0) src = 'https:' + src;" +
                "    src = src.replace(/_\\d+x\\d+[^/]*$/i, '').replace(/_q\\d+\\.jpg.*$/i, '');" +
                "    if(mainImages.indexOf(src) < 0) mainImages.push(src);" +
                "  }" +
                "}" +
                "return { title: title, price: price, shopName: shopName, mainImages: mainImages };" +
                "})();";
        Map<String, Object> result = browser.executeScript(script);
        return result != null ? result : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<TbProductSpecItem> readProductSpecs(Browser browser) {
        String script = "return (function() {" +
                "function text(el){return el?(el.innerText||el.textContent||'').replace(/\\s+/g,' ').trim():'';}" +
                "var specs = [];" +
                "var pairs = document.querySelectorAll('[class*=\"generalParamsInfoItem--\"], [class*=\"emphasisParamsInfoItem--\"]');" +
                "for(var p=0; p<pairs.length; p++){" +
                "  var t = text(pairs[p].querySelector('[class*=\"ItemTitle--\"]'));" +
                "  var s = text(pairs[p].querySelector('[class*=\"ItemSubTitle--\"]'));" +
                "  var emphasis = String(pairs[p].className).indexOf('emphasis') >= 0;" +
                "  if(t && s){ specs.push(emphasis ? {name:s, value:t} : {name:t, value:s}); }" +
                "}" +
                "if(specs.length){ return specs; }" +
                "var items = document.querySelectorAll('[class*=\"ItemAttributes--attrList--\"] [class*=\"attrItem--\"], [class*=\"Attributes--list--\"] li, #attributes li, .attributes-list li');" +
                "for(var i=0; i<items.length; i++){" +
                "  var t = text(items[i]);" +
                "  var idx = t.indexOf(':') >= 0 ? t.indexOf(':') : t.indexOf('：');" +
                "  if(idx > 0){ var name = t.substring(0, idx).trim(), val = t.substring(idx + 1).trim();" +
                "    if(name && val){ specs.push({ name: name, value: val }); } }" +
                "}" +
                "return specs;" +
                "})();";
        List<Map<String, Object>> rawList = browser.executeScript(script);
        List<TbProductSpecItem> specs = new ArrayList<TbProductSpecItem>();
        if (rawList != null) {
            for (Map<String, Object> map : rawList) {
                TbProductSpecItem item = new TbProductSpecItem();
                item.setName(asString(map.get("name")));
                item.setValue(asString(map.get("value")));
                specs.add(item);
            }
        }
        return specs;
    }

    /**
     * 详情滚动终点：图文详情容器底部。有详情节点时不以相关推荐提前截断。
     * 没有详情节点时才停在推荐区上方，避免整页 scrollHeight 被看了又看撑高。
     */
    private static final String DETAIL_SCROLL_PROBE_SCRIPT =
            "return (function(){" +
            "function yTop(el){if(!el){return null;}var r=el.getBoundingClientRect();" +
            "return (window.scrollY||window.pageYOffset||0)+r.top;}" +
            "function yBottom(el){if(!el){return null;}var r=el.getBoundingClientRect();" +
            "if(r.height<40){return null;}" +
            "return (window.scrollY||window.pageYOffset||0)+r.bottom;}" +
            "var detail=document.querySelector('#imageTextInfo-content,#imageTextInfo-container,.desc-root,#description,[class*=\"descV8-container\"],[class*=\"Desc--desc--\"]');" +
            "var detailBottom=yBottom(detail);" +
            "var related=document.querySelector('.store-recommend-feeds,[class*=\"recommendsWrap\"],[data-spm=\"recommends\"]');" +
            "var relatedTop=null;" +
            "if(related){var rr=related.getBoundingClientRect();" +
            "if(rr.height>20||rr.width>40){relatedTop=yTop(related);}}" +
            "var stop=null;var reason='document';" +
            "if(detailBottom!=null){stop=detailBottom;reason='detail-desc';}" +
            "else if(relatedTop!=null){stop=relatedTop;reason='before-related';}" +
            "else{var doc=document.documentElement;stop=(doc?doc.scrollHeight:0)-2;reason='document';}" +
            "var viewBottom=(window.scrollY||window.pageYOffset||0)+window.innerHeight;" +
            "var room=Math.round(stop-viewBottom);" +
            "return {reached:room<=40,room:room>0?room:0,reason:reason};" +
            "})();";

    /**
     * 先把图文详情滚到底，再额外往下两下后停。不跟着看了又看继续加载。每步收集一次详情图。
     */
    private List<String> scrollDetailAndCollect(Browser browser, TbMallConfig config) throws TimeoutException {
        List<String> allDetailImages = new ArrayList<String>();
        int steps = config.getDetailScrollSteps();
        int stagnant = 0;
        int scrolled = 0;
        int tailLeft = 0;
        boolean detailDone = false;
        String stopReason = "max-steps";
        for (int i = 0; i < steps; i++) {
            ensureTaskDeadline("scrollDetail");
            if (detailDone) {
                if (tailLeft <= 0) {
                    stopReason = stopReason + "-tail";
                    break;
                }
                browser.scrollPage(0, 480 + ThreadLocalRandom.current().nextInt(280));
                scrolled++;
                tailLeft--;
                Extends.sleep(Math.max(300, config.nextStepDelayMillis() / 2));
                allDetailImages = mergeUniqueUrls(allDetailImages, readDetailImagesNow(browser));
                continue;
            }
            Map<String, Object> probe = probeDetailScroll(browser);
            int room = roomPx(probe);
            String reason = asString(probe.get("reason"));
            boolean reached = Boolean.TRUE.equals(probe.get("reached")) || (room >= 0 && room <= 40);
            if (reached && ("detail-desc".equals(reason) || ("before-related".equals(reason) && scrolled > 0))) {
                Extends.sleep(350 + ThreadLocalRandom.current().nextInt(250));
                Map<String, Object> again = probeDetailScroll(browser);
                int roomAgain = roomPx(again);
                String reasonAgain = asString(again.get("reason"));
                if ("detail-desc".equals(reasonAgain) && roomAgain > 80 && !Boolean.TRUE.equals(again.get("reached"))) {
                    continue;
                }
                if (!Strings.isEmpty(reasonAgain)) {
                    reason = reasonAgain;
                }
                stopReason = Strings.isEmpty(reason) ? "detail-bottom" : reason;
                detailDone = true;
                tailLeft = 2;
                continue;
            }
            if (reached && scrolled > 0) {
                stopReason = Strings.isEmpty(reason) ? "detail-bottom" : reason;
                break;
            }
            int human = 520 + ThreadLocalRandom.current().nextInt(320);
            int delta = human;
            if (room >= 0) {
                delta = room <= 280 ? Math.max(20, room / 3) : Math.min(human, Math.max(40, room - 40));
            }
            Object before = pageOffset(browser);
            browser.scrollPage(0, delta);
            scrolled++;
            Extends.sleep(Math.max(300, config.nextStepDelayMillis() / 2));
            allDetailImages = mergeUniqueUrls(allDetailImages, readDetailImagesNow(browser));
            Object after = pageOffset(browser);
            if (before != null && String.valueOf(before).equals(String.valueOf(after))) {
                stagnant++;
                if (stagnant >= 2) {
                    stopReason = "offset-stalled";
                    break;
                }
            } else {
                stagnant = 0;
            }
        }
        log.info("TB detail scroll stop, reason={}, steps={}, tailLeft={}", stopReason, scrolled, tailLeft);
        return allDetailImages;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> probeDetailScroll(Browser browser) {
        try {
            Object raw = browser.executeScript(DETAIL_SCROLL_PROBE_SCRIPT);
            if (raw instanceof Map) {
                return (Map<String, Object>) raw;
            }
        } catch (Exception e) {
            log.warn("TB detail scroll probe fail, error={}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    private int roomPx(Map<String, Object> probe) {
        Object room = probe.get("room");
        if (room instanceof Number) {
            return ((Number) room).intValue();
        }
        return -1;
    }

    private Object pageOffset(Browser browser) {
        try {
            return browser.executeScript("return Math.round(window.pageYOffset||window.scrollY||0);");
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readDetailImagesNow(Browser browser) {
        String script = "return (function() {" +
                "function attr(el, name){return el?(el.getAttribute(name)||'').trim():'';}" +
                "var images = [];" +
                "var scope = document.querySelector('#description, [class*=\"descV8\"], [class*=\"Desc--desc--\"], [class*=\"desc-root\"], [class*=\"imageTextInfo\"]');" +
                "if(!scope){ return images; }" +
                "var imgEls = scope.querySelectorAll('img');" +
                "for(var i=0; i<imgEls.length; i++){" +
                "  var src = attr(imgEls[i], 'data-src') || attr(imgEls[i], 'src') || attr(imgEls[i], 'data-original');" +
                "  if(src && src.indexOf('alicdn.com')>=0){" +
                "    if(src.indexOf('//') === 0) src = 'https:' + src;" +
                "    if(images.indexOf(src) < 0) images.push(src);" +
                "  }" +
                "}" +
                "return images;" +
                "})();";
        List<String> list = browser.executeScript(script);
        return list != null ? list : Collections.<String>emptyList();
    }

    private boolean isRiskLimited(Browser browser) {
        return bodySnippet(browser, 6000).contains(RISK_TEXT);
    }

    private boolean clearSlider(Browser browser, TbMallConfig config, DebugRecorder debug, String step) {
        if (!sliderVerifyHandler.isSliderVerifyPage(browser)) {
            return true;
        }
        debug.snapshot(browser, step);
        return sliderVerifyHandler.checkAndHandle(browser, step, config.getSliderMaxAutoAttempts(),
                config.getStepDelayMillis(), (b, name) -> debug.snapshot(b, name));
    }

    private List<TbImageInfo> downloadImages(Browser browser, String itemId, List<String> urls,
            String subDir, TbMallConfig config) throws TimeoutException {
        List<TbImageInfo> out = new ArrayList<TbImageInfo>();
        if (urls == null || urls.isEmpty()) {
            return out;
        }
        Path dir = Paths.get(config.getImageSaveDir(), itemId, subDir);
        int index = 0;
        for (String url : urls) {
            index++;
            ensureTaskDeadline("image-download");
            try {
                byte[] bytes = browser.downloadAsBytes(url);
                if (bytes == null || bytes.length == 0) {
                    throw new InvalidException("Empty image body: %s", url);
                }
                Files.createDirectories(dir);
                String fileName = String.format("%03d%s", index, imageExtension(url));
                Path file = dir.resolve(fileName);
                Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                TbImageInfo info = new TbImageInfo();
                info.setUrl(url);
                info.setLocalPath(file.toString());
                info.setFileName(fileName);
                out.add(info);
            } catch (Exception e) {
                log.warn("Download TB image fail, url={}, error={}", url, e.getMessage());
            }
            if (index < urls.size()) {
                Extends.sleep(config.getDownloadDelayMillis());
            }
        }
        return out;
    }

    private List<TbImageInfo> toImageInfos(List<String> urls) {
        List<TbImageInfo> list = new ArrayList<TbImageInfo>();
        if (urls == null) {
            return list;
        }
        for (String u : urls) {
            TbImageInfo info = new TbImageInfo();
            info.setUrl(u);
            list.add(info);
        }
        return list;
    }

    private List<String> dedupeDetailImages(List<String> detailUrls, List<String> mainUrls, int maxCount) {
        List<String> result = new ArrayList<String>();
        for (String u : detailUrls) {
            if (mainUrls.contains(u) || result.contains(u)) {
                continue;
            }
            result.add(u);
            if (result.size() >= maxCount) {
                break;
            }
        }
        return result;
    }

    private List<String> mergeUniqueUrls(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<String>(first);
        if (second != null) {
            for (String s : second) {
                if (!merged.contains(s)) {
                    merged.add(s);
                }
            }
        }
        return merged;
    }

    private String imageExtension(String url) {
        if (url == null) {
            return ".jpg";
        }
        int q = url.indexOf('?');
        String clean = q >= 0 ? url.substring(0, q) : url;
        int dot = clean.lastIndexOf('.');
        if (dot >= 0 && dot < clean.length() - 1) {
            String ext = clean.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png") || ext.equals(".webp") || ext.equals(".avif")) {
                return ext;
            }
        }
        return ".jpg";
    }

    private boolean isLoginRequiredPage(Browser browser) {
        String url = browser.getCurrentUrl();
        if (Strings.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("login.taobao.com") || lower.contains("login_jump") || lower.contains("_____tmd_____/page/login_jump")) {
            return true;
        }
        String body = bodySnippet(browser);
        return body.contains("密码登录") && body.contains("扫码登录");
    }

    private CrawlEntryOptions createEntryOptions(String profileName, Boolean forcePreflight, Boolean keepOpen,
            Integer loginWaitSeconds, String initialUrl, String taskType, TbMallConfig config) {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setTaskType(taskType);
        options.setProfileName(profileName);
        options.setPreflightEnabled(config.isPreflightEnabled());
        options.setPreflightUrl(config.getPreflightUrl());
        options.setPreflightStrict(config.isPreflightStrict());
        options.setPreflightCacheMinutes(config.getPreflightCacheMinutes());
        options.setForcePreflight(forcePreflight != null ? forcePreflight : config.isForcePreflight());
        options.setInitialUrl(initialUrl);
        options.setLoginUrlPrefix(config.getLoginUrlPrefix());
        options.setInitialPageTimeoutSeconds(config.getInitialPageTimeoutSeconds());
        options.setLoginWaitSeconds(loginWaitSeconds != null ? loginWaitSeconds : config.getLoginWaitSeconds());
        options.setStepDelayMillis(config.getStepDelayMillis());
        options.setStepDelayRandomMillis(config.getStepDelayRandomMillis());
        options.setKeepBrowserOpenOnLoginRequired(keepOpen != null ? keepOpen : config.isKeepBrowserOpenOnLoginRequired());
        options.setKeepBrowserOpenSecondsOnLoginRequired(config.getKeepBrowserOpenSecondsOnLoginRequired());
        options.setLoginRequiredUrlMatcher(url -> {
            if (Strings.isEmpty(url)) return false;
            String l = url.toLowerCase(Locale.ROOT);
            return l.contains("login.taobao.com") || l.contains("login_jump") || l.contains("/login");
        });
        options.setLoginRequiredBodyMatcher(body -> {
            if (Strings.isEmpty(body)) return false;
            return (body.contains("密码登录") || body.contains("扫码登录") || body.contains("短信登录")) && body.contains("登录");
        });
        options.setLoggedInUrlMatcher(url -> {
            if (Strings.isEmpty(url)) return false;
            String l = url.toLowerCase(Locale.ROOT);
            return l.contains("item.taobao.com") || l.contains("detail.tmall.com") || l.contains("www.taobao.com")
                    || l.contains("s.taobao.com");
        });
        options.setLoggedInBodyMatcher(body -> {
            if (Strings.isEmpty(body)) return false;
            return body.contains("加入购物车") || body.contains("立即购买") || body.contains("我的淘宝") || body.contains("宝贝详情");
        });
        return options;
    }

    private TbProductDetailRequest normalizeDetailRequest(TbProductDetailRequest raw, TbMallConfig config) {
        TbProductDetailRequest req = raw != null ? raw : new TbProductDetailRequest();
        String itemId = req.getItemId();
        String url = req.getProductUrl();
        if (Strings.isEmpty(itemId) && !Strings.isEmpty(url)) {
            Matcher m = ITEM_ID_PATTERN.matcher(url);
            if (m.find()) {
                itemId = m.group(1);
            }
        }
        if (Strings.isEmpty(url) && !Strings.isEmpty(itemId)) {
            url = config.getItemUrlTemplate().replace("{itemId}", itemId);
        }
        req.setItemId(itemId != null ? itemId.trim() : "");
        req.setProductUrl(url != null ? url.trim() : "");
        if (req.getDownloadImages() == null) {
            req.setDownloadImages(false);
        }
        if (req.getIncludeSpecs() == null) {
            req.setIncludeSpecs(true);
        }
        if (Strings.isEmpty(req.getProfileName())) {
            req.setProfileName(profileManager.defaultProfileName());
        }
        req.setProfileName(profileManager.normalizeProfileName(req.getProfileName()));
        if (Strings.isEmpty(req.getOutputPath())) {
            req.setOutputPath(config.getDefaultOutputPath());
        }
        if (req.getDebugEnabled() == null) {
            req.setDebugEnabled(appConfig.getCustom().isDebugEnabled());
        }
        if (Strings.isEmpty(req.getDebugOutputDir())) {
            req.setDebugOutputDir(config.getDebugOutputDir());
        }
        return req;
    }

    private TbProductDetailResult createDetailResult(TbProductDetailRequest req) {
        TbProductDetailResult result = new TbProductDetailResult();
        result.setTaskType(DETAIL_TASK_TYPE);
        result.setItemId(req.getItemId());
        result.setProductUrl(req.getProductUrl());
        result.setProfileName(req.getProfileName());
        return result;
    }

    private WebBrowserConfig createBrowserConfig(TbMallConfig config) {
        WebBrowserConfig browserConfig = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
        browserConfig.setProfileDataPath(profileManager.resolveProfileDataPath(config.getProfileName()));
        browserConfig.setHeadless(config.isHeadless());
        browserConfig.setFingerprintEnabled(config.isFingerprintEnabled());
        browserConfig.setFingerprintHeadless(config.isHeadless());
        return browserConfig;
    }

    private void humanNavigationPause(TbMallConfig config) {
        int base = config.getNavigationPauseMillis();
        int maxRandom = config.getNavigationPauseRandomMillis();
        if (base <= 0 && maxRandom <= 0) {
            return;
        }
        int delay = base + (maxRandom > 0 ? ThreadLocalRandom.current().nextInt(maxRandom) : 0);
        Extends.sleep(delay);
    }

    private void applyEntryResult(TbProductDetailResult result, CrawlEntryResult entry) {
        result.setCurrentUrl(entry.getCurrentUrl());
        result.setLoginRequired(entry.isLoginRequired());
        result.setFingerprintPassed(entry.isFingerprintPassed());
        if (entry.getDiagnostics() != null) {
            result.getDiagnostics().putAll(entry.getDiagnostics());
        }
        if (!entry.isPassed()) {
            fail(result, entry.getStatus(), entry.getMessage());
        }
    }

    private void fail(TbProductDetailResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message);
    }

    private void fail(TbSearchResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message);
    }

    private void ensureTaskDeadline(String step) throws TimeoutException {
        Long deadline = taskDeadlineHolder.get();
        if (deadline != null && System.currentTimeMillis() > deadline) {
            throw new TimeoutException("TB mall task deadline exceeded at step: " + step);
        }
    }

    private static String firstNonEmpty(String first, String second) {
        return Strings.isEmpty(first) ? (second == null ? "" : second) : first;
    }

    private String asString(Object obj) {
        return obj == null ? "" : String.valueOf(obj).trim();
    }

    private List<String> asStringList(Object obj) {
        if (obj instanceof List) {
            List<?> l = (List<?>) obj;
            List<String> out = new ArrayList<String>(l.size());
            for (Object item : l) {
                if (item != null) out.add(String.valueOf(item).trim());
            }
            return out;
        }
        return Collections.emptyList();
    }

    private String bodySnippet(Browser browser) {
        return bodySnippet(browser, 1000);
    }

    private String bodySnippet(Browser browser, int maxLength) {
        String body;
        try {
            body = browser.executeScript("return document.body ? document.body.innerText : '';");
        } catch (Exception e) {
            return "";
        }
        return Strings.isEmpty(body) || body.length() <= maxLength ? (body == null ? "" : body) : body.substring(0, maxLength);
    }

    private static class DebugRecorder {
        private final String taskDir;
        private final boolean enabled;
        private int sequence = 1;

        DebugRecorder(String itemId, String profileName, boolean debugEnabled, String explicitDir, String defaultDir) {
            this.enabled = debugEnabled;
            String root = Strings.isEmpty(explicitDir) ? defaultDir : explicitDir;
            String time = LocalDateTime.now().format(DEBUG_TIME_FORMATTER);
            String safeId = Strings.isEmpty(itemId) ? "unknown" : itemId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            this.taskDir = Paths.get(root, profileName, safeId + "-" + time).toString();
        }

        boolean enabled() {
            return enabled;
        }

        Path getTaskDir() {
            return Paths.get(taskDir);
        }

        void snapshot(Browser browser, String name) {
            if (!enabled) return;
            try {
                Files.createDirectories(Paths.get(taskDir));
                String content = browser.executeScript("return document.documentElement ? document.documentElement.outerHTML : '';");
                String fileName = String.format("%02d_%s.html", sequence++, name);
                Files.write(Paths.get(taskDir, fileName), (content != null ? content : "").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.debug("Write TB mall snapshot fail: {}", e.getMessage());
            }
        }

        void snapshotText(String name, String text) {
            if (!enabled) return;
            try {
                Files.createDirectories(Paths.get(taskDir));
                String fileName = String.format("%02d_%s.txt", sequence++, name);
                Files.write(Paths.get(taskDir, fileName), (text != null ? text : "").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.debug("Write TB mall text snapshot fail: {}", e.getMessage());
            }
        }
    }
}
