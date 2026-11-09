package org.rx.crawler.task.jd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.task.common.*;
import org.rx.crawler.task.price.BrowserPromotionNavigator;
import org.rx.crawler.task.price.PriceCodes;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicitly enabled real-site regression; uses the existing signed-in common profile. */
@EnabledIfSystemProperty(named = "jd.promotion.regression.live", matches = "true")
class JdPromotionRegressionLiveTest {
    @Test
    void suppliedShortLinksResolveAndGenerateBothPromotionOutputs() throws Exception {
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(true);
        config.getCustom().getJdUnion().setDebugOutputDir(System.getProperty("jd.regression.debugDir",
                Path.of(System.getProperty("java.io.tmpdir"), "ai", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE),
                        "jd-promotion-live").toString()));
        ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        BrowserProfileManager manager = new BrowserProfileManager(config);
        manager.setBrowserFactory((settings, type) -> {
            WebBrowser browser = spy(new WebBrowser(settings, type));
            Page page = (Page) ReflectionTestUtils.getField(browser, "page");
            page.onResponse(response -> {
                if (response.url().contains("unionSearchGoods")) {
                    System.out.println("JD_LIVE_SEARCH_HTTP status=" + response.status()
                            + " type=" + response.headers().get("content-type"));
                }
            });
            doAnswer(invocation -> {
                String selector = invocation.getArgument(0);
                if (selector.contains("primary-promote")) {
                    Object target = browser.executeScript("var e=document.querySelector('[data-rx-jd-primary-promote=\"1\"]');return e?{tag:e.tagName,css:e.className,text:e.innerText,parentCss:e.parentElement.className}:null;");
                    System.out.println("JD_LIVE_PROMOTION_TARGET=" + new ObjectMapper().writeValueAsString(target));
                }
                return invocation.callRealMethod();
            }).when(browser).elementClick(anyString(), anyBoolean());
            return browser;
        });
        ObjectMapper mapper = new ObjectMapper();
        JdUnionPromotionTask task = new JdUnionPromotionTask(config, manager,
                new CrawlEntryService(new BrowserPreflightService()), mapper);
        BrowserPromotionNavigator navigator = new BrowserPromotionNavigator(config, manager);
        String inputs = System.getProperty("jd.regression.links");
        assertNotNull(inputs, "Supply comma-separated short links with -Djd.regression.links; live material is not stored in source");
        var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
        try {
            for (String input : inputs.split(",")) {
                var nav = navigator.open(PriceCodes.JD, "common", input);
                if (nav.getStatus() != CustomCrawlStatus.SUCCESS) {
                    checks.add(() -> assertEquals(CustomCrawlStatus.SUCCESS, nav.getStatus(), "short-link identity resolution"));
                    continue;
                }
                PromotionUrlRequest request = new PromotionUrlRequest();
                request.setKeyword(nav.getProductId());
                request.setAdSiteName("0"); request.setMediaName("wx");
                request.setUseCache(false); request.setDebugEnabled(true);
                var result = task.getPromotionUrl(request);
                var summary = new LinkedHashMap<String, Object>();
                summary.put("productId", nav.getProductId()); summary.put("status", result.getStatus());
                summary.put("errorCode", result.getErrorCode()); summary.put("message", result.getMessage());
                summary.put("productInfo", result.getProductInfo());
                summary.put("hasShortUrl", result.getShortUrl() != null);
                summary.put("hasToken", result.getPromotionToken() != null);
                summary.put("ajax", result.getDiagnostics().get("ajaxPromotion"));
                summary.put("promoteButtonKind", result.getDiagnostics().get("promoteButtonKind"));
                String body = String.valueOf(result.getDiagnostics().get("body"));
                summary.put("frequentAccess", body.contains("访问频繁"));
                System.out.println("JD_LIVE_SUMMARY=" + mapper.writeValueAsString(summary));
                checks.add(() -> {
                    assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus(), "promotion for " + nav.getProductId());
                    assertNotNull(result.getShortUrl()); assertNotNull(result.getPromotionToken());
                });
            }
            assertAll(checks);
        } finally {
            manager.destroy();
        }
    }
}
