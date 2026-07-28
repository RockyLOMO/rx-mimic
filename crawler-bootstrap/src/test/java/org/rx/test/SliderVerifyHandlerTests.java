package org.rx.test;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.tb.SliderVerifyHandler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SliderVerifyHandlerTests {
    @Test
    public void sliderVerifyShouldKeepBodyKeywordDetection() {
        Browser browser = mock(Browser.class);
        when(browser.getCurrentUrl()).thenReturn("https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm");
        when(browser.<String>executeScript(anyString())).thenReturn("亲，请拖动下方滑块完成验证");

        assertTrue(new SliderVerifyHandler().isSliderVerifyPage(browser));
        verify(browser, times(1)).executeScript(anyString());
    }

    @Test
    public void sliderVerifyShouldDetectPopupDomWhenBodySnippetMissed() {
        Browser browser = mock(Browser.class);
        when(browser.getCurrentUrl()).thenReturn("https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm");
        when(browser.executeScript(anyString())).thenReturn("正常商品页", Boolean.TRUE);

        assertTrue(new SliderVerifyHandler().isSliderVerifyPage(browser));

        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        verify(browser, times(2)).executeScript(scriptCaptor.capture());
        assertTrue(scriptCaptor.getAllValues().get(1).contains("querySelectorAll('iframe,div,span,input,button')"));
    }

    @Test
    public void sliderVerifyShouldIgnoreNormalOrderPageWhenDomProbeMissed() {
        Browser browser = mock(Browser.class);
        when(browser.getCurrentUrl()).thenReturn("https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm");
        when(browser.executeScript(anyString())).thenReturn("推广订单明细 订单信息 暂无数据", Boolean.FALSE);

        assertFalse(new SliderVerifyHandler().isSliderVerifyPage(browser));
    }

    @Test
    public void jumpedVerifyUrlShouldBeDetectedByPunishMarkers() {
        SliderVerifyHandler handler = new SliderVerifyHandler();
        assertTrue(handler.isJumpedVerifyUrl(
                "https://pub.alimama.com/_____tmd_____/punish?x5secdata=abc"));
        assertTrue(handler.isJumpedVerifyUrl(
                "https://login.taobao.com/member/x5secdata/verify.htm"));
        assertFalse(handler.isJumpedVerifyUrl(
                "https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm"));
    }

    @Test
    public void isSliderVerifyPageShouldTreatJumpedPunishUrlAsVerify() {
        Browser browser = mock(Browser.class);
        when(browser.getCurrentUrl()).thenReturn(
                "https://pub.alimama.com/_____tmd_____/punish?x5secdata=token");

        assertTrue(new SliderVerifyHandler().isSliderVerifyPage(browser));
        // URL 命中后无需再读 body
        verify(browser, times(0)).executeScript(anyString());
    }
}
