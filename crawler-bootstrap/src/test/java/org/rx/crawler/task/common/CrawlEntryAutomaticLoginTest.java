package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.rx.crawler.service.Browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrawlEntryAutomaticLoginTest {
    @Test
    void passwordLoginRunsBeforeTheHumanWait() throws Exception {
        Browser browser = mock(Browser.class);
        when(browser.getCurrentUrl()).thenReturn("https://union.jd.com/index?returnUrl=https://union.jd.com/entire",
                "https://union.jd.com/entire");
        when(browser.executeScript(anyString())).thenReturn("请使用京东商城账号登录", "我要推广 商品推广");
        CrawlEntryOptions options = loginOptions();
        options.setAutomaticLogin(() -> true);

        CrawlEntryResult result = service().enter(browser, null, options);

        assertTrue(result.isPassed());
        assertFalse(result.isLoginRequired());
        assertEquals(true, result.getDiagnostics().get("passwordLoginCompleted"));
        assertEquals("password-login", result.getDiagnostics().get("loginDetectedBy"));
    }

    @Test
    void failedPasswordLoginFallsThroughWithoutWaitingWhenWaitIsZero() throws Exception {
        Browser browser = mock(Browser.class);
        when(browser.getCurrentUrl()).thenReturn("https://union.jd.com/index?returnUrl=https://union.jd.com/entire");
        when(browser.executeScript(anyString())).thenReturn("请使用京东商城账号登录");
        CrawlEntryOptions options = loginOptions();
        options.setAutomaticLogin(() -> false);

        CrawlEntryResult result = service().enter(browser, null, options);

        assertFalse(result.isPassed());
        assertTrue(result.isLoginRequired());
        assertEquals(true, result.getDiagnostics().get("automaticLoginAttempted"));
        assertEquals(false, result.getDiagnostics().get("automaticLoginReturned"));
    }

    private static CrawlEntryService service() {
        return new CrawlEntryService(new BrowserPreflightService(), LoginNotificationService.NOOP);
    }

    private static CrawlEntryOptions loginOptions() {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setPreflightEnabled(false);
        options.setInitialUrl("https://union.jd.com/entire");
        options.setLoginWaitSeconds(0);
        options.setKeepBrowserOpenOnLoginRequired(false);
        options.setStepDelayMillis(0);
        options.setStepDelayRandomMillis(0);
        options.setLoginRequiredUrlMatcher(url -> url != null && url.contains("union.jd.com/index?"));
        options.setLoginRequiredBodyMatcher(body -> body != null && body.contains("请使用京东商城账号登录"));
        options.setLoggedInBodyMatcher(body -> body != null && body.contains("我要推广"));
        return options;
    }
}
