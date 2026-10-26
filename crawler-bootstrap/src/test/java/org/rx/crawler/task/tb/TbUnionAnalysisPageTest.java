package org.rx.crawler.task.tb;

import org.junit.jupiter.api.Test;
import org.rx.crawler.service.Browser;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TbUnionAnalysisPageTest {
    @Test void readyFormWithoutHintSubmitsOnceAndWaitsForMaterial() throws Exception {
        var browser = mock(Browser.class);
        String base = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
        String material = "ReadyMaterialAlpha_123456789";
        when(browser.getCurrentUrl()).thenReturn(base + "&itemId=123456789");
        when(browser.executeScript(anyString(), eq(TbUnionAnalysisPage.INPUT), eq(TbUnionAnalysisPage.ANALYZE), eq("123456789")))
                .thenReturn("READY");
        doAnswer(call -> { when(browser.getCurrentUrl()).thenReturn(base + "&itemId=" + material); return null; })
                .when(browser).elementClick(TbUnionAnalysisPage.ANALYZE);
        assertEquals(material, new TbUnionAnalysisPage().resolveMaterial(browser, base, "123456789", 4));
        verify(browser, times(1)).elementClick(TbUnionAnalysisPage.ANALYZE);
    }

    @Test void persistentReadyWithoutExplicitEmptyIsTimeoutAndNeverNonRebate() {
        var browser = mock(Browser.class);
        String base = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
        when(browser.getCurrentUrl()).thenReturn(base + "&itemId=123456789");
        when(browser.executeScript(anyString(), eq(TbUnionAnalysisPage.INPUT), eq(TbUnionAnalysisPage.ANALYZE), eq("123456789")))
                .thenReturn("READY");
        var now = new java.util.concurrent.atomic.AtomicLong();
        doAnswer(call -> { now.addAndGet(500_000_000L); return null; }).when(browser).waitForEvents(500);
        assertThrows(java.util.concurrent.TimeoutException.class,
                () -> new TbUnionAnalysisPage(now::get).resolveMaterial(browser, base, "123456789", 4));
        verify(browser, times(1)).elementClick(TbUnionAnalysisPage.ANALYZE);
    }

    @Test void busyPageBreaksReadinessStreakBeforeClick() {
        var browser = mock(Browser.class);
        String base = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
        when(browser.getCurrentUrl()).thenReturn(base + "&itemId=123456789");
        when(browser.executeScript(anyString(), eq(TbUnionAnalysisPage.INPUT), eq(TbUnionAnalysisPage.ANALYZE), eq("123456789")))
                .thenReturn("READY", "BUSY", "READY", "BUSY");
        var now = new java.util.concurrent.atomic.AtomicLong();
        doAnswer(call -> { now.addAndGet(500_000_000L); return null; }).when(browser).waitForEvents(500);
        assertThrows(java.util.concurrent.TimeoutException.class,
                () -> new TbUnionAnalysisPage(now::get).resolveMaterial(browser, base, "123456789", 2));
        verify(browser, never()).elementClick(anyString());
    }
    @Test void materialPollingInvokesSharedVerificationBeforeReadingTheChangedUrl() throws Exception {
        var browser = mock(Browser.class);
        String base = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
        String material = "DynamicMaterialAlpha_123456789";
        var gate = mock(TbUnionAnalysisPage.VerificationGate.class);
        when(gate.clear()).thenAnswer(call -> {
            when(browser.getCurrentUrl()).thenReturn(base + "&itemId=" + material);
            return true;
        });
        assertEquals(material, new TbUnionAnalysisPage().resolveMaterial(browser, base, "776371444627", 10, gate));
        verify(gate).clear();
        when(gate.clear()).thenReturn(false);
        assertEquals("TB_SLIDER_VERIFICATION_REQUIRED", assertThrows(org.rx.crawler.task.common.TaskApiException.class,
                () -> new TbUnionAnalysisPage().resolveMaterial(browser, base, "776371444627", 10, gate)).getCode());
    }
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
        assertTrue(TbUnionAnalysisPage.isAnalysisUrl("http://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic&itemId=123456789"));
        assertFalse(TbUnionAnalysisPage.isAnalysisUrl("https://user@pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic"));
        assertFalse(TbUnionAnalysisPage.isAnalysisUrl("https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic-fake&itemId=123456789"));
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

    @Test void settledEmptyAnalysisIsSubmittedOnceThenReturnsBusinessFailure() {
        var browser = mock(Browser.class);
        String base = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
        when(browser.getCurrentUrl()).thenReturn(base + "&itemId=123456789");
        when(browser.executeScript(anyString(), eq(TbUnionAnalysisPage.INPUT), eq(TbUnionAnalysisPage.ANALYZE), eq("123456789")))
                .thenReturn("EMPTY");
        var now = new java.util.concurrent.atomic.AtomicLong();
        doAnswer(call -> { now.addAndGet(500_000_000L); return null; }).when(browser).waitForEvents(500);
        var page = new TbUnionAnalysisPage(now::get);
        var error = assertThrows(org.rx.crawler.task.common.TaskApiException.class,
                () -> page.resolveMaterial(browser, base, "123456789", 4));
        assertEquals("NO_REBATE_PRODUCT", error.getCode());
        assertEquals("非返利商品，暂不支持转链", error.getMessage());
        verify(browser, times(1)).elementClick(TbUnionAnalysisPage.ANALYZE);
    }

    @Test void initialOrLoadingOrUnknownPageNeverBecomesNonRebateFailure() {
        for (String state : new String[]{"EMPTY", "BUSY", "UNKNOWN"}) {
            var browser = mock(Browser.class);
            String base = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
            when(browser.getCurrentUrl()).thenReturn(base + "&itemId=123456789");
            when(browser.executeScript(anyString(), eq(TbUnionAnalysisPage.INPUT), eq(TbUnionAnalysisPage.ANALYZE), eq("123456789")))
                    .thenReturn(state);
            var now = new java.util.concurrent.atomic.AtomicLong();
            doAnswer(call -> { now.addAndGet(500_000_000L); return null; }).when(browser).waitForEvents(500);
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> new TbUnionAnalysisPage(now::get).resolveMaterial(browser, base, "123456789", 1));
        }
    }

    @Test void loadingAfterSubmissionDoesNotBecomeNonRebateFailure() {
        var browser = mock(Browser.class);
        String base = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
        when(browser.getCurrentUrl()).thenReturn(base + "&itemId=123456789");
        when(browser.executeScript(anyString(), eq(TbUnionAnalysisPage.INPUT), eq(TbUnionAnalysisPage.ANALYZE), eq("123456789")))
                .thenReturn("EMPTY", "EMPTY", "BUSY");
        var now = new java.util.concurrent.atomic.AtomicLong();
        doAnswer(call -> { now.addAndGet(500_000_000L); return null; }).when(browser).waitForEvents(500);
        assertThrows(java.util.concurrent.TimeoutException.class,
                () -> new TbUnionAnalysisPage(now::get).resolveMaterial(browser, base, "123456789", 4));
        verify(browser, times(1)).elementClick(TbUnionAnalysisPage.ANALYZE);
    }

    private static Map<String, Object> data(String text) {
        return Map.of("title", "商品全名", "shop", "店铺", "link", "https://uland.taobao.com/item/edetail?id=TemporaryMaterial123",
                "material", "TemporaryMaterial123", "text", text);
    }
}
