package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.*;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TbVerificationFlowTest {
    @Test void orderChallengeRecoveryRequestsReplayInsteadOfReadingRowsWithResetDateFilters() {
        var config = new AppConfig();
        var task = new TbPromotionOrdersTask(config, mock(BrowserProfileManager.class), mock(CrawlEntryService.class),
                mock(ResultWriter.class), new ObjectMapper());
        var handler = mock(SliderVerifyHandler.class);
        var browser = mock(Browser.class);
        ReflectionTestUtils.setField(task, "sliderVerifyHandler", handler);
        when(handler.isSliderVerifyPage(browser)).thenReturn(true);
        when(handler.checkAndHandle(eq(browser), anyString(), eq(5), anyLong(), any(),
                eq(config.getCustom().getTbPromotion().getOrderUrl()))).thenReturn(true);
        RuntimeException retry = assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(task,
                "checkAndHandleSliderVerify", browser, config.getCustom().getTbPromotion(),
                new TbPromotionOrdersResult(), null, "order-rows-slider"));
        assertEquals("OrderVerificationRestart", retry.getClass().getSimpleName());
    }
    @Test void lightweightDetailUsesTheSameSliderHandlerAndKeepsItsDetailRecoveryUrl() {
        var config = new AppConfig();
        var task = new TbMallTask(config, mock(BrowserProfileManager.class), mock(CrawlEntryService.class),
                mock(ResultWriter.class), new ObjectMapper());
        var handler = mock(SliderVerifyHandler.class);
        var browser = mock(Browser.class);
        ReflectionTestUtils.setField(task, "sliderVerifyHandler", handler);
        ((ThreadLocal<Boolean>) ReflectionTestUtils.getField(task, "identityOnly")).set(true);
        String target = "https://item.taobao.com/item.htm?id=123456789";
        ((ThreadLocal<String>) ReflectionTestUtils.getField(task, "sliderEntryUrl")).set(target);
        when(browser.getCurrentUrl()).thenReturn("https://item.taobao.com/punish");
        when(handler.isJumpedVerifyUrl(anyString())).thenReturn(true);
        when(handler.isSliderVerifyPage(browser)).thenReturn(true);
        when(handler.checkAndHandle(eq(browser), anyString(), anyInt(), anyLong(), any(), eq(target))).thenReturn(true);
        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(task, "clearSlider", browser,
                config.getCustom().getTbMall(), null, "detail-slider"));
        verify(handler).checkAndHandle(eq(browser), eq("detail-slider"), anyInt(), anyLong(), any(), eq(target));
    }

    @Test void promotionAnalysisPassesItsOwnQueryUrlToTheExistingHandler() {
        var config = new AppConfig();
        var task = new TbPromotionUrlTask(config, mock(BrowserProfileManager.class), mock(CrawlEntryService.class),
                mock(ResultWriter.class), new ObjectMapper());
        var handler = mock(SliderVerifyHandler.class);
        var browser = mock(Browser.class);
        ReflectionTestUtils.setField(task, "sliderVerifyHandler", handler);
        String target = TbUnionAnalysisPage.entryUrl(config.getCustom().getTbPromotion().getAnalysisUrl(), "123456789");
        when(handler.checkAndHandle(eq(browser), anyString(), eq(5), anyLong(), any(), eq(target))).thenReturn(true);
        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(task, "onSliderVerifyDetected", browser,
                config.getCustom().getTbPromotion(), new PromotionUrlResult(), null, "analysis-slider", target));
        verify(handler).checkAndHandle(eq(browser), eq("analysis-slider"), eq(5), anyLong(), any(), eq(target));
    }
}
