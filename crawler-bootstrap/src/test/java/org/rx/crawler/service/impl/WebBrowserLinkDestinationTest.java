package org.rx.crawler.service.impl;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebBrowserLinkDestinationTest {
    @Test void readsPopupAndClosesOnlyThatPopupThenRestoresTheSource() {
        Fixture f = new Fixture();
        when(f.detail.url()).thenReturn("https://detail.tmall.com/item.htm?id=123456789");
        String url = f.browser.readLinkDestination("a.product", value -> value.contains("detail.tmall.com"), 10);
        assertTrue(url.contains("123456789"));
        verify(f.browser).elementClick("a.product");
        verify(f.detail).close();
        verify(f.source, never()).close();
        assertSame(f.source, ReflectionTestUtils.getField(f.browser, "page"));
        assertEquals(1, f.tabs.size());
    }

    @Test void failedPopupReadStillClosesTemporaryTabAndRestoresSource() {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("read failed")).when(f.detail).waitForTimeout(anyDouble());
        assertThrows(IllegalStateException.class, () -> f.browser.readLinkDestination("a.product", value -> false, 10));
        verify(f.detail).close();
        assertSame(f.source, ReflectionTestUtils.getField(f.browser, "page"));
    }

    @Test void refusesAThirdTabWithoutClosingUnownedTabs() {
        Fixture f = new Fixture();
        Page existing = mock(Page.class);
        when(f.context.pages()).thenReturn(List.of(f.source, existing));
        assertThrows(RuntimeException.class, () -> f.browser.readLinkDestination("a.product", value -> true, 10));
        verify(existing, never()).close();
        verify(f.browser, never()).elementClick(anyString());
    }

    @Test void popupCreatedBeforeWaitFailureIsStillClosed() {
        Fixture f = new Fixture();
        when(f.source.waitForPopup(any(Page.WaitForPopupOptions.class), any(Runnable.class))).thenAnswer(call -> {
            f.popupListener.get().accept(f.detail);
            throw new IllegalStateException("click failed after popup");
        });
        assertThrows(IllegalStateException.class, () -> f.browser.readLinkDestination("a.product", value -> false, 10));
        verify(f.detail).close();
        assertSame(f.source, ReflectionTestUtils.getField(f.browser, "page"));
    }

    @Test void adoptedIdlePrefetchTabReleasesItsSlotAfterTargetMarkerWasCleared() {
        Fixture f = new Fixture();
        Page idle = mock(Page.class);
        f.tabs.put("adopted", idle);
        ReflectionTestUtils.setField(f.browser, "prefetchOwnedTabs", new java.util.HashSet<>(List.of(idle)));
        when(f.context.pages()).thenAnswer(call -> f.tabs.values().stream().toList());
        when(f.detail.url()).thenReturn("https://detail.tmall.com/item.htm?id=123456789");
        assertTrue(f.browser.readLinkDestination("a.product", value -> value.contains("detail.tmall.com"), 10)
                .contains("123456789"));
        verify(idle).close();
        verify(f.source, never()).close();
    }

    @Test void adoptingLastPrefetchAlsoMarksPreviousTaskPageDisposable() {
        Fixture f = new Fixture();
        Page warm = mock(Page.class);
        f.tabs.put("warm", warm);
        ReflectionTestUtils.setField(f.browser, "prefetchTargetByHandle",
                new LinkedHashMap<>(java.util.Map.of("warm", "https://pub.alimama.com/")));
        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(f.browser,
                "adoptPrefetchedTab", "https://pub.alimama.com/", 1));
        var owned = (java.util.Set<?>) ReflectionTestUtils.getField(f.browser, "prefetchOwnedTabs");
        assertTrue(owned.contains(f.source));
        assertSame(warm, ReflectionTestUtils.getField(f.browser, "page"));
    }

    @Test void closeFailureStillRestoresOriginalPage() {
        Fixture f = new Fixture();
        when(f.detail.url()).thenReturn("https://detail.tmall.com/item.htm?id=123456789");
        doThrow(new IllegalStateException("close failed")).when(f.detail).close();
        assertThrows(RuntimeException.class, () -> f.browser.readLinkDestination("a.product", value -> true, 10));
        assertSame(f.source, ReflectionTestUtils.getField(f.browser, "page"));
    }

    private static final class Fixture {
        final WebBrowser browser = mock(WebBrowser.class, CALLS_REAL_METHODS);
        final BrowserContext context = mock(BrowserContext.class);
        final Page source = mock(Page.class), detail = mock(Page.class);
        final LinkedHashMap<String, Page> tabs = new LinkedHashMap<>();
        final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Page>> popupListener = new java.util.concurrent.atomic.AtomicReference<>();
        Fixture() {
            tabs.put("source", source);
            ReflectionTestUtils.setField(browser, "context", context);
            ReflectionTestUtils.setField(browser, "page", source);
            ReflectionTestUtils.setField(browser, "tabs", tabs);
            ReflectionTestUtils.setField(browser, "prefetchTargetByHandle", new LinkedHashMap<String, String>());
            ReflectionTestUtils.setField(browser, "prefetchOwnedTabs", new java.util.HashSet<Page>());
            ReflectionTestUtils.setField(browser, "config", new WebBrowserConfig());
            when(context.pages()).thenReturn(List.of(source));
            doAnswer(call -> { popupListener.set(call.getArgument(0)); return null; }).when(source).onPopup(any());
            doNothing().when(browser).elementClick(anyString());
            when(source.waitForPopup(any(Page.WaitForPopupOptions.class), any(Runnable.class))).thenAnswer(call -> {
                call.getArgument(1, Runnable.class).run();
                return detail;
            });
        }
    }
}
