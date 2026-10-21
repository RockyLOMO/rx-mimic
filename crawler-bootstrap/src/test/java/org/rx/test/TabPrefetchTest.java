package org.rx.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rx.crawler.service.TabPrefetch;
import org.rx.crawler.task.common.ChromeProfileConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TabPrefetchTest {
    @AfterEach
    public void clearPlan() {
        TabPrefetch.clear();
    }

    @Test
    public void defaultIsTwoTabsAndOneChrome() {
        ChromeProfileConfig config = new ChromeProfileConfig();
        assertEquals(2, config.getTabSlots());
        assertEquals(1, config.getMaxActive());
    }

    @Test
    public void adoptIsClaimedOnceAndNextStaysUntilMarked() {
        TabPrefetch.arm("https://www.jd.com/", "https://www.taobao.com");
        assertEquals("https://www.jd.com", TabPrefetch.adoptUrl());
        assertFalse(TabPrefetch.claimAdopt("https://s.taobao.com/search?q=a"));
        assertTrue(TabPrefetch.claimAdopt("https://www.jd.com"));
        assertFalse(TabPrefetch.claimAdopt("https://www.jd.com"));
        assertEquals("https://www.taobao.com", TabPrefetch.peekNext());
        TabPrefetch.markNextStarted();
        assertNull(TabPrefetch.peekNext());
    }

    @Test
    public void queryStaysInTheMatch() {
        assertFalse(TabPrefetch.same(
                "https://item.jd.com/1.html?extra=1",
                "https://item.jd.com/1.html?extra=2"));
        assertEquals("https://item.jd.com/1.html", TabPrefetch.brief("https://item.jd.com/1.html?extra=1"));
    }
}
