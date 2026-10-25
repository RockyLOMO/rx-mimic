package org.rx.crawler.service.impl;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.task.common.OrderProductLinkReader;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.crawler.task.price.PriceCodes;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native installed Chrome; all requests fulfilled locally, including the encrypted link redirect. */
@EnabledIfSystemProperty(named = "browser.order.integration", matches = "true")
class WebBrowserTbOrderProductIntegrationTest {
    @TempDir Path profile;

    @Test void nativeTitlePopupFollowsEncryptedRedirectAndRestoresOrderPage() {
        String sourceUrl = "https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm";
        String encryptedUrl = "https://uland.taobao.com/item/edetail?id=EncryptedProductAlias";
        String finalUrl = "https://detail.tmall.com/item.htm?id=786248509602";
        String title = "店铺专用订单打印纸";
        AtomicInteger productRequests = new AtomicInteger();
        var observedUrls = new java.util.ArrayList<String>();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(profile,
                     new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(false))) {
            context.route("**/*", route -> {
                String url = route.request().url();
                observedUrls.add(url);
                if (url.equals(sourceUrl)) {
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody("""
                            <!doctype html><meta charset="utf-8"><link rel="icon" href="data:,">
                            <style>td{padding:20px}a{display:block;padding:8px}</style>
                            <table><tbody>
                            <tr><td><a target="_blank" href="https://attacker.test/item.htm?id=111">错误行推荐商品</a>
                            子订单编号：5127891589169007940</td></tr>
                            <tr><td><a target="_blank" href="https://shop.test/">店铺链接</a>
                            <a target="_blank" href="%s">%s</a>
                            父订单编号：5127891589169007940 子订单编号：5127891589169007941</td></tr>
                            </tbody></table>
                            """.formatted(encryptedUrl, title)));
                } else if (url.equals(encryptedUrl)) {
                    // Browser routing handles the first request of an HTTP redirect chain only.
                    // A page redirect keeps the destination inside this local fixture route too.
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html")
                            .setBody("<meta http-equiv=\"refresh\" content=\"0;url=" + finalUrl + "\">"));
                } else if (url.equals(finalUrl)) {
                    productRequests.incrementAndGet();
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<h1>Product</h1>"));
                } else {
                    route.fulfill(new Route.FulfillOptions().setStatus(404).setBody("local fixture only"));
                }
            });
            Page source = context.pages().getFirst();
            WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
            WebBrowserConfig config = new WebBrowserConfig();
            config.setHumanActionMinDelayMillis(0); config.setHumanActionMaxDelayMillis(0);
            config.setOperationRandomMinDelayMillis(0); config.setOperationRandomMaxDelayMillis(0);
            config.setMouseMoveMinSteps(1); config.setMouseMoveMaxSteps(1);
            ReflectionTestUtils.setField(browser, "context", context);
            ReflectionTestUtils.setField(browser, "page", source);
            ReflectionTestUtils.setField(browser, "config", config);
            ReflectionTestUtils.setField(browser, "tabs", new LinkedHashMap<String, Page>());
            ReflectionTestUtils.setField(browser, "prefetchTargetByHandle", new LinkedHashMap<String, String>());
            ReflectionTestUtils.setField(browser, "injectedScript", ConcurrentHashMap.<String>newKeySet());
            context.onPage(page -> ReflectionTestUtils.invokeMethod(browser, "registerTab", page));
            ReflectionTestUtils.invokeMethod(browser, "setCurrentPage", source, true);
            source.navigate(sourceUrl);
            var item = new PromotionOrderItem();
            item.setOrderNo("5127891589169007941");
            item.setProductName(title);
            item.setProductLink(encryptedUrl);
            var cache = new HashMap<String, String>();
            OrderProductLinkReader.resolve(browser, item, PriceCodes.TB, 8, cache);
            assertEquals("786248509602", item.getSkuId(), item.getExtensions() + " routes=" + observedUrls);
            assertEquals(finalUrl, item.getProductLink());
            assertEquals(1, productRequests.get());
            assertSame(source, ReflectionTestUtils.getField(browser, "page"));
            assertEquals(sourceUrl, browser.getCurrentUrl());
            assertEquals(1, context.pages().size());
            assertEquals(0, source.locator("[data-rx-order-product-link]").count());
        }
    }
}
