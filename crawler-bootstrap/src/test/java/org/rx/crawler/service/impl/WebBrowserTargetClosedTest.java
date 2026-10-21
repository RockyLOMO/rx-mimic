package org.rx.crawler.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebBrowserTargetClosedTest {
    @Test
    public void targetClosedMatchesPlaywrightCloseErrors() {
        assertTrue(WebBrowser.targetClosed(new RuntimeException(
                "Target page, context or browser has been closed")));
        assertTrue(WebBrowser.targetClosed(new RuntimeException("navigate",
                new IllegalStateException("TargetClosedError: Target closed"))));
        assertFalse(WebBrowser.targetClosed(new RuntimeException("net::ERR_TIMED_OUT")));
        assertFalse(WebBrowser.targetClosed(null));
    }
}
