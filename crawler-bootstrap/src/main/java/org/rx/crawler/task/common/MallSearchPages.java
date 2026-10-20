package org.rx.crawler.task.common;

/**
 * 主站搜索翻页：用期望条数和单页实际条数计算需要打开的页数。
 */
public final class MallSearchPages {
    private MallSearchPages() {
    }

    public static int pageCount(int maxResults, int pageSize) {
        int want = Math.max(1, maxResults);
        int size = Math.max(1, pageSize);
        return (want + size - 1) / size;
    }
}
