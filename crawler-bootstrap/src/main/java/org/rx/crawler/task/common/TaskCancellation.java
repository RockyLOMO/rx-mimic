package org.rx.crawler.task.common;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative task cancellation; Chrome is only touched by its owner thread. */
public final class TaskCancellation {
    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();
    private TaskCancellation() { }
    public static final class Token {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public void cancel() { cancelled.set(true); }
    }
    // Task adapters catch Exception to attempt fallbacks. Cancellation must unwind those
    // fallbacks too; this private control signal is thrown only at cooperative boundaries.
    public static final class Cancelled extends Error {
        private Cancelled() { super("TASK_CANCELLED", null, false, false); }
    }
    public static Scope open(Token token) {
        Token previous = CURRENT.get();
        if (token == null) CURRENT.remove(); else CURRENT.set(token);
        return new Scope(previous);
    }
    public static Scope suspend() { return open(null); }
    public static void check() {
        Token token = CURRENT.get();
        if (token != null && token.cancelled.get()) throw new Cancelled();
    }
    public static final class Scope implements AutoCloseable {
        private final Token previous;
        private Scope(Token previous) { this.previous = previous; }
        public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
