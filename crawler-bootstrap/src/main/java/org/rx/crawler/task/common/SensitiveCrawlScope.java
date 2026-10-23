package org.rx.crawler.task.common;

/** Prevent raw union pages/materials being archived while platform adapters are running. */
public final class SensitiveCrawlScope implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private SensitiveCrawlScope() { DEPTH.set(DEPTH.get() + 1); }
    public static SensitiveCrawlScope open() { return new SensitiveCrawlScope(); }
    public static boolean active() { return DEPTH.get() > 0; }
    @Override public void close() {
        int count = DEPTH.get() - 1;
        if (count <= 0) DEPTH.remove(); else DEPTH.set(count);
    }
}
