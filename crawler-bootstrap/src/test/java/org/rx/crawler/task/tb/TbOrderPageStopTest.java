package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TbOrderPageStopTest {
    @Test
    public void firstPageIsKeptEvenWhenEmpty() {
        assertFalse(TbPromotionOrdersTask.repeatedOrderPage(1, 0));
        assertFalse(TbPromotionOrdersTask.repeatedOrderPage(1, 4));
    }

    @Test
    public void laterPageWithNoNewOrdersStops() {
        assertTrue(TbPromotionOrdersTask.repeatedOrderPage(2, 0));
        assertTrue(TbPromotionOrdersTask.repeatedOrderPage(28, 0));
        assertFalse(TbPromotionOrdersTask.repeatedOrderPage(3, 1));
    }

    @Test
    public void emptyOrderPageStopsOnlyWhenNoRowsAndEmptyListHint() {
        assertTrue(TbPromotionOrdersTask.emptyOrderPage(0, true));
        assertFalse(TbPromotionOrdersTask.emptyOrderPage(0, false));
        assertFalse(TbPromotionOrdersTask.emptyOrderPage(1, true));
    }

    @Test
    public void nextOrderPageScriptExcludesNoticeChromeAndAvoidsSyntheticEvents() {
        String checkScript = TbPromotionOrdersTask.nextOrderPageScript(false);
        String markScript = TbPromotionOrdersTask.nextOrderPageScript(true);

        assertTrue(checkScript.contains("AlpMessage"));
        assertTrue(checkScript.contains("#widget-topMsg"));
        assertTrue(checkScript.contains(".union-widgets-alp-message"));
        assertTrue(checkScript.contains(".union-shell-footer"));
        assertTrue(checkScript.contains("tbody"));
        assertFalse(checkScript.contains("setAttribute(attr,'1')"));

        assertTrue(markScript.contains("removeAttribute(attr)"));
        assertTrue(markScript.contains("target.setAttribute(attr,'1')"));

        String lower = (checkScript + markScript).toLowerCase(Locale.ROOT);
        assertFalse(lower.contains(".click("));
        assertFalse(lower.contains(".focus("));
        assertFalse(lower.contains("dispatchevent"));
        assertFalse(lower.contains("mouseevent"));
    }

    @Test
    public void hasNextOrderPageAndNativeClickUseSharedScriptAndNativeClick() {
        AppConfig config = new AppConfig();
        TbPromotionOrdersTask task = new TbPromotionOrdersTask(
                config,
                new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()),
                new ObjectMapper());
        Browser browser = mock(Browser.class);
        String checkScript = TbPromotionOrdersTask.nextOrderPageScript(false);
        String markScript = TbPromotionOrdersTask.nextOrderPageScript(true);

        when(browser.executeScript(checkScript)).thenReturn(false);
        assertFalse(task.hasNextOrderPage(browser));

        when(browser.executeScript(markScript)).thenReturn(false);
        assertFalse(task.nativeClickNextOrderPage(browser));
        verify(browser, never()).elementClick("[" + TbPromotionOrdersTask.NEXT_PAGE_MARKER_ATTR + "='1']", false);

        when(browser.executeScript(checkScript)).thenReturn(true);
        assertTrue(task.hasNextOrderPage(browser));

        when(browser.executeScript(markScript)).thenReturn(true);
        assertTrue(task.nativeClickNextOrderPage(browser));
        verify(browser).elementClick("[" + TbPromotionOrdersTask.NEXT_PAGE_MARKER_ATTR + "='1']", false);
    }
}
