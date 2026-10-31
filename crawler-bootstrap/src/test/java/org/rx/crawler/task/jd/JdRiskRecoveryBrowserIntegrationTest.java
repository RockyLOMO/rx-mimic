package org.rx.crawler.task.jd;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Installed visible Chrome and disposable profile; every page request uses a local fixture. */
@EnabledIfSystemProperty(named = "browser.riskrecovery.integration", matches = "true")
class JdRiskRecoveryBrowserIntegrationTest {
    private static final String ORIGIN = "https://union.jd.com";
    private static final String LOGIN_PREFIX = "https://union.jd.com/index?returnUrl=";
    @TempDir Path temp;

    @Test
    void riskRecoveryRequiresWorkbenchSessionAndRejectsRiskOrVerificationPages() {
        AtomicInteger fixtureRequests = new AtomicInteger();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext context = playwright.chromium().launchPersistentContext(temp,
                     new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(false)
                             .setViewportSize((ViewportSize) null).setServiceWorkers(ServiceWorkerPolicy.BLOCK)
                             .setArgs(List.of("--disable-background-networking", "--disable-component-update", "--no-first-run")))) {
            context.route("**/*", route -> {
                URI uri = URI.create(route.request().url());
                String body = "union.jd.com".equals(uri.getHost()) ? switch (uri.getPath()) {
                    case "/proManager/index" -> "<h1>推广管理</h1><main>我要推广 商品推广</main>";
                    case "/blank" -> "";
                    case "/riskinfo" -> "<h1>推广管理</h1><main>访问受限</main>";
                    case "/verification-overlay" -> "<h1>推广管理</h1><main>我要推广</main>"
                            + "<div role='dialog' style='position:fixed;inset:0;background:white'>安全验证 请完成验证</div>";
                    default -> "local fixture only";
                } : "local fixture only";
                fixtureRequests.incrementAndGet();
                route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8")
                        .setBody("<!doctype html><html><head><meta charset='utf-8'></head><body>" + body + "</body></html>"));
            });
            Page page = context.pages().getFirst();
            page.navigate(ORIGIN + "/proManager/index");
            assertFalse(verified(page, context), "Workbench text without a session must not permit recovery");

            context.addCookies(List.of(new Cookie("pt_key", "fixture-session").setDomain(".jd.com").setPath("/"),
                    new Cookie("pt_pin", "fixture-user").setDomain(".jd.com").setPath("/")));
            assertEquals(2, context.cookies(ORIGIN).size());
            assertTrue(verified(page, context), "Workbench and browser session must verify successfully");

            page.navigate(ORIGIN + "/blank");
            assertFalse(verified(page, context), "A blank page with retained cookies must fail");
            page.navigate(ORIGIN + "/riskinfo");
            assertFalse(verified(page, context), "A risk page must fail even with workbench text and cookies");
            page.navigate(ORIGIN + "/verification-overlay");
            assertFalse(verified(page, context), "A verification overlay on the workbench must fail");
            assertTrue(fixtureRequests.get() >= 4);
        }
    }

    private static boolean verified(Page page, BrowserContext context) {
        String cookie = context.cookies(ORIGIN).stream().map(value -> value.name + "=" + value.value)
                .collect(Collectors.joining("; "));
        return JdUnionPromotionTask.verifiedUnionLogin(page.url(), page.locator("body").innerText(), cookie, LOGIN_PREFIX);
    }
}
