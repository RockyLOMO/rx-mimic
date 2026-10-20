package org.rx.crawler.task.common;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

/** All persistent Playwright sessions and their maintenance share this thread. */
@Service
@lombok.extern.slf4j.Slf4j
public class BrowserOwnerExecutor {
    private volatile Thread ownerThread;
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chrome-owner");
        thread.setDaemon(true);
        ownerThread = thread;
        return thread;
    });

    public boolean isOwnerThread() { return Thread.currentThread() == ownerThread; }
    public boolean isAvailable() { return !executor.isShutdown(); }
    public int getPendingCount() { return pending.get(); }
    public void execute(Runnable runnable) {
        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try { runnable.run(); }
                catch (Throwable error) { log.error("Browser owner operation failed", error); }
                finally { pending.decrementAndGet(); }
            });
        } catch (RuntimeException e) { pending.decrementAndGet(); throw e; }
    }

    public <T> T call(Callable<T> callable) {
        if (isOwnerThread()) {
            try { return callable.call(); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        FutureTask<T> future = new FutureTask<>(callable);
        execute(future);
        try { return future.get(); }
        catch (InterruptedException e) {
            // A disconnected waiter must never interrupt shared Chrome mid-operation.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for browser owner", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) { throw runtime; }
            throw new IllegalStateException(e.getCause());
        }
    }

    @PreDestroy
    public void shutdown() { executor.shutdown(); }
}
