package org.rx.crawler.task.common;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 任务耗时。只在 debug 打开、并且当前线程已经 {@link #open} 时记录。
 * 任务结束打一行 TASK_TIMING，标出导航、页面 HTTP、滑块、截图这些节点，以及没落到节点上的 otherMs。
 */
@Slf4j
public final class TaskTiming {
    static final int HTTP_KEEP_MS = 30;
    static final int SLIDER_KEEP_MS = 400;
    static final int NODE_KEEP_MS = 150;
    static final int SLOW_LOG_MS = 1000;
    static final int MAX_NODES = 40;

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<Scope>();
    private static volatile String lastSummary;

    private TaskTiming() {
    }

    public static boolean active() {
        Scope scope = CURRENT.get();
        return scope != null && scope.live;
    }

    /** 未打开 debug 时的空范围，close 什么都不做。 */
    public static Scope inactive() {
        return Scope.NOOP;
    }

    /**
     * 打开当前线程的计时。已经有范围时返回空范围，避免内层把外层提前关掉。
     */
    public static Scope open(boolean enabled, String action, Object taskId) {
        if (!enabled || active()) {
            return Scope.NOOP;
        }
        Scope scope = new Scope(action == null || action.isBlank() ? "task" : action, taskId);
        CURRENT.set(scope);
        return scope;
    }

    /** 包住一段工作。没有打开计时时返回空 span。 */
    public static Span span(String node, String detail) {
        Scope scope = CURRENT.get();
        if (scope == null || !scope.live) {
            return Span.NOOP;
        }
        return new Span(scope, node, detail, System.nanoTime());
    }

    /** 补记一段已经结束的耗时，例如页面自己发出的 HTTP。 */
    public static void note(String node, long elapsedMs, String detail) {
        Scope scope = CURRENT.get();
        if (scope == null || !scope.live || elapsedMs < 0) {
            return;
        }
        long start = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(elapsedMs);
        scope.add(node, start, elapsedMs, detail);
    }

    static String lastSummary() {
        return lastSummary;
    }

    static void resetForTest() {
        CURRENT.remove();
        lastSummary = null;
    }

    static String render(String action, Object taskId, long totalMs, List<Node> nodes) {
        long accounted = mergedMillis(nodes);
        long other = Math.max(0, totalMs - accounted);
        List<Node> interesting = new ArrayList<Node>();
        for (Node node : nodes) {
            if (interesting(node)) {
                interesting.add(node);
            }
        }
        int omitted = 0;
        if (interesting.size() > MAX_NODES) {
            omitted = interesting.size() - MAX_NODES;
            interesting.sort(Comparator.comparingInt((Node node) -> node.name.startsWith("http") ? 0 : 1)
                    .thenComparingLong(node -> -node.elapsedMs));
            interesting = new ArrayList<Node>(interesting.subList(0, MAX_NODES));
            interesting.sort(Comparator.comparingLong(node -> node.offsetMs));
        }
        StringBuilder timeline = new StringBuilder();
        for (Node node : interesting) {
            if (timeline.length() > 0) {
                timeline.append(" | ");
            }
            timeline.append('+').append(node.offsetMs).append("ms ")
                    .append(node.name).append(' ').append(node.elapsedMs).append("ms");
            if (!node.detail.isEmpty()) {
                timeline.append(' ').append(node.detail);
            }
        }
        StringBuilder line = new StringBuilder();
        line.append("TASK_TIMING action=").append(action)
                .append(" taskId=").append(taskId == null ? "" : taskId)
                .append(" totalMs=").append(totalMs)
                .append(" otherMs=").append(other);
        if (omitted > 0) {
            line.append(" omitted=").append(omitted);
        }
        line.append(" nodes=").append(timeline.length() == 0 ? "-" : timeline);
        return line.toString();
    }

    static boolean interesting(Node node) {
        if (node.name.startsWith("http")) {
            return node.elapsedMs >= HTTP_KEEP_MS;
        }
        if (node.name.startsWith("slider")) {
            return node.elapsedMs >= SLIDER_KEEP_MS;
        }
        return node.elapsedMs >= NODE_KEEP_MS;
    }

    static long mergedMillis(List<Node> nodes) {
        if (nodes.isEmpty()) {
            return 0;
        }
        List<Node> ordered = new ArrayList<Node>(nodes);
        ordered.sort(Comparator.comparingLong(node -> node.offsetMs));
        long covered = 0;
        long currentStart = -1;
        long currentEnd = -1;
        for (Node node : ordered) {
            long start = Math.max(0, node.offsetMs);
            long end = start + Math.max(0, node.elapsedMs);
            if (currentStart < 0) {
                currentStart = start;
                currentEnd = end;
                continue;
            }
            if (start <= currentEnd) {
                currentEnd = Math.max(currentEnd, end);
                continue;
            }
            covered += currentEnd - currentStart;
            currentStart = start;
            currentEnd = end;
        }
        if (currentStart >= 0) {
            covered += currentEnd - currentStart;
        }
        return covered;
    }

    static String sanitize(String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        String text = SensitiveUrls.redactText(detail).replace('\n', ' ').replace('\r', ' ').trim();
        if (text.length() > 160) {
            return text.substring(0, 160);
        }
        return text;
    }

    static final class Node {
        final String name;
        final long offsetMs;
        final long elapsedMs;
        final String detail;

        Node(String name, long offsetMs, long elapsedMs, String detail) {
            this.name = name == null || name.isBlank() ? "node" : name;
            this.offsetMs = offsetMs;
            this.elapsedMs = elapsedMs;
            this.detail = detail == null ? "" : detail;
        }
    }

    public static final class Scope implements AutoCloseable {
        private static final Scope NOOP = new Scope();

        private final boolean live;
        private final String action;
        private final Object taskId;
        private final long startNano;
        private final List<Node> nodes = new ArrayList<Node>();

        private Scope() {
            this.live = false;
            this.action = "";
            this.taskId = "";
            this.startNano = 0;
        }

        private Scope(String action, Object taskId) {
            this.live = true;
            this.action = action;
            this.taskId = taskId == null ? "" : taskId;
            this.startNano = System.nanoTime();
        }

        void add(String node, long startNano, long elapsedMs, String detail) {
            if (!live) {
                return;
            }
            String name = node == null || node.isBlank() ? "node" : node;
            long offset = TimeUnit.NANOSECONDS.toMillis(startNano - this.startNano);
            String safeDetail = sanitize(detail);
            nodes.add(new Node(name, Math.max(0, offset), elapsedMs, safeDetail));
            if (elapsedMs >= SLOW_LOG_MS) {
                log.info("TASK_TIMING action={} taskId={} node={} elapsedMs={} detail={}",
                        action, taskId, name, elapsedMs, safeDetail);
            }
        }

        @Override
        public void close() {
            if (!live || CURRENT.get() != this) {
                return;
            }
            CURRENT.remove();
            try {
                long total = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano);
                boolean worth = total >= 200;
                if (!worth) {
                    for (Node node : nodes) {
                        if (interesting(node)) {
                            worth = true;
                            break;
                        }
                    }
                }
                String line = render(action, taskId, total, nodes);
                lastSummary = line;
                if (worth) {
                    log.info("{}", line);
                }
            } catch (RuntimeException e) {
                log.warn("TASK_TIMING summary failed: {}", e.getMessage());
            }
        }
    }

    public static final class Span implements AutoCloseable {
        private static final Span NOOP = new Span(null, null, null, 0);

        private final Scope scope;
        private final String node;
        private final String detail;
        private final long startNano;
        private boolean closed;

        private Span(Scope scope, String node, String detail, long startNano) {
            this.scope = scope;
            this.node = node;
            this.detail = detail;
            this.startNano = startNano;
        }

        @Override
        public void close() {
            if (closed || scope == null) {
                return;
            }
            closed = true;
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano);
            scope.add(node, startNano, elapsed, detail);
        }
    }
}
