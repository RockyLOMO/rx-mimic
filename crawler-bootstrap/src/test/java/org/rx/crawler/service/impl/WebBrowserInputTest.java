package org.rx.crawler.service.impl;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebBrowserInputTest {
    private static WebBrowser browser(Page page) {
        WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
        WebBrowserConfig config = new WebBrowserConfig();
        config.setHumanActionMinDelayMillis(0);
        config.setHumanActionMaxDelayMillis(0);
        config.setOperationRandomMinDelayMillis(0);
        config.setOperationRandomMaxDelayMillis(0);
        config.setMouseMoveMinSteps(1);
        config.setMouseMoveMaxSteps(1);
        ReflectionTestUtils.setField(browser, "config", config);
        ReflectionTestUtils.setField(browser, "page", page);
        return browser;
    }

    private static Locator input(Page page) {
        Frame frame = mock(Frame.class);
        Locator locator = mock(Locator.class);
        when(page.mainFrame()).thenReturn(frame);
        when(frame.locator("#input")).thenReturn(locator);
        when(locator.count()).thenReturn(1);
        when(locator.nth(0)).thenReturn(locator);
        when(locator.isEditable()).thenReturn(true);
        BoundingBox box = new BoundingBox();
        box.x = 20; box.y = 20; box.width = 120; box.height = 30;
        when(locator.boundingBox()).thenReturn(box);
        return locator;
    }

    @Test
    void maximizedWindowUsesRealDimensionsAndStillSendsNativeWheel() {
        Page page = mock(Page.class);
        Mouse mouse = mock(Mouse.class);
        when(page.mouse()).thenReturn(mouse);
        when(page.viewportSize()).thenReturn(null);
        when(page.evaluate(anyString())).thenReturn(Map.of("innerWidth", 1280, "innerHeight", 720));
        WebBrowser browser = browser(page);
        browser.scrollPage(0, 180);
        verify(mouse, times(2)).wheel(anyDouble(), anyDouble());
        verify(page, never()).mainFrame(); // No JS-scroll fallback.
    }

    @Test
    void failedActionabilityDoesNotRetryWithForceOrSendCoordinateClick() {
        Page page = mock(Page.class);
        Locator locator = input(page);
        doThrow(new PlaywrightException("Target is obscured")).when(locator).click(any(Locator.ClickOptions.class));
        WebBrowser browser = browser(page);
        assertThrows(PlaywrightException.class, () -> browser.elementClick("#input"));
        ArgumentCaptor<Locator.ClickOptions> options = ArgumentCaptor.forClass(Locator.ClickOptions.class);
        verify(locator).click(options.capture());
        assertEquals(Boolean.TRUE, options.getValue().trial);
        assertNotEquals(Boolean.TRUE, options.getValue().force);
        verify(page, never()).mouse();
    }

    @Test
    void inputWithoutFocusNeverSendsSelectAllOrDeletesOriginalControl() {
        Page page = mock(Page.class);
        when(page.mouse()).thenReturn(mock(Mouse.class));
        Locator locator = input(page);
        when(locator.evaluate(anyString())).thenReturn(false);
        WebBrowser browser = browser(page);
        assertThrows(PlaywrightException.class, () -> browser.elementPress("#input", "replacement"));
        verify(page, never()).keyboard();
    }

    @Test
    void inputValueMismatchIsAnExplicitFailure() {
        Page page = mock(Page.class);
        Locator locator = input(page);
        when(locator.evaluate(anyString())).thenReturn("old-value");
        WebBrowser browser = browser(page);
        WebBrowserConfig config = (WebBrowserConfig) ReflectionTestUtils.getField(browser, "config");
        config.setHumanInputEnabled(false);
        assertThrows(PlaywrightException.class, () -> browser.elementPress("#input", "replacement"));
        verify(locator).fill("replacement");
    }

    @Test
    void mouseButtonIsReleasedWhenPauseAfterPressIsInterrupted() {
        Page page = mock(Page.class);
        Mouse mouse = mock(Mouse.class);
        when(page.mouse()).thenReturn(mouse);
        doAnswer(invocation -> { Thread.currentThread().interrupt(); return null; }).when(mouse).down();
        WebBrowser browser = browser(page);
        try {
            assertThrows(InterruptedException.class, () -> browser.mouseClick(200, 100));
            verify(mouse).up();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void eventWaitPumpsPlaywrightInsteadOfSleepingOutsideItsLoop() {
        Page page = mock(Page.class);
        browser(page).waitForEvents(125);
        verify(page).waitForTimeout(125);
    }
}
