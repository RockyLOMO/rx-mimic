package org.rx.crawler.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.*;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Installed Chrome executes the production AJAX script against local response fixtures only. */
@EnabledIfSystemProperty(named = "browser.jd.fast.integration", matches = "true")
class JdFastPromotionBrowserIntegrationTest {
    @TempDir Path temp;

    @Test void fastConversionReturnsCompleteMaterialWithoutClickingWorkbenchMenus() throws Exception {
        var calls = new ArrayList<String>();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(temp.resolve("chrome"),
                     new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(false))) {
            context.route("**/*", route -> {
                String url = route.request().url();
                String body;
                String type = "application/json";
                if (url.startsWith("https://union.jd.com/")) {
                    type = "text/html";
                    body = "<html><body>Local logged-in workbench fixture<script>" +
                            "window.ParamsSign=function(){this.sign=async function(){return {h5st:'local-fixture'};}};" +
                            "window.getJsToken=function(cb){cb({jsToken:'local-fixture'});};" +
                            "document.addEventListener('click',()=>window.clicks=(window.clicks||0)+1);" +
                            "</script></body></html>";
                } else if (url.startsWith("https://api.m.jd.com/api?")) {
                    if (url.contains("functionId=unionSearchGoods")) {
                        calls.add("search");
                        body = """
                                {"code":200,"result":{"goodsSynopsisList":[{"skuId":1001,"skuName":"fixture product",
                                "wlPrice":10,"wlCommissionRatio":3,"shopName":"fixture shop","imageUrl":"https://img.example/item.jpg"}]}}
                                """;
                    } else if (url.contains("functionId=unionMedia")) {
                        calls.add("media");
                        body = "{\"code\":200,\"result\":[{\"id\":\"4101055439\",\"mediaName\":\"wx\"}]}";
                    } else if (url.contains("functionId=unionPromotion")) {
                        calls.add("site");
                        body = "{\"code\":200,\"result\":[{\"id\":\"3100287104\",\"promotionName\":\"0\"}]}";
                    } else if (url.contains("functionId=unionPromoteLinkService")) {
                        calls.add("code");
                        body = "{\"code\":200,\"result\":{\"shortUrl\":\"https://u.jd.com/local-short\",\"jShortCommand\":\"! LocalCommand! ZH9112\"}}";
                    } else throw new AssertionError("Unexpected AJAX endpoint");
                } else {
                    route.abort(); return;
                }
                route.fulfill(new Route.FulfillOptions().setContentType(type).setBody(body).setHeaders(Map.of(
                        "Access-Control-Allow-Origin", "https://union.jd.com", "Access-Control-Allow-Credentials", "true")));
            });
            Page page = context.pages().getFirst();
            page.navigate("https://union.jd.com/proManager/index?pageNo=1");
            WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(browser, "page", page);
            ReflectionTestUtils.setField(browser, "config", new WebBrowserConfig());
            ReflectionTestUtils.setField(browser, "injectedScript", ConcurrentHashMap.<String>newKeySet());
            BrowserProfileManager profiles = mock(BrowserProfileManager.class);
            var lease = mock(BrowserProfileManager.ProfileLease.class);
            when(profiles.normalizeProfileName(anyString())).thenAnswer(call -> call.getArgument(0));
            when(profiles.resolveProfileDataPath(anyString())).thenReturn(temp.resolve("chrome").toString());
            when(profiles.acquire(anyString(), any())).thenReturn(lease);
            when(lease.getBrowser()).thenReturn(browser);
            CrawlEntryService entry = mock(CrawlEntryService.class);
            CrawlEntryResult passed = new CrawlEntryResult(); passed.setPassed(true); passed.setFingerprintPassed(true);
            when(entry.enter(any(), any(), any())).thenReturn(passed);
            AppConfig config = new AppConfig();
            var task = new JdUnionPromotionTask(config, profiles, entry, new ObjectMapper());
            var navigator = mock(org.rx.crawler.task.price.PromotionNavigator.class);
            var identities = new org.rx.crawler.task.price.ProductIdentityResolver(null, navigator, null, null, null, config);
            ReflectionTestUtils.setField(task, "identities", identities);
            PromotionUrlRequest request = new PromotionUrlRequest();
            request.setKeyword("1001"); request.setAdSiteName("0"); request.setProfileName("common");
            request.setFast(true); request.setDebugEnabled(true); request.setDebugOutputDir(temp.resolve("debug").toString());
            PromotionUrlResult result = task.getPromotionUrl(request);
            assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus(), result.getMessage());
            var boundary = new org.rx.crawler.task.price.UnionMaterialBoundary(new ObjectMapper(), identities);
            var output = boundary.result("getPromotionUrl", new ObjectMapper().valueToTree(result), 1);
            assertEquals("1001", output.path("productId").asText());
            assertTrue(output.path("identityResolved").asBoolean());
            verifyNoInteractions(navigator);
            assertEquals("fixture product", result.getProductInfo().getProductName());
            assertEquals("https://u.jd.com/local-short", result.getShortUrl());
            assertEquals("! LocalCommand! ZH9112", result.getPromotionToken());
            assertEquals(java.util.List.of("search", "media", "site", "code"), calls);
            assertEquals("ajax", result.getDiagnostics().get("channel"));
            assertEquals(0, ((Number) page.evaluate("window.clicks||0")).intValue());
            assertFalse(result.getDiagnostics().containsKey("fastPageFallback"));
            var cancellation = new TaskCancellation.Token();
            Thread cancelRequest = new Thread(() -> {
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                cancellation.cancel();
            });
            long cancelStart = System.nanoTime();
            try (var ignored = TaskCancellation.open(cancellation)) {
                cancelRequest.start();
                assertThrows(TaskCancellation.Cancelled.class, () -> browser.waitForEvents(5000));
            }
            cancelRequest.join();
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelStart) < 2000);
            assertTrue(browser.getCurrentUrl().startsWith("https://union.jd.com/"));
            System.out.println("JD_FAST_LOCAL_BROWSER_OK calls=" + calls.size() + " clicks=0 name=true shortUrl=true token=true identity=true retailVisits=0");
        }
    }
}
