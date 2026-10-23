package org.rx.crawler.task.tb;

import org.junit.jupiter.api.Test;
import org.rx.crawler.service.Browser;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TbUnionAnalysisPageTest {
    @Test void routesIdsLinksAndTokensButKeepsKeywordSearch() {
        assertTrue(TbUnionAnalysisPage.isProductInput("123456789"));
        assertTrue(TbUnionAnalysisPage.isProductInput("https://m.tb.cn/h.example"));
        assertTrue(TbUnionAnalysisPage.isProductInput("复制 ￥Abcd1234567￥ 打开淘宝"));
        assertTrue(TbUnionAnalysisPage.isProductInput("SomeTemporaryMaterial_123456"));
        assertFalse(TbUnionAnalysisPage.isProductInput("抽纸 三层"));
        assertFalse(TbUnionAnalysisPage.isProductInput("旗舰店猫粮"));
    }

    @Test void readsLabeledCurrentPriceAndKeepsVipInSeparateSeries() {
        var hit = TbUnionAnalysisPage.parse(data("价格\n88VIP价 ￥1,949 . 00\n优惠500元\n佣金￥46.78\n佣金率2 . 40%"));
        assertEquals("1949.00", hit.info().getPrice());
        assertEquals("unionAnalysis88VipPrice", hit.info().getPriceField());
        assertEquals("2.40%", hit.info().getCommissionRate());
        assertNull(hit.info().getProductId()); // A marketing ID is never claimed to be a numeric identity.
        hit = TbUnionAnalysisPage.parse(data("价格 到手价￥105.00 佣金￥1.58 佣金率1.50%"));
        assertEquals("105.00", hit.info().getPrice());
        assertEquals("unionAnalysisPrice", hit.info().getPriceField());
    }

    @Test void missingCurrentPriceDoesNotFallBackToCommissionOrHistoricalPrice() {
        var hit = TbUnionAnalysisPage.parse(data("佣金￥46.78 佣金率2.40% 历史最低价￥99.00"));
        assertNull(hit.info().getPrice());
        assertNull(TbUnionAnalysisPage.parse(Map.of("title", "title", "material", "")));
    }

    @Test void waitsForSubmissionAndUsesNativeActionsOnly() throws Exception {
        var browser = mock(Browser.class);
        when(browser.executeScript(anyString(), eq(TbUnionAnalysisPage.ANALYZE))).thenReturn(false, true);
        when(browser.executeScript(argThat(s -> s.contains("var root=document.getElementById"))))
                .thenReturn(data("价格 到手价￥105.00 佣金率1.50%"));
        var page = new TbUnionAnalysisPage();
        assertEquals("105.00", page.analyze(browser, "123456789", 10).info().getPrice());
        page.promote(browser);
        var order = inOrder(browser);
        order.verify(browser).waitElementLocated(TbUnionAnalysisPage.INPUT, 10);
        order.verify(browser).elementPress(TbUnionAnalysisPage.INPUT, "123456789", false);
        order.verify(browser).elementClick(TbUnionAnalysisPage.ANALYZE);
        order.verify(browser, times(2)).waitForEvents(500);
        verify(browser).elementClick(TbUnionAnalysisPage.PROMOTE);
    }

    private static Map<String, Object> data(String text) {
        return Map.of("title", "商品全名", "shop", "店铺", "link", "https://uland.taobao.com/item/edetail?id=TemporaryMaterial123",
                "material", "TemporaryMaterial123", "text", text);
    }
}
