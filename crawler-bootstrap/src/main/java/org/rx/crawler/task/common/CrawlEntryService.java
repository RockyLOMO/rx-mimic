package org.rx.crawler.task.common;

import lombok.RequiredArgsConstructor;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CrawlEntryService {
    private final BrowserPreflightService preflightService;
    private final LoginNotificationService loginNotificationService;

    public CrawlEntryService(BrowserPreflightService preflightService) {
        this(preflightService, LoginNotificationService.NOOP);
    }

    public CrawlEntryResult enter(Browser browser, BrowserProfileManager.ProfileLease lease, CrawlEntryOptions options)
            throws TimeoutException {
        CrawlEntryResult result = new CrawlEntryResult();
        if (!runPreflight(browser, options, result)) {
            return result;
        }

        browser.navigateUrl(options.getInitialUrl(), Browser.BODY_SELECTOR, options.getInitialPageTimeoutSeconds());
        Extends.sleep(Math.max(400, options.nextStepDelayMillis() * 2 / 3));
        result.setCurrentUrl(browser.getCurrentUrl());
        String body = bodySnippet(browser);
        result.getDiagnostics().put("entryBodySnippet", snippet(body, 500));

        // 会话续期中间页：common profile 已记住登录，仅需业务侧点「快速进入」，不进人工等登录
        if (isSessionResumeBody(body, options)) {
            result.setPassed(true);
            result.getDiagnostics().put("sessionResume", true);
            result.getDiagnostics().put("loginDetectedBy", "session-resume");
            return result;
        }

        // URL 或 body 任一判定为硬登录页 → 等人工登录（解决 proManager/overview URL 仍显示登录框的问题）
        if (!needsLogin(browser.getCurrentUrl(), body, options)) {
            result.setPassed(true);
            result.getDiagnostics().put("loginDetectedBy", "none");
            return result;
        }

        result.setLoginRequired(true);
        notifyLoginRequired(options, result.getCurrentUrl(),
                "Login required. Finish login in the opened Chrome profile, then retry.");
        result.getDiagnostics().put("loginNotificationAttempted", true);
        result.getDiagnostics().put("loginDetectedBy", describeLoginDetection(browser.getCurrentUrl(), body, options));
        if (options.isKeepBrowserOpenOnLoginRequired() && waitLoginCompleted(browser, options, result)) {
            result.setLoginRequired(false);
            result.setCurrentUrl(browser.getCurrentUrl());
            result.getDiagnostics().put("loginWaitCompleted", true);
            result.setPassed(true);
            return result;
        }

        result.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
        result.setMessage("Login required. Finish login in the opened Chrome profile, then retry.");
        if (options.isKeepBrowserOpenOnLoginRequired()) {
            lease.keepOpen(options.getKeepBrowserOpenSecondsOnLoginRequired());
        }
        return result;
    }

    /**
     * 业务中途发现登录页时复用同一套等待逻辑（URL + body）。
     */
    public boolean waitLoginCompleted(Browser browser, CrawlEntryOptions options, CrawlEntryResult result) {
        int waitSeconds = Math.max(0, options.getLoginWaitSeconds());
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        result.getDiagnostics().put("loginWaitSeconds", waitSeconds);
        try {
            browser.maximize();
            browser.focus();
        } catch (Exception ignored) {
        }
        while (System.currentTimeMillis() < deadline) {
            Extends.sleep(Math.max(1000, options.nextStepDelayMillis()));
            String currentUrl = browser.getCurrentUrl();
            String body = bodySnippet(browser);
            result.setCurrentUrl(currentUrl);
            if (needsLogin(currentUrl, body, options)) {
                continue;
            }
            if (isLoggedInUrl(currentUrl, options) || isInitialUrl(currentUrl, options)) {
                return true;
            }
            if (options.getLoggedInBodyMatcher() != null && options.getLoggedInBodyMatcher().test(body)) {
                return true;
            }
            // 不再需要登录，也有一定业务正文 → 视为已登录
            if (!Strings.isEmpty(body) && body.length() > 80
                    && options.getLoginRequiredBodyMatcher() != null
                    && !options.getLoginRequiredBodyMatcher().test(body)) {
                return true;
            }
        }
        return false;
    }

    public boolean needsLogin(Browser browser, CrawlEntryOptions options) {
        return needsLogin(browser.getCurrentUrl(), bodySnippet(browser), options);
    }

    public void notifyLoginRequired(LoginNotificationContext context) {
        loginNotificationService.notifyLoginRequired(context);
    }

    public void notifyLoginRequired(CrawlEntryOptions options, String currentUrl, String message) {
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType(options.getTaskType());
        context.setProfileName(options.getProfileName());
        context.setInitialUrl(options.getInitialUrl());
        context.setCurrentUrl(currentUrl);
        context.setMessage(message);
        context.setLoginWaitSeconds(options.getLoginWaitSeconds());
        context.setKeepBrowserOpenSeconds(options.getKeepBrowserOpenSecondsOnLoginRequired());
        notifyLoginRequired(context);
    }

    private boolean runPreflight(Browser browser, CrawlEntryOptions options, CrawlEntryResult result) {
        BrowserPreflightResult preflight = preflightService.check(browser, options, options.isForcePreflight());
        result.setFingerprintPassed(preflight.isPassed());
        result.getDiagnostics().put("preflight", preflight);
        if (preflight.isPassed()) {
            return true;
        }

        result.setStatus(CustomCrawlStatus.BROWSER_FINGERPRINT_CHECK_FAILED);
        result.setMessage(preflight.getMessage());
        return false;
    }

    public static boolean needsLogin(String currentUrl, String body, CrawlEntryOptions options) {
        // 会话续期页（确认登录/快速进入）优先：Cookie 仍有效，不算硬登录
        if (isSessionResumeBody(body, options)) {
            return false;
        }
        if (isLoginRequiredUrl(currentUrl, options)) {
            return true;
        }
        return isLoginRequiredByBody(body, options);
    }

    static boolean isSessionResumeBody(String body, CrawlEntryOptions options) {
        return !Strings.isEmpty(body)
                && options.getSessionResumeBodyMatcher() != null
                && options.getSessionResumeBodyMatcher().test(body);
    }

    static boolean isLoginRequiredUrl(String currentUrl, CrawlEntryOptions options) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        if (options.getLoginRequiredUrlMatcher() != null) {
            return options.getLoginRequiredUrlMatcher().test(currentUrl);
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        return (!Strings.isEmpty(options.getLoginUrlPrefix()) && currentUrl.startsWith(options.getLoginUrlPrefix()))
                || lower.contains("passport.")
                || lower.contains("/login");
    }

    static boolean isLoginRequiredByBody(String body, CrawlEntryOptions options) {
        return !Strings.isEmpty(body)
                && options.getLoginRequiredBodyMatcher() != null
                && options.getLoginRequiredBodyMatcher().test(body);
    }

    private boolean isLoggedInUrl(String currentUrl, CrawlEntryOptions options) {
        return !Strings.isEmpty(currentUrl)
                && options.getLoggedInUrlMatcher() != null
                && options.getLoggedInUrlMatcher().test(currentUrl);
    }

    private boolean isInitialUrl(String currentUrl, CrawlEntryOptions options) {
        return !Strings.isEmpty(currentUrl)
                && !Strings.isEmpty(options.getInitialUrl())
                && currentUrl.startsWith(options.getInitialUrl());
    }

    private String describeLoginDetection(String url, String body, CrawlEntryOptions options) {
        boolean byUrl = isLoginRequiredUrl(url, options);
        boolean byBody = isLoginRequiredByBody(body, options);
        if (byUrl && byBody) {
            return "url+body";
        }
        if (byUrl) {
            return "url";
        }
        if (byBody) {
            return "body";
        }
        return "none";
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
                    Extends.sleep(500L * (i + 1));
                    continue;
                }
                return "";
            }
        }
        return snippet(body, 2000);
    }

    private String snippet(String body, int max) {
        if (Strings.isEmpty(body) || body.length() <= max) {
            return body;
        }
        return body.substring(0, max);
    }
}
