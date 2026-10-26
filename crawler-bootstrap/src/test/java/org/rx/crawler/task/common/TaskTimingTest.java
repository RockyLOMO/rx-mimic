package org.rx.crawler.task.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTimingTest {
    @BeforeEach
    void reset() {
        TaskTiming.resetForTest();
    }

    @AfterEach
    void cleanup() {
        TaskTiming.resetForTest();
    }

    @Test
    void disabledScopeDropsNodesAndDoesNotLog() {
        try (TaskTiming.Scope scope = TaskTiming.open(false, "jdSearchProduct", 7)) {
            try (TaskTiming.Span ignored = TaskTiming.span("navigate", "https://www.jd.com/?keyword=secret")) {
            }
            TaskTiming.note("http.api", 400, "https://api.m.jd.com/api?functionId=unionSearchGoods&token=secret");
            scope.close();
        }
        assertNull(TaskTiming.lastSummary());
        assertFalse(TaskTiming.active());
    }

    @Test
    void summaryKeepsHttpStripsQueryAndDropsFastClick() {
        try (TaskTiming.Scope ignored = TaskTiming.open(true, "tbSearchProduct", 29)) {
            TaskTiming.note("http.xhr", 180, "https://h5api.m.taobao.com/h5/mtop.demo/2.0/?token=secret");
            try (TaskTiming.Span ignoredSpan = TaskTiming.span("click", "button")) {
            }
        }
        String summary = TaskTiming.lastSummary();
        assertTrue(summary.contains("action=tbSearchProduct"));
        assertTrue(summary.contains("taskId=29"));
        assertTrue(summary.contains("http.xhr"));
        assertTrue(summary.contains("https://h5api.m.taobao.com/h5/mtop.demo/2.0/"));
        assertFalse(summary.contains("token="));
        assertFalse(summary.contains("click"));
        assertFalse(TaskTiming.active());
    }

    @Test
    void nestedOpenDoesNotCloseTheOuterTask() {
        TaskTiming.Scope outer = TaskTiming.open(true, "jdProductDetail", 3);
        TaskTiming.Scope nested = TaskTiming.open(true, "inner", 9);
        TaskTiming.note("http.api", 80, "https://api.m.jd.com/api?functionId=listOrderSku");
        nested.close();
        assertTrue(TaskTiming.active());
        assertNull(TaskTiming.lastSummary());
        outer.close();
        String summary = TaskTiming.lastSummary();
        assertTrue(summary.contains("action=jdProductDetail"));
        assertTrue(summary.contains("taskId=3"));
        assertTrue(summary.contains("http.api"));
        assertFalse(summary.contains("functionId="));
    }

    @Test
    void overlappingNodesCountOnceAndSlowSliderStays() {
        List<TaskTiming.Node> nodes = List.of(
                new TaskTiming.Node("navigate", 0, 1000, "https://www.jd.com/"),
                new TaskTiming.Node("prefetch", 100, 400, "https://www.taobao.com/"),
                new TaskTiming.Node("slider.tb", 50, 80, "probe"),
                new TaskTiming.Node("slider.jd", 1200, 900, "captcha"));
        String line = TaskTiming.render("jdSearchProduct", 1, 2200, nodes);
        assertTrue(line.contains("otherMs=300"));
        assertTrue(line.contains("navigate 1000ms"));
        assertTrue(line.contains("prefetch 400ms"));
        assertTrue(line.contains("slider.jd 900ms"));
        assertFalse(line.contains("slider.tb"));
        assertEquals(1900, TaskTiming.mergedMillis(nodes));
    }
}
