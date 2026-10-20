package org.rx.test;

import org.junit.jupiter.api.Test;
import org.rx.crawler.task.common.MallSearchPages;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MallSearchPagesTest {
    @Test
    public void pageCountShouldCoverPartialLastPage() {
        assertEquals(1, MallSearchPages.pageCount(30, 30));
        assertEquals(2, MallSearchPages.pageCount(31, 30));
        assertEquals(2, MallSearchPages.pageCount(60, 30));
        assertEquals(3, MallSearchPages.pageCount(100, 48));
        assertEquals(1, MallSearchPages.pageCount(1, 30));
        assertEquals(40, MallSearchPages.pageCount(40, 0));
    }
}
