package org.rx.crawler.task.tb;

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
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.DebugSnapshots;
import org.rx.crawler.task.common.HumanPageNoise;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.CrawlEntryResult;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CrawlResultValidator;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.CustomCrawlTask;
import org.rx.crawler.task.common.KeepAliveUrlStore;
import org.rx.crawler.task.common.LoginNotificationContext;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.exception.InvalidException;
import org.rx.util.BeanMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
public class TbPromotionOrdersTask implements CustomCrawlTask<TbPromotionOrdersRequest, TbPromotionOrdersResult> {
    private static final String TASK_TYPE = "getTbPromotionOrders";
    private static final DateTimeFormatter DEBUG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern MONTH_PATTERN = Pattern.compile("(\\d{4})年(\\d{1,2})月");
    private static final Pattern MONEY_PATTERN = Pattern.compile("[￥¥]\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern DATE_TIME_PATTERN = Pattern
            .compile("\\d{4}-\\d{2}-\\d{2}(?:\\s+\\d{2}:\\d{2}:\\d{2})?");
    private static final Pattern LONG_NO_PATTERN = Pattern.compile("\\b[A-Za-z0-9_-]{10,}\\b");
    private static final String QUICK_ENTER_TEXT = "快速进入";
    private static final String QUICK_LOGIN_TEXT = "快速登录";
    private static final String JS_VISIBLE_STRICT = "function visible(el){for(var p=el;p&&p.nodeType===1;p=p.parentElement){var st=getComputedStyle(p),c=((p.className||'')+'').toLowerCase();if(st.display==='none'||st.visibility==='hidden'||c.indexOf('hidden')>=0){return false;}}var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}";

    private final AppConfig appConfig;
    private final BrowserProfileManager profileManager;
    private final CrawlEntryService entryService;
    private final ObjectMapper objectMapper;
    private final KeepAliveUrlStore keepAliveUrlStore;
    private final SliderVerifyHandler sliderVerifyHandler = new SliderVerifyHandler();
    private final ThreadLocal<Long> taskDeadlineHolder = new ThreadLocal<Long>();

    public TbPromotionOrdersTask(AppConfig appConfig, BrowserProfileManager profileManager, CrawlEntryService entryService,
            ObjectMapper objectMapper) {
        this(appConfig, profileManager, entryService, objectMapper, KeepAliveUrlStore.NOOP);
    }

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public TbPromotionOrdersResult execute(TbPromotionOrdersRequest request) {
        return getPromotionOrders(request);
    }

    public TbPromotionOrdersResult getPromotionOrders(TbPromotionOrdersRequest request) {
        return executeInternal(request);
    }

    public boolean closeProfile(String profileName) {
        return profileManager.closeSession(profileName);
    }

    private TbPromotionOrdersResult executeInternal(TbPromotionOrdersRequest rawRequest) {
        TbPromotionConfig tbConfig = appConfig.getCustom().getTbPromotion();
        TbPromotionOrdersRequest request = normalizeRequest(rawRequest, tbConfig);
        TbPromotionOrdersResult result = createResult(request);
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
            browser.startResponseCapture("report.getTbkOrderDetails.json");

            CrawlEntryResult entry = entryService.enter(browser, lease, createEntryOptions(request, tbConfig));
            applyEntryResult(result, entry);
            debug.snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", result.getMessage());
                return result;
            }

            for (int replay = 0; replay < 2; replay++) {
                try {
                    runOrdersFlow(browser, request, tbConfig, result, debug);
                    break;
                } catch (OrderVerificationRestart retry) {
                    result.setOrders(new ArrayList<PromotionOrderItem>());
                    if (replay == 1) {
                        fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB_ORDER_VERIFICATION_RESTART_LIMIT");
                        break;
                    }
                    browser.navigateUrl(tbConfig.getOrderUrl(), Browser.BODY_SELECTOR, tbConfig.getPageTimeoutSeconds());
                }
            }
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("TB promotion orders fail, startTime={}, endTime={}, profile={}, error={}",
                    request.getStartTime(), request.getEndTime(), request.getProfileName(), e.getMessage(), e);
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            if (lease != null) {
                try { lease.getBrowser().stopResponseCapture("report.getTbkOrderDetails.json"); }
                catch (Exception ignored) { }
            }
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private CrawlEntryOptions createEntryOptions(TbPromotionOrdersRequest request, TbPromotionConfig config) {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setTaskType(TASK_TYPE);
        options.setProfileName(request.getProfileName());
        options.setPreflightEnabled(config.isPreflightEnabled());
        options.setPreflightUrl(config.getPreflightUrl());
        options.setPreflightStrict(config.isPreflightStrict());
        options.setPreflightCacheMinutes(config.getPreflightCacheMinutes());
        options.setForcePreflight(
                request.getForcePreflight() == null ? config.isForcePreflight() : request.getForcePreflight());
        // 直达订单页，少一次 home 中转；会话续期「确认登录」仍由 completeForwardLanding 处理
        options.setInitialUrl(config.getOrderUrl());
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
                && containsAny(body, "阿里妈妈", "淘宝联盟", "效果报表", "订单", "付款时间",
                QUICK_ENTER_TEXT, QUICK_LOGIN_TEXT, "确认继续用以下账号登录"));
        return options;
    }

    private void runOrdersFlow(Browser browser, TbPromotionOrdersRequest request, TbPromotionConfig config,
            TbPromotionOrdersResult result, DebugRecorder debug) throws TimeoutException {
        browser.maximize();
        if (completeForwardLanding(browser, config)) {
            debug.snapshot(browser, "02-forward-landing-entered");
        }
        if (isForwardLandingIncomplete(browser)) {
            String bodySnippet = sliderVerifyHandler.readBodySnippet(browser);
            if (isHardLoginBody(bodySnippet)) {
                fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                        "TB promotion login required at forward landing page. Finish login in the opened common Chrome profile, then retry.");
                result.setLoginRequired(true);
                debug.snapshot(browser, "02-forward-landing-login-required");
                return;
            }
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion forward landing quick-enter page not completed");
            result.getDiagnostics().put("currentUrl", browser.getCurrentUrl());
            result.getDiagnostics().put("body", bodySnippet);
            debug.snapshot(browser, "02-forward-landing-incomplete");
            return;
        }

        // entry 已直达订单页时跳过重复导航，只做轻量 settle
        String currentUrl = browser.getCurrentUrl();
        boolean alreadyOnOrder = !Strings.isEmpty(currentUrl)
                && currentUrl.toLowerCase(Locale.ROOT).contains("overvieworder");
        if (!alreadyOnOrder) {
            browser.navigateUrl(config.getOrderUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
            Extends.sleep(Math.max(400, config.nextStepDelayMillis() / 2));
            if (completeForwardLanding(browser, config)) {
                debug.snapshot(browser, "02-forward-landing-after-order-nav");
            }
        } else {
            Extends.sleep(Math.max(400, config.nextStepDelayMillis() / 2));
        }
        // 导航后检测并处理滑块验证
        if (!checkAndHandleSliderVerify(browser, config, result, debug, "02-order-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED,
                    "TB promotion slider verify not cleared before order page ready");
            result.getDiagnostics().put("body", sliderVerifyHandler.readBodySnippet(browser));
            debug.snapshot(browser, "02-order-slider-not-cleared");
            return;
        }
        result.setCurrentUrl(browser.getCurrentUrl());
        debug.snapshot(browser, "02-order-page-loaded");
        if (isHardLoginUrl(result.getCurrentUrl(), config)
                || isHardLoginBody(sliderVerifyHandler.readBodySnippet(browser))) {
            fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                    "TB promotion login expired before entering order page. Finish login in the opened common Chrome profile, then retry.");
            result.setLoginRequired(true);
            debug.snapshot(browser, "02-order-login-required");
            return;
        }
        if (!waitOrderPageReady(browser, config, result, debug)) {
            String bodyNow = sliderVerifyHandler.readBodySnippet(browser);
            String message;
            if (isSliderVerifyPage(browser)) {
                message = "TB promotion slider verify not cleared while entering order page";
            } else if (isSessionResumeBody(bodyNow) || hasQuickEntrance(bodyNow)) {
                message = "TB promotion session-resume (确认登录/快速进入) not completed while entering order page";
            } else {
                message = "TB promotion order page not ready";
            }
            fail(result, CustomCrawlStatus.PAGE_CHANGED, message);
            result.getDiagnostics().put("body", bodyNow);
            debug.snapshot(browser, "02-order-page-not-ready");
            return;
        }
        debug.snapshot(browser, "02-order-page-ready");
        HumanPageNoise.applyRandomNoise(browser, "tb-orders");

        result.getDiagnostics().put("channel", "page-with-passive-response");

        LocalDate startDate = LocalDate.parse(request.getStartTime(), DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(request.getEndTime(), DATE_FORMATTER);
        // 选日期前检测滑块
        checkAndHandleSliderVerify(browser, config, result, debug, "03-date-range-slider");
        if (!selectPaymentDateRange(browser, startDate, endDate, config, debug)) {
            // 操作失败后检测是否因滑块弹出导致，处理后重试
            if (checkAndHandleSliderVerify(browser, config, result, debug, "03-date-range-retry-slider")
                    && selectPaymentDateRange(browser, startDate, endDate, config, debug)) {
                log.info("TB promotion date range selected after slider retry");
            } else {
                // 再次深度检测滑块，可能文案没变但 UI 被遮挡
                if (isSliderVerifyPage(browser)) {
                    log.info("TB promotion slider detected after second failure, retrying flow");
                    if (onSliderVerifyDetected(browser, config, result, debug, "03-date-range-final-slider")
                        && selectPaymentDateRange(browser, startDate, endDate, config, debug)) {
                        log.info("TB promotion date range selected after final slider retry");
                    } else {
                        fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion order date range picker blocked by slider or missing");
                        result.getDiagnostics().put("body", sliderVerifyHandler.readBodySnippet(browser));
                        debug.snapshot(browser, "03-date-range-missing");
                        return;
                    }
                } else {
                    fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion order date range picker not found");
                    result.getDiagnostics().put("body", sliderVerifyHandler.readBodySnippet(browser));
                    debug.snapshot(browser, "03-date-range-missing");
                    return;
                }
            }
        }
        debug.snapshot(browser, "03-date-range-selected");

        // 日期「确定」后列表会自动按付款时间刷新，不必再点「搜索订单编号」。
        // 新版右侧橙色按钮是按订单号精确查：空输入点击会把已刷出的列表打成「暂无数据」。
        checkAndHandleSliderVerify(browser, config, result, debug, "04-list-pre-slider");
        waitOrderRowsSettled(browser, config, result, debug);
        if (!hasVisibleOrderRows(browser) && !isEmptyOrderList(browser)) {
            // 仍未 settle 时再短等一轮
            Extends.sleep(Math.max(400, config.nextStepDelayMillis() / 2));
            waitOrderRowsSettled(browser, config, result, debug);
        }
        // 仅当列表既无行也不是明确空态时，才尝试文案型「查询/查找订单」（绝不点订单号搜索图标）
        if (!hasVisibleOrderRows(browser) && !isEmptyOrderList(browser)) {
            if (nativeClickOrderQueryByText(browser)) {
                result.getDiagnostics().put("orderQueryTextClicked", true);
                if (!checkAndHandleSliderVerify(browser, config, result, debug, "04-query-slider")) {
                    fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared after query");
                    result.getDiagnostics().put("body", sliderVerifyHandler.readBodySnippet(browser));
                    debug.snapshot(browser, "04-query-slider-not-cleared");
                    return;
                }
                waitOrderRowsSettled(browser, config, result, debug);
            } else {
                result.getDiagnostics().put("orderQuerySkipped", true);
            }
        } else {
            result.getDiagnostics().put("orderListReadyAfterDateRange", true);
            result.getDiagnostics().put("skipOrderNumberSearch", true);
        }
        debug.snapshot(browser, "04-order-list-ready");

        LinkedHashMap<String, PromotionOrderItem> orders = new LinkedHashMap<String, PromotionOrderItem>();
        int pageNo = 1;
        while (pageNo <= 100) {
            ensureTaskDeadline("getTbPromotionOrders.pageLoop");
            // 读取订单前检测滑块
            checkAndHandleSliderVerify(browser, config, result, debug, String.format("05-page-%03d-pre-read-slider", pageNo));
            List<PromotionOrderItem> pageOrders = readCapturedOrderRows(browser, readOrderRows(browser));
            for (PromotionOrderItem item : pageOrders) {
                ensureTaskDeadline("getTbPromotionOrders.productIdentity");
                org.rx.crawler.task.common.OrderProductLinkReader.resolve(browser, item,
                        org.rx.crawler.task.price.PriceCodes.TB, Math.min(8, config.getPageTimeoutSeconds()));
            }
            result.getDiagnostics().put("lastPageNo", pageNo);
            result.getDiagnostics().put("lastPageRows", pageOrders.size());
            for (PromotionOrderItem item : pageOrders) {
                orders.put(rowKey(item), item);
            }
            debug.snapshot(browser, String.format("05-page-%03d-collected", pageNo));

            scrollOrderPageBottom(browser, config);
            // 翻页前检测滑块
            checkAndHandleSliderVerify(browser, config, result, debug, String.format("05-page-%03d-pre-next-slider", pageNo));
            if (!hasNextOrderPage(browser)) {
                break;
            }
            HumanPageNoise.applyPaginationNoise(browser, pageNo);
            if (!nativeClickNextOrderPage(browser)) {
                result.getDiagnostics().put("nextPageClickFailedAt", pageNo);
                break;
            }
            // 翻页后：poll 列表 + 滑块，不再固定 sleep *2
            if (!checkAndHandleSliderVerify(browser, config, result, debug,
                    String.format("05-page-%03d-slider", pageNo + 1))) {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared during page turn");
                result.getDiagnostics().put("body", sliderVerifyHandler.readBodySnippet(browser));
                return;
            }
            waitOrderRowsSettled(browser, config, result, debug);
            pageNo++;
        }

        result.setOrders(new ArrayList<PromotionOrderItem>(orders.values()));
        result.getDiagnostics().put("pages", pageNo);
        result.getDiagnostics().put("orderCount", result.getOrders().size());
        List<String> validationErrors = TbResultChecks.orders(result.getOrders());
        if (!validationErrors.isEmpty()) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB order item validation failed");
            result.getDiagnostics().put("validationErrors", validationErrors);
            return;
        }
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setComplete(true);
        result.setHasMore(false);
        result.setPagesFetched(pageNo);
        result.setTotalCount(result.getOrders().size());
        keepAliveUrlStore.collect("tb", browser, result.getDiagnostics());
    }

    /**
     * 私有入口：委托 SliderVerifyHandler 检测并处理滑块验证。
     * 自动重试 3 次，全部失败后等待人工接管。
     */
    private boolean onSliderVerifyDetected(Browser browser, TbPromotionConfig config,
            TbPromotionOrdersResult result, DebugRecorder debug, String stepTag) throws TimeoutException {
        ensureTaskDeadline("onSliderVerifyDetected." + stepTag);
        result.getDiagnostics().put("sliderVerifyAt", stepTag);

        // 委托公共处理器执行滑块验证（含拟人拖拽、验证失败重试点击）
        boolean passed = sliderVerifyHandler.checkAndHandle(browser, stepTag, 5,
                config.nextStepDelayMillis(), (b, name) -> debug.snapshot(b, name), config.getOrderUrl());

        if (passed) {
            result.getDiagnostics().put("sliderVerifyPassed", true);
            // 滑块通过后短 settle，再判断是否被踢到中转页
            Extends.sleep(Math.max(500, config.nextStepDelayMillis()));
            // 滑块通过后可能跳到「确认登录/快速进入」页面或其他中转页，需要重新走完前置流程
            String currentUrl = browser.getCurrentUrl();
            if (currentUrl != null && !currentUrl.toLowerCase(Locale.ROOT).contains("overvieworder")) {
                log.info("TB promotion slider passed but page navigated away, currentUrl={}", currentUrl);
                debug.snapshot(browser, stepTag + "-post-slider-landing");
                completeForwardLanding(browser, config);
                browser.navigateUrl(config.getOrderUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
                Extends.sleep(Math.max(400, config.nextStepDelayMillis() / 2));
                // 导航后可能再次弹出滑块
                if (sliderVerifyHandler.isSliderVerifyPage(browser)) {
                    log.info("TB promotion slider appeared again after re-navigation, step={}", stepTag);
                    if (!sliderVerifyHandler.checkAndHandle(browser, stepTag + "-re-nav", 5,
                            config.nextStepDelayMillis(), (b, name) -> debug.snapshot(b, name), config.getOrderUrl())) return false;
                    Extends.sleep(Math.max(400, config.nextStepDelayMillis() / 2));
                }
            }
            return true;
        }

        // 自动验证失败，发邮件后等待人工接管
        log.warn("TB promotion slider verify failed after automatic attempts, step={}", stepTag);
        notifySliderFailed(result, browser, config, stepTag);
        debug.snapshot(browser, stepTag + "-manual-wait");
        if (waitSliderVerifyCleared(browser, config, stepTag)) {
            log.info("TB promotion slider verify cleared by manual takeover, step={}", stepTag);
            result.getDiagnostics().put("sliderVerifyManualPassed", true);
            debug.snapshot(browser, stepTag + "-manual-cleared");
            return true;
        }
        result.getDiagnostics().put("sliderVerifyFailed", stepTag);
        return false;
    }

    /**
     * 检测页面是否出现阿里妈妈滑块验证，若出现则委托 onSliderVerifyDetected 处理。
     * 验证成功或无验证时返回 true。
     */
    private boolean checkAndHandleSliderVerify(Browser browser, TbPromotionConfig config,
            TbPromotionOrdersResult result, DebugRecorder debug, String stepTag) throws TimeoutException {
        if (!isSliderVerifyPage(browser)) {
            return true;
        }
        if (!onSliderVerifyDetected(browser, config, result, debug, stepTag))
            throw new org.rx.crawler.task.common.TaskApiException(409, "TB_SLIDER_VERIFICATION_REQUIRED", "TB_SLIDER_VERIFICATION_REQUIRED");
        // A challenge refresh may reset dates/page state: replay once from the original request, never read default rows.
        throw new OrderVerificationRestart();
    }

    private static final class OrderVerificationRestart extends RuntimeException { }

    private boolean isSliderVerifyPage(Browser browser) {
        return sliderVerifyHandler.isSliderVerifyPage(browser);
    }

    private boolean waitSliderVerifyCleared(Browser browser, TbPromotionConfig config, String stepTag)
            throws TimeoutException {
        int waitSeconds = Math.max(10, config.getLoginWaitSeconds());
        extendTaskDeadline(waitSeconds + Math.max(10, config.getPageTimeoutSeconds()));
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(waitSeconds);
        long poll = pollIntervalMillis(config, 350);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("waitSliderVerifyCleared." + stepTag);
            if (!isSliderVerifyPage(browser)) {
                return true;
            }
            Extends.sleep(poll);
        }
        return false;
    }

    /** 轮询间隔：先看状态再 sleep，避免「先睡 1s 再发现已就绪」。 */
    private long pollIntervalMillis(TbPromotionConfig config, int floor) {
        return Math.max(floor, config.nextStepDelayMillis() / 2);
    }

    private void extendTaskDeadline(int seconds) {
        Long current = taskDeadlineHolder.get();
        if (current == null) {
            return;
        }
        long minDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1, seconds));
        if (current < minDeadline) {
            taskDeadlineHolder.set(minDeadline);
        }
    }

    /** Object -> double 工具方法，兼容 Integer / Long / Double / String */
    private double toDouble(Object val) {
        if (val == null)
            return 0;
        if (val instanceof Number)
            return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean nativeClickMarkedElement(Browser browser, String selector) {
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            try {
                Map<String, Object> point = browser.executeScript("var target=document.querySelector(arguments[0]);" +
                        "if(!target){return null;}var r=target.getBoundingClientRect();" +
                        "if(r.width<=0||r.height<=0){return null;}" +
                        "return {x:r.left+r.width/2,y:r.top+r.height/2};", selector);
                if (point == null || point.isEmpty()) {
                    return false;
                }
                double x = toDouble(point.get("x"));
                double y = toDouble(point.get("y"));
                browser.mouseDrag(x, y, x, y, 1);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private boolean completeForwardLanding(Browser browser, TbPromotionConfig config) throws TimeoutException {
        String currentUrl = browser.getCurrentUrl();
        String body = sliderVerifyHandler.readBodySnippet(browser);
        if (!isForwardLandingUrl(currentUrl) && !hasQuickEntrance(body) && !isSessionResumeBody(body)) {
            return false;
        }
        long deadline = System.currentTimeMillis() + Math.max(10, config.getPageTimeoutSeconds()) * 1000L;
        boolean clicked = false;
        long lastClickAt = 0L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.completeForwardLanding");
            currentUrl = browser.getCurrentUrl();
            body = sliderVerifyHandler.readBodySnippet(browser);
            boolean forwardLanding = isForwardLandingUrl(currentUrl);
            boolean hasQuickEntrance = hasQuickEntrance(body);
            if (!forwardLanding && !hasQuickEntrance && isLoggedInUrl(currentUrl, config)) {
                return true;
            }
            if (!forwardLanding && !hasQuickEntrance
                    && containsAny(body, "阿里妈妈", "淘宝联盟", "效果报表", "订单", "付款时间")) {
                return true;
            }
            // 会话续期点「快速进入」：可立刻点，防连点间隔 ~0.7s（旧逻辑 3s 偏慢）
            if (hasQuickEntrance
                    && System.currentTimeMillis() - lastClickAt >= Math.max(700L, config.nextStepDelayMillis() * 2L / 3)
                    && nativeClickQuickEnter(browser)) {
                clicked = true;
                lastClickAt = System.currentTimeMillis();
                log.info("TB promotion quick-enter clicked, currentUrl={}", currentUrl);
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
        // 上限 4s 仅兜底；就绪即返回。网络慢时多等不硬砍
        long deadline = Math.min(outerDeadline,
                System.currentTimeMillis() + Math.max(4000L, config.nextStepDelayMillis() * 4L));
        long poll = pollIntervalMillis(config, 220);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.waitForwardLandingResolvedAfterClick");
            String currentUrl = browser.getCurrentUrl();
            String body = sliderVerifyHandler.readBodySnippet(browser);
            boolean forwardLanding = isForwardLandingUrl(currentUrl);
            boolean hasQuickEntrance = hasQuickEntrance(body);
            if (!forwardLanding && !hasQuickEntrance && isLoggedInUrl(currentUrl, config)) {
                return true;
            }
            if (!forwardLanding && !hasQuickEntrance
                    && containsAny(body, "阿里妈妈", "淘宝联盟", "效果报表", "订单", "付款时间")) {
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
        String selector = "[data-rx-tb-quick-enter='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-quick-enter';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                "if(document.readyState==='loading'){return false;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}"
                +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,[role=\"button\"],span,div'));"
                +
                "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=text(e);if(t!=='快速进入'&&t!=='快速登录'&&t.indexOf('快速进入')<0&&t.indexOf('快速登录')<0){continue;}"
                +
                "var target=e.closest('button,a,[role=\"button\"]')||e,r=target.getBoundingClientRect(),score=r.width*r.height/1000+(t==='快速进入'?0:80);if(score<bestScore){best=target;bestScore=score;}}"
                +
                "if(!best){return false;}best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        return nativeClickMarkedElement(browser, selector);
    }

    private String readForwardUrl(Browser browser) {
        String url = browser.executeScript("try{" +
                "var u=new URL(location.href),f=u.searchParams.get('forward');" +
                "return f?decodeURIComponent(f):'';" +
                "}catch(e){return '';}");
        return url == null ? "" : url;
    }

    private boolean waitOrderPageReady(Browser browser, TbPromotionConfig config,
            TbPromotionOrdersResult result, DebugRecorder debug) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, config.getInitialPageTimeoutSeconds()) * 1000L;
        long poll = pollIntervalMillis(config, 350);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.waitOrderPageReady");
            if (isSliderVerifyPage(browser)
                    && !checkAndHandleSliderVerify(browser, config, result, debug, "02-order-ready-slider")) {
                return false;
            }
            String body = sliderVerifyHandler.readBodySnippet(browser);
            // 滑块通过后常落到「确认登录/快速进入」，必须点快速进入，否则空等到超时
            if (isSessionResumeBody(body) || hasQuickEntrance(body)) {
                log.info("Order page wait hit session-resume (确认登录/快速进入), completeForwardLanding");
                debug.snapshot(browser, "02-order-session-resume");
                completeForwardLanding(browser, config);
                String after = browser.getCurrentUrl();
                if (Strings.isEmpty(after) || !after.toLowerCase(Locale.ROOT).contains("overvieworder")) {
                    browser.navigateUrl(config.getOrderUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
                    Extends.sleep(Math.max(400, config.nextStepDelayMillis() / 2));
                }
                body = sliderVerifyHandler.readBodySnippet(browser);
            }
            if (isOrderUiReady(body) && !isSessionResumeBody(body) && !isSliderVerifyPage(browser)) {
                return true;
            }
            Extends.sleep(poll);
        }
        return false;
    }

    /** 订单页搜索/筛选 UI 就绪（侧栏「订单」文案不足）。 */
    private boolean isOrderUiReady(String body) {
        return containsAny(body, "付款时间", "搜索订单编号", "查找订单", "订单状态", "佣金比例");
    }

    private boolean isOrderListLoaded(Browser browser) {
        String body = sliderVerifyHandler.readBodySnippet(browser);
        return containsAny(body, "订单信息", "订单状态", "总提成率", "付款预估收入", "暂无数据")
                && containsAny(body, "上一页", "下一页", "子订单编号", "父订单编号", "暂无数据");
    }

    /** 页面上已有可解析的订单行（父/子订单编号或已付款类状态）。 */
    private boolean hasVisibleOrderRows(Browser browser) {
        String body = sliderVerifyHandler.readBodySnippet(browser);
        return containsAny(body, "父订单编号", "子订单编号")
                && !containsAny(body, "暂无数据");
    }

    /** 列表已明确空（有表格壳 + 暂无），不是加载中。 */
    private boolean isEmptyOrderList(Browser browser) {
        String body = sliderVerifyHandler.readBodySnippet(browser);
        return containsAny(body, "暂无数据", "没有相关数据", "暂无订单")
                && !containsAny(body, "加载中", "查询中")
                && !containsAny(body, "父订单编号", "子订单编号");
    }

    private boolean selectPaymentDateRange(Browser browser, LocalDate startDate, LocalDate endDate,
            TbPromotionConfig config,
            DebugRecorder debug) throws TimeoutException {
        if (!nativeClickPaymentTimeRange(browser)) {
            debug.snapshot(browser, "03-date-range-open-click-missing");
            return false;
        }
        // 短 settle 后立刻 poll：浮层已开但月文案未出时，勿等满 waitDatePickerMonths（曾白白 2.5s+）
        Extends.sleep(Math.max(280, config.nextStepDelayMillis() / 3L));
        debug.snapshot(browser, "03-date-range-after-open-click");

        List<String> months = waitDatePickerMonths(browser, config, 900L);
        if (months.isEmpty() && !isDateRangePopupOpen(browser) && nativeClickPaymentTimeRange(browser)) {
            Extends.sleep(Math.max(320, config.nextStepDelayMillis() / 2L));
            debug.snapshot(browser, "03-date-range-after-open-retry-click");
            months = waitDatePickerMonths(browser, config, 900L);
        }
        if (months.isEmpty()) {
            if (!isDateRangePopupOpen(browser)) {
                debug.snapshot(browser, "03-date-range-popup-closed-after-open");
                return false;
            }
            // 新版：外层 label 打开后还要再点内层输入，面板月才会出现
            debug.snapshot(browser, "03-date-range-before-inner-input-click");
            if (!nativeClickDateInputInRangePopup(browser)) {
                debug.snapshot(browser, "03-date-range-inner-input-missing");
                return false;
            }
            Extends.sleep(Math.max(280, config.nextStepDelayMillis() / 3L));
            debug.snapshot(browser, "03-date-range-after-inner-input-click");
            months = waitDatePickerMonths(browser, config);
            if (months.isEmpty()) {
                debug.snapshot(browser, "03-date-range-months-empty");
                return false;
            }
        }

        YearMonth startMonth = YearMonth.from(startDate);
        YearMonth endMonth = YearMonth.from(endDate);
        if (!navigateDatePickerToMonth(browser, config, startMonth, true, "start")) {
            debug.snapshot(browser, "03-date-range-start-month-nav-missing");
            return false;
        }
        if (!nativeClickDateInMonth(browser, startDate)) {
            debug.snapshot(browser, "03-date-range-start-day-missing");
            return false;
        }
        // 拟人短顿，给 range 选中态渲染；过长会叠加成 >2s
        Extends.sleep(Math.max(220, config.nextStepDelayMillis() / 3));
        debug.snapshot(browser, "03-date-range-start-day-selected");

        months = readDatePickerMonths(browser);
        YearMonth left = months.isEmpty() ? null : parseYearMonthText(months.get(0));
        YearMonth right = months.size() > 1 ? parseYearMonthText(months.get(1)) : left;
        // 双月面板：结束月已在左或右格上则可直接点
        if (endMonth.equals(left) || endMonth.equals(right)) {
            boolean clicked = nativeClickDateInMonth(browser, endDate);
            if (!clicked) {
                debug.snapshot(browser, "03-date-range-end-day-left-missing");
            }
            return clicked && finishDateRangeSelection(browser, startDate, endDate, config, debug);
        }
        if (!navigateDatePickerToMonth(browser, config, endMonth, false, "end")) {
            debug.snapshot(browser, "03-date-range-end-month-nav-missing");
            return false;
        }
        boolean clicked = nativeClickDateInMonth(browser, endDate);
        if (!clicked) {
            debug.snapshot(browser, "03-date-range-end-day-missing");
        }
        return clicked && finishDateRangeSelection(browser, startDate, endDate, config, debug);
    }

    /**
     * 将日期面板导航到目标月。
     * <p>只在「年份不同」时才点年按钮；同年只按月前进/后退。
     * 切勿按 monthsDiff≥3 就跳年——同年内 4 月↔7 月会先跳到上一年再跳回，2025/2026 来回卡死。
     *
     * @param preferLeft true 时对齐左格（起始月）；false 时左或右格命中均可（结束月）
     */
    private boolean navigateDatePickerToMonth(Browser browser, TbPromotionConfig config, YearMonth target,
            boolean preferLeft, String tag) throws TimeoutException {
        for (int i = 0; i < 36; i++) {
            ensureTaskDeadline("getTbPromotionOrders.navigateMonth." + tag);
            List<String> months = readDatePickerMonths(browser);
            if (months.isEmpty()) {
                return false;
            }
            YearMonth left = parseYearMonthText(months.get(0));
            YearMonth right = months.size() > 1 ? parseYearMonthText(months.get(1)) : left;
            if (preferLeft) {
                if (target.equals(left)) {
                    return true;
                }
            } else if (target.equals(left) || target.equals(right)) {
                return true;
            }
            YearMonth pivot = preferLeft ? left : (right != null ? right : left);
            if (pivot == null) {
                return false;
            }
            String before = months.get(0) + "|" + (months.size() > 1 ? months.get(1) : "");
            int yearDiff = target.getYear() - pivot.getYear();
            boolean clicked;
            if (yearDiff != 0) {
                // 仅跨年时 year 跳转一步（对齐到目标年），之后同年改用月按钮
                clicked = nativeClickDatePickerYearNav(browser, yearDiff > 0);
                if (!clicked) {
                    // 无年按钮时退回按月（最多 12 次/年，近 30 天场景几乎不会走到）
                    clicked = nativeClickDatePickerNav(browser, target.isAfter(pivot));
                }
            } else {
                clicked = nativeClickDatePickerNav(browser, target.isAfter(pivot));
            }
            if (!clicked) {
                return false;
            }
            if (!waitDatePickerMonthChanged(browser, config, before)) {
                Extends.sleep(Math.max(220, config.nextStepDelayMillis() / 3));
            }
        }
        List<String> finalMonths = readDatePickerMonths(browser);
        if (finalMonths.isEmpty()) {
            return false;
        }
        YearMonth left = parseYearMonthText(finalMonths.get(0));
        YearMonth right = finalMonths.size() > 1 ? parseYearMonthText(finalMonths.get(1)) : left;
        return preferLeft ? target.equals(left) : (target.equals(left) || target.equals(right));
    }

    private boolean waitDatePickerMonthChanged(Browser browser, TbPromotionConfig config, String beforeKey)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1800L, config.nextStepDelayMillis() * 2L);
        long poll = pollIntervalMillis(config, 180);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.waitDatePickerMonthChanged");
            List<String> months = readDatePickerMonths(browser);
            if (!months.isEmpty()) {
                String key = months.get(0) + "|" + (months.size() > 1 ? months.get(1) : "");
                if (!beforeKey.equals(key)) {
                    return true;
                }
            }
            Extends.sleep(poll);
        }
        return false;
    }

    private boolean finishDateRangeSelection(Browser browser, LocalDate startDate, LocalDate endDate,
            TbPromotionConfig config, DebugRecorder debug) throws TimeoutException {
        if (!nativeClickDateRangeConfirmIfPresent(browser)) {
            debug.snapshot(browser, "03-date-range-confirm-missing");
            return false;
        }
        if (waitPaymentDateRangeSelected(browser, startDate, endDate, config)) {
            return true;
        }
        if (isDateRangePopupOpen(browser)) {
            nativeClickDateRangeConfirmIfPresent(browser);
            if (waitPaymentDateRangeSelected(browser, startDate, endDate, config)) {
                return true;
            }
        }
        debug.snapshot(browser, "03-date-range-selected-not-applied");
        return false;
    }

    private boolean waitPaymentDateRangeSelected(Browser browser, LocalDate startDate, LocalDate endDate,
            TbPromotionConfig config) throws TimeoutException {
        // poll-first：确定后 label 常立刻更新，上限留给偶发慢渲染
        long deadline = System.currentTimeMillis() + Math.max(3200L, config.nextStepDelayMillis() * 3L);
        long poll = pollIntervalMillis(config, 220);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.waitPaymentDateRangeSelected");
            if (isPaymentDateRangeSelected(browser, startDate, endDate)) {
                return true;
            }
            Extends.sleep(poll);
        }
        return false;
    }

    private boolean isPaymentDateRangeSelected(Browser browser, LocalDate startDate, LocalDate endDate) {
        Boolean selected = browser.executeScript(JS_VISIBLE_STRICT +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var start=arguments[0],end=arguments[1];" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('.mux-calendar-label-container'));" +
                "for(var i=0;i<labels.length;i++){var e=labels[i];if(!visible(e)||e.closest('.mux-tooltip,.mux-picker-dropdown,.mux-picker-panel-container')){continue;}"
                +
                "var t=norm(e.innerText||e.textContent);if(t.indexOf(start)>=0&&t.indexOf(end)>=0){return true;}}" +
                "return false;", startDate.toString(), endDate.toString());
        return Boolean.TRUE.equals(selected);
    }

    private List<String> waitDatePickerMonths(Browser browser, TbPromotionConfig config) throws TimeoutException {
        return waitDatePickerMonths(browser, config, Math.max(2500L, config.nextStepDelayMillis() * 3L));
    }

    private List<String> waitDatePickerMonths(Browser browser, TbPromotionConfig config, long maxWaitMillis)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(400L, maxWaitMillis);
        long poll = pollIntervalMillis(config, 180);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.waitDatePickerMonths");
            List<String> months = readDatePickerMonths(browser);
            if (!months.isEmpty()) {
                return months;
            }
            // 外层浮层已开但月未出：不必空等到满超时，交给内层 input 点击
            if (maxWaitMillis <= 1000L && isDateRangePopupOpen(browser) && System.currentTimeMillis() + poll * 2 > deadline) {
                break;
            }
            Extends.sleep(poll);
        }
        return new ArrayList<String>();
    }

    private boolean isDateRangePopupOpen(Browser browser) {
        Boolean open = browser.executeScript(JS_VISIBLE_STRICT +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('[class*=calendar],[class*=Calendar],[class*=picker],[class*=Picker],[class*=dropdown],[class*=Dropdown],[class*=overlay],[class*=Overlay],[class*=popup],[class*=Popup]'));"
                +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var r=e.getBoundingClientRect(),st=getComputedStyle(e),c=((e.className||'')+'').toLowerCase();"
                +
                "if(r.width<160||r.height<60){continue;}if(st.position!=='absolute'&&st.position!=='fixed'&&c.indexOf('overlay')<0&&c.indexOf('dropdown')<0&&c.indexOf('popup')<0){continue;}"
                +
                "var t=norm(e.innerText||e.textContent);if(c.indexOf('mux-calendar-dropdown-overlay')>=0||t.indexOf('选择时间')>=0||t.indexOf('选择日期')>=0||t.indexOf('快捷日期')>=0||/\\d{4}年\\d{1,2}月/.test(t)||t.indexOf('开始')>=0||t.indexOf('结束')>=0){return true;}}"
                +
                "return false;");
        return Boolean.TRUE.equals(open);
    }

    private boolean nativeClickPaymentTimeRange(Browser browser) {
        String selector = "[data-rx-tb-payment-time-range='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-payment-time-range';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}"
                +
                "function rect(el){return el.getBoundingClientRect();}" +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('placeholder')||el.getAttribute('title')||el.getAttribute('aria-label'));}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,button,a,span,div,[role=\"combobox\"]'));"
                +
                "var direct=null;for(var i=0;i<nodes.length;i++){var e=nodes[i],t=text(e);if(visible(e)&&(t==='时间范围'||t.indexOf('时间范围')>=0)){direct=e;break;}}"
                +
                "if(!direct){var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p')),label=null;"
                +
                "for(var l=0;l<labels.length;l++){var lt=text(labels[l]);if(visible(labels[l])&&lt.indexOf('付款时间')>=0&&lt.length<=12){label=labels[l];break;}}"
                +
                "if(label){var lr=rect(label),best=null,bestScore=999999;" +
                "for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n)||n===label||label.contains(n)){continue;}var nr=rect(n),t=text(n);"
                +
                "var dy=Math.abs((nr.top+nr.bottom)/2-(lr.top+lr.bottom)/2),dx=nr.left-lr.right;" +
                "if(dx>=-30&&dy<80&&(/时间|日期|开始|结束|range|date/i.test(t+' '+n.className+' '+n.id)||nr.width>80)){var score=dy*20+Math.max(0,dx)+nr.width/20;if(score<bestScore){best=n;bestScore=score;}}}"
                +
                "direct=best;}}" +
                "if(!direct){return false;}var target=null;" +
                "if(direct.matches&&direct.matches('.mux-calendar-label-container')){target=direct;}" +
                "if(!target&&direct.querySelector){target=direct.querySelector('.mux-calendar-label-container');}" +
                "if(!target&&direct.closest){target=direct.closest('.mux-calendar-label-container');}" +
                "if(!target&&direct.closest){var wrap=direct.closest('.mux-calendar-dropdown-wrapper');if(wrap){target=wrap.querySelector('.mux-calendar-label-container')||wrap;}}"
                +
                "if(!target){target=(direct.querySelector&&direct.querySelector('.next-date-picker,.next-range-picker,.ant-picker,.el-date-editor,input,[role=\"combobox\"]'))||direct.closest('.next-select,.next-date-picker,.next-range-picker,.ant-picker,.el-date-editor,[role=\"combobox\"],button,a')||direct;}"
                +
                "target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        return nativeClickMarkedElement(browser, selector);
    }

    private boolean nativeClickDateInputInRangePopup(Browser browser) {
        String selector = "[data-rx-tb-date-input='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-date-input';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                JS_VISIBLE_STRICT +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function scoreRoot(el){var t=norm(el.innerText||el.textContent);return t.indexOf('选择日期')>=0||t.indexOf('选择时间')>=0||t.indexOf('快捷日期')>=0?0:200;}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.mux-calendar-dropdown-overlay .mux-picker-input-active input,.mux-calendar-dropdown-overlay .mux-picker-input input,input,.next-date-picker,.next-range-picker,.ant-picker,.el-date-editor,[role=\"combobox\"]'));"
                +
                "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)||e.disabled){continue;}var p=e,score=999;"
                +
                "for(var d=0;p&&d<6;d++,p=p.parentElement){score=Math.min(score,scoreRoot(p));}" +
                "var ph=norm(e.getAttribute&&e.getAttribute('placeholder'));if(ph.indexOf('开始日期')>=0){score-=30;}if((e.className||'').indexOf('active')>=0){score-=20;}"
                +
                "var r=e.getBoundingClientRect();score+=r.top/100+r.left/1000;if(score<bestScore){best=e;bestScore=score;}}"
                +
                "if(!best){return false;}var target=best.closest('.mux-picker-input,.mux-picker,.next-date-picker,.next-range-picker,.ant-picker,.el-date-editor,[role=\"combobox\"]')||best;"
                +
                "target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        return nativeClickMarkedElement(browser, selector);
    }

    private boolean nativeClickDateRangeConfirmIfPresent(Browser browser) {
        String selector = "[data-rx-tb-date-confirm='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-date-confirm';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                JS_VISIBLE_STRICT +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var overlays=Array.prototype.slice.call(document.querySelectorAll('.mux-calendar-dropdown-overlay,[class*=calendar][class*=overlay],[class*=picker][class*=dropdown]')).filter(visible);"
                +
                "if(overlays.length===0){return null;}" +
                "var root=overlays[0],nodes=Array.prototype.slice.call(root.querySelectorAll('button,a,[role=\"button\"],span,div'));"
                +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)||e.disabled||e.getAttribute('aria-disabled')==='true'){continue;}var t=norm(e.innerText||e.textContent||e.value||e.getAttribute('title')||e.getAttribute('aria-label'));if(t==='确定'){var target=e.closest('button,a,[role=\"button\"]')||e;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;}}"
                +
                "return false;");
        if (marked == null) {
            return true;
        }
        if (!Boolean.TRUE.equals(marked)) {
            return true;
        }
        if (nativeClickMarkedElement(browser, selector)) {
            Extends.sleep(280L); // 确定后短 settle，等 label 回写
            return true;
        }
        return false;
    }

    private List<String> readDatePickerMonths(Browser browser) {
        List<String> months = browser.executeScript(JS_VISIBLE_STRICT +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var items=[];" +
                "var pickers=Array.prototype.slice.call(document.querySelectorAll('.mux-picker-dropdown,.mux-picker-panel-container,.mux-picker-panels')).filter(visible);"
                +
                "for(var p=0;p<pickers.length;p++){var views=Array.prototype.slice.call(pickers[p].querySelectorAll('.mux-picker-header-view'));"
                +
                "for(var v=0;v<views.length;v++){var view=views[v];if(!visible(view)){continue;}var y=view.querySelector('.mux-picker-year-btn'),m=view.querySelector('.mux-picker-month-btn');"
                +
                "var yt=norm(y&&y.innerText||y&&y.textContent),mt=norm(m&&m.innerText||m&&m.textContent);if(/^\\d{4}年$/.test(yt)&&/^\\d{1,2}月$/.test(mt)){var vr=view.getBoundingClientRect();items.push({text:yt+mt,top:vr.top,left:vr.left});}}}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('div,span,button,th'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],t=norm(e.innerText||e.textContent||e.getAttribute('title'));"
                +
                "if(visible(e)&&/^\\d{4}年\\d{1,2}月$/.test(t)){var r=e.getBoundingClientRect();items.push({text:t,top:r.top,left:r.left});}}"
                +
                "items.sort(function(a,b){return Math.abs(a.top-b.top)>20?a.top-b.top:a.left-b.left;});" +
                "var out=[];for(var j=0;j<items.length;j++){if(out.indexOf(items[j].text)<0){out.push(items[j].text);}}"
                +
                "return out;");
        return months == null ? new ArrayList<String>() : months;
    }

    private boolean nativeClickDatePickerNav(Browser browser, boolean next) {
        return nativeClickDatePickerNavInternal(browser, next, false);
    }

    /** 年跳转：mux super prev/next，跨多月时少点几次。 */
    private boolean nativeClickDatePickerYearNav(Browser browser, boolean next) {
        return nativeClickDatePickerNavInternal(browser, next, true);
    }

    private boolean nativeClickDatePickerNavInternal(Browser browser, boolean next, boolean year) {
        String selector = "[data-rx-tb-picker-nav='1']";
        Boolean marked = browser.executeScript(
                "var next=arguments[0],year=arguments[1],attr='data-rx-tb-picker-nav';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                        +
                        JS_VISIBLE_STRICT +
                        "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                        "function disabled(el){var c=((el.className||'')+'').toLowerCase();return !!el.disabled||el.getAttribute('aria-disabled')==='true'||/disabled|disable/.test(c);}"
                        +
                        "var picker=Array.prototype.slice.call(document.querySelectorAll('.mux-picker-dropdown,.mux-picker-panel-container')).filter(visible)[0];"
                        +
                        "if(picker){var sel=year?(next?'.mux-picker-header-super-next-btn':'.mux-picker-header-super-prev-btn')"
                        +
                        ":(next?'.mux-picker-header-next-btn':'.mux-picker-header-prev-btn');" +
                        "var muxNodes=Array.prototype.slice.call(picker.querySelectorAll(sel)).filter(function(e){"
                        +
                        "var c=((e.className||'')+'');if(!year&&c.indexOf('super')>=0){return false;}return visible(e)&&!disabled(e);});"
                        +
                        "if(muxNodes.length>0){muxNodes[0].setAttribute(attr,'1');muxNodes[0].scrollIntoView({block:'center',inline:'center'});return true;}"
                        +
                        "if(year){return false;}}" +
                        "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,span,i,div'));" +
                        "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)||disabled(e)){continue;}var t=norm(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title),c=((e.className||'')+'').toLowerCase();"
                        +
                        "var ok=year?(next?/下一年|后一年|super-next|double-right|>>|»/.test(t+' '+c):/上一年|前一年|super-prev|double-left|<<|«/.test(t+' '+c))"
                        +
                        ":(next?(t==='>'||t==='›'||/下个月|后一月|next|right|arrow-right/.test(t+' '+c)):(t==='<'||t==='‹'||/上个月|前一月|prev|left|arrow-left/.test(t+' '+c)));"
                        +
                        "if(!ok){continue;}var r=e.getBoundingClientRect();if(r.width>90||r.height>90){continue;}var score=next?(window.innerWidth-r.left):r.left;if(score<bestScore){best=e;bestScore=score;}}"
                        +
                        "if(!best){return false;}var target=best.closest('button,a')||best;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;",
                next, year);
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

    private boolean nativeClickDateInMonth(Browser browser, LocalDate date) {
        String selector = "[data-rx-tb-picker-day='1']";
        Boolean marked = browser.executeScript(
                "var monthText=arguments[0],day=String(arguments[1]),dateText=arguments[2],attr='data-rx-tb-picker-day';"
                        +
                        "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                        +
                        JS_VISIBLE_STRICT +
                        "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                        "function bad(el){var c=((el.className||'')+'').toLowerCase();return /disabled|prev|next|other|off|unavailable/.test(c)||el.getAttribute('aria-disabled')==='true'||(c.indexOf('mux-picker-cell')>=0&&c.indexOf('mux-picker-cell-in-view')<0);}"
                        +
                        "var picker=Array.prototype.slice.call(document.querySelectorAll('.mux-picker-dropdown,.mux-picker-panel-container')).filter(visible)[0];"
                        +
                        "if(picker){var exact=Array.prototype.slice.call(picker.querySelectorAll('td[title=\"'+dateText+'\"],td[title=\"'+dateText+'\"] .mux-picker-cell-inner')).filter(function(e){var cell=e.closest('td')||e;return visible(e)&&visible(cell)&&!bad(cell);});"
                        +
                        "if(exact.length>0){var target=exact[0].closest('td,button,a')||exact[0];target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;}}"
                        +
                        "var headers=Array.prototype.slice.call(document.querySelectorAll('div,span,button,th')).filter(function(e){return visible(e)&&norm(e.innerText||e.textContent||e.title)===monthText;});"
                        +
                        "if(headers.length===0){return false;}headers.sort(function(a,b){var ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();return Math.abs(ar.top-br.top)>20?ar.top-br.top:ar.left-br.left;});"
                        +
                        "var h=headers[0],hr=h.getBoundingClientRect(),root=h;for(var d=0;root.parentElement&&d<8;d++,root=root.parentElement){var rr=root.getBoundingClientRect(),txt=norm(root.innerText||root.textContent);if(txt.indexOf(monthText)>=0&&rr.width>=180&&rr.width<=900&&rr.height>=180&&rr.height<=700){break;}}"
                        +
                        "var nodes=Array.prototype.slice.call(root.querySelectorAll('td,button,span,div'));" +
                        "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)||bad(e)){continue;}var t=norm(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title);if(t!==day){continue;}var r=e.getBoundingClientRect();if(r.top<hr.bottom-5||r.width>80||r.height>80){continue;}var score=Math.abs((r.left+r.right)/2-(hr.left+hr.right)/2)+Math.abs(r.top-hr.bottom);if(score<bestScore){best=e;bestScore=score;}}"
                        +
                        "if(!best){return false;}var target=best.closest('td,button,a')||best;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;",
                formatMonth(date), date.getDayOfMonth(), date.toString());
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

    /**
     * 仅点击文案型「查询/查找订单」。
     * 禁止点击「搜索订单编号」旁的图标按钮：空单号会清空按日期刷出的列表。
     */
    private boolean nativeClickOrderQueryByText(Browser browser) {
        String selector = "[data-rx-tb-order-query='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-order-query';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}"
                +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('aria-label')||'');}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,[role=\"button\"],span,div'));"
                +
                "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}"
                +
                "var c=((e.className||'')+'');if(c.indexOf('mux-input-btn--search')>=0||c.indexOf('btn--search')>=0){continue;}"
                +
                "var t=text(e);if(t!=='查找订单'&&t!=='查询'&&t.indexOf('查找订单')<0){continue;}"
                +
                "if(e.closest&&e.closest('.mux-input--search,.mux-input-addon')){continue;}"
                +
                "var target=e.closest('button,a,[role=\"button\"]')||e,r=target.getBoundingClientRect(),score=r.width*r.height/1000+r.top;"
                +
                "if(score<bestScore){best=target;bestScore=score;}}"
                +
                "if(!best){return false;}best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});return true;");
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

    private void waitOrderRowsSettled(Browser browser, TbPromotionConfig config,
            TbPromotionOrdersResult result, DebugRecorder debug) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(10, config.getPageTimeoutSeconds()) * 1000L;
        long poll = pollIntervalMillis(config, 300);
        // 至少观察到一次「非加载」稳定帧，优先等到有订单行或明确空态
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.waitOrderRowsSettled");
            checkAndHandleSliderVerify(browser, config, result, debug, "order-rows-slider");
            String body = sliderVerifyHandler.readBodySnippet(browser);
            boolean loading = containsAny(body, "加载中", "查询中");
            if (!loading) {
                if (containsAny(body, "父订单编号", "子订单编号")) {
                    return;
                }
                if (containsAny(body, "暂无数据", "没有相关数据", "暂无订单")) {
                    return;
                }
                // 表头已出但数据区未刷完时继续等
                if (containsAny(body, "订单信息", "订单状态", "付款预估")
                        && System.currentTimeMillis() + poll * 2L > deadline) {
                    return;
                }
            }
            Extends.sleep(poll);
        }
    }

    private List<PromotionOrderItem> readCapturedOrderRows(Browser browser, List<PromotionOrderItem> domRows) {
        List<String> captured = browser.drainResponseCapture("report.getTbkOrderDetails.json");
        if (captured == null) return domRows;
        for (int i = captured.size() - 1; i >= 0; i--) {
            try {
                var rows = objectMapper.readTree(captured.get(i)).path("data").path("result");
                if (!rows.isArray()) continue;
                List<PromotionOrderItem> items = TbUnionPageApi.parseOrders(rows);
                if (domRows.isEmpty() && !items.isEmpty()) continue;
                // An earlier automatic date-picker refresh must not replace the final visible page.
                if (!domRows.isEmpty()) {
                    var visibleNumbers = domRows.stream().map(PromotionOrderItem::getOrderNo)
                            .collect(java.util.stream.Collectors.toSet());
                    var capturedNumbers = items.stream().map(PromotionOrderItem::getOrderNo)
                            .collect(java.util.stream.Collectors.toSet());
                    if (!visibleNumbers.equals(capturedNumbers)) continue;
                    for (PromotionOrderItem item : items) {
                        domRows.stream().filter(dom -> item.getOrderNo().equals(dom.getOrderNo())).findFirst().ifPresent(dom -> {
                            if (Strings.isEmpty(item.getProductLink())) item.setProductLink(dom.getProductLink());
                            if (Strings.isEmpty(item.getSkuId())) item.setSkuId(dom.getSkuId());
                        });
                    }
                }
                return items;
            } catch (Exception e) {
                log.debug("Cannot parse naturally captured TB order response: {}", e.getClass().getSimpleName());
            }
        }
        return domRows;
    }

    private List<PromotionOrderItem> readOrderRows(Browser browser) {
        // Use the same field parser for live DOM and saved HTML. Each commission is a
        // separate value; subsidy/service-fee amounts must never be added to it.
        String html = browser.executeScript("function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}"
                + "var rows=Array.prototype.slice.call(document.querySelectorAll('tbody tr,.next-table-row,[class*=\"table-row\"],[class*=\"TableRow\"]')).filter(visible);"
                + "return '<div>'+rows.map(function(row){return row.tagName==='TR'?'<table><tbody>'+row.outerHTML+'</tbody></table>':row.outerHTML;}).join('')+'</div>';");
        List<PromotionOrderItem> items = parseOrderRowsFromHtml(html);
        return items.isEmpty() ? parseOrderRowsFromHtml(readOrderListOuterHtml(browser)) : items;
    }

    private String readOrderListOuterHtml(Browser browser) {
        String html = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}"
                +
                "function score(el){var t=norm(el.innerText||el.textContent),s=0;if(t.indexOf('订单状态')>=0){s+=20;}if(t.indexOf('佣金比例')>=0){s+=20;}if(t.indexOf('预估')>=0){s+=10;}if(t.indexOf('实际')>=0){s+=10;}s+=el.querySelectorAll('tbody tr,.next-table-row,[class*=\"table-row\"],[class*=\"TableRow\"]').length;return s;}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.next-table,table,[class*=\"table\"],[class*=\"Table\"]')).filter(visible);"
                +
                "var best=null,bestScore=-1;for(var i=0;i<nodes.length;i++){var sc=score(nodes[i]);if(sc>bestScore){best=nodes[i];bestScore=sc;}}"
                +
                "return best&&bestScore>0?best.outerHTML:'';");
        return html == null ? "" : html;
    }

    private List<PromotionOrderItem> parseOrderRowsFromHtml(String html) {
        if (Strings.isEmpty(html)) {
            return new ArrayList<PromotionOrderItem>();
        }
        try {
            Document document = Jsoup.parse(html);
            Elements rows = document.select("tbody tr, .next-table-row, [class*=table-row], [class*=TableRow]");
            List<PromotionOrderItem> items = new ArrayList<PromotionOrderItem>();
            for (Element row : rows) {
                String rowText = normalizeText(row.text());
                if (Strings.isEmpty(rowText) || containsAny(rowText, "暂无", "无数据", "没有")) {
                    continue;
                }
                if ((rowText.contains("订单信息") && rowText.contains("订单状态") && !DATE_TIME_PATTERN.matcher(rowText).find())
                        || (rowText.contains("订单状态") && (rowText.contains("佣金比例") || rowText.contains("总提成率"))
                                && !DATE_TIME_PATTERN.matcher(rowText).find())) {
                    continue;
                }
                Elements cells = rowCells(row);
                if (cells.size() < 5) {
                    continue;
                }
                PromotionOrderItem item = parseOrderCells(cells);
                if (Strings.isEmpty(item.getOrderNo()) && Strings.isEmpty(item.getMainOrderNo())) {
                    continue;
                }
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.debug("Parse TB promotion order rows from html fail, error={}", e.getMessage());
            return new ArrayList<PromotionOrderItem>();
        }
    }

    private Elements rowCells(Element row) {
        Elements cells = new Elements();
        for (Element child : row.children()) {
            String className = child.className() == null ? "" : child.className().toLowerCase(Locale.ROOT);
            if ("td".equalsIgnoreCase(child.tagName()) || "th".equalsIgnoreCase(child.tagName())
                    || className.contains("cell")) {
                cells.add(child);
            }
        }
        if (cells.size() < 5) {
            cells = row.select("td, .next-table-cell, [class*=table-cell], [class*=TableCell]");
        }
        return cells;
    }

    private PromotionOrderItem parseOrderCells(Elements cells) {
        PromotionOrderItem item = new PromotionOrderItem();
        Element first = cells.get(0);
        String firstText = normalizeText(first.text());
        fillProductInfo(item, first);
        fillOrderNumbers(item, firstText);
        item.setEstimatedBillingAmount(labeledMoney(firstText, "付款金额"));
        item.setActualBillingAmount(labeledMoney(firstText, "结算金额"));
        item.setOrderTime(labeledDate(firstText, "下单时间|创建时间"));
        item.setPaidTime(labeledDate(firstText, "付款时间|付款"));
        item.setFinishTime(labeledDate(firstText, "结算时间|结算"));
        item.setSettleTime(org.rx.crawler.task.common.OrderTimeFields.nextMonthSettlementDate(item.getFinishTime()));
        if (!Strings.isEmpty(item.getSettleTime())) item.getExtensions().put("settlementDateSource", "receipt-next-month-20");
        item.getExtensions().put("rawEarningTime", item.getFinishTime());
        item.getExtensions().put("parentOrderNo", item.getMainOrderNo());
        item.setOrderStatus(cellTextAt(cells, 1));
        item.setNormalizedStatus(org.rx.crawler.task.order.OrderFields.normalizedStatus("tb", item.getOrderStatus(), item.getExtensions()));
        item.setCommissionRate(firstMatch(cellTextAt(cells, 2), Pattern.compile("\\d+(?:\\.\\d+)?%")));
        item.setEstimatedCommission(commissionMoney(cellTextAt(cells, 3), "付款预估佣金收入|预估佣金"));
        item.setActualCommission(commissionMoney(cellTextAt(cells, 4), "结算预估佣金收入|实际佣金"));
        item.setProductQuantity(firstLabeled(firstText, "(?:商品数量|购买数量)[:：\\s]*(\\d+)"));
        return item;
    }

    private String labeledMoney(String text, String labels) {
        Matcher matcher = Pattern.compile("(?:" + labels + ")[:：\\s]*[￥¥]?\\s*(-?\\d+(?:\\.\\d+)?)")
                .matcher(text == null ? "" : text);
        return matcher.find() ? formatMoney(new BigDecimal(matcher.group(1))) : null;
    }

    private String commissionMoney(String text, String labels) {
        String labeled = labeledMoney(text, labels);
        if (labeled != null) {
            return labeled;
        }
        if (Pattern.compile("(?:" + labels + ")").matcher(text == null ? "" : text).find()) {
            return null;
        }
        // A lone amount is safe; multiple unlabeled amounts have ambiguous meaning.
        Matcher matcher = MONEY_PATTERN.matcher(text == null ? "" : text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        return matcher.find() ? null : formatMoney(new BigDecimal(value));
    }

    private String labeledDate(String text, String labels) {
        String date = "(\\d{4}-\\d{2}-\\d{2}(?:\\s+\\d{2}:\\d{2}:\\d{2})?)";
        String source = text == null ? "" : text;
        // The screenshot uses "datetime付款 / datetime结算". Read the suffix first:
        // otherwise "付款 <next datetime>" accidentally captures the settlement timestamp.
        Matcher after = Pattern.compile(date + "\\s*(?:" + labels + ")").matcher(source);
        if (after.find()) return after.group(1);
        Matcher before = Pattern.compile("(?:" + labels + ")[:：\\s]*" + date).matcher(source);
        return before.find() ? before.group(1) : null;
    }

    private void fillProductInfo(PromotionOrderItem item, Element cell) {
        String productName = "";
        String productLink = "";
        int bestScore = -1;
        for (Element link : cell.select("a[href]")) {
            String text = normalizeText(link.text());
            if (Strings.isEmpty(text) || containsAny(text, "订单", "主单", "复制", "查看")) {
                continue;
            }
            if (text.length() > bestScore) {
                bestScore = text.length();
                productName = text;
                productLink = normalizeText(link.attr("href"));
            }
        }
        List<String> lines = textLines(cell);
        if (Strings.isEmpty(productName) && !lines.isEmpty()) {
            productName = lines.get(0);
        }
        item.setProductName(productName);
        item.setProductLink(productLink);
        Matcher numericId = Pattern.compile("(?:https?:)?//(?:item\\.taobao\\.com|detail\\.tmall\\.com)/[^#]*[?&]id=(\\d+)(?:[&#]|$)")
                .matcher(productLink);
        if (numericId.find()) {
            item.setSkuId(numericId.group(1));
        }
        for (String line : lines) {
            if (line.equals(productName) || containsAny(line, "订单", "主单", "￥", "¥")
                    || DATE_TIME_PATTERN.matcher(line).find()) {
                continue;
            }
            if (Strings.isEmpty(item.getStoreName()) || containsAny(line, "店", "铺")) {
                item.setStoreName(line.replaceFirst("^店铺名[:：\\s]*", ""));
                if (containsAny(line, "店", "铺")) {
                    break;
                }
            }
        }
    }

    private void fillOrderNumbers(PromotionOrderItem item, String text) {
        item.setMainOrderNo(firstLabeled(text, "(?:父订单编号|父订单号|主单号|主订单号|主订单编号)[:：\\s]*([A-Za-z0-9_-]{8,})"));
        item.setOrderNo(firstLabeled(text, "(?:子订单编号|子订单号)[:：\\s]*([A-Za-z0-9_-]{8,})"));
        if (Strings.isEmpty(item.getOrderNo())) {
            item.setOrderNo(firstLabeled(text, "(?<![父子主])(?:订单号|订单编号)[:：\\s]*([A-Za-z0-9_-]{8,})"));
        }
        List<String> numbers = allMatches(text, LONG_NO_PATTERN);
        if (Strings.isEmpty(item.getMainOrderNo()) && !numbers.isEmpty()) {
            item.setMainOrderNo(numbers.get(0));
        }
        if (Strings.isEmpty(item.getOrderNo())) {
            if (numbers.size() > 1) {
                item.setOrderNo(numbers.get(1));
            } else if (!numbers.isEmpty()) {
                item.setOrderNo(numbers.get(numbers.size() - 1));
            }
        }
    }

    private void scrollOrderPageBottom(Browser browser, TbPromotionConfig config) throws TimeoutException {
        // 一次滚到底即可暴露分页；拟人短 settle，避免双次全量 sleep
        ensureTaskDeadline("getTbPromotionOrders.scrollPageBottom");
        browser.executeScript(
                "function canScrollY(e){return e&&e.scrollHeight&&e.clientHeight&&e.scrollHeight>e.clientHeight+8;}"
                        +
                        "function canScrollX(e){return e&&e.scrollWidth&&e.clientWidth&&e.scrollWidth>e.clientWidth+8;}"
                        +
                        "var roots=[document.scrollingElement,document.documentElement,document.body];" +
                        "Array.prototype.slice.call(document.querySelectorAll('*')).forEach(function(e){if(canScrollY(e)||canScrollX(e)){roots.push(e);}});"
                        +
                        "roots.forEach(function(e){try{if(canScrollY(e)){e.scrollTop=e.scrollHeight;}if(canScrollX(e)){e.scrollLeft=e.scrollWidth;}}catch(ex){}});"
                        +
                        "try{document.documentElement.scrollLeft=document.documentElement.scrollWidth;document.body.scrollLeft=document.body.scrollWidth;}catch(ex){}"
                        +
                        "window.scrollTo(Math.max(document.body.scrollWidth,document.documentElement.scrollWidth),Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));");
        Extends.sleep(Math.max(280, config.nextStepDelayMillis() / 2));
    }

    private boolean hasNextOrderPage(Browser browser) {
        Boolean ok = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}"
                +
                "function disabled(el){var c=((el.className||'')+'').toLowerCase();return !!el.disabled||el.getAttribute('aria-disabled')==='true'||/disabled|disable/.test(c);}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.next-pagination button,.next-pagination a,.next-pagination span,.next-pagination li,button,a,li,span'));"
                +
                "for(var i=nodes.length-1;i>=0;i--){var e=nodes[i];if(!visible(e)){continue;}var t=norm(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title),c=((e.className||'')+'').toLowerCase();"
                +
                "if(t.indexOf('下一页')>=0||/next/.test(c)){var target=e.closest('button,a,li')||e;if(!disabled(e)&&!disabled(target)){return true;}}}"
                +
                "return false;");
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickNextOrderPage(Browser browser) {
        String selector = "[data-rx-tb-next-page='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-next-page';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}"
                +
                "function disabled(el){var c=((el.className||'')+'').toLowerCase();return !!el.disabled||el.getAttribute('aria-disabled')==='true'||/disabled|disable/.test(c);}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.next-pagination button,.next-pagination a,.next-pagination span,.next-pagination li,button,a,li,span'));"
                +
                "for(var i=nodes.length-1;i>=0;i--){var e=nodes[i];if(!visible(e)){continue;}var t=norm(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title),c=((e.className||'')+'').toLowerCase();"
                +
                "if(t.indexOf('下一页')>=0||/next/.test(c)){var target=e.closest('button,a,li')||e;if(!disabled(e)&&!disabled(target)){target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;}}}"
                +
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

    @SneakyThrows
    private WebBrowserConfig createBrowserConfig(TbPromotionOrdersRequest request, TbPromotionConfig tbConfig) {
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

    private TbPromotionOrdersRequest normalizeRequest(TbPromotionOrdersRequest request, TbPromotionConfig config) {
        if (request == null) {
            request = new TbPromotionOrdersRequest();
        }
        if (Strings.isEmpty(request.getStartTime()) || Strings.isEmpty(request.getEndTime())) {
            throw new InvalidException("startTime and endTime are required");
        }
        if (Strings.isEmpty(request.getProfileName())) {
            request.setProfileName(Strings.isEmpty(config.getProfileName()) ? profileManager.defaultProfileName()
                    : config.getProfileName());
        }
        request.setProfileName(profileManager.normalizeProfileName(request.getProfileName()));
        LocalDate startDate = LocalDate.parse(request.getStartTime(), DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(request.getEndTime(), DATE_FORMATTER);
        if (startDate.isAfter(endDate)) {
            throw new InvalidException("startTime must be less than or equal to endTime");
        }
        return request;
    }

    private TbPromotionOrdersResult createResult(TbPromotionOrdersRequest request) {
        TbPromotionOrdersResult result = new TbPromotionOrdersResult();
        result.setTaskType(TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setStartTime(request.getStartTime());
        result.setEndTime(request.getEndTime());
        result.setProfileName(request.getProfileName());
        result.setSourceId(request.getSourceId());
        return result;
    }

    private void applyEntryResult(TbPromotionOrdersResult result, CrawlEntryResult entry) {
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
     * 若 body 是「确认登录/快速进入」会话续期，CrawlEntryService 会通过 sessionResumeBodyMatcher 覆盖。
     * 去掉过宽的裸 {@code /login}，避免误伤业务 URL。
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
        return isForwardLandingUrl(browser.getCurrentUrl()) || hasQuickEntrance(sliderVerifyHandler.readBodySnippet(browser));
    }

    private boolean hasQuickEntrance(String body) {
        return containsAny(body, QUICK_ENTER_TEXT, QUICK_LOGIN_TEXT);
    }

    private String rowKey(PromotionOrderItem row) {
        return String.valueOf(row.getOrderNo()) + "|" + row.getMainOrderNo() + "|" + row.getOrderTime() + "|"
                + row.getEstimatedBillingAmount() + "|" + row.getEstimatedCommission() + "|"
                + row.getActualCommission();
    }

    private YearMonth parseYearMonthText(String text) {
        if (Strings.isEmpty(text)) {
            return null;
        }
        Matcher matcher = MONTH_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    private String formatMonth(LocalDate date) {
        return date.getYear() + "年" + date.getMonthValue() + "月";
    }

    private String cellTextAt(Elements cells, int index) {
        if (cells == null || index < 0 || index >= cells.size()) {
            return "";
        }
        return normalizeText(cells.get(index).text());
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private List<String> textLines(Element element) {
        List<String> lines = new ArrayList<String>();
        String text = element == null ? "" : element.wholeText();
        if (Strings.isEmpty(text) && element != null) {
            text = element.text();
        }
        for (String line : text.split("\\R+")) {
            String value = normalizeText(line);
            if (!Strings.isEmpty(value)) {
                lines.add(value);
            }
        }
        if (element != null) {
            for (Element child : element.select("a,div,p,span")) {
                if (!child.children().isEmpty() && !"a".equalsIgnoreCase(child.tagName())) {
                    continue;
                }
                String value = normalizeText(child.text());
                if (!Strings.isEmpty(value) && !lines.contains(value)) {
                    lines.add(value);
                }
            }
        }
        if (lines.isEmpty() && element != null && !Strings.isEmpty(element.text())) {
            lines.add(normalizeText(element.text()));
        }
        return lines;
    }

    private String formatMoney(BigDecimal value) {
        return "￥" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String firstLabeled(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text == null ? "" : text);
        return matcher.find() ? normalizeText(matcher.group(1)) : "";
    }

    private String firstMatch(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group() : "";
    }

    private List<String> allMatches(String text, Pattern pattern) {
        List<String> values = new ArrayList<String>();
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private void fail(TbPromotionOrdersResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message == null ? "" : message);
        if (status == CustomCrawlStatus.LOGIN_REQUIRED) {
            result.setLoginRequired(true);
            notifyLoginRequired(result);
        }
    }

    private void notifyLoginRequired(TbPromotionOrdersResult result) {
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

    private void notifySliderFailed(TbPromotionOrdersResult result, Browser browser, TbPromotionConfig config,
            String stepTag) {
        if (Boolean.TRUE.equals(result.getDiagnostics().get("sliderNotificationAttempted"))) {
            return;
        }
        result.getDiagnostics().put("sliderNotificationAttempted", true);
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType(TASK_TYPE);
        context.setProfileName(result.getProfileName());
        context.setInitialUrl(config.getOrderUrl());
        context.setCurrentUrl(browser.getCurrentUrl());
        context.setMessage("自动滑动验证失败，步骤 " + stepTag);
        context.setLoginWaitSeconds(config.getLoginWaitSeconds());
        context.setKeepBrowserOpenSeconds(config.getKeepBrowserOpenSecondsOnLoginRequired());
        entryService.notifySliderFailed(context);
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

    private boolean isDebugEnabled(TbPromotionOrdersRequest request) {
        if (request != null && request.getDebugEnabled() != null) {
            return request.getDebugEnabled();
        }
        return appConfig.getCustom().isDebugEnabled();
    }

    private String resolveDebugOutputDir(TbPromotionOrdersRequest request, TbPromotionConfig config) {
        if (request != null && !Strings.isEmpty(request.getDebugOutputDir())) {
            return request.getDebugOutputDir();
        }
        return config == null ? null : config.getDebugOutputDir();
    }

    private void ensureTaskDeadline(String stage) throws TimeoutException {
        Long deadline = taskDeadlineHolder.get();
        if (deadline != null && System.currentTimeMillis() > deadline) {
            throw new TimeoutException("TB promotion task timeout at " + stage);
        }
    }

    private final class DebugRecorder {
        private final boolean enabled;
        private final Path taskDir;
        private int stepIndex;

        private DebugRecorder(TbPromotionOrdersRequest request, TbPromotionConfig config) {
            this.enabled = isDebugEnabled(request);
            if (!enabled) {
                this.taskDir = null;
                return;
            }
            String baseDir = resolveDebugOutputDir(request, config);
            String profileName = Strings.isEmpty(request.getProfileName()) ? "common" : request.getProfileName();
            String range = request.getStartTime() + "-" + request.getEndTime();
            String time = LocalDateTime.now().format(DEBUG_TIME_FORMATTER);
            this.taskDir = Paths.get(baseDir, profileName, range + "-" + time);
            try {
                Files.createDirectories(this.taskDir);
                DebugSnapshots.writeUtf8(this.taskDir.resolve("_task.txt"),
                        "taskType=" + TASK_TYPE + System.lineSeparator()
                                + "profileName=" + profileName + System.lineSeparator()
                                + "startTime=" + request.getStartTime() + System.lineSeparator()
                                + "endTime=" + request.getEndTime() + System.lineSeparator());
            } catch (Exception e) {
                log.warn("TB promotion debug directory init fail, path={}, error={}", this.taskDir, e.getMessage(), e);
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
                log.warn("TB promotion debug snapshot fail, step={}, error={}", step, e.getMessage(), e);
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
