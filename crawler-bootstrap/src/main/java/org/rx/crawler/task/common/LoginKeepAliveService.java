package org.rx.crawler.task.common;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.jd.JdLoginState;
import org.rx.crawler.task.price.PriceWatchCoordinator;
import org.rx.crawler.task.jd.JdUnionConfig;
import org.rx.crawler.task.tb.TbPromotionConfig;
import org.rx.util.BeanMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.rx.core.Extends.tryClose;

@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class LoginKeepAliveService {
    private static final String JD_PLATFORM = "jd";
    private static final String TB_PLATFORM = "tb";
    private static final String JD_TASK_TYPE = "jdLoginKeepAlive";
    private static final String TB_TASK_TYPE = "tbLoginKeepAlive";

    private final AppConfig appConfig;
    @Autowired(required = false)
    private BrowserOwnerExecutor ownerExecutor;
    @Autowired(required = false)
    private PriceWatchCoordinator priceWatch;
    private final BrowserProfileManager profileManager;
    private final CrawlEntryService entryService;
    private final KeepAliveUrlStore keepAliveUrlStore;
    private java.util.function.BooleanSupplier executionGate = () -> true;

    public void setExecutionGate(java.util.function.BooleanSupplier gate) { this.executionGate = gate; }

    private final AtomicBoolean running = new AtomicBoolean(false);

    public LoginKeepAliveService(AppConfig appConfig, BrowserProfileManager profileManager,
            CrawlEntryService entryService) {
        this(appConfig, profileManager, entryService, KeepAliveUrlStore.NOOP);
    }

    @Scheduled(initialDelayString = "${app.custom.loginKeepAlive.initialDelayMillis:60000}",
            fixedDelayString = "${app.custom.loginKeepAlive.fixedDelayMillis:1800000}")
    public void scheduledKeepAlive() {
        AppConfig.LoginKeepAliveConfig config = appConfig.getCustom().getLoginKeepAlive();
        if (config == null || !config.isEnabled() || !executionGate.getAsBoolean()) {
            return;
        }
        if (!isInKeepAliveTimeRange(config)) {
            log.debug("Current time is not in keep-alive range [{} - {}], skip scheduled run",
                    config.getStartTime(), config.getEndTime());
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            if (config.isJdEnabled() && allowPlatform("jd")) {
                checkJd();
            }
            if (config.isTbEnabled() && allowPlatform("tb")) {
                checkTb();
            }
        } finally {
            running.set(false);
        }
    }

    private boolean allowPlatform(String platform) {
        return priceWatch == null || priceWatch.allowKeepAlive(platform);
    }

    boolean isInKeepAliveTimeRange(AppConfig.LoginKeepAliveConfig config) {
        String startTime = config.getStartTime();
        String endTime = config.getEndTime();
        if (Strings.isEmpty(startTime) || Strings.isEmpty(endTime)) {
            return true;
        }
        try {
            java.time.LocalTime start = parseLocalTime(startTime);
            java.time.LocalTime end = parseLocalTime(endTime);
            java.time.LocalTime now = java.time.LocalTime.now();
            if (start.isBefore(end)) {
                return !now.isBefore(start) && !now.isAfter(end);
            } else {
                return !now.isBefore(start) || !now.isAfter(end);
            }
        } catch (Exception e) {
            log.warn("Parse loginKeepAlive startTime {} or endTime {} failed, fallback to 24h open: {}",
                    startTime, endTime, e.getMessage());
            return true;
        }
    }

    private java.time.LocalTime parseLocalTime(String timeStr) {
        String trimmed = timeStr.trim();
        if (trimmed.length() == 5) {
            return java.time.LocalTime.parse(trimmed, java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        }
        return java.time.LocalTime.parse(trimmed);
    }

    public LoginKeepAliveResult checkJd() {
        if (ownerExecutor != null && !ownerExecutor.isOwnerThread()) {
            return ownerExecutor.call(this::checkJd);
        }
        if (!executionGate.getAsBoolean()) { throw new IllegalStateException("Browser maintenance unavailable or draining"); }
        JdUnionConfig config = appConfig.getCustom().getJdUnion();
        String profileName = profileManager.normalizeProfileName(Strings.isEmpty(config.getProfileName())
                ? profileManager.defaultProfileName() : config.getProfileName());
        List<String> urls = new ArrayList<String>();
        addUrl(urls, config.getOverviewUrl());
        addUrl(urls, config.getEntireUrl());
        addUrl(urls, config.getOrderUrl());
        addUrl(urls, config.getWorkbenchUrl());
        urls = keepAliveUrlStore.getCandidateUrls(JD_PLATFORM, urls);
        return check(JD_PLATFORM, JD_TASK_TYPE, profileName, pickUrl(urls), config.getLoginWaitSeconds(),
                config.getKeepBrowserOpenSecondsOnLoginRequired(), config.isHeadless(), config.isFingerprintEnabled());
    }

    public LoginKeepAliveResult checkTb() {
        if (ownerExecutor != null && !ownerExecutor.isOwnerThread()) {
            return ownerExecutor.call(this::checkTb);
        }
        if (!executionGate.getAsBoolean()) { throw new IllegalStateException("Browser maintenance unavailable or draining"); }
        TbPromotionConfig config = appConfig.getCustom().getTbPromotion();
        String profileName = profileManager.normalizeProfileName(Strings.isEmpty(config.getProfileName())
                ? profileManager.defaultProfileName() : config.getProfileName());
        List<String> urls = new ArrayList<String>();
        addUrl(urls, config.getHomeUrl());
        addUrl(urls, config.getPromotionGoodsUrl());
        addUrl(urls, config.getOrderUrl());
        urls = keepAliveUrlStore.getCandidateUrls(TB_PLATFORM, urls);
        return check(TB_PLATFORM, TB_TASK_TYPE, profileName, pickUrl(urls), config.getLoginWaitSeconds(),
                config.getKeepBrowserOpenSecondsOnLoginRequired(), config.isHeadless(), config.isFingerprintEnabled());
    }

    boolean isLoginUnhealthy(String platform, String currentUrl, String body) {
        String lowerUrl = value(currentUrl).toLowerCase(Locale.ROOT);
        String text = value(body);
        if (JD_PLATFORM.equals(platform)) {
            return JdLoginState.unionLoginWall(currentUrl, text,
                    appConfig.getCustom().getJdUnion().getLoginUrlPrefix());
        }
        return lowerUrl.contains("pub.alimama.com") && lowerUrl.contains("login_jump")
                || lowerUrl.startsWith(value(appConfig.getCustom().getTbPromotion().getLoginUrlPrefix()).toLowerCase(Locale.ROOT))
                || lowerUrl.contains("login.taobao.com")
                || lowerUrl.contains("havanalogin")
                || (lowerUrl.contains("/login") && !lowerUrl.contains("login_jump"))
                || (containsAny(text, "扫码登录", "密码登录", "验证码") && containsAny(text, "登录"));
    }

    private LoginKeepAliveResult check(String platform, String taskType, String profileName, String url,
            int loginWaitSeconds, int keepBrowserOpenSeconds, boolean headless, boolean fingerprintEnabled) {
        LoginKeepAliveResult result = new LoginKeepAliveResult();
        result.setPlatform(platform);
        result.setTaskType(taskType);
        result.setProfileName(profileName);
        result.setCheckedUrl(url);
        result.getDiagnostics().put("candidateUrlSource", "harvested+default");
        BrowserProfileManager.ProfileLease lease = null;
        try {
            lease = profileManager.acquire(profileName, createBrowserConfig(profileName, headless, fingerprintEnabled));
            Browser browser = lease.getBrowser();
            if (!passBotCheck(browser, profileName)) {
                result.setHealthy(false);
                result.setStatus(CustomCrawlStatus.BROWSER_FINGERPRINT_CHECK_FAILED);
                result.setMessage("Bot check failed. Business page was not opened.");
                log.warn("Login keep-alive skipped, bot check failed, platform={}, profile={}", platform, profileName);
                return result;
            }
            browser.navigateUrl(url, Browser.BODY_SELECTOR, appConfig.getCustom().getLoginKeepAlive().getPageTimeoutSeconds());
            Extends.sleep(800);
            String currentUrl = browser.getCurrentUrl();
            String body = bodySnippet(browser);
            result.setCurrentUrl(currentUrl);
            result.getDiagnostics().put("bodySnippet", body);
            if (isLoginUnhealthy(platform, currentUrl, body)) {
                result.setHealthy(false);
                result.setLoginRequired(true);
                result.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
                result.setMessage("Login keep-alive unhealthy. Finish login in the opened Chrome profile, then retry.");
                lease.keepOpen(keepBrowserOpenSeconds);
                notifyLoginRequired(taskType, profileName, url, currentUrl, result.getMessage(), loginWaitSeconds,
                        keepBrowserOpenSeconds);
                log.warn("Login keep-alive unhealthy, platform={}, profile={}, url={}, currentUrl={}",
                        platform, profileName, url, currentUrl);
                return result;
            }
            result.setHealthy(true);
            result.setStatus(CustomCrawlStatus.SUCCESS);
            result.setMessage("Login keep-alive healthy");
            log.info("Login keep-alive healthy, platform={}, profile={}, url={}", platform, profileName, url);
            return result;
        } catch (Exception e) {
            result.setHealthy(false);
            result.setStatus(CustomCrawlStatus.FAILED);
            result.setMessage(e.getMessage());
            log.warn("Login keep-alive fail, platform={}, profile={}, url={}, error={}",
                    platform, profileName, url, e.getMessage(), e);
            return result;
        } finally {
            tryClose(lease);
        }
    }

    @SneakyThrows
    private WebBrowserConfig createBrowserConfig(String profileName, boolean headless, boolean fingerprintEnabled) {
        WebBrowserConfig config = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
        config.setProfileDataPath(profileManager.resolveProfileDataPath(profileName));
        config.setHeadless(headless);
        config.setFingerprintEnabled(fingerprintEnabled);
        config.setFingerprintHeadless(headless);
        if (Strings.isEmpty(config.getConfigureScriptExecutorType())) {
            config.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        }
        return config;
    }

    private boolean passBotCheck(Browser browser, String profileName) {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setPreflightEnabled(true);
        options.setPreflightStrict(true);
        options.setForcePreflight(true);
        options.setPreflightUrl(SannysoftFingerprintEvaluator.DEFAULT_URL);
        options.setProfileName(profileName);
        options.setInitialPageTimeoutSeconds(appConfig.getCustom().getLoginKeepAlive().getPageTimeoutSeconds());
        BrowserPreflightResult preflight = new BrowserPreflightService().check(browser, options, true);
        return preflight.isPassed();
    }

    private void notifyLoginRequired(String taskType, String profileName, String initialUrl, String currentUrl,
            String message, int loginWaitSeconds, int keepBrowserOpenSeconds) {
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType(taskType);
        context.setProfileName(profileName);
        context.setInitialUrl(initialUrl);
        context.setCurrentUrl(currentUrl);
        context.setMessage(message);
        context.setLoginWaitSeconds(loginWaitSeconds);
        context.setKeepBrowserOpenSeconds(keepBrowserOpenSeconds);
        entryService.notifyLoginRequired(context);
    }

    private void addUrl(List<String> urls, String url) {
        if (!Strings.isEmpty(url)) {
            urls.add(url);
        }
    }

    private String pickUrl(List<String> urls) {
        if (urls.isEmpty()) {
            throw new IllegalStateException("Login keep-alive url is empty");
        }
        return urls.get(ThreadLocalRandom.current().nextInt(urls.size()));
    }

    private String bodySnippet(Browser browser) {
        String body = "";
        try {
            body = browser.executeScript("return document.body ? document.body.innerText : '';");
        } catch (Exception e) {
            return "";
        }
        return Strings.isEmpty(body) || body.length() <= 2000 ? body : body.substring(0, 2000);
    }

    private boolean containsAny(String value, String... keywords) {
        if (Strings.isEmpty(value)) {
            return false;
        }
        for (String keyword : keywords) {
            if (!Strings.isEmpty(keyword) && value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
