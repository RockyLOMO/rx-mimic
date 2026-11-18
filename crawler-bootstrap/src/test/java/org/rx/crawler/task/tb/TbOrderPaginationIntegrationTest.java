package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Answers;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Local Chrome DOM verification for Alimama order pagination and AlpMessage exclusion. */
@EnabledIfSystemProperty(named = "tb.pagination.integration", matches = "true")
class TbOrderPaginationIntegrationTest {
    private static final String BASE_STYLE = "<style>"
            + "button{display:inline-block;padding:4px 10px;min-width:60px;height:28px;}"
            + ".mux-pagination-pages{display:flex;align-items:center;gap:6px;}"
            + ".mux-pagination-item,.mux-pagination-icon-next,.next-next{display:inline-block;width:24px;height:24px;}"
            + "</style>";

    @Test
    void disabledTableNextIgnoresBothDisabledAndEnabledAlpMessage() {
        verifyDom(page -> {
            String disabledTable = buildPageHtml(alpMessageHtml(true),
                    "<button class='mux-button mux-button--disabled' disabled>上一页</button>"
                            + "<button class='mux-button mux-button--disabled' disabled onclick=\"window.__clicked='table-next'\">下一页</button>",
                    false);
            assertPagination(page, disabledTable, false, 0, null);

            String enabledAlpDisabledTable = buildPageHtml(alpMessageHtml(false),
                    "<button class='mux-button mux-button--disabled' disabled>上一页</button>"
                            + "<button class='mux-button mux-button--disabled' disabled onclick=\"window.__clicked='table-next'\">下一页</button>",
                    false);
            assertPagination(page, enabledAlpDisabledTable, false, 0, null);
        });
    }

    @Test
    void enabledTableNextClicksOrderButtonInsteadOfAlpMessage() {
        verifyDom(page -> {
            String html = buildPageHtml(alpMessageHtml(false),
                    "<button class='mux-button mux-button--disabled' disabled>上一页</button>"
                            + "<button id='table-next' class='mux-button mux-button--type-hollow' onclick=\"window.__clicked='table-next'\">下一页</button>",
                    true);
            assertPagination(page, html, true, 1, "table-next");
        });
    }

    @Test
    void iconOnlyPaginationAndTableCellLinksFollowOrderTableStateOnly() {
        verifyDom(page -> {
            String disabledMuxIcon = buildPageHtml(alpMessageHtml(false),
                    "<div class='mux-pagination'><div class='mux-pagination-pages'>"
                            + "<div class='mux-pagination-item mux-pagination-item-disable' onclick=\"window.__clicked='mux-icon'\">"
                            + "<span class='mux-icon mux-pagination-icon-next'></span></div></div></div>",
                    true);
            assertPagination(page, disabledMuxIcon, false, 0, null);

            String enabledMuxIcon = buildPageHtml(alpMessageHtml(false),
                    "<div class='mux-pagination'><div class='mux-pagination-pages'>"
                            + "<div class='mux-pagination-item' onclick=\"window.__clicked='mux-icon'\">"
                            + "<span class='mux-icon mux-pagination-icon-next'></span></div></div></div>",
                    true);
            assertPagination(page, enabledMuxIcon, true, 1, "mux-icon");

            String disabledNextBtn = buildPageHtml(alpMessageHtml(false),
                    "<div class='next-pagination'><button class='next-btn next-pagination-item next-next' aria-disabled='true' onclick=\"window.__clicked='next-icon'\"><i></i></button></div>",
                    true);
            assertPagination(page, disabledNextBtn, false, 0, null);

            String enabledNextBtn = buildPageHtml(alpMessageHtml(false),
                    "<div class='next-pagination'><button class='next-btn next-pagination-item next-next' onclick=\"window.__clicked='next-icon'\"><i></i></button></div>",
                    true);
            assertPagination(page, enabledNextBtn, true, 1, "next-icon");

            String rowLinkOnly = buildPageHtml(alpMessageHtml(false), "", true);
            assertPagination(page, rowLinkOnly, false, 0, null);

            String disabledButtonWithLowerNextClass = buildPageHtml(alpMessageHtml(false),
                    "<button class='mux-button mux-button--disabled' disabled>下一页</button>"
                            + "<span class='unrelated-next-badge'>推广中</span>",
                    true);
            assertPagination(page, disabledButtonWithLowerNextClass, false, 0, null);
        });
    }

    private static void verifyDom(java.util.function.Consumer<Page> body) {
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             com.microsoft.playwright.Browser chrome = pw.chromium().launch(new BrowserType.LaunchOptions()
                     .setChannel("chrome")
                     .setHeadless(true))) {
            Page page = chrome.newPage();
            body.accept(page);
        }
    }

    private static void assertPagination(Page page, String html, boolean expectedHasNext,
                                         int expectedClicks, String expectedClickedTarget) {
        page.setContent(html);
        page.evaluate("() => { window.__clicked = null; }");
        AtomicInteger clicks = new AtomicInteger();
        Browser browser = mock(Browser.class, call -> {
            Object[] args = call.getArguments();
            return switch (call.getMethod().getName()) {
                case "executeScript" -> page.evaluate(
                        "args=>(function(){" + args[0] + "}).apply(null,args)",
                        Arrays.asList(Arrays.copyOfRange(args, 1, args.length)));
                case "elementClick" -> {
                    clicks.incrementAndGet();
                    page.locator((String) args[0]).click();
                    yield null;
                }
                default -> Answers.RETURNS_DEFAULTS.answer(call);
            };
        });
        AppConfig config = new AppConfig();
        TbPromotionOrdersTask task = new TbPromotionOrdersTask(
                config,
                new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()),
                new ObjectMapper());

        assertEquals(expectedHasNext, task.hasNextOrderPage(browser));
        assertEquals(expectedHasNext, task.nativeClickNextOrderPage(browser));
        assertEquals(expectedClicks, clicks.get());
        Object clicked = page.evaluate("() => window.__clicked || null");
        assertEquals(expectedClickedTarget, clicked);
        int markedInAlp = ((Number) page.evaluate(
                "() => document.querySelectorAll('#widget-topMsg [data-rx-tb-next-page]').length")).intValue();
        assertEquals(0, markedInAlp);
        if (!expectedHasNext) {
            int markedAnywhere = ((Number) page.evaluate(
                    "() => document.querySelectorAll('[data-rx-tb-next-page]').length")).intValue();
            assertEquals(0, markedAnywhere);
        }
    }

    private static String alpMessageHtml(boolean disabled) {
        String itemCls = disabled
                ? "mux-pagination-item mux-pagination-item-prev mux-pagination-item-disable"
                : "mux-pagination-item mux-pagination-item-prev";
        String text = disabled ? "1/1" : "1/2";
        return "<div id='widget-topMsg'><div class='union-widgets-alp-message' data-spm='AlpMessage'>"
                + "<div class='mux-pagination mux-pagination-simple'><div class='mux-pagination-pages'>"
                + "<div class='mux-pagination-item mux-pagination-item-prev mux-pagination-item-disable'>"
                + "<span class='mux-icon-left mux-icon mux-pagination-icon-prev'></span></div>"
                + "<span class='mux-pagination-display'>" + text + "</span>"
                + "<div class='" + itemCls + "' onclick=\"window.__clicked='alp-banner'\">"
                + "<span class='mux-icon-right mux-icon mux-pagination-icon-next'></span></div>"
                + "</div></div></div></div>";
    }

    private static String buildPageHtml(String topBanner, String pagerFooter, boolean withRow) {
        String tableBody = withRow
                ? "<table><tbody><tr class='next-table-row'><td><a href='#' onclick=\"window.__clicked='row-link'\">下一页</a></td></tr></tbody></table>"
                : "<table><tbody><tr class='next-table-row'><td>暂无数据</td></tr></tbody></table>";
        return "<!doctype html><html><head>" + BASE_STYLE + "</head><body>"
                + "<div class='union-shell-header'><header>顶部导航</header></div>"
                + "<aside class='union-shell-sider'><nav><button>下一页</button></nav></aside>"
                + topBanner
                + "<main>" + tableBody + "<div class='next-box'>" + pagerFooter + "</div></main>"
                + "<footer class='union-shell-footer'><a href='#'>下一页</a></footer>"
                + "</body></html>";
    }
}
