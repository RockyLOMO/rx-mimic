package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskCancellationTest {
    @Test void cancellationIsScopedAndCleanupCanRun() throws Exception {
        var token = new TaskCancellation.Token();
        try (var ignored = TaskCancellation.open(token)) {
            token.cancel();
            assertThrows(TaskCancellation.Cancelled.class, TaskCancellation::check);
            try (var cleanup = TaskCancellation.suspend()) { TaskCancellation.check(); }
            assertThrows(TaskCancellation.Cancelled.class, TaskCancellation::check);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread other = new Thread(() -> { try { TaskCancellation.check(); } catch (Throwable t) { failure.set(t); } });
            other.start(); other.join(); assertNull(failure.get(), "unrelated threads must not inherit cancellation");
        }
        assertDoesNotThrow(TaskCancellation::check);
    }
}
