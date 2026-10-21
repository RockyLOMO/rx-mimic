package org.rx.crawler.task.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugSnapshotPolicyTest {
    @TempDir
    Path temp;

    @BeforeEach
    void reset() {
        DebugSnapshotPolicy.resetForTests();
        DebugSnapshotPolicy.configure(1, 512L * 1024 * 1024, 0, 15);
    }

    @Test
    void expiredInactiveSnapshotIsRemovedAndActiveOneStays() throws Exception {
        Path root = temp.resolve("debug");
        Path oldFile = root.resolve("old-task").resolve("page.html");
        Path activeFile = root.resolve("active-task").resolve("page.html");
        Files.createDirectories(oldFile.getParent());
        Files.createDirectories(activeFile.getParent());
        Files.writeString(oldFile, "old");
        Files.writeString(activeFile, "new");
        Instant old = Instant.now().minus(3, ChronoUnit.DAYS);
        Files.setLastModifiedTime(oldFile, FileTime.from(old));
        Files.setLastModifiedTime(oldFile.getParent(), FileTime.from(old));

        assertTrue(DebugSnapshotPolicy.allow(root.resolve("active-task").resolve("next.html")));
        assertFalse(Files.exists(oldFile));
        assertTrue(Files.exists(activeFile));
    }

    @Test
    void activeDirectoryIsKeptWhenBudgetIsAlreadyFull() throws Exception {
        Path root = temp.resolve("debug");
        Path file = root.resolve("active-task").resolve("page.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "still-needed");
        DebugSnapshotPolicy.configure(72, 1, 0, 15);

        assertFalse(DebugSnapshotPolicy.allow(root.resolve("active-task").resolve("next.html")));
        assertTrue(Files.exists(file));
    }

    @Test
    void lowFreeDiskStopsNewSnapshots() {
        DebugSnapshotPolicy.configure(72, 512L * 1024 * 1024, Long.MAX_VALUE, 15);
        Path file = temp.resolve("debug").resolve("task").resolve("page.html");
        assertFalse(DebugSnapshotPolicy.allow(file));
    }
}
