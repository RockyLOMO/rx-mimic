package org.rx.crawler.task.jd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.*;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdPromotionRateLimitRetryTest {
    private static final String LIMITED = "访问频繁，请稍后重试 抱歉，没有找到相关商品";
    private static final String FOUND = "所有结果 共1件商品 100256373049";

    @Test void ordinaryResultsAndRealNoResultsAreNotRetried() {
        for (String body : new String[]{FOUND, "抱歉，没有找到相关商品"}) {
            Fixture f = new Fixture(0); f.body = body;
            assertEquals(body, f.run());
            assertEquals(0, f.clicks);
            assertEquals(0, f.waitMillis);
        }
    }

    @Test void stopsImmediatelyAfterTheFirstSuccessfulRetry() {
        Fixture f = new Fixture(1);
        assertEquals(FOUND, f.run());
        assertEquals(1, f.clicks);
        assertEquals(1, f.result.getDiagnostics().get("searchRateLimitRetryCount"));
        assertEquals(3000, f.firstClickWaitMillis);
    }

    @Test void performsExactlyThreeRetriesAndCanSucceedOnTheThird() {
        Fixture f = new Fixture(3);
        assertEquals(FOUND, f.run());
        assertEquals(3, f.clicks);
        assertEquals(3, f.result.getDiagnostics().get("searchRateLimitRetryCount"));
        assertTrue(f.waitMillis >= 9000);
    }

    @Test void persistentRateLimitingStopsAfterThreeClicks() {
        Fixture f = new Fixture(Integer.MAX_VALUE);
        assertEquals(LIMITED, f.run());
        assertEquals(3, f.clicks);
    }

    @Test void anExpiredRateLimitToastDoesNotTurnThePreviousEmptyListIntoARealNoResult() {
        Fixture f = new Fixture(2); f.expireToastDuringCooldown = true;
        assertEquals(FOUND, f.run());
        assertEquals(2, f.clicks);
    }

    @Test void keepsTheRateLimitFailureWhenTheFinalToastExpiresWithoutResults() {
        Fixture f = new Fixture(Integer.MAX_VALUE); f.expireToastDuringCooldown = true;
        assertEquals(LIMITED, f.run());
        assertEquals(3, f.clicks);
    }

    @Test void waitsPastAnEmptyPlaceholderAndStopsWhenThePendingRetryCompletes() {
        Fixture f = new Fixture(1); f.delayResultsUntilNextCooldown = true;
        assertEquals(FOUND, f.run());
        assertEquals(1, f.clicks);
    }

    @Test void failedNativeClickReturnsAnExplicitFailure() {
        Fixture f = new Fixture(1); f.buttonAvailable = false;
        assertNull(f.run());
        assertEquals("PROMOTION_SEARCH_RETRY_FAILED", f.result.getErrorCode());
        assertEquals(0, f.clicks);
    }

    @Test void cancellationDuringTheThreeSecondWaitPreventsAnyRetryClick() {
        Fixture f = new Fixture(1);
        TaskCancellation.Token token = new TaskCancellation.Token();
        f.cancelOnWait = token;
        try (var ignored = TaskCancellation.open(token)) {
            assertThrows(TaskCancellation.Cancelled.class, f::run);
        }
        assertEquals(0, f.clicks);
    }

    private static final class Fixture {
        final PromotionUrlResult result = new PromotionUrlResult();
        final AppConfig config = new AppConfig();
        final JdUnionPromotionTask task;
        final Browser browser;
        String body = LIMITED;
        boolean buttonAvailable = true;
        boolean expireToastDuringCooldown;
        boolean delayResultsUntilNextCooldown;
        int clicks;
        long waitMillis, firstClickWaitMillis;
        TaskCancellation.Token cancelOnWait;

        Fixture(int succeedOnClick) {
            config.getCustom().setDebugEnabled(true);
            browser = mock(Browser.class, invocation -> {
                switch (invocation.getMethod().getName()) {
                    case "executeScript":
                        String script = invocation.getArgument(0);
                        if (script.contains("var attr='data-rx-jd-search-button'")) return buttonAvailable;
                        if (script.contains("document.body ? document.body.innerText")) return body;
                        return RETURNS_DEFAULTS.answer(invocation);
                    case "waitForEvents":
                        waitMillis += (long) invocation.getArgument(0);
                        if (expireToastDuringCooldown && clicks > 0 && clicks < succeedOnClick
                                && (long) invocation.getArgument(0) == 100) body = "抱歉，没有找到相关商品";
                        if (delayResultsUntilNextCooldown && clicks >= succeedOnClick
                                && (long) invocation.getArgument(0) == 100) body = FOUND;
                        if (cancelOnWait != null) cancelOnWait.cancel();
                        return null;
                    case "elementClick":
                        if (++clicks == 1) firstClickWaitMillis = waitMillis;
                        body = clicks >= succeedOnClick ? FOUND : LIMITED;
                        if (delayResultsUntilNextCooldown) body = "抱歉，没有找到相关商品";
                        return null;
                    default:
                        return RETURNS_DEFAULTS.answer(invocation);
                }
            });
            task = new JdUnionPromotionTask(config, mock(BrowserProfileManager.class),
                    mock(CrawlEntryService.class), new ObjectMapper());
        }

        String run() {
            return ReflectionTestUtils.invokeMethod(task, "waitPromotionSearchResult", browser,
                    config.getCustom().getJdUnion(), "100256373049", result);
        }
    }
}
