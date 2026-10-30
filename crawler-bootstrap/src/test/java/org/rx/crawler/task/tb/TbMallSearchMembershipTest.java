package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TbMallSearchMembershipTest {
    @Test
    void confirmedLoginStopsBeforeInputOrSliderRetries() throws Exception {
        var task = new TbMallTask(new org.rx.crawler.config.AppConfig(), null, null, new ObjectMapper());
        var browser = org.mockito.Mockito.mock(org.rx.crawler.service.Browser.class);
        org.mockito.Mockito.when(browser.getCurrentUrl()).thenReturn("https://s.taobao.com/search?q=ddr3");
        org.mockito.Mockito.when(browser.executeScript(org.mockito.ArgumentMatchers.contains("querySelectorAll('iframe')"))).thenReturn(true);
        Class<?> debugClass = Class.forName(TbMallTask.class.getName()+"$DebugRecorder");
        var constructor = debugClass.getDeclaredConstructor(String.class,String.class,boolean.class,String.class,String.class);
        constructor.setAccessible(true);
        var debug = constructor.newInstance("login-test","common",false,"unused","unused");
        var method=TbMallTask.class.getDeclaredMethod("runSearchFlow",org.rx.crawler.service.Browser.class,
                TbSearchRequest.class,TbMallConfig.class,TbSearchResult.class,debugClass);
        method.setAccessible(true);
        var result=new TbSearchResult();
        method.invoke(task,browser,new TbSearchRequest(),new TbMallConfig(),result,debug);
        assertEquals(org.rx.crawler.task.common.CustomCrawlStatus.LOGIN_REQUIRED,result.getStatus());
        assertTrue(result.isLoginRequired());
        org.mockito.Mockito.verify(browser,org.mockito.Mockito.never()).pressKey(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(browser,org.mockito.Mockito.never()).nativeGet(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void loginIframeStopsSearchEvenWhenResultCardsRemainBehindDialog() throws Exception {
        var task = new TbMallTask(new org.rx.crawler.config.AppConfig(), null, null, new ObjectMapper());
        var browser = org.mockito.Mockito.mock(org.rx.crawler.service.Browser.class);
        org.mockito.Mockito.when(browser.getCurrentUrl()).thenReturn("https://s.taobao.com/search?q=ddr3");
        org.mockito.Mockito.when(browser.executeScript(org.mockito.ArgumentMatchers.contains("querySelectorAll('iframe')"))).thenReturn(true);
        var method = TbMallTask.class.getDeclaredMethod("isLoginRequiredPage", org.rx.crawler.service.Browser.class);
        method.setAccessible(true);
        assertEquals(true, method.invoke(task, browser));
    }

    @Test
    void sameSizedResponseCannotReplaceVisibleMembersOrTheirOrder() {
        var first = item("101", "first DOM title", "18.5");
        var last = item("102", "last DOM title", "7.02");
        var wrong = item("999", "unrelated recommendation", "99");
        var response = item("101", "response title", "999");
        response.setShopName("same-ID shop");
        var merged = TbMallTask.mergeSearchItems(List.of(wrong, response), List.of(first, last));
        assertEquals(List.of("101", "102"), merged.stream().map(TbSearchItem::getItemId).toList());
        assertEquals("first DOM title", merged.getFirst().getTitle());
        assertEquals("18.5", merged.getFirst().getPrice());
        assertEquals("same-ID shop", merged.getFirst().getShopName());
        assertEquals("101", merged.getFirst().getProductId());
        assertEquals("102", merged.getLast().getProductId());
    }

    @Test
    void responseAloneCannotPretendToBeCurrentSearchPage() {
        assertTrue(TbMallTask.mergeSearchItems(List.of(item("1", "recommendation", "1")), List.of()).isEmpty());
    }

    @Test
    void duplicateVisibleIdIsRemovedButDifferentProductsWithSameTitleRemain() {
        var first = item("1", "DDR3 memory", "17");
        var second = item("2", "DDR3 memory", "17");
        var merged = TbMallTask.mergeSearchItems(null, List.of(first, first, second));
        assertEquals(List.of("1", "2"), merged.stream().map(TbSearchItem::getItemId).toList());
    }

    @Test
    void sameIdResponseSupplementsOnlyBlankFields() {
        var visible = item("1", "", "");
        visible.setProductUrl("https://item.taobao.com/item.htm?id=1");
        var response = item("1", "DDR3 memory", "18");
        response.setImageUrl("https://img.alicdn.com/a.jpg");
        response.setShopName("memory shop");
        response.setSoldCount("10人付款");
        response.setLocation("上海");
        var merged = TbMallTask.mergeSearchItems(List.of(response), List.of(visible)).getFirst();
        assertEquals("DDR3 memory", merged.getTitle());
        assertEquals("18", merged.getPrice());
        assertEquals(response.getImageUrl(), merged.getImageUrl());
        assertEquals("memory shop", merged.getShopName());
        assertEquals("10人付款", merged.getSoldCount());
        assertEquals("上海", merged.getLocation());
    }

    @Test
    void passiveResponseAlsoExposesCommonProductIdentity() throws Exception {
        var data = new ObjectMapper().readTree("""
                {"data":{"itemsArray":[{"item_id":"12345","title":"DDR3","price":"18"}]}}
                """);
        var parsed = TbMallPageApi.parseSearchItems(data, 10);
        assertEquals("12345", parsed.getFirst().getProductId());
    }

    @Test
    void validSearchCardWithoutShopIsPreserved() {
        var card = item("12345", "DDR3 memory", "18");
        card.setProductUrl("https://item.taobao.com/item.htm?id=12345");
        card.setImageUrl("https://img.alicdn.com/a.jpg");
        assertTrue(TbResultChecks.mallItem(card, 0).isEmpty());
        assertNull(card.getShopName());
    }

    @Test
    void degradedDetailPageStopsImmediatelyAndRecordsRiskWithoutScrolling() throws Exception {
        var entryService = org.mockito.Mockito.mock(org.rx.crawler.task.common.CrawlEntryService.class);
        var task = new TbMallTask(new org.rx.crawler.config.AppConfig(), null, entryService, new ObjectMapper());
        var browser = org.mockito.Mockito.mock(org.rx.crawler.service.Browser.class);
        org.mockito.Mockito.when(browser.getCurrentUrl()).thenReturn("https://item.taobao.com/item.htm?id=1013262035693");
        org.mockito.Mockito.when(browser.executeScript(org.mockito.ArgumentMatchers.contains("__ICE_APP_CONTEXT__"))).thenReturn(false);
        org.mockito.Mockito.when(browser.executeScript(org.mockito.ArgumentMatchers.contains("innerText.indexOf(arguments[0])"),
                org.mockito.ArgumentMatchers.eq("系统检测到当前访问存在异常"))).thenReturn(true);
        org.mockito.Mockito.when(browser.executeScript(org.mockito.ArgumentMatchers.contains("text.indexOf(arguments[0])"),
                org.mockito.ArgumentMatchers.eq("系统检测到当前访问存在异常"))).thenReturn(true);

        Class<?> debugClass = Class.forName(TbMallTask.class.getName() + "$DebugRecorder");
        var constructor = debugClass.getDeclaredConstructor(String.class, String.class, boolean.class, String.class, String.class);
        constructor.setAccessible(true);
        var debug = constructor.newInstance("degraded-test", "common", false, "unused", "unused");
        var method = TbMallTask.class.getDeclaredMethod("runDetailFlow", org.rx.crawler.service.Browser.class,
                TbProductDetailRequest.class, TbMallConfig.class, TbProductDetailResult.class, debugClass);
        method.setAccessible(true);

        var request = new TbProductDetailRequest();
        request.setItemId("1013262035693");
        var result = new TbProductDetailResult();
        result.setItemId("1013262035693");

        method.invoke(task, browser, request, new TbMallConfig(), result, debug);
        assertEquals(org.rx.crawler.task.common.CustomCrawlStatus.PAGE_CHANGED, result.getStatus());
        assertEquals(Boolean.TRUE, result.getDiagnostics().get("riskLimited"));
        org.mockito.Mockito.verify(entryService).notifyRiskLimited(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(browser, org.mockito.Mockito.never())
                .scrollPage(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    private static TbSearchItem item(String id, String title, String price) {
        var item = new TbSearchItem();
        item.setItemId(id);
        item.setTitle(title);
        item.setPrice(price);
        return item;
    }
}
