package org.rx.crawler.task.price;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserOwnerExecutor;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.tb.TbPromotionUrlTask;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Visible installed Chrome, disposable profile, every HTTP request fulfilled by local fixtures. */
@EnabledIfSystemProperty(named = "browser.shortlink.integration", matches = "true")
class JdShortLinkBrowserIntegrationTest {
    private static final String SHORT_LINK = "https://3.cn/38H-SBL0?jkl=@Example123@";
    private static final String PRODUCT_ID = "100017795494";
    private static final String PRODUCT_URL = "https://item.m.jd.com/product/" + PRODUCT_ID + ".html";
    @TempDir Path temp;

    @Test
    void nativeShortLinkNavigationAndFullShareCopyResolveOnlyTheFinalTrustedProductPath() throws Exception {
        AtomicInteger shortRequests = new AtomicInteger();
        AtomicInteger productRequests = new AtomicInteger();
        String redirectFixture = """
                <!doctype html><meta charset="utf-8"><h1>本地分享短链</h1>
                <script>setTimeout(function(){location.assign('%s')},30)</script>
                """.formatted(PRODUCT_URL);
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(temp,
                     new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(false)
                             .setViewportSize((ViewportSize) null)
                             .setArgs(List.of("--disable-background-networking", "--disable-component-update", "--no-first-run")))) {
            context.route("**/*", route -> {
                String url = route.request().url();
                URI uri = URI.create(url);
                if ("3.cn".equals(uri.getHost()) && "/38H-SBL0".equals(uri.getPath())) {
                    shortRequests.incrementAndGet();
                    // JS navigation rather than HTTP 302 keeps both requests intercepted by local routes.
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(redirectFixture));
                } else if (PRODUCT_URL.equals(url)) {
                    productRequests.incrementAndGet();
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8")
                            .setBody("<!doctype html><meta charset='utf-8'><h1>本地净水器滤芯商品详情</h1>"));
                } else {
                    // No route may fall through to a real JD endpoint, login or production profile.
                    route.fulfill(new Route.FulfillOptions().setStatus(404).setBody("local fixture only"));
                }
            });
            Page page = context.pages().getFirst();
            WebBrowser browser = mock(WebBrowser.class);
            doAnswer(call -> { page.navigate(call.getArgument(0, String.class)); return null; })
                    .when(browser).navigateUrl(anyString(), eq(Browser.BODY_SELECTOR), anyInt());
            when(browser.getCurrentUrl()).thenAnswer(call -> page.url());
            doAnswer(call -> { page.waitForTimeout(call.getArgument(0, Long.class)); return null; })
                    .when(browser).waitForEvents(anyLong());
            when(browser.executeScript(anyString())).thenAnswer(call -> page.evaluate("() => {" + call.getArgument(0, String.class) + "}"));

            BrowserProfileManager profiles = mock(BrowserProfileManager.class);
            BrowserProfileManager.ProfileLease lease = mock(BrowserProfileManager.ProfileLease.class);
            when(profiles.normalizeProfileName(anyString())).thenReturn("fixture");
            when(profiles.resolveProfileDataPath("fixture")).thenReturn(temp.toString());
            when(profiles.acquire(eq("fixture"), any(WebBrowserConfig.class))).thenReturn(lease);
            when(lease.getBrowser()).thenReturn(browser);
            AppConfig config = new AppConfig();
            config.getCustom().getJdUnion().setHeadless(false);
            config.getCustom().getPriceWatch().setWindowStart("00:00");
            config.getCustom().getPriceWatch().setWindowEnd("23:59");
            BrowserPromotionNavigator navigator = new BrowserPromotionNavigator(config, profiles);

            PromotionNavigator.Nav nav = navigator.open(PriceCodes.JD, "fixture", SHORT_LINK);
            assertEquals(CustomCrawlStatus.SUCCESS, nav.getStatus());
            assertEquals(ProductIdentityParser.PageKind.PRODUCT, nav.getPageKind());
            assertEquals(PRODUCT_URL, nav.getFinalUrl());
            assertEquals(PRODUCT_ID, nav.getProductId());
            assertEquals(1, shortRequests.get());
            assertEquals(1, productRequests.get());

            BrowserOwnerExecutor executor = mock(BrowserOwnerExecutor.class);
            when(executor.isOwnerThread()).thenReturn(true);
            PriceWatchStore store = mock(PriceWatchStore.class);
            when(store.reserveVisit(anyInt(), anyString(), anyInt())).thenReturn(1);
            PriceRiskGate risk = mock(PriceRiskGate.class);
            TbPromotionUrlTask tb = mock(TbPromotionUrlTask.class);
            ProductIdentityResolver resolver = new ProductIdentityResolver(executor, navigator, tb, risk, store, config);
            String copy = "【京东】" + SHORT_LINK + " MF3390 「米家净水器S1 800G UF超滤滤芯」";
            assertEquals(PRODUCT_ID, resolver.resolve(PriceCodes.JD, "fixture", copy));
            assertEquals(PRODUCT_ID, resolver.cached(PriceCodes.JD, "fixture", copy));
            assertEquals(2, shortRequests.get());
            assertEquals(2, productRequests.get());
            verify(lease, times(2)).close();
            verifyNoInteractions(tb);

            // Product identity comes from a trusted product path, never arbitrary query IDs on a bridge.
            ProductIdentityParser.Hit bridge = ProductIdentityParser.inspect(PriceCodes.JD,
                    "https://3.cn/38H-SBL0?id=" + PRODUCT_ID);
            assertEquals(ProductIdentityParser.PageKind.INTERMEDIATE, bridge.kind);
            assertTrue(bridge.productId.isEmpty());
            ProductIdentityParser.Hit arbitrary = ProductIdentityParser.inspect(PriceCodes.JD,
                    "https://example.test/product?id=" + PRODUCT_ID);
            assertEquals(ProductIdentityParser.PageKind.OTHER, arbitrary.kind);
            assertTrue(arbitrary.productId.isEmpty());
        }
    }
}

