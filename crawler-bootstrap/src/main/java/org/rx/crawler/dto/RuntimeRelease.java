package org.rx.crawler.dto;

import java.util.concurrent.atomic.AtomicInteger;

/** Publish sequence returned by the API. Zero means this process was not assigned one. */
public final class RuntimeRelease {
    private static final AtomicInteger CURRENT = new AtomicInteger();

    private RuntimeRelease() {
    }

    public static void publish(int release) {
        CURRENT.set(Math.max(0, release));
    }

    public static int current() {
        return CURRENT.get();
    }
}
