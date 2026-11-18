package org.rx.crawler.task.aqc;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.CrawlEntryResult;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.CustomCrawlTask;
import org.rx.crawler.task.common.DebugSnapshots;
import org.rx.crawler.task.common.HumanPageNoise;
import org.rx.crawler.task.common.LoginNotificationContext;
import org.rx.exception.InvalidException;
import org.rx.util.BeanMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.rx.core.Extends.tryClose;

/**
 * 爱企查（aiqicha.baidu.com）公司信息抓取任务。
 *
 * <p>搜索流程：进入 aiqicha.baidu.com → 拟人在搜索框输入关键字 → 回车提交 →
 * 读取公司卡片（pid/公司名/法定代表人/信用代码/登记状态）；输入未生效时直跳搜索链接兜底。
 *
 * <p>详情流程：company_detail_{pid} → 读工商信息（执照面字段 + 经营范围）→
 * 拟人分段滚动触发懒加载栏目 → 按栏目标题归类读取表格（股东/主要人员/对外投资/分支机构/
 * 变更记录/风险栏目），未归类表格原样保留到 sections。
 *
 * <p>百度安全验证（cbae）无已实现自动滑块：页面被拦时保留浏览器等人工通过；
 * 登录复用公共人工接管流程。所有业务交互只用原生输入/点击/滚动，JS 仅读 DOM 与打标。
 */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class AqcMallTask implements CustomCrawlTask<AqcCompanySearchRequest, AqcCompanySearchResult> {
    public static final String TASK_TYPE = "aqcCompanySearch";
    public static final String DETAIL_TASK_TYPE = "aqcCompanyDetail";

    private static final String SEARCH_INPUT_SELECTOR = "[data-rx-aqc-search-input='1']";
    private static final DateTimeFormatter DEBUG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    /** 统一社会信用代码 18 位（可能脱敏为 *，卡片上命中完整 18 位才记）。 */
    private static final Pattern CREDIT_CODE_PATTERN = Pattern.compile("[0-9A-HJ-NPQRTUWXY]{2}\\d{6}[0-9A-HJ-NPQRTUWXY]{10}");
    private static final Pattern PID_URL_PATTERN = Pattern.compile("company_detail_(\\d+)");
    private static final Pattern BUSINESS_STATUS_PATTERN =
            Pattern.compile("(存续|在业|注销|吊销|迁入|迁出|清算|停业|歇业|撤销|其他)");
    private static final Pattern PID_DIGITS_PATTERN = Pattern.compile("\\d+");

    private final AppConfig appConfig;
    private final BrowserProfileManager profileManager;
    private final CrawlEntryService entryService;
    private final ObjectMapper objectMapper;

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public AqcCompanySearchResult execute(AqcCompanySearchRequest request) {
        return search(request);
    }

    public AqcCompanySearchResult search(AqcCompanySearchRequest request) {
        return executeSearchInternal(request);
    }

    public AqcCompanyDetailResult companyDetail(AqcCompanyDetailRequest request) {
        return executeDetailInternal(request);
    }

    public boolean closeProfile(String profileName) {
        return profileManager.closeSession(profileName);
    }

    // ==================== 搜索 ====================

    private AqcCompanySearchResult executeSearchInternal(AqcCompanySearchRequest rawRequest) {
        AqcConfig config = appConfig.getCustom().getAqc();
        AqcCompanySearchRequest request = normalizeSearchRequest(rawRequest, config);
        if (Strings.isEmpty(request.getKeyword())) {
            throw new InvalidException("keyword is required");
        }
        AqcCompanySearchResult result = createSearchResult(request);
        DebugRecorder debug = new DebugRecorder(request.getProfileName(), request.getKeyword(),
                request.getDebugEnabled(), request.getDebugOutputDir(), config, TASK_TYPE);
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }
        BrowserProfileManager.ProfileLease lease = null;
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(config));
            Browser browser = lease.getBrowser();

            CrawlEntryOptions options = createEntryOptions(request.getProfileName(), request.getForcePreflight(),
                    request.getKeepBrowserOpenOnLoginRequired(), config, config.getHomeUrl(), TASK_TYPE);
            humanNavigationPause(config);
            CrawlEntryResult entry = entryService.enter(browser, lease, options);
            applyEntryResult(result, entry);
            debug.snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", String.valueOf(result.getMessage()));
                return result;
            }
            if (!ensureSecurityClear(browser, config, debug, result.getDiagnostics(), "02-home")) {
                fail(result, CustomCrawlStatus.FAILED, "Baidu security verification not passed in time on home page");
                return result;
            }
            runSearchFlow(browser, request, config, options, result, debug);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", String.valueOf(result.getMessage()));
            return result;
        } catch (Exception e) {
            log.warn("AQC company search fail, keyword={}, profile={}, error={}",
                    request.getKeyword(), request.getProfileName(), e.getMessage(), e);
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", String.valueOf(result.getMessage()));
            return result;
        } finally {
            tryClose(lease);
        }
    }

    private void runSearchFlow(Browser browser, AqcCompanySearchRequest request, AqcConfig config,
            CrawlEntryOptions options, AqcCompanySearchResult result, DebugRecorder debug) throws TimeoutException {
        String keyword = request.getKeyword().trim();
        // 优先拟人输入 + 回车；未生效时直跳搜索链接兜底（真人也可能粘贴搜索链接）
        boolean submitted = false;
        if (nativeSetSearchValue(browser, keyword)) {
            try {
                browser.pressKey("Enter");
            } catch (Exception e) {
                log.debug("Press Enter to submit AQC search ignored, error={}", e.getMessage());
            }
            Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
            submitted = searchPageReached(browser);
        }
        if (!submitted) {
            result.getDiagnostics().put("searchNavigateFallback", true);
            browser.navigateUrl(buildSearchUrl(keyword, config), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
            Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
        }
        debug.snapshot(browser, "03-search-submitted");
        result.setCurrentUrl(browser.getCurrentUrl());

        if (!waitPageReady(browser, config, options, debug, result.getDiagnostics(), "search",
                b -> {
                    Integer cards = countCompanyCards(b);
                    return cards != null && cards > 0;
                })) {
            if (entryService.needsLogin(browser, options)) {
                result.setLoginRequired(true);
                fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                        "Login required. Finish login in the opened Chrome profile, then retry.");
                return;
            }
            if (isSecurityBlocked(browser.getCurrentUrl(), bodySnippet(browser))) {
                fail(result, CustomCrawlStatus.FAILED, "Baidu security verification not passed in time on search page");
                return;
            }
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "04-search-not-ready");
            // 超时不直接定论：按卡片读取结果判定
        }
        HumanPageNoise.applyRandomNoise(browser, "aqc-search");
        List<AqcSearchItem> raw = readSearchCards(browser);
        debug.snapshot(browser, "05-cards-read");
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        List<AqcSearchItem> items = new ArrayList<AqcSearchItem>();
        for (AqcSearchItem item : raw) {
            if (item.getPid() == null || !seen.add(item.getPid())) {
                continue;
            }
            item.setExactNameMatch(keyword.equals(item.getCompanyName()));
            items.add(item);
        }
        List<String> validationErrors = new ArrayList<String>();
        List<AqcSearchItem> complete = AqcResultChecks.completeItems(items, validationErrors);
        if (!validationErrors.isEmpty()) {
            result.getDiagnostics().put("droppedIncompleteItems", validationErrors.size());
        }
        if (complete.size() > request.getMaxResults()) {
            complete = new ArrayList<AqcSearchItem>(complete.subList(0, request.getMaxResults()));
        }
        result.setItems(complete);
        result.setResultCount(complete.size());
        result.setCurrentUrl(browser.getCurrentUrl());
        if (complete.isEmpty()) {
            String body = bodySnippet(browser);
            if (body != null && (body.contains("没有找到") || body.contains("暂无相关") || body.contains("0条"))) {
                fail(result, CustomCrawlStatus.NOT_FOUND, "No companies found for keyword: " + keyword);
            } else {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "AQC search cards not recognized");
                result.getDiagnostics().put("body", body);
            }
            debug.snapshot(browser, "06-search-empty");
            return;
        }
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("Found " + complete.size() + " companies for keyword: " + keyword);
    }

    /**
     * 通用页面就绪等待：百度安全验证（cbae）等人工通过；登录墙推送通知并等人工完成；
     * 业务就绪条件由 isReady 给出。返回 false 表示未就绪（原因由调用方复查 needsLogin/安全墙）。
     */
    private boolean waitPageReady(Browser browser, AqcConfig config, CrawlEntryOptions options, DebugRecorder debug,
            Map<String, Object> diagnostics, String step, java.util.function.Predicate<Browser> isReady)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + config.getPageTimeoutSeconds() * 1000L;
        boolean loginNotified = false;
        while (System.currentTimeMillis() < deadline) {
            if (isSecurityBlocked(browser.getCurrentUrl(), bodySnippet(browser))) {
                if (!ensureSecurityClear(browser, config, debug, diagnostics, step + "-security")) {
                    return false;
                }
            }
            if (entryService.needsLogin(browser, options)) {
                if (!loginNotified) {
                    debug.snapshot(browser, step + "-login-required");
                    entryService.notifyLoginRequired(options, browser.getCurrentUrl(),
                            "Login required. Finish login in the opened Chrome profile, then retry.");
                    loginNotified = true;
                }
                CrawlEntryResult waitResult = new CrawlEntryResult();
                if (!entryService.waitLoginCompleted(browser, options, waitResult)) {
                    diagnostics.put("loginWaitTimeout", true);
                    return false;
                }
            }
            if (isReady.test(browser)) {
                return true;
            }
            Extends.sleep(900 + ThreadLocalRandom.current().nextInt(500));
        }
        return false;
    }

    /** 详情任务页面未就绪时按原因落状态：登录→LOGIN_REQUIRED，安全验证→FAILED；true=已落状态可返回。 */
    private boolean mapPageNotReady(AqcCompanyDetailResult result, Browser browser, CrawlEntryOptions options, String step) {
        if (entryService.needsLogin(browser, options)) {
            result.setLoginRequired(true);
            fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                    "Login required. Finish login in the opened Chrome profile, then retry.");
            return true;
        }
        if (isSecurityBlocked(browser.getCurrentUrl(), bodySnippet(browser))) {
            fail(result, CustomCrawlStatus.FAILED, "Baidu security verification not passed in time on " + step);
            return true;
        }
        return false;
    }

    // ==================== 详情 ====================

    private AqcCompanyDetailResult executeDetailInternal(AqcCompanyDetailRequest rawRequest) {
        AqcConfig config = appConfig.getCustom().getAqc();
        AqcCompanyDetailRequest request = normalizeDetailRequest(rawRequest, config);
        AqcCompanyDetailResult result = createDetailResult(request);
        DebugRecorder debug = new DebugRecorder(request.getProfileName(),
                Strings.isEmpty(request.getPid()) ? request.getKeyword() : request.getPid(),
                request.getDebugEnabled(), request.getDebugOutputDir(), config, DETAIL_TASK_TYPE);
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }
        BrowserProfileManager.ProfileLease lease = null;
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(config));
            Browser browser = lease.getBrowser();

            String initialUrl = Strings.isEmpty(request.getPid())
                    ? config.getHomeUrl() : buildDetailUrl(request.getPid(), config);
            CrawlEntryOptions options = createEntryOptions(request.getProfileName(), request.getForcePreflight(),
                    request.getKeepBrowserOpenOnLoginRequired(), config, initialUrl, DETAIL_TASK_TYPE);
            humanNavigationPause(config);
            CrawlEntryResult entry = entryService.enter(browser, lease, options);
            applyEntryResult(result, entry);
            debug.snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", String.valueOf(result.getMessage()));
                return result;
            }
            if (!ensureSecurityClear(browser, config, debug, result.getDiagnostics(), "02-entry")) {
                fail(result, CustomCrawlStatus.FAILED, "Baidu security verification not passed in time");
                return result;
            }
            // 无 pid：先搜索并自动匹配（精确同名 / 信用代码唯一命中）
            if (Strings.isEmpty(request.getPid())) {
                String keyword = request.getKeyword().trim();
                if (!nativeSetSearchValue(browser, keyword)) {
                    browser.navigateUrl(buildSearchUrl(keyword, config), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
                } else {
                    try {
                        browser.pressKey("Enter");
                    } catch (Exception e) {
                        log.debug("Press Enter to submit AQC search ignored, error={}", e.getMessage());
                    }
                }
                Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
                if (!waitPageReady(browser, config, options, debug, result.getDiagnostics(), "match-search",
                        b -> {
                            Integer cards = countCompanyCards(b);
                            return cards != null && cards > 0;
                        })) {
                    if (mapPageNotReady(result, browser, options, "match-search")) {
                        return result;
                    }
                    debug.snapshot(browser, "03-match-search-not-ready");
                }
                String pid = resolvePid(browser, keyword, result, debug);
                if (pid == null) {
                    return result; // NOT_FOUND / MULTIPLE_MATCHED 已在 resolvePid 内落状态
                }
                request.setPid(pid);
                result.setPid(pid);
                result.setPageUrl(buildDetailUrl(pid, config));
                humanNavigationPause(config);
                browser.navigateUrl(result.getPageUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
            }
            if (!waitPageReady(browser, config, options, debug, result.getDiagnostics(), "detail",
                    b -> {
                        String body = bodySnippet(b);
                        return body != null && (body.contains("统一社会信用代码") || body.contains("法定代表人"));
                    })) {
                if (mapPageNotReady(result, browser, options, "detail")) {
                    return result;
                }
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "AQC company detail page not recognized");
                result.getDiagnostics().put("body", bodySnippet(browser));
                debug.snapshot(browser, "04-detail-not-ready");
                return result;
            }
            runDetailFlow(browser, config, result, debug);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", String.valueOf(result.getMessage()));
            return result;
        } catch (Exception e) {
            log.warn("AQC company detail fail, pid={}, keyword={}, profile={}, error={}",
                    request.getPid(), request.getKeyword(), request.getProfileName(), e.getMessage(), e);
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", String.valueOf(result.getMessage()));
            return result;
        } finally {
            tryClose(lease);
        }
    }

    /**
     * 从当前搜索结果页解析 pid：精确同名优先，信用代码唯一命中次之；
     * 多家同名/无法唯一时置 MULTIPLE_MATCHED 并带回候选，交上层选择后重提。
     */
    private String resolvePid(Browser browser, String keyword, AqcCompanyDetailResult result, DebugRecorder debug) {
        List<AqcSearchItem> items = readSearchCards(browser);
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        List<AqcSearchItem> candidates = new ArrayList<AqcSearchItem>();
        for (AqcSearchItem item : items) {
            if (item.getPid() == null || !seen.add(item.getPid())) {
                continue;
            }
            item.setExactNameMatch(keyword.equals(item.getCompanyName()));
            candidates.add(item);
        }
        debug.snapshot(browser, "03-match-cards");
        if (candidates.isEmpty()) {
            fail(result, CustomCrawlStatus.NOT_FOUND, "No company matched keyword: " + keyword);
            return null;
        }
        boolean keywordIsCreditCode = CREDIT_CODE_PATTERN.matcher(keyword).matches();
        List<AqcSearchItem> picked = new ArrayList<AqcSearchItem>();
        for (AqcSearchItem item : candidates) {
            if (keywordIsCreditCode && keyword.equalsIgnoreCase(item.getCreditCode())) {
                picked.add(item);
            } else if (!keywordIsCreditCode && item.isExactNameMatch()) {
                picked.add(item);
            }
        }
        if (picked.size() == 1) {
            result.getDiagnostics().put("autoMatched", picked.get(0).getCompanyName());
            return picked.get(0).getPid();
        }
        if (picked.isEmpty() && candidates.size() == 1) {
            result.getDiagnostics().put("autoMatched", candidates.get(0).getCompanyName());
            result.getDiagnostics().put("autoMatchFuzzy", true);
            return candidates.get(0).getPid();
        }
        int limit = Math.min(candidates.size(), 10);
        result.setCandidates(new ArrayList<AqcSearchItem>(candidates.subList(0, limit)));
        result.setStatus(CustomCrawlStatus.MULTIPLE_MATCHED);
        result.setMessage("Multiple companies matched keyword: " + keyword + ", resubmit with pid");
        return null;
    }

    private void runDetailFlow(Browser browser, AqcConfig config, AqcCompanyDetailResult result, DebugRecorder debug)
            throws TimeoutException {
        HumanPageNoise.applyRandomNoise(browser, "aqc-detail");
        Map<String, Object> base = readCompanyBase(browser);
        // 工商信息为异步渲染：核心字段空时短暂轮询补读
        long baseDeadline = System.currentTimeMillis() + 10000L;
        while (Strings.isEmpty(asString(base.get("companyName")))
                || asMap(base.get("fields")).isEmpty()
                && System.currentTimeMillis() < baseDeadline) {
            Extends.sleep(900 + ThreadLocalRandom.current().nextInt(400));
            base = readCompanyBase(browser);
        }
        applyCompanyBase(result, base);
        debug.snapshot(browser, "05-base-collected");

        // 拟人分段滚动触发懒加载栏目（股东/人员/变更等表格随滚动挂载）
        scrollDetailSections(browser, config);
        debug.snapshot(browser, "06-detail-scrolled");

        List<Map<String, Object>> tables = readCompanyTables(browser);
        applyCompanyTables(result, tables, config);
        debug.snapshotText("07-sections-collected", "tables=" + tables.size()
                + ", shareholders=" + result.getShareholders().size()
                + ", staff=" + result.getStaff().size()
                + ", branches=" + result.getBranches().size()
                + ", changes=" + result.getChanges().size()
                + ", risks=" + result.getRisks().size());

        // 滚动后首屏字段可能补挂载，空缺补读一次
        if (Strings.isEmpty(result.getCompanyName()) || Strings.isEmpty(result.getAddress())) {
            Map<String, Object> baseAfter = readCompanyBase(browser);
            if (Strings.isEmpty(result.getCompanyName())) {
                result.setCompanyName(asString(baseAfter.get("companyName")));
            }
            if (Strings.isEmpty(result.getAddress())) {
                result.setAddress(asString(asMap(baseAfter.get("fields")).get("注册地址")));
            }
            if (Strings.isEmpty(result.getBusinessScope())) {
                result.setBusinessScope(asString(baseAfter.get("businessScope")));
            }
        }
        List<String> errors = new ArrayList<String>();
        AqcResultChecks.detail(result, errors);
        if (!errors.isEmpty()) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "AQC company detail missing required fields");
            result.getDiagnostics().put("validationErrors", errors);
            debug.snapshot(browser, "07-detail-invalid");
            return;
        }
        result.setCurrentUrl(browser.getCurrentUrl());
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("Company detail captured: " + result.getCompanyName()
                + ", shareholders=" + result.getShareholders().size()
                + ", staff=" + result.getStaff().size()
                + ", branches=" + result.getBranches().size()
                + ", changes=" + result.getChanges().size()
                + ", risks=" + result.getRisks().size());
    }

    private void scrollDetailSections(Browser browser, AqcConfig config) {
        for (int i = 0; i < config.getDetailScrollSteps(); i++) {
            browser.scrollPage(500 + ThreadLocalRandom.current().nextInt(500));
            Extends.sleep(600 + ThreadLocalRandom.current().nextInt(500));
        }
    }

    // ==================== 百度安全验证（cbae）：人工通过，程序只等待 ====================

    private boolean isSecurityBlocked(String url, String body) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.contains("/cbae/") || lower.contains("wappass.baidu.com/captcha")) {
            return true;
        }
        return body != null && body.contains("百度安全验证");
    }

    private boolean ensureSecurityClear(Browser browser, AqcConfig config, DebugRecorder debug,
            Map<String, Object> diagnostics, String step) {
        String url = browser.getCurrentUrl();
        String body = bodySnippet(browser);
        if (!isSecurityBlocked(url, body)) {
            return true;
        }
        log.warn("AQC security verification encountered, waiting human up to {}s, step={}",
                config.getSecurityHumanWaitSeconds(), step);
        diagnostics.put("securityVerificationWait", step);
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType(DETAIL_TASK_TYPE);
        context.setCurrentUrl(url);
        context.setMessage("Baidu security verification (slider) shown; finish it in the opened Chrome, task is waiting.");
        entryService.notifySliderFailed(context);
        try {
            browser.maximize();
            browser.focus();
        } catch (Exception ignored) {
        }
        debug.snapshot(browser, step + "-security-blocked");
        long deadline = System.currentTimeMillis() + config.getSecurityHumanWaitSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Extends.sleep(2000);
            if (!isSecurityBlocked(browser.getCurrentUrl(), bodySnippet(browser))) {
                debug.snapshot(browser, step + "-security-cleared");
                return true;
            }
        }
        debug.snapshotText(step + "-security-timeout", "Baidu security verification not passed in time");
        return false;
    }

    // ==================== 原生输入 ====================

    /**
     * 拟人在爱企查搜索框输入关键字：JS 打标可见搜索输入框后原生键盘输入，
     * 未生效返回 false 由调用方走搜索链接兜底；禁止 JS 写值或合成事件。
     */
    private boolean nativeSetSearchValue(Browser browser, String value) {
        if (Boolean.TRUE.equals(markSearchInput(browser))) {
            return typeSearchValue(browser, value);
        }
        // SPA 水合慢时输入框可能未挂载：短暂等待后重试一次
        Extends.sleep(1200);
        if (Boolean.TRUE.equals(markSearchInput(browser))) {
            return typeSearchValue(browser, value);
        }
        return false;
    }

    private Boolean markSearchInput(Browser browser) {
        return browser.executeScript("var attr='data-rx-aqc-search-input';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function ph(el){return norm(el.getAttribute('placeholder')||el.getAttribute('aria-label')||'');}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input'));" +
                "var chosen=null;" +
                // 爱企查搜索框固定 id=aqc-search-input（首页/结果页一致），最稳
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "if((e.id||'')==='aqc-search-input'){chosen=e;break;}}" +
                "if(!chosen){for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n)){continue;}" +
                "var m=ph(n)+(n.name||'')+(n.className||'');" +
                "if(/查|搜|search|企业|公司/i.test(m)&&n.getBoundingClientRect().width>=140){chosen=n;break;}}}" +
                "if(!chosen){return false;}" +
                "chosen.setAttribute(attr,'1');return true;");
    }

    private boolean typeSearchValue(Browser browser, String value) {
        try {
            browser.elementClick(SEARCH_INPUT_SELECTOR, false);
            Extends.sleep(200 + ThreadLocalRandom.current().nextInt(300));
            browser.elementPress(SEARCH_INPUT_SELECTOR, value, false);
        } catch (Exception e) {
            log.warn("Native type AQC search keyword fail, error={}", e.getMessage());
        }
        return searchInputEquals(browser, value);
    }

    private boolean searchInputEquals(Browser browser, String expected) {
        Boolean ok = browser.executeScript("var exp=String(arguments[0]||'');" +
                "var el=document.querySelector('[data-rx-aqc-search-input=\"1\"]');" +
                "return !!el && String(el.value||'')===exp;", expected);
        return Boolean.TRUE.equals(ok);
    }

    private boolean searchPageReached(Browser browser) {
        String url = browser.getCurrentUrl();
        if (url != null && url.contains("aiqicha.baidu.com/s")) {
            return true;
        }
        Integer cards = countCompanyCards(browser);
        return cards != null && cards > 0;
    }

    private Integer countCompanyCards(Browser browser) {
        return browser.executeScript("return document.querySelectorAll(\"a[href*='company_detail_']\").length;");
    }

    // ==================== DOM 读取（只读 JS） ====================

    /**
     * 搜索结果卡片：公司链接 + 所在卡片容器文本（Java 侧再解析信用代码/法人/状态，抗改版）。
     */
    @SuppressWarnings("unchecked")
    private List<AqcSearchItem> readSearchCards(Browser browser) {
        List<Map<String, Object>> raw = browser.executeScript(
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "var out=[];" +
                "var anchors=Array.prototype.slice.call(document.querySelectorAll(\"a[href*='company_detail_']\"));" +
                "for(var i=0;i<anchors.length;i++){" +
                "  var a=anchors[i];var name=norm(a.innerText);" +
                "  if(!name||name.length>80){continue;}" +
                "  var card=a;var text=name;" +
                "  for(var up=0;up<6&&card.parentElement;up++){" +
                "    card=card.parentElement;var t=norm(card.innerText);" +
                "    if(t.length>text.length){text=t;}" +
                "    if(text.length>=120){break;}" +
                "  }" +
                "  out.push({pid:(a.getAttribute('href')||'').match(/company_detail_(\\d+)/)||[,''][1]," +
                "    name:name,cardText:text.substring(0,400)});" +
                "}" +
                "return out;");
        List<AqcSearchItem> items = new ArrayList<AqcSearchItem>();
        if (raw == null) {
            return items;
        }
        for (Map<String, Object> entry : raw) {
            AqcSearchItem item = new AqcSearchItem();
            item.setPid(asString(entry.get("pid")));
            item.setCompanyName(asString(entry.get("name")));
            String cardText = asString(entry.get("cardText"));
            Matcher credit = CREDIT_CODE_PATTERN.matcher(cardText);
            if (credit.find()) {
                item.setCreditCode(credit.group());
            }
            item.setLegalPerson(extractAfterLabel(cardText, "法定代表人"));
            Matcher status = BUSINESS_STATUS_PATTERN.matcher(cardText);
            if (status.find()) {
                item.setBusinessStatus(status.group());
            }
            if (Strings.isNotEmpty(item.getPid())) {
                item.setPageUrl(buildDetailUrl(item.getPid(), appConfig.getCustom().getAqc()));
            }
            items.add(item);
        }
        return items;
    }

    /** 卡片文本按行取标签后一行/同行冒号后的值。 */
    private String extractAfterLabel(String text, String label) {
        if (Strings.isEmpty(text)) {
            return "";
        }
        String[] lines = text.split("\\n|\\s{2,}");
        for (int i = 0; i < lines.length; i++) {
            String norm = lines[i] == null ? "" : lines[i].trim();
            if (norm.equals(label) && i + 1 < lines.length) {
                return lines[i + 1] == null ? "" : lines[i + 1].trim();
            }
            if (norm.startsWith(label) && norm.length() > label.length()) {
                String rest = norm.substring(label.length()).replace(":", "").replace("：", "").trim();
                if (!rest.isEmpty() && rest.length() <= 30) {
                    return rest;
                }
            }
        }
        return "";
    }

    /**
     * 工商信息首屏字段：叶子标签元素 → 相邻值；不依赖具体 class，抗改版。
     */
    private Map<String, Object> readCompanyBase(Browser browser) {
        Map<String, Object> raw = browser.executeScript(READ_COMPANY_BASE_SCRIPT);
        return raw == null ? new LinkedHashMap<String, Object>() : raw;
    }

    private static final String READ_COMPANY_BASE_SCRIPT =
            "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
            "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
            "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
            "function leafs(){return Array.prototype.slice.call(document.querySelectorAll('*')).filter(function(e){" +
            "return visible(e)&&e.children.length===0&&norm(e.innerText).length>0&&norm(e.innerText).length<=14;});}" +
            "function pick(labels){var out={};var ls=leafs();" +
            "labels.forEach(function(label){" +
            "for(var i=0;i<ls.length;i++){var e=ls[i];if(norm(e.innerText)!==label){continue;}" +
            "var val='';" +
            "var sib=e.nextElementSibling;if(sib&&norm(sib.innerText)){val=norm(sib.innerText);}" +
            "if(!val){var p=e.parentElement,ps=p?p.nextElementSibling:null;if(ps&&norm(ps.innerText)){val=norm(ps.innerText);}}" +
            "if(!val){var td=e.closest('td,th');if(td&&td.parentElement){var cells=td.parentElement.querySelectorAll('td,th');" +
            "for(var c=0;c<cells.length;c++){if(cells[c]===td&&c+1<cells.length){val=norm(cells[c+1].innerText);break;}}}}" +
            "if(val&&val!==label){out[label]=val.substring(0,300);break;}}});" +
            "return out;}" +
            "var fields=pick(['法定代表人','注册资本','实缴资本','成立日期','企业类型','登记状态','经营状态'," +
            "'统一社会信用代码','纳税人识别号','注册号','组织机构代码','所属行业','核准日期','登记机关','营业期限'," +
            "'人员规模','参保人数','曾用名','英文名','注册地址']);" +
            "var name='';var hs=Array.prototype.slice.call(document.querySelectorAll('h1,h2,h3'));" +
            "for(var i=0;i<hs.length;i++){if(visible(hs[i])&&norm(hs[i].innerText).length>=4){name=norm(hs[i].innerText).substring(0,80);break;}}" +
            "var scope='';var ls2=leafs();for(var j=0;j<ls2.length;j++){if(norm(ls2[j].innerText)==='经营范围'){" +
            "var c=ls2[j];for(var up=0;up<4&&c.parentElement;up++){c=c.parentElement;var t=norm(c.innerText);" +
            "if(t.length>10){scope=t.substring(0,2000);break;}}break;}}" +
            "return {companyName:name,fields:fields,businessScope:scope};";

    /**
     * 详情页全部可见表格：[{title, rows:[[cell..]]}]，title 取表格前方最近的栏目标题文本。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readCompanyTables(Browser browser) {
        List<Map<String, Object>> raw = browser.executeScript(
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function titleOf(table){var node=table;var hops=0;" +
                "while(node&&hops<14){var prev=node.previousElementSibling;var p=node.parentElement;" +
                "node=(prev&&prev.contains&&!prev.contains(table))?prev:p;hops++;" +
                "var cand=node?norm(node.innerText):'';" +
                "if(cand&&cand.length>=2&&cand.length<=16&&!/^\\d+$/.test(cand)){return cand;}}" +
                "return '';}" +
                "var out=[];var tables=Array.prototype.slice.call(document.querySelectorAll('table'));" +
                "for(var i=0;i<tables.length;i++){var tb=tables[i];if(!visible(tb)){continue;}" +
                "var trs=Array.prototype.slice.call(tb.querySelectorAll('tr')).slice(0,80);" +
                "if(trs.length<1){continue;}" +
                "var rows=[];for(var r=0;r<trs.length;r++){var cells=Array.prototype.slice.call(trs[r].querySelectorAll('td,th'));" +
                "var row=[];for(var c=0;c<cells.length&&c<12;c++){row.push(norm(cells[c].innerText).substring(0,200));}if(row.length){rows.push(row);}}" +
                "if(rows.length<2){continue;}" +
                "out.push({title:titleOf(tb),rows:rows});}" +
                "return out.slice(0,30);");
        return raw == null ? new ArrayList<Map<String, Object>>() : raw;
    }

    // ==================== 结果组装 ====================

    private void applyCompanyBase(AqcCompanyDetailResult result, Map<String, Object> base) {
        Map<String, Object> fields = asMap(base.get("fields"));
        result.setCompanyName(asString(base.get("companyName")));
        result.setLegalPerson(asString(fields.get("法定代表人")));
        result.setRegisteredCapital(asString(fields.get("注册资本")));
        result.setPaidInCapital(asString(fields.get("实缴资本")));
        result.setFoundDate(asString(fields.get("成立日期")));
        result.setCompanyType(asString(fields.get("企业类型")));
        String status = asString(fields.get("登记状态"));
        if (Strings.isEmpty(status)) {
            status = asString(fields.get("经营状态"));
        }
        result.setBusinessStatus(status);
        result.setCreditCode(asString(fields.get("统一社会信用代码")));
        result.setRegistrationAuthority(asString(fields.get("登记机关")));
        result.setApprovedDate(asString(fields.get("核准日期")));
        result.setBusinessTerm(asString(fields.get("营业期限")));
        result.setStaffScale(asString(fields.get("人员规模")));
        result.setInsuredCount(asString(fields.get("参保人数")));
        result.setFormerNames(asString(fields.get("曾用名")));
        result.setAddress(asString(fields.get("注册地址")));
        result.setBusinessScope(asString(base.get("businessScope")));
    }

    private void applyCompanyTables(AqcCompanyDetailResult result, List<Map<String, Object>> tables, AqcConfig config) {
        List<String> captured = new ArrayList<String>();
        for (Map<String, Object> table : tables) {
            String title = asString(table.get("title"));
            List<List<String>> rows = asRows(table.get("rows"));
            if (rows.isEmpty()) {
                continue;
            }
            List<String> headers = rows.get(0);
            String joined = String.join("", headers);
            boolean known = true;
            if (title.contains("股东") || joined.contains("持股比例") || joined.contains("认缴出资额")) {
                mapShareholders(result, headers, rows, config);
            } else if (title.contains("主要人员") || joined.contains("职务")) {
                mapStaff(result, headers, rows, config);
            } else if (title.contains("变更") || joined.contains("变更项目")) {
                mapChanges(result, headers, rows, config);
            } else if (title.contains("分支机构") || title.contains("分支")) {
                mapBranches(result, rows, config);
            } else if (isRiskSection(title, joined)) {
                mapRisks(result, title, headers, rows, config);
            } else {
                known = false;
            }
            if (known) {
                captured.add(Strings.isEmpty(title) ? joined.substring(0, Math.min(joined.length(), 12)) : title);
            } else if (!Strings.isEmpty(title)) {
                result.getSections().put(title, clampRows(rows, config));
                captured.add(title);
            }
        }
        result.getDiagnostics().put("sectionsCaptured", captured);
    }

    private boolean isRiskSection(String title, String headerJoined) {
        String[] riskKeywords = {"经营异常", "行政处罚", "严重违法", "动产抵押", "股权出质", "抽查检查",
                "失信", "被执行", "欠税", "法律诉讼", "开庭公告", "立案信息", "司法协助", "限制消费令", "终本案件", "破产重整"};
        for (String keyword : riskKeywords) {
            if (title.contains(keyword) || headerJoined.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void mapShareholders(AqcCompanyDetailResult result, List<String> headers, List<List<String>> rows, AqcConfig config) {
        int nameIdx = columnOf(headers, "股东", "名称", "姓名", "合伙人");
        int ratioIdx = columnOf(headers, "持股比例", "比例");
        int subIdx = columnOf(headers, "认缴");
        int paidIdx = columnOf(headers, "实缴");
        for (List<String> row : clampRows(rows, config)) {
            if (isHeaderRow(row, headers)) {
                continue;
            }
            AqcShareholderItem item = new AqcShareholderItem();
            item.setName(cellAt(row, nameIdx));
            item.setRatio(cellAt(row, ratioIdx));
            item.setSubscribedAmount(cellAt(row, subIdx));
            item.setPaidAmount(cellAt(row, paidIdx));
            if (Strings.isEmpty(item.getName())) {
                continue;
            }
            item.setExtra(joinExtra(row, nameIdx, ratioIdx, subIdx, paidIdx));
            result.getShareholders().add(item);
        }
    }

    private void mapStaff(AqcCompanyDetailResult result, List<String> headers, List<List<String>> rows, AqcConfig config) {
        int nameIdx = columnOf(headers, "姓名", "名称", "人员");
        int posIdx = columnOf(headers, "职务", "职位");
        for (List<String> row : clampRows(rows, config)) {
            if (isHeaderRow(row, headers)) {
                continue;
            }
            AqcStaffItem item = new AqcStaffItem();
            item.setName(cellAt(row, nameIdx < 0 ? 0 : nameIdx));
            item.setPosition(cellAt(row, posIdx < 0 ? 1 : posIdx));
            if (Strings.isEmpty(item.getName())) {
                continue;
            }
            result.getStaff().add(item);
        }
    }

    private void mapChanges(AqcCompanyDetailResult result, List<String> headers, List<List<String>> rows, AqcConfig config) {
        int dateIdx = columnOf(headers, "时间", "日期");
        int itemIdx = columnOf(headers, "变更项目", "项目");
        int beforeIdx = columnOf(headers, "变更前");
        int afterIdx = columnOf(headers, "变更后");
        for (List<String> row : clampRows(rows, config)) {
            if (isHeaderRow(row, headers)) {
                continue;
            }
            AqcChangeItem item = new AqcChangeItem();
            item.setDate(cellAt(row, dateIdx < 0 ? 0 : dateIdx));
            item.setItem(cellAt(row, itemIdx < 0 ? 1 : itemIdx));
            item.setBeforeValue(cellAt(row, beforeIdx));
            item.setAfterValue(cellAt(row, afterIdx));
            if (Strings.isEmpty(item.getDate()) && Strings.isEmpty(item.getItem())) {
                continue;
            }
            result.getChanges().add(item);
        }
    }

    private void mapBranches(AqcCompanyDetailResult result, List<List<String>> rows, AqcConfig config) {
        for (List<String> row : clampRows(rows, config)) {
            String first = row.isEmpty() ? "" : row.get(0);
            if (Strings.isEmpty(first) || "机构名称".equals(first) || "名称".equals(first)) {
                continue;
            }
            result.getBranches().add(first);
        }
    }

    private void mapRisks(AqcCompanyDetailResult result, String title, List<String> headers, List<List<String>> rows, AqcConfig config) {
        int dateIdx = columnOf(headers, "时间", "日期");
        int authIdx = columnOf(headers, "机关", "部门", "法院");
        for (List<String> row : clampRows(rows, config)) {
            if (isHeaderRow(row, headers)) {
                continue;
            }
            AqcRiskItem item = new AqcRiskItem();
            item.setCategory(Strings.isEmpty(title) ? "风险" : title);
            item.setDate(cellAt(row, dateIdx));
            item.setAuthority(cellAt(row, authIdx));
            List<String> extra = new ArrayList<String>();
            for (int i = 0; i < row.size(); i++) {
                if (i != dateIdx && i != authIdx && Strings.isNotEmpty(row.get(i))) {
                    extra.add(row.get(i));
                }
            }
            item.setDetail(String.join(" | ", extra));
            result.getRisks().add(item);
        }
    }

    private int columnOf(List<String> headers, String... keywords) {
        for (String keyword : keywords) {
            for (int i = 0; i < headers.size(); i++) {
                if (headers.get(i) != null && headers.get(i).contains(keyword)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String cellAt(List<String> row, int index) {
        if (index < 0 || index >= row.size()) {
            return "";
        }
        String value = row.get(index);
        return value == null ? "" : value.trim();
    }

    private boolean isHeaderRow(List<String> row, List<String> headers) {
        if (row.size() != headers.size() || headers.isEmpty()) {
            return false;
        }
        int same = 0;
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            String c = i < row.size() ? row.get(i) : "";
            if (h != null && h.equals(c)) {
                same++;
            }
        }
        return same >= Math.max(1, headers.size() - 1);
    }

    private String joinExtra(List<String> row, int... exclude) {
        List<String> extra = new ArrayList<String>();
        for (int i = 0; i < row.size(); i++) {
            boolean skip = false;
            for (int ex : exclude) {
                if (i == ex) {
                    skip = true;
                    break;
                }
            }
            if (!skip && Strings.isNotEmpty(row.get(i))) {
                extra.add(row.get(i));
            }
        }
        return String.join(" | ", extra);
    }

    private List<List<String>> clampRows(List<List<String>> rows, AqcConfig config) {
        int limit = Math.max(1, config.getMaxSectionRows());
        return rows.size() > limit ? new ArrayList<List<String>>(rows.subList(0, limit)) : rows;
    }

    // ==================== 通用辅助 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> asRows(Object value) {
        List<List<String>> rows = new ArrayList<List<String>>();
        if (value instanceof List) {
            for (Object row : (List<Object>) value) {
                List<String> cells = new ArrayList<String>();
                if (row instanceof List) {
                    for (Object cell : (List<Object>) row) {
                        cells.add(cell == null ? "" : String.valueOf(cell));
                    }
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String buildSearchUrl(String keyword, AqcConfig config) {
        String encoded;
        try {
            encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            encoded = keyword;
        }
        return config.getSearchUrlTemplate().replace("{keyword}", encoded);
    }

    private String buildDetailUrl(String pid, AqcConfig config) {
        return config.getDetailUrlTemplate().replace("{pid}", pid);
    }

    private WebBrowserConfig createBrowserConfig(AqcConfig config) {
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
            AqcConfig config, String initialUrl, String taskType) {
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
        options.setLoginRequiredUrlMatcher(this::isLoginRequiredUrl);
        options.setLoginRequiredBodyMatcher(this::looksLikeAqcLoginBody);
        options.setLoggedInUrlMatcher(this::isAqcPageUrl);
        return options;
    }

    private boolean isLoginRequiredUrl(String currentUrl) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        return lower.startsWith(config().getLoginUrlPrefix())
                || lower.contains("passport.baidu.com")
                || lower.contains("/login");
    }

    /**
     * 页面正文是否像百度登录页：命中 2 个登录强特征（扫码/账号/短信登录、忘记密码等）才算，
     * 避免把含「登录」二字的正常页面误判成登录墙。
     */
    public boolean looksLikeAqcLoginBody(String body) {
        if (Strings.isEmpty(body)) {
            return false;
        }
        String[] signals = {"扫码登录", "账号登录", "短信登录", "短信验证码登录", "忘记密码", "验证码登录", "百度用户协议"};
        int hits = 0;
        for (String signal : signals) {
            if (body.contains(signal)) {
                hits++;
            }
        }
        return hits >= 2;
    }

    public boolean isAqcPageUrl(String url) {
        if (Strings.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("aiqicha.baidu.com");
    }

    private AqcConfig config() {
        return appConfig.getCustom().getAqc();
    }

    private AqcCompanySearchRequest normalizeSearchRequest(AqcCompanySearchRequest request, AqcConfig config) {
        if (request == null) {
            request = new AqcCompanySearchRequest();
        }
        if (request.getMaxResults() == null || request.getMaxResults() <= 0) {
            request.setMaxResults(config.getDefaultMaxResults());
        }
        int limit = config.getMaxResultsLimit() > 0 ? config.getMaxResultsLimit() : 50;
        if (request.getMaxResults() > limit) {
            request.setMaxResults(limit);
        }
        if (Strings.isEmpty(request.getProfileName())) {
            request.setProfileName(Strings.isEmpty(config.getProfileName())
                    ? profileManager.defaultProfileName() : config.getProfileName());
        }
        request.setProfileName(profileManager.normalizeProfileName(request.getProfileName()));
        return request;
    }

    private AqcCompanyDetailRequest normalizeDetailRequest(AqcCompanyDetailRequest request, AqcConfig config) {
        if (request == null) {
            request = new AqcCompanyDetailRequest();
        }
        if (Strings.isEmpty(request.getPid()) && !Strings.isEmpty(request.getKeyword())) {
            // keyword 也可能是详情链接
            Matcher pidMatcher = PID_URL_PATTERN.matcher(request.getKeyword());
            if (pidMatcher.find()) {
                request.setPid(pidMatcher.group(1));
            }
        }
        if (!Strings.isEmpty(request.getPid()) && !PID_DIGITS_PATTERN.matcher(request.getPid()).matches()) {
            throw new InvalidException("pid must be numeric");
        }
        if (Strings.isEmpty(request.getPid()) && Strings.isEmpty(request.getKeyword())) {
            throw new InvalidException("pid or keyword is required");
        }
        if (Strings.isEmpty(request.getProfileName())) {
            request.setProfileName(Strings.isEmpty(config.getProfileName())
                    ? profileManager.defaultProfileName() : config.getProfileName());
        }
        request.setProfileName(profileManager.normalizeProfileName(request.getProfileName()));
        return request;
    }

    private AqcCompanySearchResult createSearchResult(AqcCompanySearchRequest request) {
        AqcCompanySearchResult result = new AqcCompanySearchResult();
        result.setTaskType(TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setKeyword(request.getKeyword());
        result.setProfileName(request.getProfileName());
        return result;
    }

    private AqcCompanyDetailResult createDetailResult(AqcCompanyDetailRequest request) {
        AqcCompanyDetailResult result = new AqcCompanyDetailResult();
        result.setTaskType(DETAIL_TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setPid(request.getPid());
        result.setKeyword(request.getKeyword());
        if (!Strings.isEmpty(request.getPid())) {
            result.setPageUrl(buildDetailUrl(request.getPid(), config()));
        }
        result.setProfileName(request.getProfileName());
        return result;
    }

    private void applyEntryResult(AqcCompanySearchResult result, CrawlEntryResult entry) {
        result.setFingerprintPassed(entry.isFingerprintPassed());
        result.setCurrentUrl(entry.getCurrentUrl());
        if (!Strings.isEmpty(entry.getMessage())) {
            result.setMessage(entry.getMessage());
        }
        if (entry.isLoginRequired()) {
            result.setLoginRequired(true);
            result.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
        }
    }

    private void applyEntryResult(AqcCompanyDetailResult result, CrawlEntryResult entry) {
        result.setFingerprintPassed(entry.isFingerprintPassed());
        result.setCurrentUrl(entry.getCurrentUrl());
        if (!Strings.isEmpty(entry.getMessage())) {
            result.setMessage(entry.getMessage());
        }
        if (entry.isLoginRequired()) {
            result.setLoginRequired(true);
            result.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
        }
    }

    private void fail(AqcCompanySearchResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message);
        result.setErrorCode(status.name());
    }

    private void fail(AqcCompanyDetailResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message);
        result.setErrorCode(status.name());
    }

    private void humanNavigationPause(AqcConfig config) {
        int base = Math.max(0, config.getNavigationPauseMillis());
        int random = Math.max(0, config.getNavigationPauseRandomMillis());
        Extends.sleep(base + ThreadLocalRandom.current().nextInt(random + 1));
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

    // ==================== 调试快照 ====================

    private final class DebugRecorder {
        private final boolean enabled;
        private final Path taskDir;
        private int stepIndex;

        private DebugRecorder(String profileName, String subject, Boolean requestDebugEnabled, String requestDebugDir,
                AqcConfig config, String taskType) {
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
                DebugSnapshots.writeUtf8(this.taskDir.resolve("_task.txt"),
                        "taskType=" + taskType + System.lineSeparator()
                                + "profileName=" + profile + System.lineSeparator()
                                + "subject=" + safeSubject + System.lineSeparator());
            } catch (Exception e) {
                log.warn("AQC debug directory init fail, path={}, error={}", this.taskDir, e.getMessage(), e);
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
                log.warn("AQC debug snapshot fail, step={}, error={}", step, e.getMessage(), e);
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
                    "<html><body><pre>" + body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                            + "</pre></body></html>");
        }
    }
}
