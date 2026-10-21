package org.rx.crawler.task.common;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 只清理名字为 debug 的调试目录里的 html、png、txt。
 * 正在写入的任务目录不删。预算用尽时停止新的调试文件，不中断业务结果。
 */
@Slf4j
public final class DebugSnapshotPolicy {
    private static volatile int retentionHours = 72;
    private static volatile long maxBytes = 512L * 1024 * 1024;
    private static volatile long minFreeBytes = 1024L * 1024 * 1024;
    private static volatile int activeMinutes = 15;
    private static volatile long lastPruneAt;
    private static volatile long lastWarnAt;

    private DebugSnapshotPolicy() {
    }

    static void resetForTests() {
        lastPruneAt = 0;
        lastWarnAt = 0;
    }

    public static void configure(int retentionHours, long maxBytes, long minFreeBytes, int activeMinutes) {
        DebugSnapshotPolicy.retentionHours = Math.max(1, retentionHours);
        DebugSnapshotPolicy.maxBytes = Math.max(1, maxBytes);
        DebugSnapshotPolicy.minFreeBytes = Math.max(0, minFreeBytes);
        DebugSnapshotPolicy.activeMinutes = Math.max(1, activeMinutes);
    }

    public static boolean allow(Path file) {
        if (file == null) {
            return false;
        }
        Path root = debugRoot(file);
        if (root == null) {
            return true;
        }
        try {
            prune(root);
            if (usable(root) < minFreeBytes) {
                warn("Debug snapshots paused because free disk is below the threshold");
                return false;
            }
            if (used(root) >= maxBytes) {
                warn("Debug snapshots paused because the debug directory is over its byte budget");
                return false;
            }
            return true;
        } catch (Exception e) {
            warn("Debug snapshot write skipped, error=" + e.getMessage());
            return false;
        }
    }

    static Path debugRoot(Path file) {
        Path current = file.toAbsolutePath().normalize();
        while (current != null) {
            Path name = current.getFileName();
            if (name != null && "debug".equalsIgnoreCase(name.toString())) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static void prune(Path root) throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastPruneAt < 60_000L) {
            return;
        }
        lastPruneAt = now;
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant expireBefore = Instant.now().minus(Duration.ofHours(retentionHours));
        Instant activeAfter = Instant.now().minus(Duration.ofMinutes(activeMinutes));
        List<Path> files = listDebugFiles(root);
        for (Path file : files) {
            if (active(file.getParent(), activeAfter)) {
                continue;
            }
            if (Files.getLastModifiedTime(file).toInstant().isBefore(expireBefore)) {
                Files.deleteIfExists(file);
            }
        }
        long size = used(root);
        if (size < maxBytes) {
            deleteEmptyDirs(root);
            return;
        }
        List<Path> tasks = taskDirs(root);
        tasks.sort(Comparator.comparingLong(DebugSnapshotPolicy::oldestMillis));
        for (Path task : tasks) {
            if (size < maxBytes) {
                break;
            }
            if (active(task, activeAfter)) {
                continue;
            }
            size -= deleteDebugFiles(task);
        }
        deleteEmptyDirs(root);
    }

    private static boolean active(Path dir, Instant activeAfter) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        try {
            if (Files.getLastModifiedTime(dir).toInstant().isAfter(activeAfter)) {
                return true;
            }
            try (Stream<Path> children = Files.list(dir)) {
                return children.anyMatch(child -> {
                    try {
                        return Files.getLastModifiedTime(child).toInstant().isAfter(activeAfter);
                    } catch (IOException e) {
                        return true;
                    }
                });
            }
        } catch (IOException e) {
            return true;
        }
    }

    private static List<Path> listDebugFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).filter(DebugSnapshotPolicy::debugFile).forEach(files::add);
        }
        return files;
    }

    private static List<Path> taskDirs(Path root) throws IOException {
        List<Path> dirs = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory).filter(dir -> !dir.equals(root)).forEach(dirs::add);
        }
        return dirs;
    }

    private static long deleteDebugFiles(Path dir) throws IOException {
        long removed = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> files = walk.filter(Files::isRegularFile).filter(DebugSnapshotPolicy::debugFile).toList();
            for (Path file : files) {
                removed += Files.size(file);
                Files.deleteIfExists(file);
            }
        }
        return removed;
    }

    private static void deleteEmptyDirs(Path root) throws IOException {
        List<Path> dirs = taskDirs(root);
        dirs.sort(Comparator.comparingInt((Path path) -> path.getNameCount()).reversed());
        for (Path dir : dirs) {
            if (!dir.startsWith(root) || dir.equals(root)) {
                continue;
            }
            try (Stream<Path> children = Files.list(dir)) {
                if (children.findAny().isEmpty()) {
                    Files.deleteIfExists(dir);
                }
            }
        }
    }

    private static long used(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        long total = 0;
        for (Path file : listDebugFiles(root)) {
            total += Files.size(file);
        }
        return total;
    }

    private static long usable(Path root) throws IOException {
        Path probe = Files.isDirectory(root) ? root : root.getParent();
        if (probe == null) {
            return Long.MAX_VALUE;
        }
        FileStore store = Files.getFileStore(probe);
        return store.getUsableSpace();
    }

    private static long oldestMillis(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean debugFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".png") || name.endsWith(".txt");
    }

    private static void warn(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < 60_000L) {
            return;
        }
        lastWarnAt = now;
        log.warn(message);
    }
}
