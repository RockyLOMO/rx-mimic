package org.rx.crawler.task.jd;

import com.fasterxml.jackson.core.type.TypeReference;
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
import org.rx.crawler.task.common.CrawlFallbackService;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.CrawlEntryResult;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.CustomCrawlTask;
import org.rx.crawler.task.common.KeepAliveUrlStore;
import org.rx.crawler.task.common.MallSearchPages;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * 京东商城（主站）商品搜索 + 商品详情抓取任务。
 *
 * <p>搜索流程：进入 www.jd.com → 拟人在搜索框输入关键字 → 点击搜索 → 等结果 →
 * 拟人滚动只补当前页懒加载 → 按期望条数和第一页实际条数自动翻页 →
 * 评分提取商品卡片（skuId/标题/价格/图/店铺/链接）。
 *
 * <p>详情流程：进入 item.jd.com/{skuId}.html → 提取标题/价格/店铺/主图相册 →
 * 拟人滚动商品详情长图到底，再往下最多两下后停 → 收集详情图
 * （描述在跨源 iframe 时导航 iframe src 收集）→ 规格参数 → 按需走浏览器上下文下载图片到本地。
 *
 * <p>登录人工接管、JDJRV 验证码先自动拟人拖拽再人工兜底，均复用公共基础设施。
 */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class JdMallTask implements CustomCrawlTask<JdSearchRequest, JdSearchResult> {
    public static final String TASK_TYPE = "jdSearchProduct";
    public static final String DETAIL_TASK_TYPE = "jdProductDetail";

    private static final String SEARCH_INPUT_SELECTOR = "[data-rx-jd-mall-search-input='1']";
    private static final String SEARCH_BUTTON_SELECTOR = "[data-rx-jd-mall-search-button='1']";
    private static final String SEARCH_NEXT_SELECTOR = "[data-rx-jd-mall-next='1']";
    private static final String PRAISE_ENTRY_SELECTOR = "[data-rx-jd-praise-entry='1']";
    private static final String REVIEW_TAB_SELECTOR = "[data-rx-jd-review-tab='1']";
    private static final String CURRENT_SKU_SELECTOR = "[data-rx-jd-current-sku='1']";
    private static final int SEARCH_PAGE_READ_LIMIT = 120;
    private static final Pattern ITEM_URL_SKU_PATTERN = Pattern.compile("item\\.jd\\.com/(\\d+)");
    private static final DateTimeFormatter DEBUG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final AppConfig appConfig;
    private final BrowserProfileManager profileManager;
    private final CrawlEntryService entryService;
    private final ResultWriter resultWriter;
    private final ObjectMapper objectMapper;
    private final KeepAliveUrlStore keepAliveUrlStore;
    @Autowired(required = false)
    private CrawlFallbackService fallbackService;
    private final JdCaptchaHandler captchaHandler = new JdCaptchaHandler();
    private final ThreadLocal<Long> taskDeadlineHolder = new ThreadLocal<Long>();

    public JdMallTask(AppConfig appConfig, BrowserProfileManager profileManager, CrawlEntryService entryService,
            ResultWriter resultWriter, ObjectMapper objectMapper) {
        this(appConfig, profileManager, entryService, resultWriter, objectMapper, KeepAliveUrlStore.NOOP);
    }

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public JdSearchResult execute(JdSearchRequest request) {
        return search(request);
    }

    public JdSearchResult search(JdSearchRequest request) {
        return executeSearchInternal(request);
    }

    public JdProductDetailResult productDetail(JdProductDetailRequest request) {
        return executeDetailInternal(request);
    }

    public boolean closeProfile(String profileName) {
        return profileManager.closeSession(profileName);
    }

    // ==================== 搜索 ====================

    private JdSearchResult executeSearchInternal(JdSearchRequest rawRequest) {
        JdMallConfig config = appConfig.getCustom().getJdMall();
        JdSearchRequest request = normalizeSearchRequest(rawRequest, config);
        if (Strings.isEmpty(request.getKeyword())) {
            throw new InvalidException("keyword is required");
        }
        JdSearchResult result = createSearchResult(request);
        DebugRecorder debug = new DebugRecorder(request.getProfileName(), request.getKeyword(),
                request.getDebugEnabled(), request.getDebugOutputDir(), config, TASK_TYPE);
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }
        BrowserProfileManager.ProfileLease lease = null;
        taskDeadlineHolder.set(System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(Math.max(1, appConfig.getCustom().getMaxTaskMinutes())));
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(config));
            Browser browser = lease.getBrowser();

            CrawlEntryOptions options = createEntryOptions(request.getProfileName(), request.getForcePreflight(),
                    request.getKeepBrowserOpenOnLoginRequired(), config, config.getHomeUrl(), TASK_TYPE);
            // 导航前随机停顿：上一任务/页面刚结束，模拟人浏览间隙再发起搜索（反爬）
            humanNavigationPause(config);
            CrawlEntryResult entry = entryService.enter(browser, lease, options);
            applyEntryResult(result, entry);
            debug.snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", result.getMessage());
                return result;
            }
            // 进入京东页先判登录：未登录推送通知并保留浏览器等人工登录，超时（loginWaitSeconds，默认180s）任务停止
            if (!ensureJdLoggedIn(browser, lease, config, options, debug)) {
                result.setLoginRequired(true);
                fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                        "JD login wait timeout after " + config.getLoginWaitSeconds() + "s, task stopped");
                debug.snapshotText("01-login-wait-timeout", result.getMessage());
                return result;
            }
            runSearchFlow(browser, lease, request, config, options, result, debug);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("JD mall search fail, keyword={}, profile={}, error={}",
                    request.getKeyword(), request.getProfileName(), e.getMessage(), e);
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            resultWriter.appendJsonLine(request.getOutputPath(), result);
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private void runSearchFlow(Browser browser, BrowserProfileManager.ProfileLease lease, JdSearchRequest request,
            JdMallConfig config, CrawlEntryOptions options, JdSearchResult result, DebugRecorder debug)
            throws TimeoutException {
        if (!ensureNotBlocked(browser, lease, options, config, debug, result.getDiagnostics(), "02-home")) {
            fail(result, CustomCrawlStatus.FAILED, "JD captcha not resolved in time on home page");
            return;
        }
        String keyword = request.getKeyword().trim();
        boolean typed = nativeSetSearchValue(browser, keyword);
        debug.snapshot(browser, "03-search-input-ready");

        // 提交优先输入框回车（React 搜索栏通用），再点真实按钮；
        // 以「到达 search.jd.com / 出现商品卡」为提交成功判据，仍停首页则直跳搜索 URL 兜底
        boolean submitted = false;
        if (typed) {
            try {
                browser.pressKey("Enter");
            } catch (Exception e) {
                log.debug("Press Enter to submit JD search ignored, error={}", e.getMessage());
            }
            Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
            submitted = searchPageReached(browser);
            if (!submitted) {
                submitted = nativeClickSearchButton(browser);
                if (submitted) {
                    Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
                    submitted = searchPageReached(browser);
                }
            }
        }
        if (!submitted) {
            // 兜底：回车/按钮都没让页面离开首页时直跳搜索 URL（真人也可能直接粘贴搜索链接）
            result.getDiagnostics().put("searchNavigateFallback", true);
            browser.navigateUrl(buildSearchUrl(keyword, config), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        }
        debug.snapshot(browser, "04-search-submitted");
        result.setCurrentUrl(browser.getCurrentUrl());

        if (!ensureNotBlocked(browser, lease, options, config, debug, result.getDiagnostics(), "05-search")) {
            fail(result, CustomCrawlStatus.FAILED, "JD captcha not resolved in time on search page");
            return;
        }
        if (!waitSearchResultsReady(browser, lease, config, options, result, debug)) {
            return;
        }
        List<JdSearchItem> items = collectSearchPages(browser, lease, request, config, options, result, debug);
        List<String> validationErrors = new ArrayList<String>();
        List<JdSearchItem> complete = JdResultChecks.completeMallItems(items, validationErrors);
        if (!validationErrors.isEmpty()) {
            result.getDiagnostics().put("validationErrors", validationErrors);
            result.getDiagnostics().put("droppedIncompleteItems", validationErrors.size());
        }
        result.setItems(complete);
        result.setResultCount(complete.size());
        result.setCurrentUrl(browser.getCurrentUrl());
        debug.snapshot(browser, "07-search-items-collected");
        if (complete.isEmpty()) {
            if (items.isEmpty()) {
                fail(result, CustomCrawlStatus.NOT_FOUND, "No products found for keyword: " + keyword);
                debug.snapshot(browser, "06-search-no-result");
            } else {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD search cards missing required fields");
                debug.snapshot(browser, "07-search-items-invalid");
            }
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("Found " + complete.size() + " products for keyword: " + keyword);
        keepAliveUrlStore.collect("jd", browser, result.getDiagnostics());
    }

    /**
     * 等搜索结果首屏商品卡出现；期间处理验证码/登录跳转；无结果文案直接判 NOT_FOUND。
     */
    private boolean waitSearchResultsReady(Browser browser, BrowserProfileManager.ProfileLease lease,
            JdMallConfig config, CrawlEntryOptions options, JdSearchResult result, DebugRecorder debug)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + config.getPageTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("search-results-wait");
            if (captchaHandler.isCaptchaPage(browser)) {
                if (!ensureNotBlocked(browser, lease, options, config, debug, result.getDiagnostics(), "05-search-captcha")) {
                    fail(result, CustomCrawlStatus.FAILED, "JD captcha not resolved in time on search page");
                    return false;
                }
            }
            if (entryService.needsLogin(browser, options)) {
                debug.snapshot(browser, "05-search-login-required");
                entryService.notifyLoginRequired(options, browser.getCurrentUrl(),
                        "Login required. Finish login in the opened Chrome profile, then retry.");
                CrawlEntryResult waitResult = new CrawlEntryResult();
                if (!entryService.waitLoginCompleted(browser, options, waitResult)) {
                    result.setLoginRequired(true);
                    fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                            "Login required. Finish login in the opened Chrome profile, then retry.");
                    return false;
                }
                result.setLoginRequired(false);
            }
            if (countSearchItems(browser) > 0) {
                return true;
            }
            String body = bodySnippet(browser);
            if (containsAny(body, "抱歉，没有找到与", "没有找到相关商品", "没有找到相关的商品")) {
                fail(result, CustomCrawlStatus.NOT_FOUND, "No products found for keyword: " + result.getKeyword());
                return false;
            }
            Extends.sleep(Math.max(600, config.nextStepDelayMillis() / 2));
        }
        debug.snapshot(browser, "05-search-results-timeout");
        fail(result, CustomCrawlStatus.TIMEOUT,
                "Search results not loaded in time, url=" + browser.getCurrentUrl());
        return false;
    }

    /**
     * 提交搜索后是否已到达结果页（URL 含 search.jd.com 或已出现商品卡）。
     */
    private boolean searchPageReached(Browser browser) {
        try {
            String url = browser.getCurrentUrl();
            if (url != null && url.contains("search.jd.com")) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return countSearchItems(browser) > 0;
    }

    /**
     * 拟人滚动只补当前页懒加载。两轮不增长就停，不用期望条数把后面的页一起滚出来。
     */
    private void scrollLoadMoreResults(Browser browser, JdMallConfig config, int maxRounds) throws TimeoutException {
        int lastCount = countSearchItems(browser);
        int stagnantRounds = 0;
        int rounds = Math.max(1, maxRounds);
        for (int round = 0; round < rounds; round++) {
            if (stagnantRounds >= 2) {
                return;
            }
            ensureTaskDeadline("search-scroll");
            browser.scrollPage(0, 550 + ThreadLocalRandom.current().nextInt(350));
            Extends.sleep(Math.max(400, config.nextStepDelayMillis()));
            int count = countSearchItems(browser);
            if (count > lastCount) {
                lastCount = count;
                stagnantRounds = 0;
            } else {
                stagnantRounds++;
            }
        }
    }

    /**
     * 先拿满当前页，不够再翻页。页数 = ceil(期望条数 / 第一页实际条数)。
     */
    private List<JdSearchItem> collectSearchPages(Browser browser, BrowserProfileManager.ProfileLease lease,
            JdSearchRequest request, JdMallConfig config, CrawlEntryOptions options, JdSearchResult result,
            DebugRecorder debug) throws TimeoutException {
        int maxResults = Math.max(1, request.getMaxResults());
        List<JdSearchItem> all = new ArrayList<JdSearchItem>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        int pageSize = 0;
        int page = 1;
        String previousKey = "";
        int pageCap = Math.max(1, config.getMaxSearchPages());
        while (all.size() < maxResults && page <= pageCap) {
            ensureTaskDeadline("search-page");
            if (!ensureNotBlocked(browser, lease, options, config, debug, result.getDiagnostics(),
                    "05-search-page-" + page)) {
                log.warn("JD mall search captcha not cleared, page={}", page);
                break;
            }
            if (countSearchItems(browser) < 20) {
                scrollLoadMoreResults(browser, config, 3);
            }
            List<JdSearchItem> pageItems = readSearchItems(browser, SEARCH_PAGE_READ_LIMIT);
            String key = pageKey(pageItems);
            if (pageItems.isEmpty()) {
                log.info("JD mall search page empty, page={}", page);
                break;
            }
            if (page > 1 && key.equals(previousKey)) {
                log.info("JD mall search page unchanged, page={}", page);
                break;
            }
            if (pageSize == 0) {
                pageSize = Math.max(1, pageItems.size());
            }
            int before = all.size();
            mergeJdItems(all, seen, pageItems, maxResults);
            if (page > 1 && all.size() == before) {
                log.info("JD mall search page has no new sku, page={}", page);
                break;
            }
            int pagesNeeded = Math.min(pageCap, MallSearchPages.pageCount(maxResults, pageSize));
            result.getDiagnostics().put("pageSize", pageSize);
            result.getDiagnostics().put("pagesNeeded", pagesNeeded);
            result.getDiagnostics().put("pagesFetched", page);
            log.info("JD mall search page collected, page={}, pageSize={}, pagesNeeded={}, collected={}, target={}",
                    page, pageSize, pagesNeeded, all.size(), maxResults);
            if (all.size() >= maxResults || page >= pagesNeeded) {
                break;
            }
            previousKey = key;
            if (!openNextJdSearchPage(browser, request.getKeyword(), config, page, pageSize, previousKey)) {
                break;
            }
            page++;
        }
        if (all.size() > maxResults) {
            return new ArrayList<JdSearchItem>(all.subList(0, maxResults));
        }
        return all;
    }

    private void mergeJdItems(List<JdSearchItem> all, LinkedHashSet<String> seen, List<JdSearchItem> pageItems,
            int maxResults) {
        if (pageItems == null) {
            return;
        }
        for (JdSearchItem item : pageItems) {
            if (all.size() >= maxResults) {
                return;
            }
            if (item == null || Strings.isEmpty(item.getSkuId()) || !seen.add(item.getSkuId())) {
                continue;
            }
            all.add(item);
        }
    }

    private String pageKey(List<JdSearchItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, items.size() - 3);
        for (int i = start; i < items.size(); i++) {
            if (i > start) {
                builder.append(',');
            }
            builder.append(items.get(i).getSkuId());
        }
        builder.append('#').append(items.size());
        return builder.toString();
    }

    private boolean openNextJdSearchPage(Browser browser, String keyword, JdMallConfig config, int logicalPage,
            int pageSize, String previousKey) throws TimeoutException {
        if (markNextPage(browser, "data-rx-jd-mall-next")) {
            try {
                browser.elementClick(SEARCH_NEXT_SELECTOR, false);
                Extends.sleep(Math.max(600, config.nextStepDelayMillis()));
                if (waitJdSearchPageTurned(browser, previousKey, config)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("JD mall next page click fail, error={}", e.getMessage());
            }
        } else {
            log.info("JD mall next page button not found");
        }
        int nextLogical = logicalPage + 1;
        String url = buildSearchPageUrl(keyword, config, nextLogical, pageSize);
        log.info("JD mall search next page by url, logicalPage={}", nextLogical);
        browser.navigateUrl(url, Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        return waitJdSearchPageTurned(browser, previousKey, config);
    }

    private boolean waitJdSearchPageTurned(Browser browser, String previousKey, JdMallConfig config)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.min(20000L, config.getPageTimeoutSeconds() * 1000L);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("search-page-turn");
            List<JdSearchItem> items = readSearchItems(browser, SEARCH_PAGE_READ_LIMIT);
            String key = pageKey(items);
            if (!items.isEmpty() && !key.equals(previousKey)) {
                return true;
            }
            Extends.sleep(400);
        }
        return false;
    }

    private boolean markNextPage(Browser browser, String attributeName) {
        try {
            Boolean marked = browser.executeScript(MARK_NEXT_PAGE_SCRIPT, attributeName);
            return Boolean.TRUE.equals(marked);
        } catch (Exception e) {
            log.warn("Mark next page fail, error={}", e.getMessage());
            return false;
        }
    }

    private String buildSearchPageUrl(String keyword, JdMallConfig config, int logicalPage, int pageSize) {
        int size = Math.max(1, pageSize);
        int jdPage = logicalPage * 2 - 1;
        int start = (logicalPage - 1) * size + 1;
        return buildSearchUrl(keyword, config) + "&page=" + jdPage + "&s=" + start;
    }

    private static final String MARK_NEXT_PAGE_SCRIPT =
            "var attr=arguments[0];" +
            "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
            "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
            "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
            "function disabled(el){var c=((el.className||'')+' '+(el.getAttribute('aria-disabled')||'')).toLowerCase();" +
            "return el.getAttribute('aria-disabled')==='true'||/disabled|stop-prev|forbid/.test(c);}" +
            "function text(el){return ((el.innerText||el.textContent||'')+'').replace(/\\s+/g,'').trim();}" +
            "function label(el){var own=text(el);if(own){return own;}" +
            "return ((el.getAttribute('aria-label')||el.getAttribute('title')||'')+'').replace(/\\s+/g,'').trim();}" +
            "var nodes=document.querySelectorAll('a,button,div,span');" +
            "for(var i=0;i<nodes.length;i++){var el=nodes[i];if(!visible(el)||disabled(el)){continue;}" +
            "if(label(el)==='下一页'){el.setAttribute(attr,'1');el.scrollIntoView({block:'center',inline:'center'});return true;}}" +
            "var fallback=document.querySelectorAll('[class*=\"pagination_next\"]');" +
            "for(var j=0;j<fallback.length;j++){var next=fallback[j];if(disabled(next)){continue;}" +
            "var ns=getComputedStyle(next);if(ns.display==='none'||ns.visibility==='hidden'){continue;}" +
            "next.setAttribute(attr,'1');next.scrollIntoView({block:'center',inline:'center'});return true;}" +
            "return false;";

    /**
     * 评分提取搜索结果商品卡：skuId/标题/价格/图/店铺/链接，按 sku 去重。读取失败重试一次。
     */
    private List<JdSearchItem> readSearchItems(Browser browser, int maxResults) throws TimeoutException {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Object raw = browser.executeScript(READ_SEARCH_ITEMS_SCRIPT, Math.max(1, maxResults));
                if (raw == null) {
                    return new ArrayList<JdSearchItem>();
                }
                return objectMapper.convertValue(raw, new TypeReference<List<JdSearchItem>>() {
                });
            } catch (Exception e) {
                log.warn("Read JD search items fail, attempt={}, error={}", attempt + 1, e.getMessage());
                ensureTaskDeadline("read-search-items");
                Extends.sleep(350);
            }
        }
        return new ArrayList<JdSearchItem>();
    }

    private int countSearchItems(Browser browser) {
        try {
            Object count = browser.executeScript("var nodes=document.querySelectorAll('li[data-sku],div[data-sku]');" +
                    "var seen={},count=0;" +
                    "for(var i=0;i<nodes.length;i++){var sku=nodes[i].getAttribute('data-sku');" +
                    "if(!sku||seen[sku]){continue;}seen[sku]=1;count++;}" +
                    "return count;");
            return count instanceof Number ? ((Number) count).intValue() : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 拟人在 www.jd.com / search.jd.com 搜索框输入关键字：JS 打标（id=key 优先）后原生键盘输入，
     * 未生效时兜底 script 写值 + input/change 事件（Vue/React 兼容）。
     */
    private boolean nativeSetSearchValue(Browser browser, String value) {
        Boolean marked = browser.executeScript("var attr='data-rx-jd-mall-search-input';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function ph(el){return norm(el.getAttribute('placeholder')||el.getAttribute('aria-label')||'');}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input'));" +
                "var chosen=null;" +
                // 京东搜索框固定 id=key（首页/结果页一致），最稳
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "if((e.id||'')==='key'){chosen=e;break;}}" +
                "if(!chosen){for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n)){continue;}" +
                "var m=ph(n)+(n.name||'')+(n.className||'');" +
                "if(/搜索|search/i.test(m)&&n.getBoundingClientRect().width>=160){chosen=n;break;}}}" +
                "if(!chosen){return false;}" +
                "chosen.setAttribute(attr,'1');chosen.scrollIntoView({block:'center',inline:'center'});chosen.focus();return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(SEARCH_INPUT_SELECTOR, false);
            Extends.sleep(200 + ThreadLocalRandom.current().nextInt(300));
            browser.elementPress(SEARCH_INPUT_SELECTOR, value, false);
        } catch (Exception e) {
            log.warn("Native type JD search keyword fail, fallback script set, error={}", e.getMessage());
        }
        if (searchInputEquals(browser, value)) {
            return true;
        }
        return setSearchValueByScript(browser, value) && searchInputEquals(browser, value);
    }

    private boolean setSearchValueByScript(Browser browser, String value) {
        Boolean ok = browser.executeScript("var value=String(arguments[0]||'');" +
                "var chosen=document.querySelector('[data-rx-jd-mall-search-input=\"1\"]');" +
                "if(!chosen||chosen.disabled||chosen.readOnly){return false;}" +
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
                "var el=document.querySelector('[data-rx-jd-mall-search-input=\"1\"]');" +
                "if(!el){el=document.querySelector('input#key');}" +
                "return !!el && String(el.value||'')===exp;", expected);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 打标并原生点击搜索按钮：优先输入框祖先范围内的文字「搜索」按钮（避免命中热搜词链接）。
     */
    private boolean nativeClickSearchButton(Browser browser) {
        Boolean marked = browser.executeScript("var attr='data-rx-jd-mall-search-button';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function mark(root){if(!root){return false;}var nodes=Array.prototype.slice.call(root.querySelectorAll('button,a,span,div'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],t=text(e);" +
                "if(!visible(e)||t!=='搜索'){continue;}" +
                // 2026 首页搜索栏为 React 结构：京言AI 区（innerText 恰为「搜索」）与图片搜索区都不是提交按钮，先排除
                "if(e.closest('[class*=\"ai_search\"],[class*=\"image_search\"]')){continue;}" +
                "if(e.tagName==='BUTTON'||/search[-_]btn|button/i.test(e.className||'')){e.setAttribute(attr,'1');" +
                "e.scrollIntoView({block:'center',inline:'center'});return true;}}return false;}" +
                "var input=document.querySelector('[data-rx-jd-mall-search-input=\"1\"]');" +
                "var p=input;for(var d=0;p&&d<7;d++,p=p.parentElement){if(mark(p)){return true;}}" +
                "return mark(document.body);");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(SEARCH_BUTTON_SELECTOR, false);
            return true;
        } catch (Exception e) {
            log.warn("Native click JD search button fail, error={}", e.getMessage());
            return false;
        }
    }

    private String buildSearchUrl(String keyword, JdMallConfig config) {
        String encoded = java.net.URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        return config.getSearchUrlTemplate().replace("{keyword}", encoded);
    }

    // ==================== 详情 ====================

    private JdProductDetailResult executeDetailInternal(JdProductDetailRequest rawRequest) {
        JdMallConfig config = appConfig.getCustom().getJdMall();
        JdProductDetailRequest request = normalizeDetailRequest(rawRequest, config);
        JdProductDetailResult result = createDetailResult(request);
        DebugRecorder debug = new DebugRecorder(request.getProfileName(), request.getSkuId(),
                request.getDebugEnabled(), request.getDebugOutputDir(), config, DETAIL_TASK_TYPE);
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }
        BrowserProfileManager.ProfileLease lease = null;
        // 带图片下载时任务时长加倍（下载串行 + 拟人停顿）
        int deadlineMinutes = Math.max(1, appConfig.getCustom().getMaxTaskMinutes())
                * (Boolean.TRUE.equals(request.getDownloadImages()) ? 2 : 1);
        taskDeadlineHolder.set(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(deadlineMinutes));
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(config));
            Browser browser = lease.getBrowser();

            CrawlEntryOptions options = createEntryOptions(request.getProfileName(), request.getForcePreflight(),
                    request.getKeepBrowserOpenOnLoginRequired(), config, request.getProductUrl(), DETAIL_TASK_TYPE);
            // 导航前随机停顿：如刚搜完列表页就提交详情任务，模拟人先浏览结果再点开的间隙（反爬）
            humanNavigationPause(config);
            CrawlEntryResult entry = entryService.enter(browser, lease, options);
            applyEntryResult(result, entry);
            debug.snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", result.getMessage());
                return result;
            }
            // 详情任务同样先判登录，未登录等人工，超时停止
            if (!ensureJdLoggedIn(browser, lease, config, options, debug)) {
                result.setLoginRequired(true);
                fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                        "JD login wait timeout after " + config.getLoginWaitSeconds() + "s, task stopped");
                debug.snapshotText("01-login-wait-timeout", result.getMessage());
                return result;
            }
            runDetailFlow(browser, lease, request, config, options, result, debug);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("JD mall product detail fail, skuId={}, profile={}, error={}",
                    request.getSkuId(), request.getProfileName(), e.getMessage(), e);
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            if (fallbackService != null) {
                fallbackService.finishJdDetail(result);
            }
            resultWriter.appendJsonLine(request.getOutputPath(), result);
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private void runDetailFlow(Browser browser, BrowserProfileManager.ProfileLease lease, JdProductDetailRequest request,
            JdMallConfig config, CrawlEntryOptions options, JdProductDetailResult result, DebugRecorder debug)
            throws TimeoutException {
        if (!ensureNotBlocked(browser, lease, options, config, debug, result.getDiagnostics(), "02-item")) {
            fail(result, CustomCrawlStatus.FAILED, "JD captcha not resolved in time on item page");
            return;
        }
        if (!waitItemPageReady(browser, lease, config, options, result, debug)) {
            return;
        }

        // 2026 版主信息区（标题/价格）为异步渲染：先等主信息块挂载，避免空读
        waitSummaryMounted(browser, config);
        // 挂载后先停留片刻再读取（模拟人先看标题/价格再继续操作）
        Extends.sleep(600 + ThreadLocalRandom.current().nextInt(900));
        Map<String, Object> base = readProductBase(browser);
        // 价格异步加载，短暂轮询补齐
        if (Strings.isEmpty(asString(base.get("price")))) {
            long priceDeadline = System.currentTimeMillis() + 8000L;
            while (Strings.isEmpty(asString(base.get("price"))) && System.currentTimeMillis() < priceDeadline) {
                ensureTaskDeadline("detail-price-wait");
                Extends.sleep(900 + ThreadLocalRandom.current().nextInt(400));
                base = readProductBase(browser);
            }
        }
        result.setTitle(asString(base.get("title")));
        result.setPrice(asString(base.get("price")));
        result.setShopName(asString(base.get("shopName")));
        List<String> mainImageUrls = asStringList(base.get("mainImages"));
        debug.snapshot(browser, "03-item-base-collected");
        result.setGoodRate(collectCurrentSkuGoodRate(browser, config, result, debug));

        // 滚动触发懒加载；途中渐进收集详情长图（2026 虚拟化可能卸载中途节点）
        List<String> detailImageUrls = scrollDetailAndCollect(browser, config);
        debug.snapshot(browser, "04-detail-scrolled");
        dumpImageInventory(browser, debug);

        // 滚动后主信息/相册可能才渲染完整（2026 异步 + 骨架屏），补读合并空缺
        Map<String, Object> baseAfter = readProductBase(browser);
        if (Strings.isEmpty(result.getTitle())) {
            result.setTitle(asString(baseAfter.get("title")));
        }
        if (Strings.isEmpty(result.getPrice())) {
            result.setPrice(asString(baseAfter.get("price")));
        }
        if (Strings.isEmpty(result.getShopName())) {
            result.setShopName(asString(baseAfter.get("shopName")));
        }
        mainImageUrls = mergeUniqueUrls(mainImageUrls, asStringList(baseAfter.get("mainImages")));

        // 规格参数模块在页底（2026 虚拟化），滚动后读取；必须在 iframe 导航前（导航后已离开商品页）
        if (Boolean.TRUE.equals(request.getIncludeSpecs())) {
            result.setSpecs(readProductSpecs(browser));
        }
        keepAliveUrlStore.collect("jd", browser, result.getDiagnostics());

        // 最终兜底收集一次（滚动结束才渲染的节点）
        Map<String, Object> detailScan = readDetailImageUrls(browser);
        detailImageUrls = mergeUniqueUrls(detailImageUrls, asStringList(detailScan.get("images")));
        String descriptionFrameUrl = asString(detailScan.get("descriptionFrameUrl"));
        // 描述在跨源 iframe 时，JS 读不到内容：导航到 iframe src 直接收集（主页面数据已提取完）
        if (detailImageUrls.isEmpty() && !Strings.isEmpty(descriptionFrameUrl)) {
            result.getDiagnostics().put("descriptionFrameNavigated", descriptionFrameUrl);
            browser.navigateUrl(descriptionFrameUrl, Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
            Extends.sleep(Math.max(600, config.nextStepDelayMillis()));
            detailImageUrls = readFrameBodyImages(browser);
            debug.snapshot(browser, "05-description-frame-read");
        }
        // 排除与主图重复的 URL 并截断上限
        List<String> mainKeySet = new ArrayList<String>(mainImageUrls);
        detailImageUrls = dedupeDetailImages(detailImageUrls, mainKeySet, config.getMaxDetailImages());

        if (Strings.isEmpty(result.getTitle()) && mainImageUrls.isEmpty() && detailImageUrls.isEmpty()) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD item page structure not recognized");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "03-item-structure-unknown");
            return;
        }

        result.setCurrentUrl(browser.getCurrentUrl());
        if (Boolean.TRUE.equals(request.getDownloadImages())) {
            result.setMainImages(downloadImages(browser, request.getSkuId(), mainImageUrls, "main", config, result.getDiagnostics()));
            result.setDetailImages(downloadImages(browser, request.getSkuId(), detailImageUrls, "detail", config, result.getDiagnostics()));
        } else {
            result.setMainImages(toImageInfos(mainImageUrls));
            result.setDetailImages(toImageInfos(detailImageUrls));
        }
        debug.snapshotText("06-images-collected", "mainImages=" + result.getMainImages().size()
                + ", detailImages=" + result.getDetailImages().size());
        List<String> detailErrors = JdResultChecks.detail(result, Boolean.TRUE.equals(request.getIncludeSpecs()));
        if (!detailErrors.isEmpty()) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD item detail missing required fields");
            result.getDiagnostics().put("validationErrors", detailErrors);
            debug.snapshot(browser, "06-detail-invalid");
            return;
        }
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("Product detail captured, mainImages=" + result.getMainImages().size()
                + ", detailImages=" + result.getDetailImages().size()
                + ", goodRate=" + result.getGoodRate());
    }

    private boolean waitItemPageReady(Browser browser, BrowserProfileManager.ProfileLease lease, JdMallConfig config,
            CrawlEntryOptions options, JdProductDetailResult result, DebugRecorder debug) throws TimeoutException {
        long deadline = System.currentTimeMillis() + config.getPageTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("item-page-wait");
            if (captchaHandler.isCaptchaPage(browser)) {
                if (!ensureNotBlocked(browser, lease, options, config, debug, result.getDiagnostics(), "02-item-captcha")) {
                    fail(result, CustomCrawlStatus.FAILED, "JD captcha not resolved in time on item page");
                    return false;
                }
            }
            if (entryService.needsLogin(browser, options)) {
                debug.snapshot(browser, "02-item-login-required");
                entryService.notifyLoginRequired(options, browser.getCurrentUrl(),
                        "Login required. Finish login in the opened Chrome profile, then retry.");
                CrawlEntryResult waitResult = new CrawlEntryResult();
                if (!entryService.waitLoginCompleted(browser, options, waitResult)) {
                    result.setLoginRequired(true);
                    fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                            "Login required. Finish login in the opened Chrome profile, then retry.");
                    return false;
                }
                result.setLoginRequired(false);
            }
            String body = bodySnippet(browser);
            if (containsAny(body, "商品介绍", "加入购物车", "选择规格", "商品详情", "规格参数", "售后保障", "关注店铺", "进店逛逛")) {
                return true;
            }
            try {
                Boolean mounted = browser.executeScript(
                        "return !!document.querySelector('.sku-title-name,.sku-name,[class*=\"price-root\"],.summary-price,.product-price--main,#spec-list,.image-carousel-track');");
                if (Boolean.TRUE.equals(mounted)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            if (containsAny(body, "您访问的页面不存在", "页面不存在", "商品已下柜", "很抱歉，您访问的页面")) {
                fail(result, CustomCrawlStatus.NOT_FOUND, "JD item page not available, skuId=" + result.getSkuId());
                return false;
            }
            Extends.sleep(Math.max(600, config.nextStepDelayMillis() / 2));
        }
        fail(result, CustomCrawlStatus.TIMEOUT, "JD item page not loaded in time");
        debug.snapshotText("02-item-page-timeout", result.getMessage());
        return false;
    }

    /**
     * 页面导航前随机停顿（navigationPauseMillis + 随机加成）：模拟人在两次操作间的浏览间隙
     * （如搜索完成→点开商品详情页之间），避免任务衔接即导航的机器节奏。
     */
    private void humanNavigationPause(JdMallConfig config) {
        long base = Math.max(0, config.getNavigationPauseMillis());
        int rnd = Math.max(0, config.getNavigationPauseRandomMillis());
        long delay = base + (rnd > 0 ? ThreadLocalRandom.current().nextLong(rnd) : 0L);
        if (delay > 0) {
            Extends.sleep(delay);
        }
    }

    /**
     * 打开「买家赞不绝口」，点「当前商品」，再读上方选中类型的好评度。
     * 入口上的汇总百分比不能直接当当前商品的好评度。
     */
    private String collectCurrentSkuGoodRate(Browser browser, JdMallConfig config, JdProductDetailResult result,
            DebugRecorder debug) throws TimeoutException {
        if (!openReviewPanel(browser, config)) {
            log.info("JD review panel not opened, skuId={}", result.getSkuId());
            return "";
        }
        debug.snapshot(browser, "03b-review-panel");
        String before = reviewRate(readReviewPanel(browser));
        if (!markText(browser, "data-rx-jd-current-sku", "exact", "当前商品", 8)) {
            log.info("JD current sku tab not found, skuId={}", result.getSkuId());
            closeReviewPanel(browser);
            return "";
        }
        try {
            browser.elementClick(CURRENT_SKU_SELECTOR, false);
        } catch (Exception e) {
            log.warn("JD current sku click fail, error={}", e.getMessage());
            closeReviewPanel(browser);
            return "";
        }
        long clickedAt = System.currentTimeMillis();
        Extends.sleep(Math.max(500, config.nextStepDelayMillis()));
        String rate = "";
        long deadline = clickedAt + 8000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("review-rate");
            Map<String, Object> panel = readReviewPanel(browser);
            rate = reviewRate(panel);
            boolean selected = Boolean.TRUE.equals(panel.get("currentSelected"));
            result.getDiagnostics().put("goodRateRaw", panel.get("raw"));
            result.getDiagnostics().put("currentSkuSelected", selected);
            if (selected && !rate.isEmpty()
                    && (!rate.equals(before) || System.currentTimeMillis() - clickedAt > 1600L)) {
                result.getDiagnostics().put("goodRateScope", "currentSku");
                break;
            }
            rate = "";
            Extends.sleep(400);
        }
        debug.snapshot(browser, "03c-current-sku-rate");
        closeReviewPanel(browser);
        log.info("JD current sku good rate, skuId={}, rate={}", result.getSkuId(), rate);
        return rate;
    }

    private boolean openReviewPanel(Browser browser, JdMallConfig config) throws TimeoutException {
        for (int attempt = 0; attempt < 2; attempt++) {
            ensureTaskDeadline("review-panel");
            if (!markText(browser, "data-rx-jd-praise-entry", "contains", "赞不绝口", 32)) {
                if (attempt == 0 && markText(browser, "data-rx-jd-review-tab", "exact", "大家评", 8)) {
                    try {
                        browser.elementClick(REVIEW_TAB_SELECTOR, false);
                    } catch (Exception e) {
                        log.warn("JD review tab click fail, error={}", e.getMessage());
                    }
                    Extends.sleep(Math.max(500, config.nextStepDelayMillis()));
                } else if (attempt == 0) {
                    browser.scrollPage(0, 640 + ThreadLocalRandom.current().nextInt(240));
                    Extends.sleep(Math.max(400, config.nextStepDelayMillis()));
                }
                continue;
            }
            try {
                browser.elementClick(PRAISE_ENTRY_SELECTOR, false);
            } catch (Exception e) {
                log.warn("JD praise entry click fail, error={}", e.getMessage());
                return false;
            }
            Extends.sleep(Math.max(500, config.nextStepDelayMillis()));
            long deadline = System.currentTimeMillis() + 8000L;
            while (System.currentTimeMillis() < deadline) {
                ensureTaskDeadline("review-panel-wait");
                if (Boolean.TRUE.equals(readReviewPanel(browser).get("open"))) {
                    return true;
                }
                Extends.sleep(400);
            }
        }
        return false;
    }

    private void closeReviewPanel(Browser browser) {
        try {
            browser.pressKey("Escape");
            Extends.sleep(300);
        } catch (Exception e) {
            log.debug("Close JD review panel ignored, error={}", e.getMessage());
        }
    }

    private String reviewRate(Map<String, Object> panel) {
        if (panel == null) {
            return "";
        }
        String rate = asString(panel.get("rate"));
        if (!Strings.isEmpty(rate)) {
            return rate;
        }
        String raw = asString(panel.get("raw"));
        if (Strings.isEmpty(raw)) {
            return "";
        }
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?%好评)").matcher(raw.replace(" ", ""));
        return matcher.find() ? matcher.group(1) : "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readReviewPanel(Browser browser) {
        try {
            Object raw = browser.executeScript(READ_REVIEW_PANEL_SCRIPT);
            if (raw instanceof Map) {
                return (Map<String, Object>) raw;
            }
        } catch (Exception e) {
            log.warn("Read JD review panel fail, error={}", e.getMessage());
        }
        return new LinkedHashMap<String, Object>();
    }

    private boolean markText(Browser browser, String attributeName, String mode, String needle, int maxLen) {
        try {
            Boolean marked = browser.executeScript(MARK_TEXT_SCRIPT, attributeName, mode, needle, maxLen);
            return Boolean.TRUE.equals(marked);
        } catch (Exception e) {
            log.warn("Mark text fail, needle={}, error={}", needle, e.getMessage());
            return false;
        }
    }

    private static final String MARK_TEXT_SCRIPT =
            "var attr=arguments[0],mode=arguments[1],needle=String(arguments[2]||''),maxLen=+arguments[3]||40;" +
            "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
            "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
            "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
            "function text(el){return ((el.innerText||el.textContent||'')+'').replace(/\\s+/g,'').trim();}" +
            "var nodes=document.querySelectorAll('a,button,div,span,li');" +
            "var best=null,bestLen=999;" +
            "for(var i=0;i<nodes.length;i++){var el=nodes[i];if(!visible(el)){continue;}" +
            "var t=text(el);if(!t||t.length>maxLen||t.length>=bestLen){continue;}" +
            "if(mode==='exact'?t===needle:t.indexOf(needle)>=0){best=el;bestLen=t.length;}}" +
            "if(!best){return false;}" +
            "best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});return true;";

    private static final String READ_REVIEW_PANEL_SCRIPT =
            "function norm(s){return ((s||'')+'').replace(/\\s+/g,'').trim();}" +
            "function visible(el){if(!el){return false;}var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
            "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
            "function text(el){return norm(el.innerText||el.textContent||'');}" +
            "function selectedOne(el){var c=((el.className||'')+' '+(el.getAttribute('aria-selected')||'')+' '" +
            "+(el.getAttribute('aria-current')||'')+' '+(el.getAttribute('aria-pressed')||'')).toLowerCase();" +
            "if(el.getAttribute('aria-selected')==='true'||el.getAttribute('aria-pressed')==='true'){return true;}" +
            "if(/(^|[^a-z])(active|selected|current|checked)([^a-z]|$)|is-active|tab-on|item-on/.test(c)){return true;}" +
            "var color=getComputedStyle(el).color||'';var m=color.match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)/);" +
            "if(m){var r=+m[1],g=+m[2],b=+m[3];if(r>160&&r>g+30&&r>b+30){return true;}}return false;}" +
            "function selected(el){if(selectedOne(el)){return true;}var p=el.parentElement;" +
            "return !!(p&&text(p).length<=12&&selectedOne(p));}" +
            "var nodes=document.querySelectorAll('div,section,aside');" +
            "var panel=null,best=1e15;" +
            "for(var i=0;i<nodes.length;i++){var el=nodes[i];if(!visible(el)){continue;}" +
            "var t=text(el);if(t.indexOf('商品评价')<0||t.indexOf('当前商品')<0){continue;}" +
            "var r=el.getBoundingClientRect();if(r.width<240||r.height<160){continue;}" +
            "var a=r.width*r.height;if(a<best){best=a;panel=el;}}" +
            "if(!panel){return {open:false,rate:'',raw:'',currentSelected:false};}" +
            "var chips=panel.querySelectorAll('a,button,div,span,li');" +
            "var raw='',rateLen=999,tabSelected=false;" +
            "for(var j=0;j<chips.length;j++){var c=chips[j];if(!visible(c)){continue;}" +
            "var ct=text(c);" +
            "if(ct==='当前商品'){tabSelected=selected(c);}" +
            "if(ct.length<=16&&/全部\\d+(?:\\.\\d+)?%好评/.test(ct)&&ct.length<rateLen){raw=ct;rateLen=ct.length;}}" +
            "if(!raw){for(var k=0;k<chips.length;k++){var d=chips[k];if(!visible(d)){continue;}" +
            "var dt=text(d);if(dt.length<=16&&/\\d+(?:\\.\\d+)?%好评/.test(dt)&&dt.length<rateLen){raw=dt;rateLen=dt.length;}}}" +
            "var found=raw.match(/(\\d+(?:\\.\\d+)?%好评)/);" +
            "return {open:true,rate:found?found[1]:'',raw:raw,currentSelected:tabSelected};";

    /**
     * 提取详情基础信息：标题/价格/店铺/主图相册（2026 .sku-title-name/.image-area，旧版 .sku-name/#spec-list）。
     */
    private Map<String, Object> readProductBase(Browser browser) {
        Map<String, Object> raw = browser.executeScript(READ_PRODUCT_BASE_SCRIPT);
        return raw == null ? new LinkedHashMap<String, Object>() : raw;
    }

    /**
     * 等主信息块（标题/价格组件）挂载，最多等 pageTimeoutSeconds 的一半；超时不阻断，由后续空值兜底。
     */
    private void waitSummaryMounted(Browser browser, JdMallConfig config) {
        long deadline = System.currentTimeMillis() + Math.max(8000, config.getPageTimeoutSeconds() * 500L);
        while (System.currentTimeMillis() < deadline) {
            try {
                Boolean mounted = browser.executeScript(
                        "return !!document.querySelector('.sku-title-name,.sku-name,[class*=\"price-root\"],.summary-price');");
                if (Boolean.TRUE.equals(mounted)) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Extends.sleep(800 + ThreadLocalRandom.current().nextInt(400));
        }
        log.warn("JD item summary block not mounted in time, url={}", safeCurrentUrl(browser));
    }

    /**
     * debug：落盘页面图片清单（可见性/自然尺寸/类路径/URL），长图缺失时据此排查页面结构。
     */
    private void dumpImageInventory(Browser browser, DebugRecorder debug) {
        if (!debug.enabled()) {
            return;
        }
        try {
            Object inventory = browser.executeScript(IMAGE_INVENTORY_SCRIPT);
            debug.snapshotText("04b-image-inventory", String.valueOf(inventory));
        } catch (Exception e) {
            debug.snapshotText("04b-image-inventory", "inventory fail: " + e.getMessage());
        }
    }

    private String safeCurrentUrl(Browser browser) {
        try {
            return browser.getCurrentUrl();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 先把商品详情长图滚到底，再额外往下两下后停。不跟着看了又看继续加载。
     * 每 3 步收集一次详情长图（虚拟化楼层中途会被卸载）。
     */
    private List<String> scrollDetailAndCollect(Browser browser, JdMallConfig config) throws TimeoutException {
        List<String> collected = new ArrayList<String>();
        int stagnant = 0;
        int scrolled = 0;
        int tailLeft = 0;
        boolean detailDone = false;
        String stopReason = "max-steps";
        for (int i = 0; i < config.getDetailScrollSteps(); i++) {
            ensureTaskDeadline("detail-scroll");
            if (detailDone) {
                if (tailLeft <= 0) {
                    stopReason = stopReason + "-tail";
                    break;
                }
                browser.scrollPage(0, 520 + ThreadLocalRandom.current().nextInt(300));
                scrolled++;
                tailLeft--;
                Extends.sleep(400 + ThreadLocalRandom.current().nextInt(500));
                if (scrolled % 3 == 0) {
                    Map<String, Object> scan = readDetailImageUrls(browser);
                    collected = mergeUniqueUrls(collected, asStringList(scan.get("images")));
                }
                continue;
            }
            Map<String, Object> probe = probeDetailScroll(browser);
            int room = roomPx(probe);
            String reason = asString(probe.get("reason"));
            boolean reached = Boolean.TRUE.equals(probe.get("reached")) || (room >= 0 && room <= 24);
            // 详情节点还没挂上时，文档底部可能只是当前已渲染高度，先滚一步把长图懒加载出来
            if (reached && ("detail-main".equals(reason) || ("before-related".equals(reason) && scrolled > 0))) {
                Extends.sleep(350 + ThreadLocalRandom.current().nextInt(250));
                Map<String, Object> again = probeDetailScroll(browser);
                int roomAgain = roomPx(again);
                String reasonAgain = asString(again.get("reason"));
                if ("detail-main".equals(reasonAgain) && roomAgain > 80 && !Boolean.TRUE.equals(again.get("reached"))) {
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
            int human = 600 + ThreadLocalRandom.current().nextInt(400);
            int delta = human;
            if (room >= 0) {
                delta = room <= 280 ? Math.max(20, room / 3) : Math.min(human, Math.max(40, room - 40));
            }
            Object before = pageOffset(browser);
            browser.scrollPage(0, delta);
            scrolled++;
            Extends.sleep(400 + ThreadLocalRandom.current().nextInt(500));
            if (scrolled % 3 == 0) {
                Map<String, Object> scan = readDetailImageUrls(browser);
                collected = mergeUniqueUrls(collected, asStringList(scan.get("images")));
            }
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
        log.info("JD detail scroll stop, reason={}, steps={}, tailLeft={}", stopReason, scrolled, tailLeft);
        return collected;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> probeDetailScroll(Browser browser) {
        try {
            Object raw = browser.executeScript(DETAIL_SCROLL_PROBE_SCRIPT);
            if (raw instanceof Map) {
                return (Map<String, Object>) raw;
            }
        } catch (Exception e) {
            log.warn("JD detail scroll probe fail, error={}", e.getMessage());
        }
        return new LinkedHashMap<String, Object>();
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

    /**
     * 按 jfs 路径键合并去重两个 URL 列表，保持首个列表的顺序。
     */
    private List<String> mergeUniqueUrls(List<String> first, List<String> second) {
        List<String> out = new ArrayList<String>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String url : first) {
            if (seen.add(imageKey(url))) {
                out.add(url);
            }
        }
        for (String url : second) {
            if (seen.add(imageKey(url))) {
                out.add(url);
            }
        }
        return out;
    }

    /**
     * 收集详情长图 URL（详情容器内 + 懒加载属性），并探测跨源描述 iframe 的 src。
     */
    private Map<String, Object> readDetailImageUrls(Browser browser) {
        Map<String, Object> raw = browser.executeScript(READ_DETAIL_IMAGES_SCRIPT);
        return raw == null ? new LinkedHashMap<String, Object>() : raw;
    }

    /**
     * 描述 iframe 页（纯图片列表页）body 内图片收集。
     */
    private List<String> readFrameBodyImages(Browser browser) {
        Object raw = browser.executeScript("function fix(u){u=String(u||'');if(!u){return '';}" +
                "if(u.indexOf('//')===0){return 'https:'+u;}return u;}" +
                "var imgs=document.querySelectorAll('img');" +
                "var seen={},out=[];" +
                "for(var i=0;i<imgs.length;i++){var u=fix(imgs[i].currentSrc||imgs[i].src||imgs[i].getAttribute('data-src')||'');" +
                "if(!u||u.indexOf('360buyimg.com')<0){continue;}" +
                "if(/\\.gif|loading|placeholder/i.test(u)){continue;}" +
                "if(seen[u]){continue;}seen[u]=1;out.push(u);}" +
                "return out;");
        return raw instanceof List ? objectMapper.convertValue(raw, new TypeReference<List<String>>() {
        }) : new ArrayList<String>();
    }

    /**
     * 规格参数提取：2026 版位于 div.attribute（display:none，必须用 title/textContent）内——
     * .list .item 行（.label .text 名称 + .value 值）为主，.highlight-attrs .item（.title 值 + .desc .text 名称）补充；
     * 注意 class="parameter" 是主图轮播里的「规格参数」导航卡，不是规格表；旧版 .Ptable dl/dt/dd 兜底。
     */
    private List<JdProductSpecItem> readProductSpecs(Browser browser) {
        try {
            Object raw = browser.executeScript("var out=[],names={};" +
                    "function n(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                    "function add(nm,vl){if(nm&&vl&&!names[nm]){names[nm]=1;out.push({name:nm,value:vl});}}" +
                    // 2026：div.attribute > div.list > div.item（隐藏区，innerText 为空，用 title/textContent）
                    "var rows=document.querySelectorAll('.attribute .list .item');" +
                    "for(var i=0;i<rows.length;i++){var r=rows[i];" +
                    "var lb=r.querySelector('.label .text')||r.querySelector('.label');" +
                    "var vl=r.querySelector('.value');" +
                    "if(!lb||!vl){continue;}" +
                    "add(n(lb.getAttribute('title')||lb.textContent),n(vl.getAttribute('title')||vl.textContent));}" +
                    // 2026 精选参数：div.attribute > div.highlight-attrs > div.item（.title=值，.desc .text=名称）
                    "var his=document.querySelectorAll('.attribute .highlight-attrs .item');" +
                    "for(var j=0;j<his.length;j++){var h=his[j];" +
                    "var t=h.querySelector('.title'),dn=h.querySelector('.desc .text');" +
                    "if(!t||!dn){continue;}" +
                    "add(n(dn.getAttribute('title')||dn.textContent),n(t.getAttribute('title')||t.textContent));}" +
                    // 旧版 Ptable
                    "if(!out.length){var groups=document.querySelectorAll('.Ptable-item,.Ptable .clearfix');" +
                    "for(var g=0;g<groups.length;g++){var dls=groups[g].querySelectorAll('dl');" +
                    "for(var d=0;d<dls.length;d++){var dt=dls[d].querySelector('dt'),dd=dls[d].querySelector('dd');" +
                    "if(!dt||!dd){continue;}" +
                    "var name2=(dt.innerText||'').replace(/\\s+/g,' ').trim(),value2=(dd.innerText||'').replace(/\\s+/g,' ').trim();" +
                    "if(name2&&value2){out.push({name:name2,value:value2});}}}}" +
                    "return out;");
            if (raw == null) {
                return new ArrayList<JdProductSpecItem>();
            }
            return objectMapper.convertValue(raw, new TypeReference<List<JdProductSpecItem>>() {
            });
        } catch (Exception e) {
            log.warn("Read JD product specs fail, error={}", e.getMessage());
            return new ArrayList<JdProductSpecItem>();
        }
    }

    private List<String> dedupeDetailImages(List<String> detailUrls, List<String> excludeUrls, int max) {
        List<String> out = new ArrayList<String>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String exclude : excludeUrls) {
            seen.add(imageKey(exclude));
        }
        for (String url : detailUrls) {
            if (Strings.isEmpty(url)) {
                continue;
            }
            // 排除主图缩略规格（/s54x54_jfs/、/s450x450_jfs/ 等尺寸段），详情长图不会是小方图
            if (url.matches(".*/s\\d+x\\d+[^/]*/.*")) {
                continue;
            }
            String key = imageKey(url);
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            out.add(url);
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    private String imageKey(String url) {
        Matcher m = Pattern.compile("jfs/[^\\s?]+").matcher(url == null ? "" : url);
        return m.find() ? m.group() : String.valueOf(url);
    }

    /**
     * 走浏览器上下文串行下载图片（带 cookie/UA，与页面请求一致），单张失败跳过不失败整个任务。
     */
    private List<JdImageInfo> downloadImages(Browser browser, String skuId, List<String> urls, String subDir,
            JdMallConfig config, Map<String, Object> diagnostics) throws TimeoutException {
        List<JdImageInfo> out = new ArrayList<JdImageInfo>();
        if (urls == null || urls.isEmpty()) {
            return out;
        }
        List<String> failures = new ArrayList<String>();
        Path dir = Paths.get(config.getImageSaveDir(), skuId, subDir);
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
                JdImageInfo info = new JdImageInfo();
                info.setUrl(url);
                info.setLocalPath(file.toString());
                info.setFileName(fileName);
                out.add(info);
            } catch (Exception e) {
                log.warn("Download JD image fail, url={}, error={}", url, e.getMessage());
                failures.add(url + " -> " + e.getMessage());
            }
            if (index < urls.size()) {
                Extends.sleep(config.getDownloadDelayMillis() + ThreadLocalRandom.current().nextInt(600));
            }
        }
        if (!failures.isEmpty()) {
            diagnostics.put("downloadFailures-" + subDir, failures);
        }
        return out;
    }

    private List<JdImageInfo> toImageInfos(List<String> urls) {
        List<JdImageInfo> out = new ArrayList<JdImageInfo>();
        for (String url : urls) {
            JdImageInfo info = new JdImageInfo();
            info.setUrl(url);
            out.add(info);
        }
        return out;
    }

    private String imageExtension(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.contains(".png")) {
            return ".png";
        }
        if (lower.contains(".webp")) {
            return ".webp";
        }
        if (lower.contains(".avif")) {
            return ".avif";
        }
        return ".jpg";
    }

    // ==================== 公共 ====================

    /**
     * 验证码处理：先 JDJRV 自动拟人拖拽；仍失败通知 + keepOpen + 等人工。
     */
    /**
     * 京东登录态检测（双标记 + DOM 兜底）：
     * passport 登录会话 cookie 为 pt_key/pt_pin（HttpOnly）；
     * 扫码/APP 授权会话为 thor + pin/unick，没有 pt_key —— 只查 pt_key 会误判"已登录但检测不到"。
     * cookie 标记都未命中时，看页头 #ttbar-login 是否仍是「你好，请登录」。
     */
    private boolean isJdLoggedIn(Browser browser) {
        String url = "";
        String body = "";
        String rawCookie = "";
        try {
            url = browser.getCurrentUrl();
        } catch (Exception e) {
            log.debug("Read JD url fail, error={}", e.getMessage());
        }
        try {
            rawCookie = browser.saveCookies(false);
        } catch (Exception e) {
            log.warn("Check JD login cookie fail, error={}", e.getMessage());
        }
        try {
            Object text = browser.executeScript("var b=document.body;return b?(b.innerText||'').slice(0,2000):'';");
            body = text == null ? "" : String.valueOf(text);
        } catch (Exception e) {
            log.debug("Read JD body fail, error={}", e.getMessage());
        }
        if (JdLoginState.mallLoginWall(url, body)) {
            return false;
        }
        if (JdLoginState.mallReady(url, body, rawCookie)) {
            return true;
        }
        try {
            Object domLoggedIn = browser.executeScript(
                    "var nick=document.querySelector('#ttbar-login .nickname,.nickname');" +
                    "if(nick){var t=(nick.innerText||'').trim();if(t&&t!=='请登录'&&t!=='免费注册'){return true;}}" +
                    "var box=document.querySelector('#ttbar-login');" +
                    "if(box){var b=(box.innerText||'');return b.indexOf('请登录')<0&&b.trim().length>0;}" +
                    "return false;");
            return Boolean.TRUE.equals(domLoggedIn);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 进入京东页先判断是否登录；未登录推送通知并保留浏览器等待人工登录；
     * 超过 loginWaitSeconds（默认 180s）仍未登录返回 false，任务以 LOGIN_REQUIRED 停止。
     */
    private boolean ensureJdLoggedIn(Browser browser, BrowserProfileManager.ProfileLease lease,
            JdMallConfig config, CrawlEntryOptions options, DebugRecorder debug) {
        if (isJdLoggedIn(browser)) {
            return true;
        }
        log.warn("JD mall not logged in, wait manual login up to {}s, url={}",
                config.getLoginWaitSeconds(), browser.getCurrentUrl());
        debug.snapshot(browser, "01-login-wait");
        entryService.notifyLoginRequired(options, browser.getCurrentUrl(),
                "JD mall task requires login. Please login in the opened Chrome window.");
        lease.keepOpen(config.getKeepBrowserOpenSecondsOnLoginRequired());
        long deadline = System.currentTimeMillis() + config.getLoginWaitSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Extends.sleep(Math.max(1000, config.nextStepDelayMillis()));
            if (isJdLoggedIn(browser)) {
                log.info("JD mall manual login completed");
                return true;
            }
        }
        log.warn("JD mall manual login wait timeout after {}s", config.getLoginWaitSeconds());
        return false;
    }

    private boolean ensureNotBlocked(Browser browser, BrowserProfileManager.ProfileLease lease, CrawlEntryOptions options,
            JdMallConfig config, DebugRecorder debug, Map<String, Object> diagnostics, String stepTag) {
        if (!captchaHandler.isCaptchaPage(browser)) {
            return true;
        }
        diagnostics.put("captchaDetected", true);
        debug.snapshot(browser, stepTag + "-captcha-detected");
        boolean auto = captchaHandler.checkAndHandle(browser, stepTag, config.getCaptchaMaxAutoAttempts(),
                config.nextStepDelayMillis(), debug::snapshot);
        if (auto) {
            diagnostics.put("captchaAutoResolved", true);
            return true;
        }
        diagnostics.put("captchaAutoResolved", false);
        entryService.notifyLoginRequired(options, browser.getCurrentUrl(),
                "JD captcha needs manual handling. Finish it in the opened Chrome window.");
        lease.keepOpen(config.getKeepBrowserOpenSecondsOnLoginRequired());
        long deadline = System.currentTimeMillis() + config.getCaptchaHumanWaitSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Extends.sleep(Math.max(1000, config.nextStepDelayMillis()));
            if (!captchaHandler.isCaptchaPage(browser)) {
                diagnostics.put("captchaManualResolved", true);
                return true;
            }
        }
        return false;
    }

    @SneakyThrows
    private WebBrowserConfig createBrowserConfig(JdMallConfig config) {
        WebBrowserConfig browserConfig = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
        browserConfig.setProfileDataPath(profileManager.resolveProfileDataPath(config.getProfileName()));
        browserConfig.setHeadless(config.isHeadless());
        browserConfig.setFingerprintEnabled(config.isFingerprintEnabled());
        browserConfig.setFingerprintHeadless(config.isHeadless());
        if (Strings.isEmpty(browserConfig.getConfigureScriptExecutorType())) {
            browserConfig.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        }
        return browserConfig;
    }

    private CrawlEntryOptions createEntryOptions(String profileName, Boolean forcePreflight, Boolean keepBrowserOpen,
            JdMallConfig config, String initialUrl, String taskType) {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setTaskType(taskType);
        options.setProfileName(profileName);
        options.setPreflightEnabled(config.isPreflightEnabled());
        options.setPreflightUrl(config.getPreflightUrl());
        options.setPreflightStrict(config.isPreflightStrict());
        options.setPreflightCacheMinutes(config.getPreflightCacheMinutes());
        options.setForcePreflight(forcePreflight == null ? config.isForcePreflight() : forcePreflight);
        options.setInitialUrl(initialUrl);
        options.setLoginUrlPrefix(config.getLoginUrlPrefix());
        options.setInitialPageTimeoutSeconds(config.getInitialPageTimeoutSeconds());
        options.setLoginWaitSeconds(config.getLoginWaitSeconds());
        options.setStepDelayMillis(config.getStepDelayMillis());
        options.setStepDelayRandomMillis(config.getStepDelayRandomMillis());
        options.setKeepBrowserOpenOnLoginRequired(keepBrowserOpen == null
                ? config.isKeepBrowserOpenOnLoginRequired() : keepBrowserOpen);
        options.setKeepBrowserOpenSecondsOnLoginRequired(config.getKeepBrowserOpenSecondsOnLoginRequired());
        options.setLoginRequiredUrlMatcher(url -> isLoginRequired(url, config));
        options.setLoginRequiredBodyMatcher(this::looksLikeJdMallLoginBody);
        options.setLoggedInUrlMatcher(this::isJdMallPageUrl);
        return options;
    }

    private boolean isLoginRequired(String currentUrl, JdMallConfig config) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        return currentUrl.startsWith(config.getLoginUrlPrefix())
                || lower.contains("passport.jd.com")
                || lower.contains("/login");
    }

    /**
     * 页面正文是否像京东登录页：首页未登录态只有「你好，请登录」入口文案，
     * 必须命中 2 个登录页强特征（扫码登录/密码登录/短信验证码登录/忘记密码）才算。
     */
    public boolean looksLikeJdMallLoginBody(String body) {
        return JdLoginState.looksLikeMallLoginBody(body);
    }

    public boolean isJdMallPageUrl(String url) {
        if (Strings.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("www.jd.com") || lower.contains("search.jd.com") || lower.contains("item.jd.com");
    }

    private JdSearchRequest normalizeSearchRequest(JdSearchRequest request, JdMallConfig config) {
        if (request == null) {
            request = new JdSearchRequest();
        }
        if (request.getMaxResults() == null || request.getMaxResults() <= 0) {
            request.setMaxResults(config.getDefaultMaxResults());
        }
        int limit = config.getMaxResultsLimit() > 0 ? config.getMaxResultsLimit() : 100;
        if (request.getMaxResults() > limit) {
            request.setMaxResults(limit);
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

    private JdProductDetailRequest normalizeDetailRequest(JdProductDetailRequest request, JdMallConfig config) {
        if (request == null) {
            request = new JdProductDetailRequest();
        }
        if (Strings.isEmpty(request.getSkuId()) && !Strings.isEmpty(request.getProductUrl())) {
            Matcher matcher = ITEM_URL_SKU_PATTERN.matcher(request.getProductUrl());
            if (matcher.find()) {
                request.setSkuId(matcher.group(1));
            }
        }
        if (Strings.isEmpty(request.getProductUrl()) && !Strings.isEmpty(request.getSkuId())) {
            request.setProductUrl(config.getItemUrlTemplate().replace("{skuId}", request.getSkuId()));
        }
        if (Strings.isEmpty(request.getSkuId()) || Strings.isEmpty(request.getProductUrl())) {
            throw new InvalidException("skuId or productUrl is required");
        }
        if (request.getDownloadImages() == null) {
            request.setDownloadImages(true);
        }
        if (request.getIncludeSpecs() == null) {
            request.setIncludeSpecs(true);
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

    private JdSearchResult createSearchResult(JdSearchRequest request) {
        JdSearchResult result = new JdSearchResult();
        result.setTaskType(TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setKeyword(request.getKeyword());
        result.setProfileName(request.getProfileName());
        return result;
    }

    private JdProductDetailResult createDetailResult(JdProductDetailRequest request) {
        JdProductDetailResult result = new JdProductDetailResult();
        result.setTaskType(DETAIL_TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setSkuId(request.getSkuId());
        result.setProductUrl(request.getProductUrl());
        result.setProfileName(request.getProfileName());
        return result;
    }

    private void applyEntryResult(JdSearchResult result, CrawlEntryResult entry) {
        result.setFingerprintPassed(entry.isFingerprintPassed());
        result.setCurrentUrl(entry.getCurrentUrl());
        result.setLoginRequired(entry.isLoginRequired());
        result.getDiagnostics().putAll(entry.getDiagnostics());
        if (!entry.isPassed()) {
            fail(result, entry.getStatus(), entry.getMessage());
        }
    }

    private void applyEntryResult(JdProductDetailResult result, CrawlEntryResult entry) {
        result.setFingerprintPassed(entry.isFingerprintPassed());
        result.setCurrentUrl(entry.getCurrentUrl());
        result.setLoginRequired(entry.isLoginRequired());
        result.getDiagnostics().putAll(entry.getDiagnostics());
        if (!entry.isPassed()) {
            fail(result, entry.getStatus(), entry.getMessage());
        }
    }

    private void fail(JdSearchResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message);
    }

    private void fail(JdProductDetailResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message);
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

    private void ensureTaskDeadline(String stage) throws TimeoutException {
        Long deadline = taskDeadlineHolder.get();
        if (deadline != null && System.currentTimeMillis() > deadline) {
            throw new TimeoutException("JD mall task timeout at " + stage);
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object value) {
        if (value instanceof List) {
            return (List<String>) value;
        }
        return new ArrayList<String>();
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
                    Extends.sleep(500L * (i + 1));
                    continue;
                }
                return "";
            }
        }
        if (Strings.isEmpty(body) || body.length() <= 2000) {
            return body;
        }
        return body.substring(0, 2000);
    }

    // ==================== 提取脚本 ====================

    private static final String READ_SEARCH_ITEMS_SCRIPT =
            "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
            "function fix(u){u=String(u||'');if(!u){return '';}" +
            "if(u.indexOf('//')===0){return 'https:'+u;}" +
            "if(u.indexOf('http')!==0&&u.indexOf('/')===0){return 'https:'+u;}" +
            "return u;}" +
            "var max=parseInt(arguments[0],10)||60;" +
            "var seen={},out=[];" +
            "var nodes=document.querySelectorAll('li.gl-item,li[data-sku],div[data-sku]');" +
            "for(var i=0;i<nodes.length&&out.length<max;i++){" +
            "  var e=nodes[i];" +
            "  var sku=e.getAttribute('data-sku');" +
            "  if(!sku||seen[sku]){continue;}" +
            "  var titleEl=e.querySelector('[class*=\"goods_title\"] span[title],.p-name a em,.p-name a');" +
            "  var title='';" +
            "  if(titleEl){title=norm(titleEl.getAttribute('title')||titleEl.innerText||titleEl.textContent);}" +
            // 2026 版商品卡：卡片内首个带 title 的节点是图片容器，title 即完整商品名
            "  if(!title){var anyTitle=e.querySelector('[title]');" +
            "    if(anyTitle){title=norm(anyTitle.getAttribute('title')||'');}}" +
            // 2026 版价格：¥ 在 <i>、数字在兄弟 <span>，必须取容器整段文本；旧版 .p-price 同理
            "  var priceEl=e.querySelector('[class*=\"_price_\"],.p-price');" +
            "  var price=priceEl?norm(priceEl.innerText||priceEl.textContent):'';" +
            // 2026 版价格 ¥ 与数字分属不同块级节点，innerText 带空白，压成 ¥1234.56
            "  price=price.replace(/\\s+/g,'');" +
            "  if(!price){var priceI=e.querySelector('.p-price i,[class*=\"price\"] i');" +
            "    price=priceI?norm(priceI.innerText||priceI.textContent):'';}" +
            "  var imgEl=e.querySelector('.p-img img,img');" +
            "  var img='';" +
            "  if(imgEl){" +
            "    var imgRaw=String(imgEl.currentSrc||imgEl.src||'');" +
            "    if(!imgRaw||/loading|\\.gif/i.test(imgRaw)){" +
            "      var lazy=String(imgEl.getAttribute('data-lazy-img')||'');" +
            "      if(lazy&&lazy!=='done'){imgRaw=lazy;}" +
            "    }" +
            "    img=fix(imgRaw);" +
            "  }" +
            // 2026 版店铺名在卡片底部 span._name_ > span._limit_，旧版才是 .p-shop a
            "  var shopEl=e.querySelector('[class*=\"_name_\"] [class*=\"_limit_\"],.p-shop a,[class*=\"p-shop\"] a,[class*=\"shopName\"] a');" +
            "  var shop=shopEl?norm(shopEl.getAttribute('title')||shopEl.innerText||''):'';" +
            // 2026 版商品卡指标条：span[title="10万+条评价"] / span[title="98%好评"|title="400+人好评"] / span[title="已售2万+"]
            "  var cmEl=e.querySelector('span[title*=\"条评价\"],span[title*=\"条评论\"]');" +
            "  var commentCount=cmEl?norm(cmEl.getAttribute('title')||cmEl.innerText||''):'';" +
            "  var grEl=e.querySelector('span[title*=\"%好评\"],span[title*=\"人好评\"]');" +
            "  var goodRate=grEl?norm(grEl.getAttribute('title')||grEl.innerText||''):'';" +
            "  var soldEl=e.querySelector('span[title*=\"已售\"]');" +
            "  var soldCount=soldEl?norm(soldEl.getAttribute('title')||soldEl.innerText||''):'';" +
            "  var linkEl=e.querySelector('.p-name a[href*=\"item.jd.com\"],a[href*=\"item.jd.com\"]');" +
            "  var link=linkEl?fix(linkEl.getAttribute('href')||''):'';" +
            "  if(!link&&sku){link='https://item.jd.com/'+sku+'.html';}" +
            "  if(!title&&!img){continue;}" +
            "  seen[sku]=1;" +
            "  out.push({skuId:sku,title:title,price:price,imageUrl:img,shopName:shop,productUrl:link," +
            "    commentCount:commentCount,goodRate:goodRate,soldCount:soldCount});" +
            "}" +
            "return out;";

    private static final String READ_PRODUCT_BASE_SCRIPT =
            "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
            "function fix(u){u=String(u||'');if(!u){return '';}" +
            "if(u.indexOf('//')===0){return 'https:'+u;}" +
            "return u;}" +
            "function keyOf(u){var m=String(u).match(/jfs\\/[^\\s?]+/);return m?m[0]:u;}" +
            "var out={title:'',price:'',shopName:'',mainImages:[]};" +
            // 标题：2026 版为 .sku-title-name 组件，旧版 .sku-name
            "var titleEl=document.querySelector('.sku-title-name,.sku-name,.itemInfo-wrap .sku-name,[class*=\"sku-name\"]');" +
            "out.title=titleEl?norm(titleEl.innerText||titleEl.textContent):'';" +
            // 价格：2026 版 product-price--main/--value（加载前是 skeleton 骨架，需跳过），旧版 summary-price
            "function visible(el){if(!el){return false;}var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
            "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
            "function skeleton(el){return !!el.closest('[class*=\"skeleton\"]');}" +
            "var priceEl=null;var pcands=document.querySelectorAll('.product-price--main,[class*=\"product-price--value\"],.page-right-price,[class*=\"price-root\"]');" +
            "for(var pi=0;pi<pcands.length;pi++){if(visible(pcands[pi])&&!skeleton(pcands[pi])){priceEl=pcands[pi];break;}}" +
            "if(!priceEl){priceEl=document.querySelector('.summary-price .p-price .price,.summary-price .price,[class*=\"summary-price\"] i,.p-price i');}" +
            "var priceText=priceEl?norm(priceEl.innerText||priceEl.textContent):'';" +
            // 价格面板整段含促销文案/划线价，只取首个 ¥金额
            "var pm=priceText.match(/(¥|￥)\\s*([0-9][\\d,]*(?:\\.\\d+)?)/);" +
            "out.price=pm?(pm[1]+pm[2]):priceText.replace(/\\s+/g,'');" +
            // 店铺：2026 版店铺条 .top-name[title]，旧版 shopName a
            "var shopEl=document.querySelector('.top-name[title],.shopName a,.shop-name a,.J-shop-name a,[class*=\"shopName\"] a');" +
            "out.shopName=shopEl?norm(shopEl.getAttribute('title')||shopEl.innerText||''):'';" +
            // 主图相册：2026 版 .image-carousel-track 缩略（s228x228）+ zoom 大图（s1440x1440），旧版 #spec-list
            "function upgrade(u){u=String(u||'').replace(/\\/n[12]\\/(s\\d+x\\d+_)?jfs\\//,'/n0/jfs/');" +
            "  return u.replace(/\\/s\\d+x\\d+_jfs\\//,'/s1440x1440_jfs/');}" +
            "var imgs=document.querySelectorAll('.image-carousel-track img,.image-area img,#spec-list img,.spec-list img,[class*=\"spec-list\"] img');" +
            "if(!imgs.length){imgs=document.querySelectorAll('img[src*=\"360buyimg.com/n1/\"],img[data-origin*=\"360buyimg.com\"]');}" +
            "var seen={};var list=[];" +
            "for(var i=0;i<imgs.length;i++){var im=imgs[i];" +
            // data-origin 为高清原图，缺省再退 src；懒加载属性兜底
            "  var u=fix(im.getAttribute('data-origin')||im.currentSrc||im.src||'');" +
            "  if(!u||/\\.gif|loading/i.test(u)){var lazy=String(im.getAttribute('data-lazyload')||im.getAttribute('data-lazy-img')||'');" +
            "    if(lazy&&lazy!=='done'){u=fix(lazy);}}" +
            "  if(!u||u.indexOf('360buyimg.com')<0){continue;}" +
            "  var k=keyOf(u);if(seen[k]){continue;}" +
            "  seen[k]=1;list.push(upgrade(u));" +
            "}" +
            "out.mainImages=list;" +
            "return out;";

    /**
     * 详情滚动终点：#detail-main 底部；没有该节点时停在相关推荐上方。
     * 两者都没有才退回当前文档高度，用来把尚未挂载的详情长图滚出来。
     */
    private static final String DETAIL_SCROLL_PROBE_SCRIPT =
            "return (function(){" +
            "function yTop(el){if(!el){return null;}var r=el.getBoundingClientRect();" +
            "return (window.scrollY||window.pageYOffset||0)+r.top;}" +
            "function yBottom(el){if(!el){return null;}var r=el.getBoundingClientRect();" +
            "if(r.height<40){return null;}" +
            "return (window.scrollY||window.pageYOffset||0)+r.bottom;}" +
            "var detail=document.querySelector('#detail-main');" +
            "var detailBottom=yBottom(detail);" +
            "var related=document.querySelector('.recommend__feed-wrapper,.page-end .recommend,.recommend-goods');" +
            "var relatedTop=null;" +
            "if(related){var rr=related.getBoundingClientRect();" +
            "if(rr.height>20||rr.width>40){relatedTop=yTop(related);}}" +
            "var stop=null;var reason='document';" +
            "if(detailBottom!=null){stop=detailBottom;reason='detail-main';}" +
            "else if(relatedTop!=null){stop=relatedTop;reason='before-related';}" +
            "else{var doc=document.documentElement;stop=(doc?doc.scrollHeight:0)-2;reason='document';}" +
            "var viewBottom=(window.scrollY||window.pageYOffset||0)+window.innerHeight;" +
            "var room=Math.round(stop-viewBottom);" +
            "return {reached:room<=30,room:room>0?room:0,reason:reason};" +
            "})();";

    private static final String READ_DETAIL_IMAGES_SCRIPT =
            "function fix(u){u=String(u||'');if(!u){return '';}" +
            "if(u.indexOf('//')===0){return 'https:'+u;}" +
            "return u;}" +
            "function keyOf(u){var m=String(u).match(/jfs\\/[^\\s?]+/);return m?m[0]:u;}" +
            // 2026 详情页无描述 iframe：长图直接内联在页面；取 n[012]/pcpubliccms 路径图片
            "function upgrade(u){return String(u||'').replace(/\\/n[12]\\/(s\\d+x\\d+_)?jfs\\//,'/n0/jfs/');}" +
            "function excluded(im){return !!im.closest('[class*=\"recommend\"],[class*=\"compare\"],[class*=\"comment\"],[class*=\"footer\"],[class*=\"activity\"],[class*=\"banner\"],nav,header,[class*=\"sidebar\"],[class*=\"bar\"]');}" +
            "var out={images:[],descriptionFrameUrl:''};" +
            // 1) 跨源描述 iframe：JS 读不到内容，返回 src 给 Java 侧导航（旧版页面）
            "var frames=document.querySelectorAll('iframe');" +
            "for(var f=0;f<frames.length;f++){var fsrc=fix(frames[f].src||'');" +
            "  if(/360buyimg\\.com\\/(mware|description)|item\\.jd\\.com\\/[^\\s]*desc|desc_lazyload/i.test(fsrc)){out.descriptionFrameUrl=fsrc;break;}}" +
            // 2) 页内详情图：n[012]/pcpubliccms 路径 img，排除推荐流/比价/评论/页脚与小缩略图
            "var imgs=document.querySelectorAll('img');" +
            "var seen={},imgOut=[];" +
            "for(var i=0;i<imgs.length;i++){var im=imgs[i];" +
            "  var u=fix(im.currentSrc||im.src||im.getAttribute('data-lazyload')||im.getAttribute('data-lazy-img')||im.getAttribute('data-src')||im.getAttribute('data-origin')||'');" +
            "  if(!u||u.indexOf('360buyimg.com')<0){continue;}" +
            "  if(!/\\/(n[012]|pcpubliccms)\\//.test(u)){continue;}" +
            "  if(/\\.gif|loading|placeholder|space\\.gif|\\/shaidan\\//i.test(u)){continue;}" +
            "  if(excluded(im)){continue;}" +
            // s###x### 缩略图（相册缩略/推荐卡/评价晒单）双边 <=300 视为非长图
            "  var rs=u.match(/\\/s(\\d+)x(\\d+)_/);if(rs&&Math.min(parseInt(rs[1],10),parseInt(rs[2],10))<=300){continue;}" +
            "  var w=im.naturalWidth||0;if(w&&w<50){continue;}" +
            "  var k=keyOf(u);if(seen[k]){continue;}" +
            "  seen[k]=1;imgOut.push(upgrade(u));}" +
            // 3) 2026 SSD 装修模块：详情长图是 CSS background-image（#detail-main 内 .ssd-module 楼层，随滚动懒挂载），img 标签收不到；
            //    imagetools 段是 UI 图标库，楼层内容图在 img/popshop/n[012]/pcpubliccms 段
            "var bgRoots=document.querySelectorAll('#detail-main');" +
            "var bgScope=bgRoots.length?bgRoots:document.querySelectorAll('[class*=\"ssd-module\"]');" +
            "for(var b=0;b<bgScope.length;b++){var els=bgScope[b].querySelectorAll('*');" +
            "  for(var e2=0;e2<els.length;e2++){var el=els[e2];var bi='';" +
            "    try{bi=getComputedStyle(el).backgroundImage||'';}catch(err){}" +
            "    if(!bi||bi==='none'||bi.indexOf('360buyimg.com')<0){continue;}" +
            "    var ms=bi.match(/url\\([^)]*\\)/g)||[];" +
            "    for(var m2=0;m2<ms.length;m2++){var u2=fix(ms[m2].replace(/^url\\(/,'').replace(/\\)$/,'').replace(/^[\"']|[\"']$/g,''));" +
            "      if(!u2||u2.indexOf('360buyimg.com')<0){continue;}" +
            "      if(!/\\/(img|popshop|sku|n[012]|pcpubliccms)\\/jfs\\//.test(u2)){continue;}" +
            "      if(/\\.gif|loading|placeholder/i.test(u2)){continue;}" +
            "      if(excluded(el)){continue;}" +
            "      var rs2=u2.match(/\\/s(\\d+)x(\\d+)_/);if(rs2&&Math.min(parseInt(rs2[1],10),parseInt(rs2[2],10))<=300){continue;}" +
            "      var k2=keyOf(u2);if(seen[k2]){continue;}" +
            "      seen[k2]=1;imgOut.push(upgrade(u2));}}}" +
            "out.images=imgOut;" +
            "return out;";

    /**
     * 图片清单快照脚本：debug 用，列出页面全部 img 的关键信息，长图缺失时据此排查。
     */
    private static final String IMAGE_INVENTORY_SCRIPT =
            "function cls(im){var p=[],e=im;for(var d=0;e&&d<3;d++,e=e.parentElement){" +
            "var c=String(e.className&&e.className.baseVal!==undefined?e.className.baseVal:e.className||'');" +
            "if(c){p.push(c.substring(0,40));}}return p.join('<');}" +
            "var out=[];var imgs=document.querySelectorAll('img');" +
            "for(var i=0;i<imgs.length&&out.length<150;i++){var im=imgs[i];" +
            "var u=String(im.currentSrc||im.src||im.getAttribute('data-src')||im.getAttribute('data-lazyload')||'');" +
            "if(!u||u.indexOf('360buyimg.com')<0){continue;}" +
            "var r=im.getBoundingClientRect();" +
            "out.push((r.width>0?'V':'H')+' '+cls(im)+' | '+String(im.naturalWidth||0)+'x'+String(im.naturalHeight||0)+' | '+u.substring(0,110));}" +
            // 取证：#detail-main 内 SSD 楼层 background-image（2026 详情长图载体）
            "var dm=document.querySelector('#detail-main');" +
            "if(!dm){out.push('BG no-detail-main ssd='+document.querySelectorAll('[class*=\"ssd-module\"]').length);}" +
            "else{var bgs=dm.querySelectorAll('*');var bcnt=0;" +
            "  for(var q=0;q<bgs.length&&bcnt<40;q++){var bimg='';" +
            "    try{bimg=getComputedStyle(bgs[q]).backgroundImage||'';}catch(e){}" +
            "    if(bimg&&bimg!=='none'&&bimg.indexOf('360buyimg.com')>=0){out.push('BG '+String(bgs[q].className).substring(0,44)+' | '+bimg.substring(0,150));bcnt++;}}" +
            "  out.push('BG total='+bcnt+' els='+bgs.length);}" +
            "return out.join('\\n');";

    // ==================== 调试快照 ====================

    private final class DebugRecorder {
        private final boolean enabled;
        private final Path taskDir;
        private int stepIndex;

        private DebugRecorder(String profileName, String subject, Boolean requestDebugEnabled, String requestDebugDir,
                JdMallConfig config, String taskType) {
            boolean debugOn = requestDebugEnabled != null ? requestDebugEnabled : appConfig.getCustom().isDebugEnabled();
            this.enabled = debugOn;
            if (!enabled) {
                this.taskDir = null;
                return;
            }
            String baseDir = Strings.isEmpty(requestDebugDir) ? config.getDebugOutputDir() : requestDebugDir;
            String profile = Strings.isEmpty(profileName) ? "default" : profileName;
            String safeSubject = Strings.isEmpty(subject) ? "unknown" : subject;
            String time = LocalDateTime.now().format(DEBUG_TIME_FORMATTER);
            this.taskDir = Paths.get(baseDir, profile, safeSubject + "-" + time);
            try {
                Files.createDirectories(this.taskDir);
                Files.writeString(this.taskDir.resolve("_task.txt"),
                        "taskType=" + taskType + System.lineSeparator()
                                + "profileName=" + profile + System.lineSeparator()
                                + "subject=" + safeSubject + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.warn("JD mall debug directory init fail, path={}, error={}", this.taskDir, e.getMessage(), e);
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
                log.warn("JD mall debug snapshot fail, step={}, error={}", step, e.getMessage(), e);
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
                log.warn("JD mall debug text snapshot fail, step={}, error={}", step, e.getMessage(), e);
            }
        }

        private String escapeHtml(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
