package org.rx.crawler.task.tb;

import org.junit.jupiter.api.Test;
import org.rx.crawler.service.Browser;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TbUnionAnalysisPageTest {
    @Test void numericInputWaitsForAddressBarMaterialThenDoesNotOpenAnyDetailTab() throws Exception {
        var browser = mock(Browser.class);
        String base = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
        String alias = "DynamicMaterialAlpha_123456789";
        when(browser.getCurrentUrl()).thenReturn(base + "&itemId=776371444627", base + "&itemId=" + alias);
        assertEquals(alias, new TbUnionAnalysisPage().resolveMaterial(browser, base, "776371444627", 10));
        verify(browser).navigateUrl(base + "&itemId=776371444627", Browser.BODY_SELECTOR, 10);
        verify(browser, times(2)).waitForEvents(500);
        verify(browser, never()).readLinkDestination(anyString(), any(), anyInt());
        verify(browser, never()).elementClick(anyString());
    }

    @Test void materialCannotBeReadFromAnUntrustedOrUnchangedNumericAddress() {
        assertEquals("", TbUnionAnalysisPage.materialFromUrl("https://example.com/?pageKey=analysic&itemId=DynamicMaterialAlpha_123456789"));
        assertEquals("776371444627", TbUnionAnalysisPage.materialFromUrl("https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic&itemId=776371444627"));
        assertFalse(TbUnionPageApi.isMktId("776371444627"));
    }
    @Test void detailIdentityComesOnlyFromTheTitlePopupFinalProductUrl() {
        var browser = mock(Browser.class);
        var page = new TbUnionAnalysisPage();
        when(browser.readLinkDestination(eq(TbUnionAnalysisPage.PRODUCT_TITLE), any(), eq(30)))
                .thenReturn("https://detail.tmall.com/item.htm?id=123456789");
        assertEquals("123456789", page.readProductId(browser, 60));
        when(browser.readLinkDestination(eq(TbUnionAnalysisPage.PRODUCT_TITLE), any(), eq(30)))
                .thenReturn("https://uland.taobao.com/item/edetail?id=123ABCtemporary");
        assertEquals("IDENTITY_UNRESOLVED", assertThrows(org.rx.crawler.task.common.TaskApiException.class,
                () -> page.readProductId(browser, 60)).getCode());
    }

    @Test void queryEntryEncodesTheWholeInputWithoutChangingThePageKey() {
        String base = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
        assertEquals(base + "&itemId=123456789", TbUnionAnalysisPage.entryUrl(base, "123456789"));
        String input = "复制 ￥Abcd1234567￥ https://m.tb.cn/h.example?a=1&b=2";
        String url = TbUnionAnalysisPage.entryUrl(base, input);
        assertEquals(input, java.net.URLDecoder.decode(url.substring(url.indexOf("&itemId=") + 8),
                java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(url.contains("&b=2"));
    }
    @Test void marketingIdWithNumericPrefixIsNeverTreatedAsAProductId() {
        var info = new org.rx.crawler.task.common.ProductInfoDto();
        info.setProductLink("https://uland.taobao.com/item/edetail?id=123ABCtemporaryMaterial");
        assertEquals("", TbPromotionUrlTask.numericItemId(info));
        info.setProductLink("https://item.taobao.com/item.htm?id=123456789");
        assertEquals("123456789", TbPromotionUrlTask.numericItemId(info));
    }
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

    @Test void waitsWhenControlIsEnabledBeforeResultDomHasFinishedRendering() throws Exception {
        var browser = mock(Browser.class);
        when(browser.executeScript(anyString(), eq(TbUnionAnalysisPage.ANALYZE))).thenReturn(true);
        when(browser.executeScript(argThat(s -> s.contains("var root=document.getElementById"))))
                .thenReturn(null, null, data("价格 到手价￥105.00 佣金率1.50%"));
        assertEquals("105.00", new TbUnionAnalysisPage().analyze(browser, "123456789", 10).info().getPrice());
        verify(browser, times(3)).waitForEvents(500);
    }

    private static Map<String, Object> data(String text) {
        return Map.of("title", "商品全名", "shop", "店铺", "link", "https://uland.taobao.com/item/edetail?id=TemporaryMaterial123",
                "material", "TemporaryMaterial123", "text", text);
    }
}
