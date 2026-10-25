package org.rx.crawler.service.impl;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.task.common.TaskApiException;
import org.rx.crawler.task.tb.TbUnionAnalysisPage;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Local-only real Chrome fixture for native analysis submission and settled empty results. */
@EnabledIfSystemProperty(named = "browser.tb.analysis.integration", matches = "true")
class WebBrowserTbAnalysisIntegrationTest {
    private static final String ANALYSIS_URL = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
    private static final String INPUT = "https://e.tb.cn/h.j3BGowC7YPUT5yB?tk=2wZzTxWfJHu";
    @TempDir Path profile;

    @Test
    void settledEmptyAnalysisReturnsNonRebateAfterOneNativeSubmission() throws Exception {
        exercise(false);
    }

    @Test
    void stillLoadingAnalysisRemainsTimeout() throws Exception {
        exercise(true);
    }

    @Test
    void freshlyAnalyzedProductAllowsDifferentMarketingAliasesAndIgnoresRecommendations() throws Exception {
        String addressAlias = "AddressMarketingAlias_0123456789";
        String inputAlias = "InputMarketingAlias_0123456789";
        String titleAlias = "TitleMarketingAlias_0123456789";
        String targetUrl = TbUnionAnalysisPage.entryUrl(ANALYSIS_URL, addressAlias);
        AtomicInteger documentRequests = new AtomicInteger();
        AtomicInteger otherRequests = new AtomicInteger();
        AtomicInteger verificationCalls = new AtomicInteger();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(profile,
                     new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(false))) {
            context.route("**/*", route -> {
                if (route.request().isNavigationRequest() && targetUrl.equals(route.request().url())) {
                    documentRequests.incrementAndGet();
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8")
                            .setBody("""
                                    <!doctype html><meta charset="utf-8"><link rel="icon" href="data:,">
                                    <style>body{font:18px sans-serif;padding:40px}</style>
                                    <section id="recommendations">
                                      <a href="https://uland.taobao.com/item/edetail?id=RecommendationAlias_0123456789">相似推荐旧商品</a>
                                      <p>价格 ¥999.00 佣金率99%%</p>
                                      <button data-spm-click="locaid=ai_good_promo">立即推广</button>
                                    </section>
                                    <input placeholder="请输入要分析的商品链接/商品ID/淘口令" value="%s">
                                    <button class="left-analysic-btn-show">立即分析</button>
                                    <div id="detail">
                                      <a class="shop-title">目标店铺</a>
                                      <article>
                                        <a href="https://uland.taobao.com/item/edetail?id=%s">目标手环表带</a>
                                        <p>价格 ¥18.80 佣金率7.5%%</p>
                                        <button data-spm-click="locaid=ai_good_promo">立即推广</button>
                                      </article>
                                    </div>
                                    """.formatted(inputAlias, titleAlias)));
                } else {
                    otherRequests.incrementAndGet();
                    route.fulfill(new Route.FulfillOptions().setStatus(404).setBody("local fixture only"));
                }
            });
            Page page = context.pages().getFirst();
            page.navigate(targetUrl);
            WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(browser, "page", page);
            ReflectionTestUtils.setField(browser, "config", new WebBrowserConfig());
            ReflectionTestUtils.setField(browser, "injectedScript", ConcurrentHashMap.<String>newKeySet());
            TbUnionAnalysisPage.Hit hit = new TbUnionAnalysisPage().awaitReadyProduct(browser, 3, () -> {
                verificationCalls.incrementAndGet();
                return true;
            });
            assertEquals(titleAlias, hit.materialId());
            assertEquals("目标手环表带", hit.info().getProductName());
            assertEquals("目标店铺", hit.info().getStoreName());
            assertEquals("18.80", hit.info().getPrice());
            assertEquals("7.5%", hit.info().getCommissionRate());
            assertEquals("https://uland.taobao.com/item/edetail?id=" + titleAlias, hit.info().getProductLink());
            assertEquals(1, verificationCalls.get(), "Shared verification must run before reading the product");
            assertEquals(inputAlias, page.locator("input").inputValue());
            assertEquals(targetUrl, page.url());
            verify(browser, never()).elementClick(anyString());
            verify(browser, never()).navigateUrl(anyString(), anyString(), anyInt());
            assertEquals(1, documentRequests.get());
            assertEquals(0, otherRequests.get());
            assertEquals(1, context.pages().size());
        }
    }

    private void exercise(boolean stuckLoading) throws Exception {
        AtomicInteger documentRequests = new AtomicInteger();
        AtomicInteger otherRequests = new AtomicInteger();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(profile,
                     new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(false))) {
            context.route("**/*", route -> {
                if (route.request().isNavigationRequest() && route.request().url().startsWith(ANALYSIS_URL)) {
                    documentRequests.incrementAndGet();
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8")
                            .setBody(fixture(stuckLoading)));
                } else {
                    otherRequests.incrementAndGet();
                    route.fulfill(new Route.FulfillOptions().setStatus(404).setBody("local fixture only"));
                }
            });
            Page page = context.pages().getFirst();
            WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
            WebBrowserConfig config = new WebBrowserConfig();
            config.setHumanActionMinDelayMillis(0); config.setHumanActionMaxDelayMillis(0);
            config.setOperationRandomMinDelayMillis(0); config.setOperationRandomMaxDelayMillis(0);
            config.setMouseMoveMinSteps(1); config.setMouseMoveMaxSteps(1);
            ReflectionTestUtils.setField(browser, "page", page);
            ReflectionTestUtils.setField(browser, "config", config);
            ReflectionTestUtils.setField(browser, "injectedScript", ConcurrentHashMap.<String>newKeySet());
            // Keep navigation local while using the production DOM reader, event pumping and native click.
            doAnswer(invocation -> { page.navigate(invocation.getArgument(0)); return null; })
                    .when(browser).navigateUrl(anyString(), anyString(), anyInt());
            TbUnionAnalysisPage analysis = new TbUnionAnalysisPage();
            if (stuckLoading) {
                assertThrows(TimeoutException.class,
                        () -> analysis.resolveMaterial(browser, ANALYSIS_URL, INPUT, 1));
                assertEquals("0", page.locator("body").getAttribute("data-clicks"));
            } else {
                TaskApiException failure = assertThrows(TaskApiException.class,
                        // First native click initializes the shared runtime as well as moving/hovering;
                        // allow that work plus the production three-poll settled-result window.
                        () -> analysis.resolveMaterial(browser, ANALYSIS_URL, INPUT, 8),
                        () -> "Analysis state: " + page.evaluate("({url:location.href,clicks:document.body.dataset.clicks,disabled:document.querySelector('button').disabled,input:document.querySelector('input').value,body:document.body.innerText})"));
                assertEquals("NO_REBATE_PRODUCT", failure.getCode());
                assertEquals("非返利商品，暂不支持转链", failure.getMessage());
                assertEquals("1", page.locator("body").getAttribute("data-clicks"));
                assertFalse(page.locator("button").isDisabled());
            }
            assertEquals(1, documentRequests.get());
            assertEquals(0, otherRequests.get(), "Analysis must only use native page controls; fixture issues no AJAX");
            assertEquals(1, context.pages().size());
        }
    }

    private static String fixture(boolean stuckLoading) {
        return """
                <!doctype html><meta charset="utf-8"><link rel="icon" href="data:,">
                <style>body{font:18px sans-serif;padding:40px}input{width:600px;padding:12px}button{padding:12px}#detail{margin-top:40px}</style>
                <body data-clicks="0">
                <h1>商品分析</h1>
                <input placeholder="请输入要分析的商品链接/商品ID/淘口令">
                <button class="left-analysic-btn-show">立即分析</button>
                <div class="next-loading"><span class="next-loading-indicator" style="display:none"></span>
                <div id="detail">输入商品链接/ID，智能分析</div></div>
                <script>
                document.querySelector('input').value=new URL(location.href).searchParams.get('itemId');
                const button=document.querySelector('button');
                button.disabled=%s;
                button.onclick=()=>{
                  document.body.dataset.clicks=String(Number(document.body.dataset.clicks)+1);
                  button.disabled=true;
                  setTimeout(()=>button.disabled=false,500);
                };
                </script>
                """.formatted(stuckLoading);
    }
}
