package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.DebugSnapshots;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.CrawlEntryResult;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CrawlResultValidator;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.CustomCrawlTask;
import org.rx.crawler.task.common.KeepAliveUrlStore;
import org.rx.crawler.task.common.LoginNotificationContext;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.crawler.task.common.ProductInfoDto;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.exception.InvalidException;
import org.rx.util.BeanMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.tryClose;

@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class TbPromotionUrlTask implements CustomCrawlTask<PromotionUrlRequest, PromotionUrlResult> {
    private static final String TASK_TYPE = "getTbPromotionUrl";
    public static final String SEARCH_TASK_TYPE = "tbUnionSearchProducts";
    private static final DateTimeFormatter DEBUG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final String QUICK_ENTER_TEXT = "快速进入";
    private static final String QUICK_LOGIN_TEXT = "快速登录";

    private final AppConfig appConfig;
    private final BrowserProfileManager profileManager;
    private final CrawlEntryService entryService;
    private final ResultWriter resultWriter;
    private final ObjectMapper objectMapper;
    private final KeepAliveUrlStore keepAliveUrlStore;
    private final SliderVerifyHandler sliderVerifyHandler = new SliderVerifyHandler();
    private final ThreadLocal<Long> taskDeadlineHolder = new ThreadLocal<Long>();

    public TbPromotionUrlTask(AppConfig appConfig, BrowserProfileManager profileManager, CrawlEntryService entryService,
            ResultWriter resultWriter, ObjectMapper objectMapper) {
        this(appConfig, profileManager, entryService, resultWriter, objectMapper, KeepAliveUrlStore.NOOP);
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
        return executeInternal(request);
    }

    public List<PromotionUrlResult> getPromotionUrls(List<String> keywords) {
        return executeBatchInternal(keywords);
    }

    public TbUnionProductSearchResult searchProducts(TbUnionProductSearchRequest request) {
        return executeSearchInternal(request);
    }

    public boolean closeProfile(String profileName) {
        return profileManager.closeSession(profileName);
    }

    private PromotionUrlResult executeInternal(PromotionUrlRequest rawRequest) {
        TbPromotionConfig tbConfig = appConfig.getCustom().getTbPromotion();
        PromotionUrlRequest request = normalizeRequest(rawRequest, tbConfig);
        PromotionUrlResult result = createResult(request);
        DebugRecorder debug = new DebugRecorder(request, tbConfig);
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }

        BrowserProfileManager.ProfileLease lease = null;
        taskDeadlineHolder.set(System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(Math.max(1, appConfig.getCustom().getMaxTaskMinutes())));
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(request, tbConfig));
            Browser browser = lease.getBrowser();

            CrawlEntryResult entry = entryService.enter(browser, lease, createEntryOptions(request, tbConfig));
            applyEntryResult(result, entry);
            debug.snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", result.getMessage());
                return result;
            }

            runPromotionFlow(browser, request, tbConfig, result, debug);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("TB promotion url fail, productInfo={}, adSiteName={}, profile={}, error={}",
                    request.getKeyword(), request.getAdSiteName(), request.getProfileName(), e.getMessage(), e);
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            resultWriter.appendJsonLine(request.getOutputPath(), result);
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private TbUnionProductSearchResult executeSearchInternal(TbUnionProductSearchRequest rawRequest) {
        TbPromotionConfig tbConfig = appConfig.getCustom().getTbPromotion();
        TbUnionProductSearchRequest request = normalizeSearchRequest(rawRequest, tbConfig);
        TbUnionProductSearchResult result = new TbUnionProductSearchResult();
        result.setTaskType(SEARCH_TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setKeyword(request.getKeyword());
        result.setProfileName(request.getProfileName());
        PromotionUrlRequest entryRequest = toPromotionRequest(request, tbConfig);
        PromotionUrlResult gate = createResult(entryRequest);
        DebugRecorder debug = new DebugRecorder(entryRequest, tbConfig);
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }
        BrowserProfileManager.ProfileLease lease = null;
        taskDeadlineHolder.set(System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(Math.max(1, appConfig.getCustom().getMaxTaskMinutes())));
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(entryRequest, tbConfig));
            Browser browser = lease.getBrowser();
            CrawlEntryResult entry = entryService.enter(browser, lease, createEntryOptions(entryRequest, tbConfig));
            applyEntryResult(gate, entry);
            copyGate(result, gate);
            debug.snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", result.getMessage());
                return result;
            }
            if (!preparePromotionGoodsPage(browser, tbConfig, gate, debug)) {
                copyGate(result, gate);
                return result;
            }
            runUnionSearchFlow(browser, request, tbConfig, result, gate, debug);
            return result;
        } catch (TimeoutException e) {
            failSearch(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("TB union product search fail, keyword={}, profile={}, error={}",
                    request.getKeyword(), request.getProfileName(), e.getMessage(), e);
            failSearch(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            resultWriter.appendJsonLine(request.getOutputPath(), result);
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private void runUnionSearchFlow(Browser browser, TbUnionProductSearchRequest request, TbPromotionConfig config,
            TbUnionProductSearchResult result, PromotionUrlResult gate, DebugRecorder debug) throws TimeoutException {
        TbUnionPageApi.SearchHit ajax = new TbUnionPageApi(objectMapper)
                .searchProducts(browser, request.getKeyword(), request.getMaxResults());
        result.getDiagnostics().put("ajaxSearch", ajax.diagnostic);
        if (ajax.success) {
            if (ajax.items.isEmpty()) {
                failSearch(result, CustomCrawlStatus.NOT_FOUND, "TB union product not found");
                result.getDiagnostics().put("channel", "ajax");
                debug.snapshot(browser, "03-search-no-result");
                return;
            }
            if (publishUnionItems(result, ajax.items, "ajax")) {
                result.setTotalCount(ajax.totalCount);
                result.setCurrentUrl(browser.getCurrentUrl());
                keepAliveUrlStore.collect("tb", browser, result.getDiagnostics());
                debug.snapshot(browser, "03-search-items-collected");
                return;
            }
            result.getDiagnostics().put("ajaxSearchValidation", result.getDiagnostics().remove("validationErrors"));
        }
        result.getDiagnostics().put("channel", "page");
        if (!nativeSetSearchValue(browser, request.getKeyword())) {
            failSearch(result, CustomCrawlStatus.PAGE_CHANGED, "TB union search input not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "04-search-input-missing");
            return;
        }
        if (!nativeClickSmartSearchButton(browser)
                || !waitSearchSubmitted(browser, config, gate, debug, request.getKeyword(), 8)) {
            navigateGoodsSearch(browser, request.getKeyword(), config);
            result.getDiagnostics().put("searchNavigateFallback", true);
        }
        waitSearchSettled(browser, config, gate, debug, request.getKeyword());
        result.setCurrentUrl(browser.getCurrentUrl());
        debug.snapshot(browser, "05-search-settled");
        if (isNoProductResult(browser)) {
            failSearch(result, CustomCrawlStatus.NOT_FOUND, "TB union product not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        List<TbUnionProductItem> items = readUnionCards(browser, request.getMaxResults());
        if (items.isEmpty()) {
            failSearch(result, CustomCrawlStatus.PAGE_CHANGED, "TB union product cards not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "06-search-cards-missing");
            return;
        }
        if (!publishUnionItems(result, items, "page")) {
            failSearch(result, CustomCrawlStatus.PAGE_CHANGED, "TB union search cards missing required fields");
            debug.snapshot(browser, "06-search-cards-invalid");
            return;
        }
        keepAliveUrlStore.collect("tb", browser, result.getDiagnostics());
        debug.snapshot(browser, "06-search-cards-collected");
    }

    private boolean publishUnionItems(TbUnionProductSearchResult result, List<TbUnionProductItem> items, String channel) {
        List<String> errors = new ArrayList<String>();
        List<TbUnionProductItem> complete = TbResultChecks.completeUnionItems(items, errors);
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
        return true;
    }

    private boolean publishPromotion(PromotionUrlResult result, String promotionUrl, ProductInfoDto info,
            String keyword, String channel) {
        List<String> errors = TbResultChecks.promotion(promotionUrl, info, keyword);
        if (!errors.isEmpty()) {
            result.getDiagnostics().put("validationErrors", errors);
            return false;
        }
        result.getDiagnostics().remove("validationErrors");
        result.setProductInfo(info);
        result.setPromotionUrl(promotionUrl);
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("");
        result.getDiagnostics().put("channel", channel);
        return true;
    }

    private List<TbUnionProductItem> readUnionCards(Browser browser, int maxResults) {
        String script = "var limit=arguments[0];" +
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function abs(u){u=u||'';return u.indexOf('//')===0?location.protocol+u:u;}" +
                "function pick(t,re){var m=t.match(re);return m?m[1]:'';}" +
                "var cards=Array.prototype.slice.call(document.querySelectorAll('[data-spm=\"GoodsListItem\"]'));" +
                "var out=[],seen={};" +
                "for(var i=0;i<cards.length&&out.length<limit;i++){var card=cards[i],raw=norm(card.innerText||'');" +
                "var a=card.querySelector('a[href*=\"promo/goods/detail\"]');if(!a){continue;}" +
                "var href=abs(a.getAttribute('href')||a.href),id=pick(href,/[?&]itemId=([^&#]+)/);" +
                "if(!id||seen[id]){continue;}seen[id]=1;" +
                "var titleNode=a.querySelector('[class*=\"title\"]')||a;" +
                "var img=card.querySelector('img');" +
                "var store=pick(raw,/店铺名称[:：]?\\s*(\\S+)/);" +
                "if(!store){var ns=card.querySelectorAll('a,span,div');for(var j=0;j<ns.length;j++){var st=norm(ns[j].innerText||'');" +
                "if(st&&st.length<=40&&ns[j].children.length===0&&/(旗舰店|专卖店|专营店|超市|店)$/.test(st)){store=st;break;}}}" +
                "out.push({itemId:decodeURIComponent(id),productName:norm(titleNode.innerText||a.getAttribute('title')||'')," +
                "productLink:href,price:pick(raw,/到手价\\s*[￥¥]?\\s*([0-9]+(?:\\.[0-9]+)?)/)||pick(raw,/[￥¥]\\s*([0-9]+(?:\\.[0-9]+)?)/)," +
                "commissionRate:pick(raw,/佣金率[:：]?\\s*([0-9.]+%)/),storeName:store," +
                "imageUrl:img?abs(img.currentSrc||img.getAttribute('src')||img.getAttribute('data-src')||''):''});}" +
                "return out;";
        try {
            Object raw = browser.executeScript(script, Math.max(1, maxResults));
            if (raw == null) {
                return new ArrayList<TbUnionProductItem>();
            }
            return objectMapper.convertValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<TbUnionProductItem>>() {
            });
        } catch (Exception e) {
            log.warn("Read TB union cards fail, error={}", e.getMessage());
            return new ArrayList<TbUnionProductItem>();
        }
    }

    private TbUnionProductSearchRequest normalizeSearchRequest(TbUnionProductSearchRequest request, TbPromotionConfig config) {
        if (request == null) {
            request = new TbUnionProductSearchRequest();
        }
        if (Strings.isEmpty(request.getKeyword())) {
            throw new InvalidException("keyword is required");
        }
        request.setKeyword(request.getKeyword().trim());
        if (request.getMaxResults() == null || request.getMaxResults() <= 0) {
            request.setMaxResults(20);
        }
        if (request.getMaxResults() > 30) {
            request.setMaxResults(30);
        }
        if (Strings.isEmpty(request.getProfileName())) {
            request.setProfileName(Strings.isEmpty(config.getProfileName())
                    ? profileManager.defaultProfileName() : config.getProfileName());
        }
        request.setProfileName(profileManager.normalizeProfileName(request.getProfileName()));
        if (Strings.isEmpty(request.getOutputPath())) {
            request.setOutputPath(config.getDefaultOutputPath());
        }
        return request;
    }

    private PromotionUrlRequest toPromotionRequest(TbUnionProductSearchRequest request, TbPromotionConfig config) {
        PromotionUrlRequest entryRequest = new PromotionUrlRequest();
        entryRequest.setKeyword(request.getKeyword());
        entryRequest.setAdSiteName(config.getDefaultAdSiteName());
        entryRequest.setMediaName(config.getDefaultMediaName());
        entryRequest.setProfileName(request.getProfileName());
        entryRequest.setForcePreflight(request.getForcePreflight());
        entryRequest.setKeepBrowserOpenOnLoginRequired(request.getKeepBrowserOpenOnLoginRequired());
        entryRequest.setOutputPath(request.getOutputPath());
        entryRequest.setDebugEnabled(request.getDebugEnabled());
        entryRequest.setDebugOutputDir(request.getDebugOutputDir());
        return normalizeRequest(entryRequest, config);
    }

    private void copyGate(TbUnionProductSearchResult result, PromotionUrlResult gate) {
        result.setCurrentUrl(gate.getCurrentUrl());
        result.setLoginRequired(gate.isLoginRequired());
        result.setFingerprintPassed(gate.isFingerprintPassed());
        result.getDiagnostics().putAll(gate.getDiagnostics());
        if (gate.getStatus() != CustomCrawlStatus.SUCCESS) {
            result.setStatus(gate.getStatus());
            result.setMessage(gate.getMessage());
        }
    }

    private void failSearch(TbUnionProductSearchResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message);
    }

    private List<PromotionUrlResult> executeBatchInternal(List<String> keywords) {
        TbPromotionConfig tbConfig = appConfig.getCustom().getTbPromotion();
        List<PromotionUrlRequest> requests = createBatchRequests(keywords, tbConfig);
        List<PromotionUrlResult> results = new ArrayList<PromotionUrlResult>();
        List<DebugRecorder> debugs = new ArrayList<DebugRecorder>();
        for (PromotionUrlRequest request : requests) {
            PromotionUrlResult result = createResult(request);
            DebugRecorder debug = new DebugRecorder(request, tbConfig);
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
        boolean[] written = new boolean[results.size()];
        taskDeadlineHolder.set(System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(Math.max(1, appConfig.getCustom().getMaxTaskMinutes())
                * Math.max(1, requests.size())));
        try {
            lease = profileManager.acquire(requests.get(0).getProfileName(), createBrowserConfig(requests.get(0), tbConfig));
            Browser browser = lease.getBrowser();

            CrawlEntryResult entry = entryService.enter(browser, lease, createEntryOptions(requests.get(0), tbConfig));
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

            if (!preparePromotionGoodsPage(browser, tbConfig, results.get(0), debugs.get(0))) {
                copyFailureToRemaining(results);
                return results;
            }

            for (int i = 0; i < requests.size(); i++) {
                PromotionUrlRequest request = requests.get(i);
                PromotionUrlResult result = results.get(i);
                DebugRecorder debug = debugs.get(i);
                try {
                    if (i > 0 && !ensurePromotionGoodsPageForNextTbKeyword(browser, tbConfig, result, debug)) {
                        continue;
                    }
                    runPromotionKeywordFlow(browser, request, tbConfig, result, debug);
                } catch (TimeoutException e) {
                    fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
                    debug.snapshotText("99-timeout", result.getMessage());
                    failRemaining(results, i + 1, CustomCrawlStatus.TIMEOUT, e.getMessage());
                    return results;
                } catch (Exception e) {
                    log.warn("TB batch promotion url fail, productInfo={}, adSiteName={}, profile={}, error={}",
                            request.getKeyword(), request.getAdSiteName(), request.getProfileName(), e.getMessage(), e);
                    fail(result, CustomCrawlStatus.FAILED, e.getMessage());
                    debug.snapshotText("99-failed", result.getMessage());
                } finally {
                    resultWriter.appendJsonLine(request.getOutputPath(), result);
                    written[i] = true;
                }
            }
            return results;
        } catch (TimeoutException e) {
            failRemaining(results, 0, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debugs.get(0).snapshotText("99-timeout", e.getMessage());
            return results;
        } catch (Exception e) {
            log.warn("TB batch promotion url fail, profile={}, error={}",
                    requests.get(0).getProfileName(), e.getMessage(), e);
            failRemaining(results, 0, CustomCrawlStatus.FAILED, e.getMessage());
            debugs.get(0).snapshotText("99-failed", e.getMessage());
            return results;
        } finally {
            for (int i = 0; i < results.size(); i++) {
                if (!written[i]) {
                    resultWriter.appendJsonLine(requests.get(i).getOutputPath(), results.get(i));
                }
            }
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private List<PromotionUrlRequest> createBatchRequests(List<String> keywords, TbPromotionConfig config) {
        List<PromotionUrlRequest> requests = new ArrayList<PromotionUrlRequest>();
        if (keywords == null) {
            return requests;
        }
        for (String keyword : keywords) {
            if (Strings.isEmpty(keyword)) {
                throw new InvalidException("productInfo is required");
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

    private CrawlEntryOptions createEntryOptions(PromotionUrlRequest request, TbPromotionConfig config) {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setTaskType(TASK_TYPE);
        options.setProfileName(request.getProfileName());
        options.setPreflightEnabled(config.isPreflightEnabled());
        options.setPreflightUrl(config.getPreflightUrl());
        options.setPreflightStrict(config.isPreflightStrict());
        options.setPreflightCacheMinutes(config.getPreflightCacheMinutes());
        options.setForcePreflight(
                request.getForcePreflight() == null ? config.isForcePreflight() : request.getForcePreflight());
        // 直接进选品页，少一次 home 中转；会话续期「确认登录」仍由 completeForwardLanding 处理
        options.setInitialUrl(config.getPromotionGoodsUrl());
        options.setLoginUrlPrefix(config.getLoginUrlPrefix());
        options.setInitialPageTimeoutSeconds(config.getInitialPageTimeoutSeconds());
        options.setLoginWaitSeconds(config.getLoginWaitSeconds());
        options.setStepDelayMillis(config.getStepDelayMillis());
        options.setStepDelayRandomMillis(config.getStepDelayRandomMillis());
        options.setKeepBrowserOpenOnLoginRequired(request.getKeepBrowserOpenOnLoginRequired() == null
                ? config.isKeepBrowserOpenOnLoginRequired()
                : request.getKeepBrowserOpenOnLoginRequired());
        options.setKeepBrowserOpenSecondsOnLoginRequired(config.getKeepBrowserOpenSecondsOnLoginRequired());
        options.setLoginRequiredUrlMatcher(url -> isHardLoginUrl(url, config));
        // 真正掉登录：密码/扫码框；「确认登录+快速进入」是会话续期，不算硬登录
        options.setLoginRequiredBodyMatcher(this::isHardLoginBody);
        options.setSessionResumeBodyMatcher(this::isSessionResumeBody);
        options.setLoggedInUrlMatcher(url -> isLoggedInUrl(url, config));
        options.setLoggedInBodyMatcher(body -> !isHardLoginBody(body)
                && containsAny(body, "阿里妈妈", "淘宝联盟", "选品广场", "智能搜索", QUICK_ENTER_TEXT, QUICK_LOGIN_TEXT,
                "确认继续用以下账号登录"));
        return options;
    }

    private void runPromotionFlow(Browser browser, PromotionUrlRequest request, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug) throws TimeoutException {
        if (!preparePromotionGoodsPage(browser, config, result, debug)) {
            return;
        }
        runPromotionKeywordFlow(browser, request, config, result, debug);
    }

    private boolean preparePromotionGoodsPage(Browser browser, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug) throws TimeoutException {
        browser.maximize();
        if (completeForwardLanding(browser, config)) {
            debug.snapshot(browser, "02-forward-landing-entered");
        }
        if (isForwardLandingIncomplete(browser)) {
            String body = bodySnippet(browser);
            if (isHardLoginBody(body)) {
                fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                        "TB promotion login required. Finish login in the opened common Chrome profile, then retry.");
                result.setLoginRequired(true);
                debug.snapshot(browser, "02-forward-landing-login-required");
                return false;
            }
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion forward landing quick-enter page not completed");
            result.getDiagnostics().put("currentUrl", browser.getCurrentUrl());
            result.getDiagnostics().put("body", body);
            debug.snapshot(browser, "02-forward-landing-incomplete");
            return false;
        }

        // entry 已直达选品页时跳过重复导航，只做轻量 settle
        String currentUrl = browser.getCurrentUrl();
        boolean alreadyOnGoods = !Strings.isEmpty(currentUrl)
                && currentUrl.toLowerCase(Locale.ROOT).contains("/pages/promo/goods");
        if (!alreadyOnGoods) {
            browser.navigateUrl(config.getPromotionGoodsUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
            Extends.sleep(config.nextStepDelayMillis());
            // 导航后又可能弹会话续期
            if (completeForwardLanding(browser, config)) {
                debug.snapshot(browser, "02-forward-landing-after-goods-nav");
            }
        } else {
            Extends.sleep(Math.max(400, config.nextStepDelayMillis() / 2));
        }
        result.setCurrentUrl(browser.getCurrentUrl());
        debug.snapshot(browser, "02-goods-page-loaded");
        if (isHardLoginUrl(result.getCurrentUrl(), config) || isHardLoginBody(bodySnippet(browser))) {
            fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                    "TB promotion login expired before entering goods page. Finish login in the opened common Chrome profile, then retry.");
            result.setLoginRequired(true);
            debug.snapshot(browser, "02-goods-login-required");
            return false;
        }
        if (!waitGoodsPageReady(browser, config, result, debug)) {
            String bodyNow = bodySnippet(browser);
            String message;
            if (sliderVerifyHandler.isSliderVerifyPage(browser)) {
                message = "TB promotion slider verify not cleared while entering goods page";
            } else if (isSessionResumeBody(bodyNow) || hasQuickEntrance(bodyNow)) {
                message = "TB promotion session-resume (确认登录/快速进入) not completed while entering goods page";
            } else {
                message = "TB promotion goods page not ready";
            }
            fail(result, CustomCrawlStatus.PAGE_CHANGED, message);
            result.getDiagnostics().put("body", bodyNow);
            debug.snapshot(browser, "02-goods-page-not-ready");
            return false;
        }
        if (!checkAndWaitSliderVerify(browser, config, result, debug, "02-goods-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared before search");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "02-goods-slider-not-cleared");
            return false;
        }
        debug.snapshot(browser, "02-goods-page-ready");
        return true;
    }

    private void runPromotionKeywordFlow(Browser browser, PromotionUrlRequest request, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug) throws TimeoutException {
        TbUnionPageApi.PromotionHit ajax = new TbUnionPageApi(objectMapper)
                .promote(browser, request.getKeyword(), request.getMediaName(), request.getAdSiteName());
        result.getDiagnostics().put("ajaxPromotion", ajax.diagnostic);
        if (ajax.success && publishPromotion(result, ajax.promotionUrl, ajax.productInfo, request.getKeyword(), "ajax")) {
            result.getDiagnostics().put("itemId", ajax.itemId);
            result.getDiagnostics().put("mktItemId", ajax.mktId);
            result.setCurrentUrl(browser.getCurrentUrl());
            keepAliveUrlStore.collect("tb", browser, result.getDiagnostics());
            debug.snapshot(browser, "03-promotion-ajax-ready");
            return;
        }
        if (ajax.success) {
            result.getDiagnostics().put("ajaxPromotionValidation", result.getDiagnostics().remove("validationErrors"));
        }
        result.getDiagnostics().put("channel", "page");
        if (TbUnionPageApi.isMktId(request.getKeyword())) {
            fail(result, CustomCrawlStatus.FAILED, "TB union promote by mktItemId failed: " + ajax.diagnostic);
            return;
        }
        if (!nativeSetSearchValue(browser, request.getKeyword())) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion search input not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "03-search-input-missing");
            return;
        }
        debug.snapshot(browser, "03-search-input-ready");
        if (!checkAndWaitSliderVerify(browser, config, result, debug, "03-search-input-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared after search input");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "03-search-input-slider-not-cleared");
            return;
        }
        if (!nativeClickSmartSearchButton(browser)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion smart search button not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "04-smart-search-missing");
            return;
        }
        Extends.sleep(Math.max(350, config.nextStepDelayMillis() / 2));
        debug.snapshot(browser, "04-smart-search-clicked");
        if (!checkAndWaitSliderVerify(browser, config, result, debug, "04-search-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared after search");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "04-search-slider-not-cleared");
            return;
        }
        if (!waitSearchSubmitted(browser, config, result, debug, request.getKeyword(), 8)) {
            navigateGoodsSearch(browser, request.getKeyword(), config);
            debug.snapshot(browser, "04-search-url-fallback");
            if (!checkAndWaitSliderVerify(browser, config, result, debug, "04-search-url-slider")) {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared after search fallback");
                result.getDiagnostics().put("body", bodySnippet(browser));
                debug.snapshot(browser, "04-search-url-slider-not-cleared");
                return;
            }
        }

        waitSearchSettled(browser, config, result, debug, request.getKeyword());
        scrollToFirstProductCard(browser, config);
        if (!checkAndWaitSliderVerify(browser, config, result, debug, "05-before-card-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared before product card");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "05-before-card-slider-not-cleared");
            return;
        }
        if (isNoProductResult(browser)) {
            fail(result, CustomCrawlStatus.NOT_FOUND, "TB promotion product not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "05-search-no-result");
            return;
        }
        if (!hoverFirstProductCard(browser, config)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion product card not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "05-product-card-missing");
            return;
        }
        Extends.sleep(Math.max(300, config.nextStepDelayMillis() / 2));
        debug.snapshot(browser, "05-product-card-hovered");

        ProductInfoDto productInfo = readProductInfo(browser);
        if (productInfo != null) {
            result.setProductInfo(productInfo);
        } else {
            result.getDiagnostics().put("productInfoMissing", true);
        }
        debug.snapshot(browser, "06-product-info-collected");

        if (!checkAndWaitSliderVerify(browser, config, result, debug, "07-before-promote-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared before promote click");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "07-before-promote-slider-not-cleared");
            return;
        }
        if (!nativeClickFirstPromoteButton(browser, config)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion first promote button not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "07-promote-button-missing");
            return;
        }
        Extends.sleep(Math.max(300, config.nextStepDelayMillis() / 2));
        debug.snapshot(browser, "07-promote-button-clicked");
        if (!checkAndWaitSliderVerify(browser, config, result, debug, "07-promote-click-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared after promote click");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "07-promote-click-slider-not-cleared");
            return;
        }
        if (!waitPromotionDialogReady(browser, config, result, debug)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion material dialog not ready");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "08-dialog-not-ready");
            return;
        }
        debug.snapshot(browser, "08-dialog-ready");

        // 立即推广弹层会先用默认推广位生成文案；选位确认后右上角 mm_ 与 m.tb.cn 文案应刷新。
        // 之前只校验「选择推广位」弹窗消失，会出现：弹窗关了但文案仍是默认未刷新。
        Map<String, String> beforeMaterial = readMaterialFingerprint(browser);
        result.getDiagnostics().put("materialBeforeSlot", beforeMaterial);
        log.info("Material before slot select: slot={}, url={}",
                beforeMaterial.get("slotId"), beforeMaterial.get("url"));

        boolean slotApplied = false;
        for (int slotRound = 1; slotRound <= 2 && !slotApplied; slotRound++) {
            if (!nativeClickPromotionSlotTrigger(browser)) {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slot trigger not found");
                result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
                debug.snapshot(browser, "09-slot-trigger-missing");
                return;
            }
            Extends.sleep(Math.max(300, config.nextStepDelayMillis() / 2));
            debug.snapshot(browser, "09-slot-popup-opened" + (slotRound > 1 ? "-retry" + slotRound : ""));

            if (!checkAndWaitSliderVerify(browser, config, result, debug, "10-before-media-type-slider")) {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared before media type");
                result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
                debug.snapshot(browser, "10-before-media-type-slider-not-cleared");
                return;
            }
            if (!selectMediaType(browser, config)) {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion media type option not found");
                result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
                debug.snapshot(browser, "10-media-type-missing");
                return;
            }
            Extends.sleep(Math.max(300, config.nextStepDelayMillis() / 2));
            debug.snapshot(browser, "10-media-type-selected");

            // 媒体名称未传时，页面常默认第一个媒体「1」；而 adSiteName=5 实际挂在媒体「5」下。
            if (!selectMediaNameIfNeeded(browser, request.getMediaName(), request.getAdSiteName(), config)) {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion media name option not found");
                result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
                debug.snapshot(browser, "11-media-name-missing");
                return;
            }
            Extends.sleep(Math.max(300, config.nextStepDelayMillis() / 2));
            debug.snapshot(browser, "11-media-name-selected");

            if (!selectAdSiteName(browser, request.getAdSiteName(), config, result, debug)) {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion ad site option not found");
                result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
                result.getDiagnostics().put("adSiteOptions", listOpenSelectOptionTexts(browser));
                debug.snapshot(browser, "13-adsite-option-missing");
                return;
            }
            Extends.sleep(Math.max(300, config.nextStepDelayMillis() / 2));
            debug.snapshot(browser, "13-adsite-option-selected");

            if (!confirmPromotionSlotDialog(browser, config, debug)) {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slot confirm button not applied");
                result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
                debug.snapshot(browser, "14-slot-confirm-missing");
                return;
            }
            Extends.sleep(Math.max(400, config.nextStepDelayMillis() / 2));
            debug.snapshot(browser, "14-slot-confirmed");

            // 真正生效：弹窗关闭后，素材区推广位 mm_ 或推广链接相对打开弹层时的默认值发生变化
            if (waitMaterialRefreshedAfterSlot(browser, config, beforeMaterial, 10)) {
                slotApplied = true;
                result.getDiagnostics().put("slotMaterialRefreshed", true);
                result.getDiagnostics().put("slotApplyRound", slotRound);
            } else {
                log.warn("Slot picker closed but material not refreshed (still default copy), round={}", slotRound);
                result.getDiagnostics().put("slotMaterialRefreshed", false);
                debug.snapshot(browser, "14-slot-material-not-refreshed-" + slotRound);
                // 刷新 before 指纹后重开选位（第二次仍用最初 before，避免把未刷新当成目标）
            }
        }
        if (!slotApplied) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED,
                    "TB promotion slot confirmed but material copy/url not refreshed (still default)");
            result.getDiagnostics().put("materialAfterSlot", readMaterialFingerprint(browser));
            result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
            debug.snapshot(browser, "14-slot-material-stale");
            return;
        }

        Map<String, String> afterMaterial = readMaterialFingerprint(browser);
        result.getDiagnostics().put("materialAfterSlot", afterMaterial);
        log.info("Material after slot select: slot={}, url={}",
                afterMaterial.get("slotId"), afterMaterial.get("url"));

        String promotionUrl = waitPromotionUrl(browser, config, result, debug, 8, beforeMaterial.get("url"));
        nativeClickCopyButton(browser);
        String copiedUrl = waitPromotionUrl(browser, config, result, debug, 3, beforeMaterial.get("url"));
        if (Strings.isEmpty(promotionUrl)) {
            promotionUrl = copiedUrl;
        }
        // 仍可能读到默认链：优先 afterMaterial 里已变化的 url
        if (Strings.isEmpty(promotionUrl)
                || (!Strings.isEmpty(beforeMaterial.get("url")) && beforeMaterial.get("url").equals(promotionUrl))) {
            String afterUrl = afterMaterial.get("url");
            if (!Strings.isEmpty(afterUrl) && !afterUrl.equals(beforeMaterial.get("url"))) {
                promotionUrl = afterUrl;
            }
        }
        if (Strings.isEmpty(promotionUrl)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion url not found");
            result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "15-promotion-url-missing");
            return;
        }
        // 页面卡片链接是联盟加密 ID，无法和数字 itemId 对比，所以页面兜底不做 itemId 校验
        if (!publishPromotion(result, promotionUrl, result.getProductInfo(), null, "page")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB product info validation failed");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "15-product-info-invalid");
            return;
        }
        keepAliveUrlStore.collect("tb", browser, result.getDiagnostics());
        debug.snapshot(browser, "15-promotion-url-ready");
    }

    private boolean ensurePromotionGoodsPageForNextTbKeyword(Browser browser, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug) throws TimeoutException {
        closeTbPromotionDialogIfNeeded(browser, config);
        browser.navigateUrl(config.getPromotionGoodsUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        Extends.sleep(config.nextStepDelayMillis());
        result.setCurrentUrl(browser.getCurrentUrl());
        if (isLoginRequired(result.getCurrentUrl(), config)) {
            fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                    "TB promotion login expired before batch keyword search. Finish login in the opened common Chrome profile, then retry.");
            debug.snapshot(browser, "02-goods-login-required-batch");
            return false;
        }
        if (waitGoodsPageReady(browser, config, result, debug)
                && checkAndWaitSliderVerify(browser, config, result, debug, "02-goods-slider-batch")) {
            debug.snapshot(browser, "02-goods-page-ready-batch");
            return true;
        }
        fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion goods page not ready for next keyword");
        result.getDiagnostics().put("body", bodySnippet(browser));
        debug.snapshot(browser, "02-goods-page-not-ready-batch");
        return false;
    }

    private void closeTbPromotionDialogIfNeeded(Browser browser, TbPromotionConfig config) {
        for (int i = 0; i < 2; i++) {
            String selector = "[data-rx-tb-dialog-close='1']";
            Boolean marked = browser.executeScript("var attr='data-rx-tb-dialog-close';" +
                    "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                    "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                    "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                    "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                    "var dialogs=Array.prototype.slice.call(document.querySelectorAll('[role=\"dialog\"],.next-dialog,.ant-modal,.modal,div'));" +
                    "var scope=null,bestTop=-999999;for(var d=0;d<dialogs.length;d++){var box=dialogs[d];if(!visible(box)){continue;}var t=text(box);if(t.indexOf('推广位')<0&&t.indexOf('一键复制')<0&&t.indexOf('文案素材')<0){continue;}var r=box.getBoundingClientRect();if(r.top>bestTop){scope=box;bestTop=r.top;}}" +
                    "if(!scope){return false;}" +
                    "var nodes=Array.prototype.slice.call(scope.querySelectorAll('button,a,[role=\"button\"],i,span'));" +
                    "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=text(e),cls=String(e.className||'').toLowerCase();" +
                    "var closeText=t==='关闭'||t==='取消'||t==='×'||t==='x'||/close|dialog-close|next-dialog-close|icon-close/.test(cls);if(!closeText){continue;}" +
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

    private boolean completeForwardLanding(Browser browser, TbPromotionConfig config) throws TimeoutException {
        String currentUrl = browser.getCurrentUrl();
        String body = bodySnippet(browser);
        if (!isForwardLandingUrl(currentUrl) && !hasQuickEntrance(body)) {
            return false;
        }
        long deadline = System.currentTimeMillis() + Math.max(10, config.getPageTimeoutSeconds()) * 1000L;
        boolean clicked = false;
        long lastClickAt = 0L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.completeForwardLanding");
            currentUrl = browser.getCurrentUrl();
            body = bodySnippet(browser);
            boolean forwardLanding = isForwardLandingUrl(currentUrl);
            boolean hasQuickEntrance = hasQuickEntrance(body);
            if (!forwardLanding && !hasQuickEntrance && isLoggedInUrl(currentUrl, config)) {
                return true;
            }
            if (!forwardLanding && !hasQuickEntrance && containsAny(body, "阿里妈妈", "淘宝联盟", "选品广场", "智能搜索")) {
                return true;
            }
            // 会话续期点「快速进入」：可立刻点，防连点间隔 ~0.7s
            if (hasQuickEntrance
                    && System.currentTimeMillis() - lastClickAt >= Math.max(700L, config.nextStepDelayMillis() * 2L / 3)
                    && nativeClickQuickEnter(browser)) {
                clicked = true;
                lastClickAt = System.currentTimeMillis();
                if (waitForwardLandingResolvedAfterClick(browser, config, deadline)) {
                    return true;
                }
                continue;
            }
            Extends.sleep(pollIntervalMillis(config, 280));
        }
        String forwardUrl = readForwardUrl(browser);
        if (!Strings.isEmpty(forwardUrl)) {
            browser.navigateUrl(forwardUrl, Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
            Extends.sleep(Math.max(300, config.nextStepDelayMillis() / 2));
            return waitForwardLandingResolvedAfterClick(browser, config,
                    System.currentTimeMillis() + Math.max(10, config.getPageTimeoutSeconds()) * 1000L);
        }
        return clicked;
    }

    private boolean waitForwardLandingResolvedAfterClick(Browser browser, TbPromotionConfig config, long outerDeadline)
            throws TimeoutException {
        long deadline = Math.min(outerDeadline,
                System.currentTimeMillis() + Math.max(4000L, config.nextStepDelayMillis() * 4L));
        long poll = pollIntervalMillis(config, 250);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitForwardLandingResolvedAfterClick");
            String currentUrl = browser.getCurrentUrl();
            String body = bodySnippet(browser);
            boolean forwardLanding = isForwardLandingUrl(currentUrl);
            boolean hasQuickEntrance = hasQuickEntrance(body);
            if (!forwardLanding && !hasQuickEntrance && isLoggedInUrl(currentUrl, config)) {
                return true;
            }
            if (!forwardLanding && !hasQuickEntrance && containsAny(body, "阿里妈妈", "淘宝联盟", "选品广场", "智能搜索")) {
                return true;
            }
            if (hasQuickEntrance) {
                return false;
            }
            Extends.sleep(poll);
        }
        return false;
    }

    private boolean nativeClickQuickEnter(Browser browser) {
        return nativeClickByText(browser, QUICK_ENTER_TEXT, false) || nativeClickByText(browser, QUICK_LOGIN_TEXT, false);
    }

    private String readForwardUrl(Browser browser) {
        String url = browser.executeScript("try{var u=new URL(location.href),f=u.searchParams.get('forward');return f?decodeURIComponent(f):'';}catch(e){return '';}");
        return url == null ? "" : url;
    }

    private boolean waitGoodsPageReady(Browser browser, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, config.getInitialPageTimeoutSeconds()) * 1000L;
        long poll = pollIntervalMillis(config, 350);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitGoodsPageReady");
            if (sliderVerifyHandler.isSliderVerifyPage(browser)
                    && !checkAndWaitSliderVerify(browser, config, result, debug, "02-goods-ready-slider")) {
                return false;
            }
            String body = bodySnippet(browser);
            // 滑块通过后常落到「确认登录/快速进入」会话续期页，必须点快速进入，否则会空等到超时
            if (isSessionResumeBody(body) || hasQuickEntrance(body)) {
                log.info("Goods page wait hit session-resume (确认登录/快速进入), completeForwardLanding");
                debug.snapshot(browser, "02-goods-session-resume");
                completeForwardLanding(browser, config);
                body = bodySnippet(browser);
            }
            // 侧栏也有「选品广场」，不能单独当就绪；必须出现搜索区关键文案
            if (isGoodsSearchUiReady(body) && !isSessionResumeBody(body)
                    && !sliderVerifyHandler.isSliderVerifyPage(browser)) {
                return true;
            }
            Extends.sleep(poll);
        }
        return false;
    }

    /** 选品搜索 UI 就绪：占位符 / 智能搜索 / 批量选品（不含仅侧栏「选品广场」）。 */
    private boolean isGoodsSearchUiReady(String body) {
        return containsAny(body,
                "请输入你要搜索的商品/类目/商品链接",
                "请输入你要搜索的商品",
                "智能搜索",
                "批量选品");
    }

    /** 轮询间隔：先看状态再 sleep，避免「先睡 1s 再发现已就绪」。 */
    private long pollIntervalMillis(TbPromotionConfig config, int floor) {
        return Math.max(floor, config.nextStepDelayMillis() / 2);
    }

    private boolean checkAndWaitSliderVerify(Browser browser, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug, String stepTag) throws TimeoutException {
        if (!sliderVerifyHandler.isSliderVerifyPage(browser)) {
            return true;
        }
        result.getDiagnostics().put(stepTag + "SliderVerify", true);
        result.getDiagnostics().put(stepTag + "SliderVerifyManualFallback", true);
        debug.snapshot(browser, stepTag + "-manual-wait");
        if (handleSliderVerify(browser, config, result, debug, stepTag)) {
            return true;
        }
        return waitSliderVerifyCleared(browser, config, stepTag);
    }

    private boolean handleSliderVerify(Browser browser, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug, String stepTag) throws TimeoutException {
        boolean handled = onSliderVerifyDetected(browser, config, result, debug, stepTag);
        result.getDiagnostics().put(stepTag + "SliderVerifyAction", handled ? "hook" : "manual");
        return handled;
    }

    /**
     * 滑动验证处理钩子。优先委托 SliderVerifyHandler 自动处理，失败后进入人工兜底等待。
     */
    private boolean onSliderVerifyDetected(Browser browser, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug, String stepTag) throws TimeoutException {
        ensureTaskDeadline("getTbPromotionUrl.onSliderVerifyDetected." + stepTag);
        result.getDiagnostics().put("sliderVerifyAt", stepTag);
        // 与订单任务对齐：最多 5 次拖拽；每次失败后 Handler 内会刷新页面再试
        boolean passed = sliderVerifyHandler.checkAndHandle(browser, stepTag, 5,
                config.nextStepDelayMillis(), (b, name) -> debug.snapshot(b, name), config.getPromotionGoodsUrl());
        if (passed) {
            result.getDiagnostics().put("sliderVerifyPassed", true);
            // 滑块本身已含拟人拖拽与后置等待，这里只短 settle
            Extends.sleep(Math.max(500, config.nextStepDelayMillis()));
        } else {
            notifySliderFailed(result, browser, config, stepTag);
        }
        return passed;
    }

    private boolean waitSliderVerifyCleared(Browser browser, TbPromotionConfig config, String stepTag)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(30, config.getLoginWaitSeconds()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitSliderVerifyCleared." + stepTag);
            Extends.sleep(Math.max(1000, config.nextStepDelayMillis()));
            if (!sliderVerifyHandler.isSliderVerifyPage(browser)) {
                return true;
            }
        }
        return false;
    }

    private boolean nativeSetSearchValue(Browser browser, String value) {
        String selector = "[data-rx-tb-promo-search-input='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-promo-search-input';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function meta(el){var v=(el.getAttribute('placeholder')||'')+(el.getAttribute('aria-label')||'')+(el.name||'')+(el.id||'')+(el.className||'');return v;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var chosen=null;for(var i=0;i<nodes.length;i++){var e=nodes[i],m=meta(e);if(visible(e)&&(m.indexOf('请输入你要搜索的商品')>=0||m.indexOf('商品/类目/商品链接')>=0)){chosen=e;break;}}" +
                "if(!chosen){for(var j=0;j<nodes.length;j++){var n=nodes[j],m2=meta(n);if(visible(n)&&(/商品|类目|链接|搜索/.test(m2))){chosen=n;break;}}}" +
                "if(!chosen){return false;}chosen.setAttribute(attr,'1');chosen.scrollIntoView({block:'center',inline:'center'});chosen.focus();return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return setSearchValueByScript(browser, value);
        }
        try {
            browser.elementPress(selector, value, false);
            Extends.sleep(300L);
            if (searchValueEquals(browser, value)) {
                return true;
            }
            return setSearchValueByScript(browser, value) && searchValueEquals(browser, value);
        } catch (Exception e) {
            return setSearchValueByScript(browser, value) && searchValueEquals(browser, value);
        }
    }

    private boolean setSearchValueByScript(Browser browser, String value) {
        Boolean ok = browser.executeScript("var value=arguments[0];" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var chosen=null;for(var i=0;i<nodes.length;i++){var e=nodes[i],m=(e.getAttribute('placeholder')||'')+(e.getAttribute('aria-label')||'');if(visible(e)&&(m.indexOf('请输入你要搜索的商品')>=0||m.indexOf('商品/类目/商品链接')>=0)){chosen=e;break;}}" +
                "if(!chosen){return false;}chosen.focus();" +
                "var setter=Object.getOwnPropertyDescriptor(chosen.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set;" +
                "setter.call(chosen,value);chosen.dispatchEvent(new Event('input',{bubbles:true}));chosen.dispatchEvent(new Event('change',{bubbles:true}));return true;", value);
        return Boolean.TRUE.equals(ok);
    }

    private boolean searchValueEquals(Browser browser, String value) {
        Boolean ok = browser.executeScript("var value=String(arguments[0]||'');" +
                "var input=document.querySelector('[data-rx-tb-promo-search-input=\"1\"]');" +
                "return !!input && String(input.value||'')===value;", value);
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickSmartSearchButton(Browser browser) {
        return nativeClickByText(browser, "智能搜索", true);
    }

    private boolean waitSearchSubmitted(Browser browser, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug, String productInfo, int maxSeconds)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, maxSeconds) * 1000L;
        long poll = pollIntervalMillis(config, 300);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitSearchSubmitted");
            if (!checkAndWaitSliderVerify(browser, config, result, debug, "04-search-submit-slider")) {
                return false;
            }
            if (isSearchUrl(browser.getCurrentUrl()) || firstProductMatchesQuery(browser, productInfo)) {
                return true;
            }
            Extends.sleep(poll);
        }
        return false;
    }

    private void navigateGoodsSearch(Browser browser, String productInfo, TbPromotionConfig config) throws TimeoutException {
        String baseUrl = config.getPromotionGoodsUrl();
        int queryIndex = baseUrl.indexOf('?');
        if (queryIndex >= 0) {
            baseUrl = baseUrl.substring(0, queryIndex);
        }
        String q = URLEncoder.encode(productInfo, StandardCharsets.UTF_8);
        String url = baseUrl + "?pageNum=1&pageSize=30&filters=%7B%7D&fn=search&q="
                + q + "&sort=default&selected=%7B%7D";
        browser.navigateUrl(url, Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        Extends.sleep(Math.max(400, config.nextStepDelayMillis() / 2));
    }

    private void waitSearchSettled(Browser browser, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug, String productInfo) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, config.getPageTimeoutSeconds()) * 1000L;
        long poll = pollIntervalMillis(config, 350);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitSearchSettled");
            if (!checkAndWaitSliderVerify(browser, config, result, debug, "04-search-settle-slider")) {
                return;
            }
            String body = bodySnippet(browser);
            if (containsAny(body, "没有商品", "暂无商品", "暂无数据", "无搜索结果", "没有找到")) {
                return;
            }
            if ((isSearchUrl(browser.getCurrentUrl()) || firstProductMatchesQuery(browser, productInfo))
                    && containsAny(body, "立即推广", "当前1-", "共")) {
                return;
            }
            Extends.sleep(poll);
        }
    }

    private boolean isSearchUrl(String currentUrl) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        return lower.contains("fn=search") || lower.contains("&q=") || lower.contains("?q=");
    }

    private boolean firstProductMatchesQuery(Browser browser, String productInfo) {
        String title = readFirstProductTitle(browser);
        return productTitleMatchesQuery(title, productInfo);
    }

    private String readFirstProductTitle(Browser browser) {
        String title = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var roots=Array.prototype.slice.call(document.querySelectorAll('[data-spm=\"GoodsListItem\"],.GoodsListPlus__CardList-sc-1i2w9r2-0>div,div,li'));" +
                "for(var i=0;i<roots.length;i++){var card=roots[i];if(!visible(card)||card.querySelectorAll('img').length===0){continue;}" +
                "var t=norm(card.innerText||card.textContent);" +
                // 兼容选品中心改版后无「到手价」：只要含佣金/单件佣金/月支出佣金 + 立即推广/￥ 即认作商品卡。
                "if(!((t.indexOf('佣金率')>=0||t.indexOf('单件佣金')>=0||t.indexOf('月支出佣金')>=0)&&(t.indexOf('立即推广')>=0||t.indexOf('￥')>=0||t.indexOf('单价')>=0))){continue;}" +
                "var a=card.querySelector('a[data-spm-click*=\"title\"],[class*=\"title-wrap\"], [class*=\"title\"]');" +
                "return norm(a?(a.innerText||a.textContent):t).substring(0,160);}" +
                "return '';");
        return title == null ? "" : title;
    }

    private boolean productTitleMatchesQuery(String title, String productInfo) {
        String t = normalizeSearchText(title);
        String q = normalizeSearchText(productInfo);
        if (Strings.isEmpty(t) || Strings.isEmpty(q)) {
            return false;
        }
        if (t.contains(q) || q.contains(t)) {
            return true;
        }
        int max = Math.min(6, q.length());
        for (int len = max; len >= 2; len--) {
            if (t.contains(q.substring(0, len))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeSearchText(String value) {
        if (Strings.isEmpty(value)) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。；：、【】（）()\\[\\]{}<>《》\"'“”‘’]+", "");
    }

    private void scrollToFirstProductCard(Browser browser, TbPromotionConfig config) {
        // 阿里妈妈选品中心改版后不再固定显示「到手价」，统一改成「佣金率 + 立即推广」识别商品卡。
        browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var marker=null,nodes=Array.prototype.slice.call(document.querySelectorAll('div,li,section,article'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],t=norm(e.innerText);if(!visible(e)||e.querySelectorAll('img').length===0){continue;}" +
                "if((t.indexOf('佣金率')>=0||t.indexOf('单件佣金')>=0||t.indexOf('月支出佣金')>=0)&&(t.indexOf('立即推广')>=0||t.indexOf('￥')>=0)){marker=e;break;}}" +
                "if(marker){marker.scrollIntoView({block:'center',inline:'center'});window.scrollBy(0,120);}else{window.scrollBy(0,Math.floor(window.innerHeight*0.7));}");
        Extends.sleep(Math.max(280, config.nextStepDelayMillis() / 2));
    }

    private boolean isNoProductResult(Browser browser) {
        String body = bodySnippet(browser);
        if (containsAny(body, "立即推广")) {
            return false;
        }
        return containsAny(body, "没有商品", "暂无商品", "暂无数据", "没有找到", "无搜索结果");
    }

    private boolean hoverFirstProductCard(Browser browser, TbPromotionConfig config) throws TimeoutException {
        for (int i = 0; i < 3; i++) {
            ensureTaskDeadline("getTbPromotionUrl.hoverFirstProductCard");
            Map<String, Object> point = markFirstProductCard(browser);
            if (point != null && !point.isEmpty()) {
                browser.mouseMove(toDouble(point.get("x")), toDouble(point.get("y")));
                if (point.get("actionX") != null && point.get("actionY") != null) {
                    Extends.sleep(260L);
                    browser.mouseMove(toDouble(point.get("actionX")), toDouble(point.get("actionY")));
                }
                return true;
            }
            browser.executeScript("window.scrollBy(0, Math.floor(window.innerHeight * 0.5));");
            Extends.sleep(Math.max(280, config.nextStepDelayMillis() / 2));
        }
        return false;
    }

    private Map<String, Object> markFirstProductCard(Browser browser) {
        return browser.executeScript("var cardAttr='data-rx-tb-promo-card',btnAttr='data-rx-tb-promo-button';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+cardAttr+'],['+btnAttr+']')).forEach(function(e){e.removeAttribute(cardAttr);e.removeAttribute(btnAttr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                // 选品中心改版后卡片不再固定带「到手价」，宽松匹配：必须含「佣金率/单件佣金/月支出佣金」之一，
                // 且含「立即推广」按钮或￥价格，过滤掉左侧筛选/导航/小二推荐弹窗等噪声节点。
                "function scoreCard(el){var r=el.getBoundingClientRect(),t=text(el);if(!visible(el)||r.width<220||r.width>760||r.height<180||r.height>560||el.querySelectorAll('img').length===0){return 999999999;}" +
                "var hasCommission=(t.indexOf('佣金率')>=0||t.indexOf('单件佣金')>=0||t.indexOf('月支出佣金')>=0);" +
                "var hasPrice=(t.indexOf('立即推广')>=0||t.indexOf('￥')>=0||t.indexOf('单价')>=0);" +
                "if(!(hasCommission&&hasPrice)){return 999999999;}" +
                "return Math.max(0,r.top)*10000+Math.max(0,r.left)+r.width*r.height/100000;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('div,li,section,article')),card=null,best=999999999;" +
                "for(var i=0;i<nodes.length;i++){var sc=scoreCard(nodes[i]);if(sc<best){best=sc;card=nodes[i];}}" +
                "if(!card){return null;}card.setAttribute(cardAttr,'1');card.scrollIntoView({block:'center',inline:'center'});" +
                "var buttons=Array.prototype.slice.call(card.querySelectorAll('button,a,[role=\"button\"],span,div'));" +
                "for(var j=0;j<buttons.length;j++){var b=buttons[j];if(visible(b)&&text(b)==='立即推广'){(b.closest('button,a,[role=\"button\"]')||b).setAttribute(btnAttr,'1');break;}}" +
                "var r=card.getBoundingClientRect();" +
                "return {x:r.left+Math.min(r.width*0.55, Math.max(80, r.width-80)),y:r.top+Math.min(r.height*0.45, Math.max(60, r.height-70))," +
                "actionX:r.left+Math.max(80, r.width-80),actionY:r.top+Math.max(60, r.height-36)};");
    }

    /**
     * 点击首条「立即推广」。
     * 只用坐标鼠标 + 必要时 JS click，禁止走 Playwright elementClick 的 6s actionability
     * （按钮在卡片底部时 scrollIntoView 常判 not visible，会白等 6s 再 force 又 6s）。
     */
    private boolean nativeClickFirstPromoteButton(Browser browser, TbPromotionConfig config) {
        String selector = "[data-rx-tb-promo-button='1']";
        long settle = Math.max(500, config.nextStepDelayMillis());
        for (int i = 0; i < 2; i++) {
            if (!markFirstPromoteButton(browser)) {
                Map<String, Object> cardPoint = markFirstProductCard(browser);
                if (cardPoint != null && cardPoint.get("actionX") != null && cardPoint.get("actionY") != null) {
                    browser.mouseMove(toDouble(cardPoint.get("actionX")), toDouble(cardPoint.get("actionY")));
                    Extends.sleep(200L);
                }
                continue;
            }
            Map<String, Object> point = ensureMarkedElementInViewport(browser, selector);
            if (point != null && point.get("x") instanceof Number && point.get("y") instanceof Number) {
                double x = ((Number) point.get("x")).doubleValue();
                double y = ((Number) point.get("y")).doubleValue();
                if (x >= 1 && y >= 1) {
                    log.info("Click promote button via mouse at ({}, {}), attempt={}", x, y, i + 1);
                    browser.mouseMove(x, y);
                    Extends.sleep(100 + ThreadLocalRandom.current().nextLong(60, 160));
                    browser.mouseClick(x, y);
                    Extends.sleep(settle);
                    if (isPromotionDialogVisible(browser) || sliderVerifyHandler.isSliderVerifyPage(browser)) {
                        return true;
                    }
                    // 再点一次（有时首点只 hover 出态）
                    browser.mouseClick(x, y);
                    Extends.sleep(settle);
                    if (isPromotionDialogVisible(browser) || sliderVerifyHandler.isSliderVerifyPage(browser)) {
                        return true;
                    }
                }
            }
            // 坐标不可用或鼠标未生效：JS 直点，仍不走 6s Playwright actionability
            if (jsClickMarkedElement(browser, selector)) {
                log.info("Click promote button via JS fallback, attempt={}", i + 1);
                Extends.sleep(settle);
                if (isPromotionDialogVisible(browser) || sliderVerifyHandler.isSliderVerifyPage(browser)) {
                    return true;
                }
            }
        }
        return isPromotionDialogVisible(browser);
    }

    /**
     * 将已 mark 的元素滚进视口并返回中心坐标；避免 Playwright scrollIntoViewIfNeeded 长超时。
     */
    private Map<String, Object> ensureMarkedElementInViewport(Browser browser, String selector) {
        return browser.executeScript("var sel=String(arguments[0]||'');var el=document.querySelector(sel);" +
                "if(!el){return null;}" +
                "try{el.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});}catch(e){" +
                "try{el.scrollIntoView(true);}catch(e2){}}" +
                "var r=el.getBoundingClientRect();" +
                "if(r.width<=0||r.height<=0){return null;}" +
                "var vh=window.innerHeight||800,vw=window.innerWidth||1200;" +
                // 仍在视口外则再滚一次父滚动容器
                "if(r.top<0||r.bottom>vh||r.left<0||r.right>vw){" +
                "  try{el.scrollIntoView({block:'center',inline:'nearest'});}catch(e3){}" +
                "  r=el.getBoundingClientRect();" +
                "}" +
                "return {x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height," +
                "inView:r.top>=0&&r.bottom<=vh&&r.left>=0&&r.right<=vw};", selector);
    }

    private boolean isPromotionDialogVisible(Browser browser) {
        return pageContainsAny(browser, "社群素材", "朋友圈素材", "文案素材", "推广位", "一键复制");
    }

    private boolean markFirstPromoteButton(Browser browser) {
        Boolean marked = browser.executeScript("var attr='data-rx-tb-promo-button';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                // 兼容选品中心改版后无「到手价」：用「佣金 + 立即推广/价格」识别商品卡作用域。
                "function goodCard(el){for(var p=el;p&&p!==document.body;p=p.parentElement){var t=text(p);if((t.indexOf('佣金率')>=0||t.indexOf('单件佣金')>=0||t.indexOf('月支出佣金')>=0)&&(t.indexOf('立即推广')>=0||t.indexOf('￥')>=0||t.indexOf('单价')>=0)&&p.querySelectorAll('img').length>0){return true;}}return false;}" +
                "function mark(el){var target=el.closest('button,a,[role=\"button\"]')||el;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;}" +
                "var scoped=document.querySelector('[data-rx-tb-promo-card=\"1\"]'),roots=scoped?[scoped]:[document.body];" +
                "if(scoped){var primary=scoped.querySelector('.good-card-promotion-btn,[data-spm-click*=\"d_good_detail_promo\"]');if(primary&&mark(primary)){return true;}}" +
                "for(var r=0;r<roots.length;r++){var nodes=Array.prototype.slice.call(roots[r].querySelectorAll('button,a,[role=\"button\"],span,div'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],t=text(e),cls=String(e.className||''),spm=String(e.getAttribute('data-spm-click')||'');" +
                "if(!visible(e)||!goodCard(e)){continue;}if(t==='立即推广'||cls.indexOf('good-card-promotion-btn')>=0||spm.indexOf('d_good_detail_promo')>=0){return mark(e);}}}" +
                "return false;");
        return Boolean.TRUE.equals(marked);
    }

    private ProductInfoDto readProductInfo(Browser browser) {
        Map<String, Object> raw = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function rawText(el){return norm((el.innerText||'')+' '+(el.textContent||''));}" +
                "function pickImage(card){var imgs=Array.prototype.slice.call(card.querySelectorAll('img'));for(var i=0;i<imgs.length;i++){var img=imgs[i];if(!visible(img)){continue;}return img.currentSrc||img.src||img.getAttribute('data-src')||img.getAttribute('src')||'';}return '';}" +
                "function extract(t,re){var m=t.match(re);return m?(m[1]||m[0]):'';}" +
                "function normalizeUrl(url){if(!url){return '';}if(url.indexOf('//')===0){return location.protocol+url;}return url;}" +
                "function hrefOf(a){return a?(a.getAttribute('href')||a.href||''):'';}" +
                "function pickTitleLink(card){var selectors=[" +
                "'a[href][data-spm-click*=\\\"d_good_select_list_title_click\\\"]'," +
                "'a[href] .union-good-card-block-title-wrap'," +
                "'a[href] [class*=\\\"union-good-card-block-title\\\"]'," +
                "'a[href] [class*=\\\"title-wrap\\\"]'," +
                "'a[href] [class*=\\\"title\\\"]'];" +
                "for(var s=0;s<selectors.length;s++){var node=card.querySelector(selectors[s]);if(!node){continue;}" +
                "var a=node.matches&&node.matches('a[href]')?node:node.closest('a[href]');if(!a){continue;}" +
                "var href=hrefOf(a);if(!href||!/portal\\/v2\\/pages\\/promo\\/goods\\/detail/.test(href)){continue;}" +
                "var titleNode=a.querySelector('.union-good-card-block-title,[class*=\\\"title\\\"]')||a;" +
                "return {name:text(titleNode)||text(a),link:href};}return null;}" +
                "var card=document.querySelector('[data-rx-tb-promo-card=\"1\"]');if(!card){return null;}var full=rawText(card);" +
                "var titleLink=pickTitleLink(card),name=titleLink?titleLink.name:'',link=titleLink?titleLink.link:'',best=-1;" +
                "var anchors=Array.prototype.slice.call(card.querySelectorAll('a[href]'));" +
                "if(!link){for(var i=0;i<anchors.length;i++){var a=anchors[i],t=text(a),href=hrefOf(a),cls=String(a.className||''),spm=String(a.getAttribute('data-spm-click')||'');" +
                "if(!href||!/\\S/.test(href)){continue;}if(/立即推广|更多信息|加入收藏|创建淘礼金|回头客|全网爆款|官方立减|券/.test(t)&&t.length<=40){continue;}" +
                "if(/shop\\/detail|third\\/manage|customer|help|logout/i.test(href)){continue;}" +
                "var titleLike=spm.indexOf('title')>=0||cls.indexOf('title')>=0||!!a.querySelector('[class*=\"title\"]');" +
                "var score=0;if(visible(a)){score+=100;}if(t){score+=Math.min(120,t.length);}if(titleLike){score+=260;}" +
                "if(/portal\\/v2\\/pages\\/promo\\/goods\\/detail|itemId=|outputMktId=/.test(href)){score+=180;}" +
                "if(/item\\.taobao\\.com|detail\\.tmall\\.com|market\\.m\\.taobao\\.com/.test(href)){score+=220;}" +
                "if(!t&&score<180){continue;}if(score>best){best=score;name=t;link=href;}}}" +
                "if(!link){var title=card.querySelector('[class*=\"title-wrap\"],[class*=\"title\"]');if(title){name=text(title);var fallbackTitleLink=title.closest('a[href]');if(fallbackTitleLink){link=hrefOf(fallbackTitleLink);}}}" +
                "var store=extract(full,/店铺名称[:：]\\s*([^\\n\\r]+?)(?:类目[:：]|2小时推广销量|月推广销量|券量|更多信息|$)/);" +
                "if(!store){var ns=Array.prototype.slice.call(card.querySelectorAll('a,span,div'));for(var j=0;j<ns.length;j++){var st=text(ns[j]);if(visible(ns[j])&&/店$|旗舰店|专卖店|店铺/.test(st)&&st.length<=50){store=st;break;}}}" +
                "return {imageUrl:normalizeUrl(pickImage(card)),productName:name,productLink:normalizeUrl(link)," +
                // 价格优先「到手价」(老版)，回退「单价/￥/¥」(新版直接显示价格)。
                "commissionRate:extract(full,/(?:佣金率|佣金比例)[:：]?\\s*([0-9.]+%)/),price:(extract(full,/到手价\\s*[￥¥]?\\s*([0-9.]+(?:\\.[0-9]+)?)/)||extract(full,/(?:单价|2件单价|到手)?\\s*[￥¥]\\s*([0-9.]+(?:\\.[0-9]+)?)/)),storeName:store};");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        ProductInfoDto dto = objectMapper.convertValue(raw, ProductInfoDto.class);
        trimProductInfo(dto);
        return dto;
    }

    private void trimProductInfo(ProductInfoDto dto) {
        if (dto == null) {
            return;
        }
        dto.setImageUrl(trim(dto.getImageUrl()));
        dto.setProductName(trim(dto.getProductName()));
        dto.setProductLink(trim(dto.getProductLink()));
        dto.setCommissionRate(trim(dto.getCommissionRate()));
        dto.setPrice(trim(dto.getPrice()));
        dto.setStoreName(trim(dto.getStoreName()));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private boolean waitPromotionDialogReady(Browser browser, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, config.getPageTimeoutSeconds()) * 1000L;
        long poll = pollIntervalMillis(config, 300);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitPromotionDialogReady");
            if (!checkAndWaitSliderVerify(browser, config, result, debug, "08-dialog-slider")) {
                return false;
            }
            if (pageContainsAny(browser, "社群素材", "朋友圈素材", "文案素材", "推广位", "一键复制")) {
                return true;
            }
            Extends.sleep(poll);
        }
        return false;
    }

    private boolean nativeClickPromotionSlotTrigger(Browser browser) {
        String selector = "[data-rx-tb-slot-trigger='1']";
        // 返回坐标，优先鼠标点，避免 elementClick 的 actionability 超时
        Object marked = browser.executeScript("var attr='data-rx-tb-slot-trigger';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('placeholder')||el.getAttribute('aria-label'));}" +
                "var dialogs=Array.prototype.slice.call(document.querySelectorAll('[role=\"dialog\"],.next-dialog')),scope=document;" +
                "for(var d=dialogs.length-1;d>=0;d--){if(visible(dialogs[d])){scope=dialogs[d];break;}}" +
                "var labels=Array.prototype.slice.call(scope.querySelectorAll('label,span,div,p')),label=null;" +
                "for(var i=0;i<labels.length;i++){var e=labels[i],t=text(e);if(visible(e)&&t.indexOf('推广位')>=0&&t.length<=8){label=e;break;}}" +
                "if(!label){return null;}var lr=label.getBoundingClientRect(),nodes=Array.prototype.slice.call(scope.querySelectorAll('input,textarea,button,a,div,span'));" +
                "var best=null,bestScore=999999;for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n)||n===label||label.contains(n)||n.contains(label)){continue;}" +
                "var r=n.getBoundingClientRect(),dy=Math.abs((r.top+r.bottom-lr.top-lr.bottom)/2),dx=r.left-lr.right,area=r.width*r.height,t=text(n);" +
                "if(dx<-30||dy>70||r.width<80||r.height<22||area>90000||t.indexOf('推广位')>=0){continue;}" +
                "var score=dy*20+Math.max(0,dx)+area/1000;if(score<bestScore){best=n;bestScore=score;}}" +
                "if(!best){return null;}var target=best.closest('button,a,[role=\"button\"],[role=\"combobox\"],.next-select')||best;" +
                "target.setAttribute(attr,'1');try{target.scrollIntoView({block:'center',inline:'nearest'});}catch(e){}" +
                "var br=target.getBoundingClientRect();return {x:br.left+br.width/2,y:br.top+br.height/2};");
        if (marked instanceof Map) {
            Map<?, ?> info = (Map<?, ?>) marked;
            if (info.get("x") instanceof Number && info.get("y") instanceof Number) {
                double x = ((Number) info.get("x")).doubleValue();
                double y = ((Number) info.get("y")).doubleValue();
                if (x > 1 && y > 1) {
                    try {
                        browser.mouseMove(x, y);
                        Extends.sleep(80 + ThreadLocalRandom.current().nextLong(40, 120));
                        browser.mouseClick(x, y);
                        return true;
                    } catch (Exception e) {
                        log.debug("slot trigger mouse click fail: {}", e.getMessage());
                    }
                }
            }
        }
        return nativeClickMarkedElement(browser, selector);
    }

    private boolean selectMediaType(Browser browser, TbPromotionConfig config) {
        // 展示值已是「…社交平台」才跳过（仅「他方平台」未选二级时不能跳）
        if (formSelectDisplayContains(browser, "siteType", "社交平台")) {
            log.info("Media type already selected (社交平台), skip cascader");
            return true;
        }
        long openWait = Math.max(280, config.nextStepDelayMillis() / 2);
        if (!nativeClickFormSelect(browser, "siteType", "data-rx-tb-media-type")
                && !nativeClickLabelControl(browser, "媒体类型", "data-rx-tb-media-type")) {
            return false;
        }
        Extends.sleep(openWait);
        if (!nativeClickOptionByText(browser, "他方平台", false)) {
            if (!nativeClickFormSelect(browser, "siteType", "data-rx-tb-media-type")
                    && !nativeClickLabelControl(browser, "媒体类型", "data-rx-tb-media-type")) {
                return false;
            }
            Extends.sleep(openWait);
            if (!nativeClickOptionByText(browser, "他方平台", false)) {
                return false;
            }
        }
        Extends.sleep(openWait);
        return nativeClickOptionByText(browser, "社交平台", false);
    }

    private boolean formSelectDisplayContains(Browser browser, String fieldId, String expectedPart) {
        Boolean ok = browser.executeScript("var fieldId=String(arguments[0]||''),part=String(arguments[1]||'');" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var input=document.getElementById(fieldId);if(!input){return false;}" +
                "var root=input.closest('.next-select')||input.parentElement;if(!root){return false;}" +
                "var t=norm((root.querySelector('.next-select-values')||root).innerText||input.getAttribute('aria-valuetext')||'');" +
                "return t.indexOf(norm(part))>=0;", fieldId, expectedPart);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 选择媒体名称。
     * <ul>
     *   <li>显式传入 mediaName：必须选中，失败返回 false</li>
     *   <li>未传 mediaName：best-effort 尝试与 adSiteName 同名的媒体（账号上常见「媒体5 / 推广位5会员」），
     *       选不到则保留页面当前默认媒体，不失败</li>
     * </ul>
     */
    private boolean selectMediaNameIfNeeded(Browser browser, String mediaName, String adSiteName,
            TbPromotionConfig config) {
        boolean optional = Strings.isEmpty(mediaName);
        String target = mediaName;
        if (optional) {
            if (Strings.isEmpty(adSiteName)) {
                return true;
            }
            // 页面默认常是第一个媒体「1」；adSiteName=5 通常挂在媒体「5」下
            if (formSelectDisplayMatches(browser, "siteId", adSiteName, true)
                    || selectedLabelControlEquals(browser, "媒体名称", adSiteName)) {
                log.info("Media already matches adSiteName={}, keep current", adSiteName);
                return true;
            }
            target = adSiteName;
            log.info("mediaName empty, try select media by adSiteName={}", adSiteName);
        } else if (formSelectDisplayMatches(browser, "siteId", target, true)
                || selectedLabelControlEquals(browser, "媒体名称", target)) {
            return true;
        }

        if (!nativeClickFormSelect(browser, "siteId", "data-rx-tb-media-name")
                && !nativeClickLabelControl(browser, "媒体名称", "data-rx-tb-media-name")) {
            if (optional) {
                log.warn("Media dropdown not found, keep page default media");
                return true;
            }
            return false;
        }
        Extends.sleep(Math.max(280, config.nextStepDelayMillis() / 2));
        // 先精确再模糊（媒体名多为纯数字「5」）
        boolean clicked = nativeClickOptionByText(browser, target, true)
                || nativeClickOptionByText(browser, target, false);
        if (clicked) {
            log.info("Media selected, target={}", target);
            Extends.sleep(Math.max(350, config.nextStepDelayMillis() / 2)); // 切换媒体后推广位列表刷新
            return true;
        }
        if (optional) {
            log.warn("Optional media '{}' not found in dropdown, keep page default; options={}",
                    target, listOpenSelectOptionTexts(browser));
            closeOpenSelectPopup(browser);
            return true;
        }
        return false;
    }

    /**
     * 打开推广位名称下拉并选中以 adSiteName 为编号前缀的项（如 5 →「5会员」）。
     * 若当前已选中则跳过；失败时返回 false。
     */
    private boolean selectAdSiteName(Browser browser, String adSiteName, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug) {
        if (Strings.isEmpty(adSiteName)) {
            return false;
        }
        // 已是目标推广位（展示为「5会员」等）则无需再点
        if (formSelectDisplayMatches(browser, "adzoneId", adSiteName, false)
                || formSelectDisplayMatches(browser, "adzoneName", adSiteName, false)) {
            log.info("Ad site already selected matching {}, skip dropdown", adSiteName);
            result.getDiagnostics().put("adSiteAlreadySelected", true);
            return true;
        }
        if (!nativeClickAdSiteDropdown(browser)) {
            return false;
        }
        Extends.sleep(config.nextStepDelayMillis());
        debug.snapshot(browser, "12-adsite-dropdown-opened");
        // 可搜索下拉：先输入编号过滤，再点选项（Next select 带 next-has-search）
        typeIntoOpenSelectSearch(browser, adSiteName);
        Extends.sleep(Math.max(400, config.nextStepDelayMillis() / 2));
        if (nativeClickAdSiteOption(browser, adSiteName)) {
            return true;
        }
        // 过滤后仍没有：清空搜索再扫一遍全量
        typeIntoOpenSelectSearch(browser, "");
        Extends.sleep(Math.max(400, config.nextStepDelayMillis() / 2));
        boolean ok = nativeClickAdSiteOption(browser, adSiteName);
        if (!ok) {
            log.warn("Ad site option '{}' not found, open options={}", adSiteName, listOpenSelectOptionTexts(browser));
        }
        return ok;
    }

    private boolean nativeClickAdSiteDropdown(Browser browser) {
        return nativeClickFormSelect(browser, "adzoneId", "data-rx-tb-adsite-name")
                || nativeClickFormSelect(browser, "adzoneName", "data-rx-tb-adsite-name")
                || nativeClickLabelControl(browser, "推广位名称", "data-rx-tb-adsite-name");
    }

    private boolean nativeClickAdSiteOption(Browser browser, String adSiteName) {
        String selector = "[data-rx-tb-adsite-option='1']";
        // 优先锁定「推广位」展开中的 select 菜单，排除媒体类型 cascader 残留；
        // 选项 DOM 常为 <span>5</span><div>会员</div>，text 归一为「5会员」；
        // 匹配规则：整段等于目标，或以目标数字开头且下一位非数字（5→5会员，不命中50）。
        Object marked = browser.executeScript("var target=String(arguments[0]||'').trim(),attr='data-rx-tb-adsite-option';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){if(!el){return false;}var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function zIndex(el){var z=0;for(var p=el;p&&p!==document.body;p=p.parentElement){var v=parseInt(getComputedStyle(p).zIndex||'0',10);if(!isNaN(v)&&v>z){z=v;}}return z;}" +
                "function isCascader(el){return !!(el&&(el.closest&&(el.closest('.next-cascader')||el.closest('.next-cascader-select-dropdown')||el.classList&&el.classList.contains('next-cascader-menu'))));}" +
                "function matches(t){if(!t){return false;}if(t===target){return true;}" +
                "  if(t.indexOf(target)===0){var next=t.charAt(target.length);return !next||next<'0'||next>'9';}" +
                "  return false;}" +
                // 优先：aria-expanded 的 adzone 控件附近 / 非 cascader 的可见 select 菜单
                "var scope=null;" +
                "var ad=document.getElementById('adzoneId')||document.getElementById('adzoneName');" +
                "var pops=Array.prototype.slice.call(document.querySelectorAll('.next-overlay-wrapper.opened .next-select-menu,.next-overlay-wrapper.opened [role=\"listbox\"],.next-select-menu,.next-virtual-list-wrapper,[role=\"listbox\"]'));" +
                "var bestZ=-1;for(var p=0;p<pops.length;p++){var pop=pops[p];if(!visible(pop)||isCascader(pop)){continue;}var z=zIndex(pop);if(z>=bestZ){bestZ=z;scope=pop;}}" +
                "if(!scope){for(var q=0;q<pops.length;q++){if(visible(pops[q])){scope=pops[q];break;}}}" +
                "if(!scope){scope=document;}" +
                "var items=Array.prototype.slice.call(scope.querySelectorAll('li[role=\"option\"],[role=\"option\"],li.next-menu-item,li.next-select-menu-item,.next-menu-item,.next-virtual-list-item'));" +
                "if(items.length===0){items=Array.prototype.slice.call(scope.querySelectorAll('li,div,span,a'));}" +
                "var best=null,bestScore=-1e9;" +
                "for(var i=0;i<items.length;i++){var e=items[i];if(!visible(e)||isCascader(e)){continue;}var t=text(e);if(!matches(t)){continue;}" +
                "var item=e.closest('li,[role=\"option\"],.next-menu-item,.next-select-menu-item,.next-virtual-list-item')||e;if(!visible(item)||isCascader(item)){continue;}" +
                "var r=item.getBoundingClientRect();var sc=0;" +
                "if(r.height>=20&&r.height<=72){sc+=300;}if(item.tagName==='LI'||item.getAttribute('role')==='option'){sc+=250;}" +
                "if(t===target){sc+=400;}else if(t.indexOf(target)===0){sc+=300;}" +
                "sc-=Math.min(150,Math.max(0,t.length-target.length)*4);sc-=Math.min(80,Math.abs(r.top)/20);" +
                "if(sc>bestScore){bestScore=sc;best=item;}}" +
                "if(!best){return {found:false,scope:scope===document?'document':'popup',options:Array.prototype.slice.call((scope||document).querySelectorAll('li[role=\"option\"],[role=\"option\"],li.next-select-menu-item')).filter(visible).slice(0,12).map(function(n){return text(n).substring(0,40);})};}" +
                "best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});" +
                "var br=best.getBoundingClientRect();return {found:true,x:br.left+br.width/2,y:br.top+br.height/2,text:text(best).substring(0,40)};", adSiteName);
        if (marked instanceof Map) {
            Map<?, ?> info = (Map<?, ?>) marked;
            if (info.get("x") instanceof Number && info.get("y") instanceof Number) {
                Object x = info.get("x"), y = info.get("y");
                log.info("Clicking ad site option '{}' at ({}, {}) text='{}'", adSiteName, x, y, info.get("text"));
                try {
                    double cx = ((Number) x).doubleValue();
                    double cy = ((Number) y).doubleValue();
                    browser.mouseMove(cx, cy);
                    Extends.sleep(120 + ThreadLocalRandom.current().nextLong(80, 220));
                    browser.mouseClick(cx, cy);
                    return true;
                } catch (Exception mouseError) {
                    log.debug("Native mouse click ad site option fail, fallback to elementClick: {}", mouseError.getMessage());
                    return nativeClickMarkedElement(browser, selector);
                }
            }
            log.info("Ad site option not in open menu, adSiteName={}, options={}", adSiteName, info.get("options"));
            return false;
        }
        return Boolean.TRUE.equals(marked) && nativeClickMarkedElement(browser, selector);
    }

    /** 判断 form select 展示值是否匹配目标（精确或「数字前缀+非数字后缀」如 5→5会员）。 */
    private boolean formSelectDisplayMatches(Browser browser, String fieldId, String expected, boolean exactOnly) {
        Boolean ok = browser.executeScript("var fieldId=String(arguments[0]||''),expected=String(arguments[1]||'').replace(/\\s+/g,'').trim(),exactOnly=!!arguments[2];" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var input=document.getElementById(fieldId);if(!input){return false;}" +
                "var root=input.closest('.next-select')||input.parentElement;if(!root){return false;}" +
                "var t=norm((root.querySelector('.next-select-values')||root).innerText||input.getAttribute('aria-valuetext')||input.value||'');" +
                "if(!t||!expected){return false;}if(t===expected){return true;}if(exactOnly){return false;}" +
                "if(t.indexOf(expected)===0){var n=t.charAt(expected.length);return !n||n<'0'||n>'9';}" +
                "return false;", fieldId, expected, exactOnly);
        return Boolean.TRUE.equals(ok);
    }

    /** 读取当前打开的 select/listbox 可见选项文案，便于诊断。 */
    private List<String> listOpenSelectOptionTexts(Browser browser) {
        try {
            Object raw = browser.executeScript(
                    "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                    "function visible(el){if(!el){return false;}var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                    "var scope=null,pops=document.querySelectorAll('.next-overlay-wrapper.opened .next-select-menu,.next-overlay-wrapper.opened [role=\"listbox\"],.next-select-menu,[role=\"listbox\"]');" +
                    "for(var i=0;i<pops.length;i++){if(visible(pops[i])&&!pops[i].closest('.next-cascader')){scope=pops[i];break;}}" +
                    "if(!scope){return [];}" +
                    "return Array.prototype.slice.call(scope.querySelectorAll('li[role=\"option\"],[role=\"option\"],li.next-menu-item')).filter(visible).slice(0,20).map(function(n){return norm(n.innerText||n.textContent).substring(0,40);});");
            if (raw instanceof List) {
                List<String> out = new java.util.ArrayList<>();
                for (Object o : (List<?>) raw) {
                    if (o != null) {
                        out.add(String.valueOf(o));
                    }
                }
                return out;
            }
        } catch (Exception e) {
            log.debug("listOpenSelectOptionTexts fail: {}", e.getMessage());
        }
        return java.util.Collections.emptyList();
    }

    /** 向当前展开的 select 搜索框输入过滤关键字（Next has-search）。 */
    private void typeIntoOpenSelectSearch(Browser browser, String text) {
        try {
            Boolean focused = browser.executeScript("var text=String(arguments[0]||'');" +
                    "function visible(el){if(!el){return false;}var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                    "var input=document.querySelector('.next-overlay-wrapper.opened .next-select-trigger-search input, .next-select.next-active input, #adzoneId, #adzoneName');" +
                    "if(!input||!visible(input)){input=document.getElementById('adzoneId')||document.getElementById('adzoneName');}" +
                    "if(!input){return false;}input.focus();" +
                    "var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');" +
                    "if(setter&&setter.set){setter.set.call(input,text);}else{input.value=text;}" +
                    "input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));return true;", text);
            if (Boolean.TRUE.equals(focused) && !Strings.isEmpty(text)) {
                // 再用键盘敲一遍更像真人（部分 Next 只听 keydown）
                try {
                    browser.elementPress("#adzoneId", text);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("typeIntoOpenSelectSearch fail: {}", e.getMessage());
        }
    }

    /** 关闭残留下拉，避免 cascader/select 抢 scope。 */
    private void closeOpenSelectPopup(Browser browser) {
        try {
            browser.executeScript(
                    "var ae=document.activeElement;if(ae&&ae.blur){ae.blur();}" +
                    "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true}));");
            Extends.sleep(200);
        } catch (Exception ignored) {
        }
    }

    private boolean nativeClickFormSelect(Browser browser, String fieldId, String attr) {
        String selector = "[" + attr + "='1']";
        Boolean marked = browser.executeScript("var fieldId=String(arguments[0]||''),attr=arguments[1];" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "var input=document.getElementById(fieldId);if(!input||!visible(input)){return false;}" +
                "var target=input.closest('.next-select-trigger,.next-select,[aria-haspopup=true],[role=combobox]')||input;" +
                "if(!visible(target)){target=input;}" +
                "target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;", fieldId, attr);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        return nativeClickMarkedElement(browser, selector);
    }

    private boolean nativeClickLabelControl(Browser browser, String labelText, String attr) {
        String selector = "[" + attr + "='1']";
        Boolean marked = browser.executeScript("var labelText=String(arguments[0]||''),attr=arguments[1];" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('placeholder')||el.getAttribute('aria-label'));}" +
                "var dialogs=Array.prototype.slice.call(document.querySelectorAll('[role=\"dialog\"],.next-dialog')),scope=document;" +
                "for(var d=dialogs.length-1;d>=0;d--){if(visible(dialogs[d])){scope=dialogs[d];break;}}" +
                "var labels=Array.prototype.slice.call(scope.querySelectorAll('label,span,div,p')),label=null;" +
                "for(var i=0;i<labels.length;i++){var e=labels[i],t=text(e);if(visible(e)&&t.indexOf(labelText)>=0&&t.length<=labelText.length+8){label=e;break;}}" +
                "if(!label){return false;}" +
                "if(label.tagName==='LABEL'&&label.getAttribute('for')){var input=document.getElementById(label.getAttribute('for'));if(input&&visible(input)){var targetByFor=input.closest('.next-select-trigger,.next-select,[aria-haspopup=true],[role=combobox]')||input;targetByFor.setAttribute(attr,'1');targetByFor.scrollIntoView({block:'center',inline:'center'});return true;}}" +
                "var lr=label.getBoundingClientRect(),nodes=Array.prototype.slice.call(scope.querySelectorAll('input,textarea,button,a,div,span'));" +
                "var best=null,bestScore=999999;for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n)||n===label||label.contains(n)||n.contains(label)){continue;}" +
                "var r=n.getBoundingClientRect(),dy=Math.abs((r.top+r.bottom-lr.top-lr.bottom)/2),dx=r.left-lr.right,area=r.width*r.height,t=text(n);" +
                "if(dx<-25||dy>55||r.width<70||r.height<22||area>70000||t.indexOf(labelText)>=0||t.indexOf('新增媒体')>=0){continue;}" +
                "var score=dy*20+Math.max(0,dx)+area/1000;if(score<bestScore){best=n;bestScore=score;}}" +
                "if(!best){return false;}var target=best.closest('button,a,[role=\"button\"],[role=\"combobox\"],.next-select-trigger,.next-select')||best;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;", labelText, attr);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        return nativeClickMarkedElement(browser, selector);
    }

    private boolean selectedLabelControlEquals(Browser browser, String labelText, String expected) {
        Boolean ok = browser.executeScript("var labelText=String(arguments[0]||''),expected=String(arguments[1]||'').replace(/\\s+/g,'').trim();" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('placeholder')||el.getAttribute('aria-label'));}" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p')),label=null;" +
                "for(var i=0;i<labels.length;i++){var e=labels[i],t=text(e);if(visible(e)&&t.indexOf(labelText)>=0&&t.length<=labelText.length+8){label=e;break;}}" +
                "if(!label){return false;}var lr=label.getBoundingClientRect(),nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea,button,a,div,span'));" +
                "for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n)||n===label||label.contains(n)||n.contains(label)){continue;}" +
                "var r=n.getBoundingClientRect(),dy=Math.abs((r.top+r.bottom-lr.top-lr.bottom)/2),dx=r.left-lr.right;if(dx<-25||dy>55){continue;}" +
                "if(text(n)===expected){return true;}}" +
                "return false;", labelText, expected);
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickOptionByText(Browser browser, String optionText, boolean exact) {
        String selector = "[data-rx-tb-option='1']";
        Boolean marked = browser.executeScript("var target=String(arguments[0]||'').replace(/\\s+/g,'').trim(),exact=arguments[1],attr='data-rx-tb-option';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('li,div,span,a,[role=\"option\"]')),best=null,bestScore=999999;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=text(e);if(!t){continue;}var matched=exact?t===target:t.indexOf(target)>=0;if(!matched){continue;}" +
                "var r=e.getBoundingClientRect();if(r.top<0||r.left<0||r.top>window.innerHeight||r.left>window.innerWidth){continue;}" +
                "var inOverlay=e.closest('.next-overlay-wrapper,.next-menu,.next-select-menu,.next-dialog,[role=\"listbox\"]')?0:500;" +
                "var score=inOverlay+(t===target?0:100)+Math.abs(r.top-260)+r.width*r.height/3000;if(score<bestScore){best=e;bestScore=score;}}" +
                "if(!best){return false;}var targetEl=best.closest('li,a,[role=\"option\"]')||best;targetEl.setAttribute(attr,'1');targetEl.scrollIntoView({block:'center',inline:'center'});return true;", optionText, exact);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        return nativeClickMarkedElement(browser, selector);
    }

    /**
     * 真实鼠标拟人点击：mouseMove → 短暂 hover → mouseClick；
     * 拿不到坐标时优先 JS click，最后才 elementClick（且 WebBrowser 已改为短超时 force，避免 6s+6s）。
     */
    private boolean nativeClickMarkedElement(Browser browser, String selector) {
        if (nativeMouseClickMarkedElement(browser, selector)) {
            return true;
        }
        if (jsClickMarkedElement(browser, selector)) {
            return true;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception clickError) {
            log.debug("Marked element click fallback fail, selector={}, error={}", selector, clickError.getMessage());
            return false;
        }
    }

    private boolean nativeMouseClickMarkedElement(Browser browser, String selector) {
        try {
            Map<String, Object> point = markedElementPoint(browser, selector);
            if (point == null || !(point.get("x") instanceof Number) || !(point.get("y") instanceof Number)) {
                return false;
            }
            double x = ((Number) point.get("x")).doubleValue();
            double y = ((Number) point.get("y")).doubleValue();
            if (x < 1 || y < 1) {
                return false;
            }
            log.info("Native mouse click '{}' at ({}, {})", selector, x, y);
            browser.mouseMove(x, y);
            Extends.sleep(120 + ThreadLocalRandom.current().nextLong(80, 220));
            browser.mouseClick(x, y);
            return true;
        } catch (Exception moveError) {
            log.debug("Native mouse click fail, selector={}, error={}", selector, moveError.getMessage());
            return false;
        }
    }

    private boolean jsClickMarkedElement(Browser browser, String selector) {
        Boolean clicked = browser.executeScript("var el=document.querySelector(String(arguments[0]||''));" +
                "if(!el){return false;}" +
                "var target=el.closest('button,a,[role=\"button\"]')||el;" +
                "target.click();" +
                "return true;", selector);
        return Boolean.TRUE.equals(clicked);
    }

    private Map<String, Object> markedElementPoint(Browser browser, String selector) {
        return browser.executeScript("var el=document.querySelector(String(arguments[0]||''));" +
                "if(!el){return null;}var r=el.getBoundingClientRect();" +
                "return {x:r.left+r.width/2,y:r.top+r.height/2};", selector);
    }

    /**
     * 点「选择推广位」弹窗底部确认，并校验弹窗关闭。
     * 常见失败：媒体/推广位下拉未关盖住确认按钮 → 鼠标看似点了但没生效。
     */
    private boolean confirmPromotionSlotDialog(Browser browser, TbPromotionConfig config, DebugRecorder debug)
            throws TimeoutException {
        for (int attempt = 1; attempt <= 3; attempt++) {
            // 1) 先关掉残留 select 下拉，避免盖住底部确认
            closeOpenSelectPopup(browser);
            Extends.sleep(150 + ThreadLocalRandom.current().nextLong(80, 180));

            if (!isPromotionSlotPickerVisible(browser)) {
                log.info("Slot picker already closed before confirm, attempt={}", attempt);
                return true;
            }

            if (!nativeClickDialogButton(browser, "确认")) {
                log.warn("Slot confirm button not found, attempt={}", attempt);
                continue;
            }
            // 2) 等弹窗关闭（成功标志：不再出现「选择推广位」表单）
            long waitDeadline = System.currentTimeMillis() + 2500L;
            while (System.currentTimeMillis() < waitDeadline) {
                ensureTaskDeadline("getTbPromotionUrl.confirmPromotionSlotDialog");
                if (!isPromotionSlotPickerVisible(browser)) {
                    log.info("Slot picker closed after confirm, attempt={}", attempt);
                    return true;
                }
                Extends.sleep(200L);
            }
            log.warn("Slot confirm click did not close picker, attempt={}, will retry", attempt);
            debug.snapshot(browser, "14-slot-confirm-retry-" + attempt);
            // 3) 重试前再 force 一次 JS 点 primary
            jsClickSlotConfirmPrimary(browser);
            Extends.sleep(400L);
            if (!isPromotionSlotPickerVisible(browser)) {
                return true;
            }
        }
        return !isPromotionSlotPickerVisible(browser);
    }

    /** 「选择推广位」子弹是否仍打开（含推广位名称 + 确认/取消）。 */
    private boolean isPromotionSlotPickerVisible(Browser browser) {
        Boolean open = browser.executeScript(
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){if(!el){return false;}var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var nodes=document.querySelectorAll('.next-dialog,[role=\"dialog\"],.next-overlay-wrapper.opened .next-dialog,.next-overlay-inner');" +
                "for(var i=0;i<nodes.length;i++){var el=nodes[i];if(!visible(el)){continue;}" +
                "var t=norm(el.innerText||'');" +
                "if(t.indexOf('选择推广位')>=0&&(t.indexOf('推广位名称')>=0||t.indexOf('媒体类型')>=0)&&t.indexOf('确认')>=0){return true;}}" +
                "return false;");
        return Boolean.TRUE.equals(open);
    }

    private boolean jsClickSlotConfirmPrimary(Browser browser) {
        Boolean ok = browser.executeScript(
                "function visible(el){if(!el){return false;}var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var dialogs=document.querySelectorAll('.next-dialog,[role=\"dialog\"]');" +
                "for(var d=dialogs.length-1;d>=0;d--){var dlg=dialogs[d];if(!visible(dlg)){continue;}" +
                "var t=norm(dlg.innerText||'');if(t.indexOf('选择推广位')<0){continue;}" +
                "var btns=dlg.querySelectorAll('button.next-btn-primary,button.next-dialog-btn');" +
                "for(var i=0;i<btns.length;i++){var b=btns[i];if(!visible(b)){continue;}" +
                "if(norm(b.innerText||'')==='确认'){b.click();return true;}}}" +
                "return false;");
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickDialogButton(Browser browser, String text) {
        String selector = "[data-rx-tb-dialog-button='1']";
        // 优先在「选择推广位」对话框内找 primary 确认，不要用全局最高 z-index（下拉菜单会抢走 scope）。
        Object marked = browser.executeScript("var target=String(arguments[0]||'').replace(/\\s+/g,'').trim(),attr='data-rx-tb-dialog-button';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){if(!el){return false;}var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                // 1) 优先 scope = 含「选择推广位」的 dialog（不是 select 下拉）
                "var dialogs=Array.prototype.slice.call(document.querySelectorAll('.next-dialog,[role=\"dialog\"],.ant-modal,.modal'));" +
                "var scope=null;" +
                "for(var d=dialogs.length-1;d>=0;d--){var dlg=dialogs[d];if(!visible(dlg)){continue;}var dt=text(dlg);" +
                "if(dt.indexOf('选择推广位')>=0||(dt.indexOf('推广位名称')>=0&&dt.indexOf('媒体类型')>=0)){scope=dlg;break;}}" +
                "if(!scope){for(var d2=dialogs.length-1;d2>=0;d2--){if(visible(dialogs[d2])){scope=dialogs[d2];break;}}}" +
                "if(!scope){scope=document;}" +
                // 2) 只认 button，优先 primary
                "var candidates=Array.prototype.slice.call(scope.querySelectorAll('button.next-btn-primary,button.next-dialog-btn,button'));" +
                "var best=null,bestScore=-1e9;" +
                "for(var i=0;i<candidates.length;i++){var btn=candidates[i];if(!visible(btn)){continue;}" +
                "var tt=text(btn);if(tt!==target){continue;}" +
                "if(tt.indexOf('取消')>=0){continue;}" +
                "var cls=String(btn.className||'').toLowerCase();" +
                "var r=btn.getBoundingClientRect();" +
                "var sc=0;" +
                "if(cls.indexOf('next-btn-primary')>=0||cls.indexOf('btn-primary')>=0){sc+=1000;}" +
                "if(cls.indexOf('next-dialog-btn')>=0){sc+=300;}" +
                "sc+=r.top*0.5+r.left*0.2;" +
                "if(sc>bestScore){bestScore=sc;best=btn;}}" +
                "if(!best){return false;}best.setAttribute(attr,'1');" +
                "try{best.scrollIntoView({block:'nearest',inline:'nearest'});}catch(e){}" +
                "var br=best.getBoundingClientRect();" +
                "return {x:br.left+br.width/2,y:br.top+br.height/2,w:br.width,h:br.height," +
                "tag:best.tagName+'#'+(best.id||'')+'.'+String(best.className||'').split(' ').slice(0,3).join('.')," +
                "cls:String(best.className||'').substring(0,80)};", text);
        if (!(marked instanceof Map) && !Boolean.TRUE.equals(marked)) {
            return false;
        }
        if (marked instanceof Map) {
            Map<?, ?> info = (Map<?, ?>) marked;
            Object x = info.get("x"), y = info.get("y");
            if (x instanceof Number && y instanceof Number) {
                try {
                    double cx = ((Number) x).doubleValue();
                    double cy = ((Number) y).doubleValue();
                    if (cx > 1 && cy > 1) {
                        log.info("Clicking dialog button '{}' at ({}, {}) tag={} cls={}",
                                text, cx, cy, info.get("tag"), info.get("cls"));
                        browser.mouseMove(cx, cy);
                        Extends.sleep(100 + ThreadLocalRandom.current().nextLong(60, 160));
                        browser.mouseClick(cx, cy);
                        return true;
                    }
                } catch (Exception mouseError) {
                    log.debug("Native mouse click dialog button fail: {}", mouseError.getMessage());
                }
            }
        }
        if (jsClickMarkedElement(browser, selector)) {
            return true;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String waitPromotionUrl(Browser browser, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug, int maxSeconds) throws TimeoutException {
        return waitPromotionUrl(browser, config, result, debug, maxSeconds, null);
    }

    /**
     * @param avoidUrl 若非空：优先等到与该默认链不同的新链（选位生效后的文案）
     */
    private String waitPromotionUrl(Browser browser, TbPromotionConfig config,
            PromotionUrlResult result, DebugRecorder debug, int maxSeconds, String avoidUrl)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, maxSeconds) * 1000L;
        long poll = pollIntervalMillis(config, 300);
        String promotionUrl = "";
        String fallback = "";
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitPromotionUrl");
            if (!checkAndWaitSliderVerify(browser, config, result, debug, "15-promotion-url-slider")) {
                return "";
            }
            promotionUrl = readPromotionUrl(browser);
            if (!Strings.isEmpty(promotionUrl)) {
                if (Strings.isEmpty(avoidUrl) || !avoidUrl.equals(promotionUrl)) {
                    return promotionUrl;
                }
                fallback = promotionUrl;
            }
            Extends.sleep(poll);
        }
        return fallback;
    }

    private boolean nativeClickCopyButton(Browser browser) {
        return nativeClickByText(browser, "一键复制", true);
    }

    /**
     * 素材弹层指纹：右上角推广位 mm_、文案内 m.tb.cn、文案摘要。
     * 用于判断「选位确认」是否真正触发了转链刷新。
     */
    private Map<String, String> readMaterialFingerprint(Browser browser) {
        Map<String, String> fp = new java.util.LinkedHashMap<>();
        try {
            Map<String, Object> raw = browser.executeScript(
                    "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                    "function visible(el){if(!el){return false;}var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                    "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                    "var body=norm(document.body?document.body.innerText:'');" +
                    "var slot='';var m=body.match(/mm_\\d+_\\d+_\\d+/);if(m){slot=m[0];}" +
                    // 右上角「推广位：」旁的 mm_ 更准
                    "var nodes=document.querySelectorAll('span,div,button,a,em');" +
                    "for(var i=0;i<nodes.length;i++){var el=nodes[i];if(!visible(el)){continue;}" +
                    "var t=norm(el.innerText||el.textContent||'');" +
                    "if(/^mm_\\d+_\\d+_\\d+$/.test(t)){slot=t;break;}" +
                    "var mm=t.match(/mm_\\d+_\\d+_\\d+/);if(mm&&t.indexOf('推广位')>=0){slot=mm[0];break;}}" +
                    "var url='';var urls=body.match(/https?:\\/\\/(?:m\\.)?tb\\.cn\\/h\\.[A-Za-z0-9]+/g)||[];" +
                    "if(urls.length){url=urls[0];}" +
                    "if(!url){var u2=body.match(/https?:\\/\\/[^\\s\"'<>，。]+/g)||[];" +
                    "for(var j=0;j<u2.length;j++){if(/tb\\.cn|s\\.click\\.taobao|uland\\.taobao/i.test(u2[j])){url=u2[j];break;}}}" +
                    // 文案区：含【下单链接】或【到手价】的块
                    "var copy='';var blocks=document.querySelectorAll('div,p,pre,textarea');" +
                    "for(var k=0;k<blocks.length;k++){var b=blocks[k];if(!visible(b)){continue;}" +
                    "var bt=norm(b.innerText||b.value||'');" +
                    "if(bt.indexOf('下单链接')>=0||bt.indexOf('到手价')>=0||bt.indexOf('推荐理由')>=0){" +
                    "if(bt.length>40&&bt.length<1200){copy=bt.substring(0,400);break;}}}" +
                    "return {slotId:slot||'',url:url||'',copy:copy||''};");
            if (raw != null) {
                for (Map.Entry<String, Object> e : raw.entrySet()) {
                    fp.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
            }
        } catch (Exception e) {
            log.debug("readMaterialFingerprint fail: {}", e.getMessage());
        }
        fp.putIfAbsent("slotId", "");
        fp.putIfAbsent("url", "");
        fp.putIfAbsent("copy", "");
        return fp;
    }

    /**
     * 选位确认后等待素材刷新：url / slotId / copy 任一相对 before 变化即视为生效。
     */
    private boolean waitMaterialRefreshedAfterSlot(Browser browser, TbPromotionConfig config,
            Map<String, String> before, int maxSeconds) throws TimeoutException {
        if (before == null) {
            before = new java.util.LinkedHashMap<>();
        }
        String beforeUrl = nullToEmpty(before.get("url"));
        String beforeSlot = nullToEmpty(before.get("slotId"));
        String beforeCopy = nullToEmpty(before.get("copy"));
        long deadline = System.currentTimeMillis() + Math.max(2, maxSeconds) * 1000L;
        long poll = pollIntervalMillis(config, 300);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitMaterialRefreshedAfterSlot");
            Map<String, String> now = readMaterialFingerprint(browser);
            String url = nullToEmpty(now.get("url"));
            String slot = nullToEmpty(now.get("slotId"));
            String copy = nullToEmpty(now.get("copy"));
            boolean urlChanged = !Strings.isEmpty(url) && !url.equals(beforeUrl);
            boolean slotChanged = !Strings.isEmpty(slot) && !slot.equals(beforeSlot);
            // copy 变化：长度或关键片段不同（排除空白抖动）
            boolean copyChanged = !Strings.isEmpty(copy) && !Strings.isEmpty(beforeCopy)
                    && !copy.equals(beforeCopy)
                    && (Math.abs(copy.length() - beforeCopy.length()) > 5
                    || !copyRegionKey(copy).equals(copyRegionKey(beforeCopy)));
            if (urlChanged || slotChanged || copyChanged) {
                log.info("Material refreshed after slot: urlChanged={}, slotChanged={} ({} -> {}), copyChanged={}",
                        urlChanged, slotChanged, beforeSlot, slot, copyChanged);
                return true;
            }
            Extends.sleep(poll);
        }
        Map<String, String> last = readMaterialFingerprint(browser);
        log.warn("Material not refreshed after slot confirm, beforeSlot={}, afterSlot={}, beforeUrl={}, afterUrl={}",
                beforeSlot, last.get("slotId"), beforeUrl, last.get("url"));
        return false;
    }

    private String copyRegionKey(String copy) {
        if (Strings.isEmpty(copy)) {
            return "";
        }
        // 用链接 + 到手价 做粗指纹，避免无关空白
        String u = "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://[^\\s]+").matcher(copy);
        if (m.find()) {
            u = m.group();
        }
        String price = "";
        java.util.regex.Matcher p = java.util.regex.Pattern.compile("到手价[^0-9]*([0-9.]+)").matcher(copy);
        if (p.find()) {
            price = p.group(1);
        }
        return u + "|" + price;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String readPromotionUrl(Browser browser) {
        // 优先从素材指纹读 m.tb.cn（与文案区一致）
        Map<String, String> fp = readMaterialFingerprint(browser);
        if (!Strings.isEmpty(fp.get("url"))) {
            return fp.get("url").trim();
        }
        String url = browser.executeScript("function pick(v){if(!v){return '';}var m=String(v).match(/https?:\\/\\/[^\\s\"'<>，。]+/);return m?m[0]:'';}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function score(url,ctx){var s=0;if(/m\\.tb\\.cn|tb\\.cn|s\\.click\\.taobao\\.com|uland\\.taobao\\.com|taobao\\.com/i.test(url)){s+=1000;}if(/下单链接|淘口令|短链接|长链接|链接/.test(ctx)){s+=100;}return s;}" +
                "var best='',bestScore=-1,nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var u=pick(e.value||e.innerText);if(!u){continue;}var ctx=(e.innerText||e.value||'');for(var p=e.parentElement;p&&p!==document.body;p=p.parentElement){ctx+=' '+(p.innerText||'');if(ctx.length>1000){break;}}" +
                "var sc=score(u,ctx);if(sc>bestScore){best=u;bestScore=sc;}}" +
                "if(best){return best;}" +
                "var body=document.body?document.body.innerText:'';var urls=body.match(/https?:\\/\\/[^\\s\"'<>，。]+/g)||[];" +
                "for(var j=0;j<urls.length;j++){var sc2=score(urls[j],body);if(sc2>bestScore){best=urls[j];bestScore=sc2;}}" +
                "return best||'';");
        return url == null ? "" : url.trim();
    }

    private boolean nativeClickByText(Browser browser, String text, boolean exact) {
        String selector = "[data-rx-tb-click-target='1']";
        // 「一键复制」等按钮通常位于最顶层对话框右下；优先在最高 z-index 的 dialog/overlay 内查找，
        // 文本严格匹配并打日志，避免命中页面其它同文案元素。
        Object marked = browser.executeScript("var target=String(arguments[0]||'').replace(/\\s+/g,'').trim(),exact=arguments[1],attr='data-rx-tb-click-target';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){if(!el){return false;}var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function zIndex(el){var z=0;for(var p=el;p&&p!==document.body;p=p.parentElement){var v=parseInt(getComputedStyle(p).zIndex||'0',10);if(!isNaN(v)&&v>z){z=v;}}return z;}" +
                "var dialogs=Array.prototype.slice.call(document.querySelectorAll('.next-overlay-wrapper,.next-dialog,[role=\"dialog\"],.ant-modal'));" +
                "var scope=null,bestZ=-1;for(var d=0;d<dialogs.length;d++){var dlg=dialogs[d];if(!visible(dlg)){continue;}var z=zIndex(dlg);if(z>=bestZ){bestZ=z;scope=dlg;}}" +
                "var scopes=scope?[scope,document]:[document];" + // 顶层 dialog 找不到时回退到整个 document
                "var best=null,bestScore=-1e9;" +
                "for(var sIdx=0;sIdx<scopes.length;sIdx++){var sc=scopes[sIdx];" +
                "  var nodes=Array.prototype.slice.call(sc.querySelectorAll('button,a,[role=\"button\"],li,span,div'));" +
                "  for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=text(e);if(!t){continue;}" +
                "  var matched=exact?t===target:t.indexOf(target)>=0;if(!matched){continue;}" +
                "  var btn=e.closest('button,a,[role=\"button\"]')||e;if(!visible(btn)){continue;}" +
                "  var tt=text(btn);if(tt.length>32){continue;}" +
                "  var r=btn.getBoundingClientRect(),cls=String(btn.className||'').toLowerCase();" +
                "  var score=0;if(sIdx===0){score+=1000;}" +
                "  if(cls.indexOf('btn-primary')>=0){score+=400;}" +
                "  if(btn.tagName==='BUTTON'){score+=200;}" +
                "  if(t===target){score+=100;}" +
                "  score+=r.top+r.left;" +
                "  if(score>bestScore){bestScore=score;best=btn;}}" +
                "  if(best){break;}" +
                "}" +
                "if(!best){return false;}best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});" +
                "var br=best.getBoundingClientRect();return {x:br.left+br.width/2,y:br.top+br.height/2,tag:best.tagName+'#'+(best.id||'')+'.'+String(best.className||'').split(' ')[0],text:text(best).substring(0,30)};", text, exact);
        if (marked instanceof Map) {
            Map<?, ?> info = (Map<?, ?>) marked;
            log.info("Clicking '{}' at ({}, {}) tag={} text='{}'", text, info.get("x"), info.get("y"), info.get("tag"), info.get("text"));
            Object x = info.get("x"), y = info.get("y");
            if (x instanceof Number && y instanceof Number) {
                double cx = ((Number) x).doubleValue();
                double cy = ((Number) y).doubleValue();
                if (cx > 1 && cy > 1) {
                    try {
                        browser.mouseMove(cx, cy);
                        Extends.sleep(120 + ThreadLocalRandom.current().nextLong(80, 220));
                        browser.mouseClick(cx, cy);
                        return true;
                    } catch (Exception mouseError) {
                        log.debug("nativeClickByText mouse click fail, fallback: {}", mouseError.getMessage());
                    }
                }
            }
            return nativeClickMarkedElement(browser, selector);
        }
        return Boolean.TRUE.equals(marked) && nativeClickMarkedElement(browser, selector);
    }

    private Map<String, Object> collectDialogDiagnostics(Browser browser) {
        return browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function item(el){var r=el.getBoundingClientRect();return {tag:el.tagName,role:el.getAttribute('role')||'',className:norm(el.className).substring(0,120),text:norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('placeholder')).substring(0,120),value:norm(el.value).substring(0,120),top:Math.round(r.top),left:Math.round(r.left),width:Math.round(r.width),height:Math.round(r.height)};}" +
                "return {url:location.href,inputs:Array.prototype.slice.call(document.querySelectorAll('input,textarea')).filter(visible).map(item),buttons:Array.prototype.slice.call(document.querySelectorAll('button,a,[role=\"button\"],[role=\"option\"],li')).filter(visible).map(item),body:norm(document.body?document.body.innerText:'').substring(0,1600)};");
    }

    @SneakyThrows
    private WebBrowserConfig createBrowserConfig(PromotionUrlRequest request, TbPromotionConfig tbConfig) {
        WebBrowserConfig config = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
        config.setProfileDataPath(profileManager.resolveProfileDataPath(request.getProfileName()));
        config.setHeadless(tbConfig.isHeadless());
        config.setFingerprintEnabled(tbConfig.isFingerprintEnabled());
        config.setFingerprintHeadless(tbConfig.isHeadless());
        if (Strings.isEmpty(config.getConfigureScriptExecutorType())) {
            config.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        }
        return config;
    }

    private PromotionUrlRequest normalizeRequest(PromotionUrlRequest request, TbPromotionConfig config) {
        if (request == null) {
            request = new PromotionUrlRequest();
        }
        if (Strings.isEmpty(request.getKeyword())) {
            throw new InvalidException("productInfo is required");
        }
        if (Strings.isEmpty(request.getAdSiteName())) {
            request.setAdSiteName(config.getDefaultAdSiteName());
        }
        if (Strings.isEmpty(request.getAdSiteName())) {
            throw new InvalidException("adSiteName is required");
        }
        if (Strings.isEmpty(request.getProfileName())) {
            request.setProfileName(Strings.isEmpty(config.getProfileName()) ? profileManager.defaultProfileName()
                    : config.getProfileName());
        }
        request.setProfileName(profileManager.normalizeProfileName(request.getProfileName()));
        if (Strings.isEmpty(request.getMediaName())) {
            request.setMediaName(config.getDefaultMediaName());
        }
        if (Strings.isEmpty(request.getOutputPath())) {
            request.setOutputPath(config.getDefaultOutputPath());
        }
        return request;
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

    private void applyEntryResult(PromotionUrlResult result, CrawlEntryResult entry) {
        result.setFingerprintPassed(entry.isFingerprintPassed());
        result.setCurrentUrl(entry.getCurrentUrl());
        result.setLoginRequired(entry.isLoginRequired());
        result.getDiagnostics().putAll(entry.getDiagnostics());
        if (!entry.isPassed()) {
            fail(result, entry.getStatus(), entry.getMessage());
        }
    }

    /**
     * 硬登录 URL：账密/扫码页 / login_jump。
     * 注意：仅看 URL 时 login_jump 仍判登录；若 body 是「确认登录/快速进入」会话续期，
     * {@link CrawlEntryService#needsLogin} 会通过 sessionResumeBodyMatcher 覆盖为不需要登录。
     * 已去掉过宽的裸 {@code /login} 匹配，避免误伤业务 URL。
     */
    private boolean isHardLoginUrl(String currentUrl, TbPromotionConfig config) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        if (!Strings.isEmpty(config.getLoginUrlPrefix()) && currentUrl.startsWith(config.getLoginUrlPrefix())) {
            return true;
        }
        return lower.contains("login.taobao.com/member/login")
                || lower.contains("login.taobao.com/havanaone/login")
                || lower.contains("login.taobao.com")
                || lower.contains("havanalogin")
                || lower.contains("passport.taobao.com")
                || lower.contains("login_jump");
    }

    private boolean isLoginRequired(String currentUrl, TbPromotionConfig config) {
        return isHardLoginUrl(currentUrl, config);
    }

    /** 硬登录正文：密码/扫码；有「快速进入」则为会话续期，不算硬登录。 */
    private boolean isHardLoginBody(String body) {
        if (Strings.isEmpty(body) || isSessionResumeBody(body)) {
            return false;
        }
        return containsAny(body, "密码登录", "扫码登录", "短信登录", "验证码登录")
                || (containsAny(body, "立即登录", "还没有淘宝账号") && containsAny(body, "阿里妈妈", "推广者", "加入推广者"));
    }

    /** 会话续期：common profile Cookie 仍在，只需点「快速进入」。 */
    private boolean isSessionResumeBody(String body) {
        // 不用单独「确认登录」二字（页面其它处也可能出现），用更具体的续期文案
        return containsAny(body, QUICK_ENTER_TEXT, QUICK_LOGIN_TEXT, "确认继续用以下账号登录");
    }

    private boolean isLoggedInUrl(String currentUrl, TbPromotionConfig config) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        if (lower.contains("login_jump")) {
            return false;
        }
        return lower.startsWith(config.getHomeUrl().toLowerCase(Locale.ROOT))
                || lower.startsWith(config.getPromotionGoodsUrl().toLowerCase(Locale.ROOT))
                || lower.startsWith(config.getOrderUrl().toLowerCase(Locale.ROOT))
                || lower.contains("pub.alimama.com/portal")
                || isForwardLandingUrl(currentUrl);
    }

    private boolean isForwardLandingUrl(String currentUrl) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://pub.alimama.com/?forward=")
                || lower.startsWith("http://pub.alimama.com/?forward=");
    }

    private boolean isForwardLandingIncomplete(Browser browser) {
        return isForwardLandingUrl(browser.getCurrentUrl()) || hasQuickEntrance(bodySnippet(browser));
    }

    private boolean hasQuickEntrance(String body) {
        return containsAny(body, QUICK_ENTER_TEXT, QUICK_LOGIN_TEXT);
    }

    private void fail(PromotionUrlResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message == null ? "" : message);
        if (status == CustomCrawlStatus.LOGIN_REQUIRED) {
            result.setLoginRequired(true);
            notifyLoginRequired(result);
        }
    }

    private void notifyLoginRequired(PromotionUrlResult result) {
        if (Boolean.TRUE.equals(result.getDiagnostics().get("loginNotificationAttempted"))) {
            return;
        }
        result.getDiagnostics().put("loginNotificationAttempted", true);
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType(TASK_TYPE);
        context.setProfileName(result.getProfileName());
        context.setInitialUrl(appConfig.getCustom().getTbPromotion().getHomeUrl());
        context.setCurrentUrl(result.getCurrentUrl());
        context.setMessage(result.getMessage());
        context.setLoginWaitSeconds(appConfig.getCustom().getTbPromotion().getLoginWaitSeconds());
        context.setKeepBrowserOpenSeconds(appConfig.getCustom().getTbPromotion().getKeepBrowserOpenSecondsOnLoginRequired());
        entryService.notifyLoginRequired(context);
    }

    private void notifySliderFailed(PromotionUrlResult result, Browser browser, TbPromotionConfig config,
            String stepTag) {
        if (Boolean.TRUE.equals(result.getDiagnostics().get("sliderNotificationAttempted"))) {
            return;
        }
        result.getDiagnostics().put("sliderNotificationAttempted", true);
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType(Strings.isEmpty(result.getTaskType()) ? TASK_TYPE : result.getTaskType());
        context.setProfileName(result.getProfileName());
        context.setInitialUrl(config.getPromotionGoodsUrl());
        context.setCurrentUrl(browser.getCurrentUrl());
        context.setMessage("自动滑动验证失败，步骤 " + stepTag);
        context.setLoginWaitSeconds(config.getLoginWaitSeconds());
        context.setKeepBrowserOpenSeconds(config.getKeepBrowserOpenSecondsOnLoginRequired());
        entryService.notifySliderFailed(context);
    }

    private String bodySnippet(Browser browser) {
        String body = "";
        for (int i = 0; i < 3; i++) {
            try {
                body = browser.executeScript("return document.body ? document.body.innerText : '';");
                break;
            } catch (Exception e) {
                String message = e.getMessage();
                if (message != null && (message.contains("Execution context was destroyed")
                        || message.contains("because of a navigation"))) {
                    log.debug("Read TB promotion url body while navigating, retry={}", i + 1);
                    Extends.sleep(500L * (i + 1));
                    continue;
                }
                log.warn("Read TB promotion url body fail, currentUrl={}, error={}", browser.getCurrentUrl(), message);
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

    private boolean pageContainsAny(Browser browser, String... keys) {
        if (keys == null || keys.length == 0) {
            return false;
        }
        Boolean ok = browser.executeScript("var keys=arguments[0]||[];" +
                "var body=document.body?(document.body.innerText||''):'';" +
                "for(var i=0;i<keys.length;i++){if(body.indexOf(keys[i])>=0){return true;}}" +
                "return false;", java.util.Arrays.asList(keys));
        return Boolean.TRUE.equals(ok);
    }

    private double toDouble(Object value) {
        if (value == null) {
            return 0D;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0D;
        }
    }

    private boolean isDebugEnabled(PromotionUrlRequest request) {
        if (request != null && request.getDebugEnabled() != null) {
            return request.getDebugEnabled();
        }
        return appConfig.getCustom().isDebugEnabled();
    }

    private String resolveDebugOutputDir(PromotionUrlRequest request, TbPromotionConfig config) {
        if (request != null && !Strings.isEmpty(request.getDebugOutputDir())) {
            return request.getDebugOutputDir();
        }
        return config == null ? null : config.getDebugOutputDir();
    }

    private void ensureTaskDeadline(String stage) throws TimeoutException {
        Long deadline = taskDeadlineHolder.get();
        if (deadline != null && System.currentTimeMillis() > deadline) {
            throw new TimeoutException("TB promotion url task timeout at " + stage);
        }
    }

    private final class DebugRecorder {
        private final boolean enabled;
        private final Path taskDir;
        private int stepIndex;

        private DebugRecorder(PromotionUrlRequest request, TbPromotionConfig config) {
            this.enabled = isDebugEnabled(request);
            if (!enabled) {
                this.taskDir = null;
                return;
            }
            String baseDir = resolveDebugOutputDir(request, config);
            String profileName = Strings.isEmpty(request.getProfileName()) ? "common" : request.getProfileName();
            String product = safePathPart(request.getKeyword());
            String time = LocalDateTime.now().format(DEBUG_TIME_FORMATTER);
            this.taskDir = Paths.get(baseDir, profileName, product + "-" + time);
            try {
                Files.createDirectories(this.taskDir);
                Files.writeString(this.taskDir.resolve("_task.txt"),
                        "taskType=" + TASK_TYPE + System.lineSeparator()
                                + "profileName=" + profileName + System.lineSeparator()
                                + "productInfo=" + request.getKeyword() + System.lineSeparator()
                                + "adSiteName=" + request.getAdSiteName() + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.warn("TB promotion url debug directory init fail, path={}, error={}", this.taskDir, e.getMessage(), e);
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
            try {
                String html = browser.executeScript("return document.documentElement ? document.documentElement.outerHTML : '';");
                Path file = taskDir.resolve(String.format("%02d_%s.html", index, safeStep));
                Files.writeString(file, html == null ? "" : html, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.warn("TB promotion url debug snapshot fail, step={}, error={}", step, e.getMessage(), e);
            }
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
            try {
                Path file = taskDir.resolve(String.format("%02d_%s.html", index, safeStep));
                Files.writeString(file, "<html><body><pre>" + escapeHtml(body) + "</pre></body></html>",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.warn("TB promotion url debug text snapshot fail, step={}, error={}", step, e.getMessage(), e);
            }
        }

        private String safePathPart(String value) {
            String text = Strings.isEmpty(value) ? "unknown" : value.trim();
            text = text.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]+", "_");
            if (text.length() > 40) {
                return text.substring(0, 40);
            }
            return text;
        }

        private String escapeHtml(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
