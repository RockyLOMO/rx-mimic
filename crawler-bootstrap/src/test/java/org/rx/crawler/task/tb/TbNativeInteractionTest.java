package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TbNativeInteractionTest {
    @Test void nativeSearchInputFailureReturnsFailureWithoutWritingTheDom() {
        var f = new Fixture();
        when(f.browser.executeScript(anyString())).thenReturn(true);
        doThrow(new IllegalStateException("input unavailable")).when(f.browser)
                .elementPress(anyString(), anyString(), anyBoolean());
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(f.task, "nativeSetSearchValue", f.browser, "strap"));
        verify(f.browser).elementPress("[data-rx-tb-promo-search-input='1']", "strap", false);
        assertEquals(1, mockingDetails(f.browser).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("executeScript")).count());
        f.assertNoSyntheticInteraction();
    }

    @Test void missingSearchTargetDoesNotTryToInventAnInputValue() {
        var f = new Fixture();
        when(f.browser.executeScript(anyString())).thenReturn(false);
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(f.task, "nativeSetSearchValue", f.browser, "strap"));
        verify(f.browser, never()).elementPress(anyString(), anyString(), anyBoolean());
        f.assertNoSyntheticInteraction();
    }

    @Test void openSelectSearchTypesOnlyIntoItsMarkedVisibleInput() {
        var f = new Fixture();
        when(f.browser.executeScript(anyString())).thenReturn(true);
        ReflectionTestUtils.invokeMethod(f.task, "typeIntoOpenSelectSearch", f.browser, "3");
        verify(f.browser).elementPress("[data-rx-tb-select-search='1']", "3", false);
        verify(f.browser, never()).elementPress(eq("#adzoneId"), anyString());
        f.assertNoSyntheticInteraction();
    }

    @Test void dropdownDismissalUsesNativeEscapeWithoutDispatchingEvents() {
        var f = new Fixture();
        ReflectionTestUtils.invokeMethod(f.task, "closeOpenSelectPopup", f.browser);
        verify(f.browser).pressKey("Escape");
        assertTrue(mockingDetails(f.browser).getInvocations().stream()
                .noneMatch(call -> call.getMethod().getName().equals("executeScript")));
    }

    @Test void missingMouseCoordinatesFallBackToNativeElementClick() {
        var f = new Fixture();
        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(f.task, "nativeClickMarkedElement", f.browser, "#option"));
        verify(f.browser).elementClick("#option", false);
        f.assertNoSyntheticInteraction();
    }

    @Test void confirmRetryMarksItsButtonThenUsesNativeElementClick() {
        var f = new Fixture();
        when(f.browser.executeScript(anyString())).thenReturn(true);
        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(f.task, "nativeClickSlotConfirmPrimary", f.browser));
        verify(f.browser).elementClick("[data-rx-tb-slot-confirm='1']", false);
        f.assertNoSyntheticInteraction();
    }

    private static final class Fixture {
        final Browser browser = mock(Browser.class);
        final TbPromotionUrlTask task = new TbPromotionUrlTask(new AppConfig(), mock(BrowserProfileManager.class),
                mock(CrawlEntryService.class), new ObjectMapper());

        void assertNoSyntheticInteraction() {
            mockingDetails(browser).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("executeScript"))
                    .map(call -> call.getArgument(0, String.class)).forEach(script -> {
                        assertFalse(script.contains("dispatchEvent"), script);
                        assertFalse(script.contains(".click("), script);
                        assertFalse(script.contains(".focus("), script);
                        assertFalse(script.contains("setter"), script);
                        assertFalse(script.matches("(?s).*\\.value\\s*=.*"), script);
                    });
        }
    }
}
