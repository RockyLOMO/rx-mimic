package org.rx.crawler.service.impl;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebBrowserSessionTest {
    @Test
    void browserContextCloseInvalidatesPooledSessionAndNotifiesRuntime() {
        WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        ReflectionTestUtils.setField(browser, "context", context);
        ReflectionTestUtils.setField(browser, "page", page);
        AtomicBoolean invalidated = new AtomicBoolean();
        browser.setSessionInvalidationListener(() -> invalidated.set(true));
        AtomicReference<Consumer<BrowserContext>> close = new AtomicReference<>();
        doAnswer(invocation -> { close.set(invocation.getArgument(0)); return null; }).when(context).onClose(any());
        browser.watchContextLifecycle(context);
        assertFalse(browser.isSessionUnavailable());
        close.get().accept(context);
        assertTrue(browser.isSessionUnavailable());
        assertTrue(invalidated.get());
    }
}
