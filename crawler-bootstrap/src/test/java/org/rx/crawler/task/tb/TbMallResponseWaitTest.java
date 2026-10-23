package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class TbMallResponseWaitTest {
    private TbMallTask task() {
        return new TbMallTask(new AppConfig(), mock(BrowserProfileManager.class),
                mock(CrawlEntryService.class), new ObjectMapper());
    }

    @Test
    void responseArrivingDuringWaitIsDeliveredBeforeDeadline() throws Exception {
        Browser browser = mock(Browser.class);
        var pending = new ArrayDeque<String>();
        when(browser.drainResponseCapture(TbMallPageApi.DETAIL_ADJUST_API)).thenAnswer(invocation -> {
            var result = new ArrayList<>(pending);
            pending.clear();
            return result;
        });
        doAnswer(invocation -> {
            pending.add("{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{\"price\":\"12.00\"}}");
            return null;
        }).when(browser).waitForEvents(anyLong());
        var result = task().waitAdjustResponses(browser, new TbMallPageApi(new ObjectMapper()), 2);
        assertEquals(1, result.size());
        assertEquals("12.00", result.getFirst().path("data").path("price").asText());
        verify(browser).waitForEvents(anyLong());
    }

    @Test
    void expiredTaskStopsWithoutWaitingForMoreResponses() {
        TbMallTask task = task();
        ThreadLocal<Long> deadline = new ThreadLocal<>();
        deadline.set(System.currentTimeMillis() - 1000);
        ReflectionTestUtils.setField(task, "taskDeadlineHolder", deadline);
        Browser browser = mock(Browser.class);
        assertThrows(TimeoutException.class,
                () -> task.waitAdjustResponses(browser, new TbMallPageApi(new ObjectMapper()), 6));
        verifyNoInteractions(browser);
    }

    @Test
    void zeroWaitReadsAlreadyCapturedResponsesWithoutSleeping() throws Exception {
        Browser browser = mock(Browser.class);
        when(browser.drainResponseCapture(TbMallPageApi.DETAIL_ADJUST_API)).thenReturn(java.util.List.of());
        assertTrue(task().waitAdjustResponses(browser, new TbMallPageApi(new ObjectMapper()), 0).isEmpty());
        verify(browser, never()).waitForEvents(anyLong());
    }
}
