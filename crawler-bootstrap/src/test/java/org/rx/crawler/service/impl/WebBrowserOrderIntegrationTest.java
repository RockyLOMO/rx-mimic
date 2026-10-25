package org.rx.crawler.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.task.common.OrderProductLinkReader;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.crawler.task.price.PriceCodes;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Visible installed Chrome with a disposable profile. Every network request is fulfilled locally. */
@EnabledIfSystemProperty(named = "browser.order.integration", matches = "true")
class WebBrowserOrderIntegrationTest {
    @TempDir Path temp;

    @Test
    @SuppressWarnings("unchecked")
    void nativeQueryCapturesOrderAmountsAndPopupReturnsToItsSource() throws Exception {
        String api = "report.getTbkOrderDetails.json";
        String response = """
                {"data":{"result":[{"tradeId":"512771454149502550","itemTitle":"本地测试鞋",
                "tradeParentId":"512771454149502549","sellerShopTitle":"本地测试店",
                "tkStatusText":"已结算","tkCreateTime":"2026-09-01 16:03:00",
                "tkPaidTime":"2026-09-01 16:04:09","tkEarningTime":"2026-09-11 16:52:30",
                "alipayTotalPrice":"27.55","payPrice":"20.00","pubSharePreFee":"1.98","pubShareFee":"1.44",
                "subsidyFee":0.35,"siteName":"媒体名称","adzoneId":"116337200060","itemNum":2}]}}
                """;
        String html = """
                <!doctype html><meta charset="utf-8"><style>body{font:18px sans-serif;padding:20px}td{padding:20px}</style>
                <button id="query" onclick="fetch('/openapi/report.getTbkOrderDetails.json').then(r=>r.json()).then(j=>{window.received=j;this.dataset.done='yes'})">查询订单</button>
                <table><tbody><tr><td><a target="_blank" href="https://item.jd.com/100059484008.html">本地测试商品</a></td>
                <td>365446701560385</td><td>已完成</td></tr></tbody></table>
                """;
        AtomicInteger orderRequests = new AtomicInteger();
        AtomicInteger detailRequests = new AtomicInteger();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(temp,
                     new BrowserType.LaunchPersistentContextOptions().setChannel("chrome")
                             .setHeadless(false).setViewportSize((ViewportSize) null))) {
            context.route("**/*", route -> {
                String url = route.request().url();
                if (url.contains(api)) {
                    orderRequests.incrementAndGet();
                    route.fulfill(new Route.FulfillOptions().setContentType("application/json; charset=utf-8").setBody(response));
                } else if (url.equals("https://item.jd.com/100059484008.html")) {
                    detailRequests.incrementAndGet();
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8")
                            .setBody("<meta charset='utf-8'><h1>本地路由商品详情</h1>"));
                } else if (url.equals("https://orders.test/")) {
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html));
                } else {
                    // Never allow fixture requests to escape to a real external service.
                    route.fulfill(new Route.FulfillOptions().setStatus(404).setBody("local fixture only"));
                }
            });
            Page source = context.pages().getFirst();
            WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
            WebBrowserConfig config = new WebBrowserConfig();
            config.setHeadless(false);
            config.setHumanActionMinDelayMillis(0); config.setHumanActionMaxDelayMillis(0);
            config.setOperationRandomMinDelayMillis(0); config.setOperationRandomMaxDelayMillis(0);
            config.setMouseMoveMinSteps(1); config.setMouseMoveMaxSteps(1);
            ReflectionTestUtils.setField(browser, "context", context);
            ReflectionTestUtils.setField(browser, "page", source);
            ReflectionTestUtils.setField(browser, "config", config);
            ReflectionTestUtils.setField(browser, "tabs", new LinkedHashMap<String, Page>());
            ReflectionTestUtils.setField(browser, "prefetchTargetByHandle", new LinkedHashMap<String, String>());
            ReflectionTestUtils.setField(browser, "responseCaptures", new ConcurrentHashMap<String, java.util.Deque<String>>());
            ReflectionTestUtils.setField(browser, "injectedScript", ConcurrentHashMap.<String>newKeySet());
            context.onPage(page -> ReflectionTestUtils.invokeMethod(browser, "registerTab", page));
            context.onResponse(value -> ReflectionTestUtils.invokeMethod(browser, "captureResponse", value));
            ReflectionTestUtils.invokeMethod(browser, "setCurrentPage", source, true);
            source.navigate("https://orders.test/");

            browser.startResponseCapture(api);
            assertTrue(browser.drainResponseCapture(api).isEmpty());
            browser.elementClick("#query");
            source.waitForCondition(() -> "yes".equals(source.locator("#query").getAttribute("data-done")));
            List<String> captured = browser.drainResponseCapture(api);
            assertEquals(1, captured.size());
            assertEquals(1, orderRequests.get());
            assertTrue(browser.drainResponseCapture(api).isEmpty());
            Method parser = Class.forName("org.rx.crawler.task.tb.TbUnionPageApi")
                    .getDeclaredMethod("parseOrders", JsonNode.class);
            parser.setAccessible(true);
            List<PromotionOrderItem> orders = (List<PromotionOrderItem>) parser.invoke(null,
                    new ObjectMapper().readTree(captured.getFirst()).path("data").path("result"));
            PromotionOrderItem tb = orders.getFirst();
            assertEquals("0", tb.getPromotionPosition());
            assertEquals("￥27.55", tb.getEstimatedBillingAmount());
            assertEquals("￥20.00", tb.getActualBillingAmount());
            assertEquals("￥1.98", tb.getEstimatedCommission());
            assertEquals("￥1.44", tb.getActualCommission());
            assertEquals("2026-09-11 16:52:30", tb.getFinishTime());
            assertEquals("2026-10-20", tb.getSettleTime());
            browser.stopResponseCapture(api);

            PromotionOrderItem jd = new PromotionOrderItem();
            jd.setOrderNo("365446701560385");
            // Simulate an onclick-only product field: resolve by clicking the visible row,
            // rather than short-circuiting on a DTO that already contains a numeric URL.
            jd.setProductLink("javascript:void(0)");
            OrderProductLinkReader.resolve(browser, jd, PriceCodes.JD, 8);
            assertEquals("100059484008", jd.getSkuId());
            assertEquals("https://item.jd.com/100059484008.html", jd.getProductLink());
            assertEquals(1, detailRequests.get());
            assertSame(source, ReflectionTestUtils.getField(browser, "page"));
            assertEquals("https://orders.test/", browser.getCurrentUrl());
            assertEquals(1, context.pages().size());
            assertFalse(source.isClosed());
            assertTrue(source.locator("#query").isVisible());
        }
    }
}
