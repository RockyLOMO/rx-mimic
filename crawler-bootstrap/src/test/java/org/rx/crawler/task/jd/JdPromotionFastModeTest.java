package org.rx.crawler.task.jd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.CrawlEntryResult;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.common.SensitiveCrawlScope;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdPromotionFastModeTest {
    @TempDir Path tempDir;

    @Test void fastUsesAjaxWithoutMenuAndKeepsCompleteProductAndMaterial() throws Exception {
        Fixture f = new Fixture(true);
        var result = f.task.getPromotionUrl(f.request(true));
        assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
        assertEquals("sample", result.getProductInfo().getProductName());
        assertEquals("https://u.jd.com/test", result.getShortUrl());
        assertEquals("opaque-short-command", result.getPromotionToken());
        assertEquals(4, f.events.size());
        assertTrue(f.events.stream().allMatch(s -> s.startsWith("ajax:")));
        verify(f.browser, never()).elementClick(anyString(), anyBoolean());
        CrawlEntryOptions entry = f.entryOptions();
        assertEquals(f.config.getCustom().getJdUnion().getWorkbenchUrl(), entry.getInitialUrl());
        assertEquals(160, entry.getStepDelayMillis());
        assertEquals(80, entry.getStepDelayRandomMillis());
        assertEquals(550, f.config.getCustom().getJdUnion().getStepDelayMillis());
        assertEquals(300, f.config.getCustom().getJdUnion().getStepDelayRandomMillis());
        verify(f.lease).close();
    }

    @Test void failedFastAjaxFallsBackToNativePageWithoutRepeatingAjax() throws Exception {
        Fixture f = new Fixture(false);
        var result = f.task.getPromotionUrl(f.request(true));
        assertEquals(CustomCrawlStatus.PAGE_CHANGED, result.getStatus());
        assertEquals(Boolean.TRUE, result.getDiagnostics().get("fastPageFallback"));
        assertEquals("ajax:unionSearchGoods", f.events.getFirst());
        assertEquals(1, f.events.stream().filter(s -> s.startsWith("ajax:")).count());
        assertTrue(f.events.contains("menu:我要推广"));
        assertTrue(f.events.contains("menu:商品推广"));
        assertTrue(f.events.contains("page:search-input"));
        verify(f.browser, atLeastOnce()).elementClick(anyString(), eq(false));
    }

    @Test void defaultAndExplicitFalseKeepWorkbenchBeforeAjaxAndOriginalPacing() throws Exception {
        for (Boolean fast : new Boolean[]{null, false}) {
            Fixture f = new Fixture(true);
            var request = f.request(fast);
            var result = f.task.getPromotionUrl(request);
            assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
            assertEquals("menu:我要推广", f.events.getFirst());
            assertTrue(f.events.indexOf("menu:商品推广") < f.events.indexOf("ajax:unionSearchGoods"));
            assertEquals(f.config.getCustom().getJdUnion().getEntireUrl(), f.entryOptions().getInitialUrl());
            assertEquals(550, f.entryOptions().getStepDelayMillis());
            assertEquals(300, f.entryOptions().getStepDelayRandomMillis());
        }
    }

    @Test void loginCheckIgnoresFastAndDoesNotAttemptPromotion() throws Exception {
        Fixture f = new Fixture(true);
        when(f.browser.getRawCookie("https://union.jd.com")).thenReturn("pt_key=test; pt_pin=test");
        // loginCheck does not open the sensitive scope itself; keep debug snapshots synthetic here.
        try (var ignored = SensitiveCrawlScope.open()) {
            var result = f.task.loginCheck(f.request(true));
            assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
            assertEquals(true, result.getDiagnostics().get("loginVerified"));
            assertNull(result.getDiagnostics().get("fast"));
        }
        assertTrue(f.events.isEmpty());
        assertEquals(550, f.entryOptions().getStepDelayMillis());
        assertEquals(300, f.entryOptions().getStepDelayRandomMillis());
        assertEquals(f.config.getCustom().getJdUnion().getWorkbenchUrl(), f.entryOptions().getInitialUrl());
    }

    @Test void loginCheckRejectsUnverifiedSessionEvenWhenEntryPassed() throws Exception {
        Fixture f = new Fixture(true);
        try (var ignored = SensitiveCrawlScope.open()) {
            var result = f.task.loginCheck(f.request(false));
            assertEquals(CustomCrawlStatus.PAGE_CHANGED, result.getStatus());
            assertEquals("LOGIN_NOT_VERIFIED", result.getErrorCode());
        }
        assertTrue(f.events.isEmpty());
    }

    @Test void verifiedLoginRequiresWorkbenchCookiesAndCorrectHostWithoutRiskPage() {
        String cookie = "pt_key=test; pt_pin=test";
        String body = "京东联盟 我要推广 商品推广";
        String prefix = "https://union.jd.com/index?returnUrl=";
        assertTrue(JdUnionPromotionTask.verifiedUnionLogin("https://union.jd.com/entire", body, cookie, prefix));
        assertFalse(JdUnionPromotionTask.verifiedUnionLogin("https://union.jd.com/entire", body, "", prefix));
        assertFalse(JdUnionPromotionTask.verifiedUnionLogin("https://union.jd.com/entire", "", cookie, prefix));
        assertFalse(JdUnionPromotionTask.verifiedUnionLogin("https://union.jd.com/riskinfo", body, cookie, prefix));
        assertFalse(JdUnionPromotionTask.verifiedUnionLogin("https://union.jd.com/entire", body + "安全验证", cookie, prefix));
        assertFalse(JdUnionPromotionTask.verifiedUnionLogin("https://example.com/entire", body, cookie, prefix));
    }

    @Test void fastConfigIsLocalAndDoesNotIncreaseAlreadyShortDelays() {
        JdUnionConfig original = new JdUnionConfig();
        JdUnionConfig fast = JdUnionPromotionTask.promotionConfig(original, true);
        assertNotSame(original, fast);
        assertSame(original, JdUnionPromotionTask.promotionConfig(original, false));
        assertEquals(original.getDefaultMediaId(), fast.getDefaultMediaId());
        assertEquals(original.getAdSites(), fast.getAdSites());
        assertEquals(original.isPreflightEnabled(), fast.isPreflightEnabled());
        original.setStepDelayMillis(70);
        original.setStepDelayRandomMillis(20);
        JdUnionConfig shortConfig = JdUnionPromotionTask.promotionConfig(original, true);
        assertEquals(70, shortConfig.getStepDelayMillis());
        assertEquals(20, shortConfig.getStepDelayRandomMillis());
        assertFalse(new PromotionUrlRequest().getFast());
    }

    private final class Fixture {
        final AppConfig config = new AppConfig();
        final ObjectMapper mapper = new ObjectMapper();
        final List<String> events = new ArrayList<>();
        final WebBrowser browser;
        final BrowserProfileManager profiles = mock(BrowserProfileManager.class);
        final BrowserProfileManager.ProfileLease lease = mock(BrowserProfileManager.ProfileLease.class);
        final CrawlEntryService entry = mock(CrawlEntryService.class);
        final JdUnionPromotionTask task;

        Fixture(boolean ajaxSuccess) throws Exception {
            browser = mock(WebBrowser.class, invocation -> {
                if (!invocation.getMethod().getName().equals("executeScript")) {
                    return RETURNS_DEFAULTS.answer(invocation);
                }
                String script = invocation.getArgument(0);
                if (invocation.getArguments().length == 2 && invocation.getArgument(1) instanceof String spec
                        && spec.startsWith("{\"functionId\"")) {
                    String function = mapper.readTree(spec).path("functionId").asText();
                    events.add("ajax:" + function);
                    if (!ajaxSuccess) return "{\"ok\":false,\"error\":\"unavailable\"}";
                    String body = switch (function) {
                        case "unionSearchGoods" -> "{\"result\":{\"goodsSynopsisList\":[{\"skuId\":1001,\"skuName\":\"sample\",\"wlPrice\":10,\"wlCommissionRatio\":2,\"shopInfo\":{\"shopName\":\"sample shop\"},\"imageUrl\":\"jfs/test.jpg\"}]}}";
                        case "unionPromoteLinkService" -> "{\"shortUrl\":\"https://u.jd.com/test\",\"jShortCommand\":\"opaque-short-command\"}";
                        default -> function.toLowerCase().contains("media")
                                ? "{\"result\":[{\"id\":\"4101055439\",\"mediaName\":\"wx\"}]}"
                                : "{\"result\":[{\"id\":\"3100287104\",\"promotionName\":\"0\"}]}";
                    };
                    return "{\"ok\":true,\"body\":" + body + "}";
                }
                if (script.contains("var attr='data-rx-jd-click-target'")) {
                    events.add("menu:" + invocation.getArgument(1));
                    return true;
                }
                if (script.contains("var attr='data-rx-jd-search-input'")) {
                    events.add("page:search-input");
                    return false; // stop after proving native fallback reached the search input
                }
                if (script.startsWith("var value=String(arguments[0]")) return false;
                if (script.contains("return hasInput&&hasButton")) return true;
                return "京东联盟 我要推广 商品推广";
            });
            when(browser.getCurrentUrl()).thenReturn(config.getCustom().getJdUnion().getOverviewUrl());
            when(profiles.normalizeProfileName(any())).thenReturn("common");
            when(profiles.resolveProfileDataPath(any())).thenReturn(tempDir.resolve("profile").toString());
            when(profiles.acquire(anyString(), any())).thenReturn(lease);
            when(lease.getBrowser()).thenReturn(browser);
            CrawlEntryResult passed = new CrawlEntryResult();
            passed.setPassed(true);
            passed.setFingerprintPassed(true);
            passed.setCurrentUrl(config.getCustom().getJdUnion().getOverviewUrl());
            when(entry.enter(eq(browser), eq(lease), any())).thenReturn(passed);
            task = new JdUnionPromotionTask(config, profiles, entry, mapper);
        }

        PromotionUrlRequest request(Boolean fast) {
            var request = new PromotionUrlRequest();
            request.setKeyword("1001");
            request.setAdSiteName("0");
            request.setProfileName("common");
            request.setDebugEnabled(true);
            request.setDebugOutputDir(tempDir.toString());
            if (fast != null) request.setFast(fast);
            return request;
        }

        CrawlEntryOptions entryOptions() throws Exception {
            var capture = ArgumentCaptor.forClass(CrawlEntryOptions.class);
            verify(entry, atLeastOnce()).enter(eq(browser), eq(lease), capture.capture());
            return capture.getValue();
        }
    }
}
