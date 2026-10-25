package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.price.PriceCodes;

import java.util.HashMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderProductLinkReaderTest {
    private PromotionOrderItem order() {
        var item = new PromotionOrderItem();
        item.setOrderNo("5127891589169007941");
        item.setProductName("店铺专用订单打印纸");
        item.setProductLink("https://uland.taobao.com/item/edetail?id=opaque-mkt-id");
        return item;
    }

    @Test void popupAcceptsOnlyTrustedProductOrLoginAndCachesSuccessfulRedirect() {
        var browser = mock(Browser.class);
        var item = order();
        when(browser.<Boolean>executeScript(anyString(), eq(item.getOrderNo()), eq(item.getProductName()), eq(item.getProductLink())))
                .thenReturn(true);
        String destination = "https://detail.tmall.com/item.htm?id=786248509602";
        when(browser.readLinkDestination(anyString(), any(), eq(8))).thenAnswer(call -> {
            Predicate<String> accepted = call.getArgument(1);
            assertFalse(accepted.test("https://uland.taobao.com/item/edetail?id=123456789"));
            assertFalse(accepted.test("https://attacker.test/item.htm?id=123456789"));
            assertTrue(accepted.test(destination));
            assertTrue(accepted.test("https://login.taobao.com/member/login.jhtml"));
            return destination;
        });
        var cache = new HashMap<String, String>();
        OrderProductLinkReader.resolve(browser, item, PriceCodes.TB, 8, cache);
        assertEquals("786248509602", item.getSkuId());
        assertEquals(destination, item.getProductLink());
        var repeated = order();
        repeated.getExtensions().put("productIdentityError", "OLD_ERROR");
        OrderProductLinkReader.resolve(browser, repeated, PriceCodes.TB, 8, cache);
        assertEquals("786248509602", repeated.getSkuId());
        assertFalse(repeated.getExtensions().containsKey("productIdentityError"));
        verify(browser, times(1)).readLinkDestination(anyString(), any(), anyInt());
    }

    @Test void invisibleRowDoesNotClickOrInventProductIdentity() {
        var browser = mock(Browser.class);
        var item = order();
        OrderProductLinkReader.resolve(browser, item, PriceCodes.TB, 8);
        assertTrue(item.getSkuId() == null || item.getSkuId().isBlank());
        assertEquals("PRODUCT_LINK_NOT_VISIBLE", item.getExtensions().get("productIdentityError"));
        verify(browser, never()).readLinkDestination(anyString(), any(), anyInt());
    }

    @Test void loginRedirectRemainsUnknownAndIsNotCached() {
        var browser = mock(Browser.class);
        var item = order();
        when(browser.<Boolean>executeScript(anyString(), eq(item.getOrderNo()), eq(item.getProductName()), eq(item.getProductLink())))
                .thenReturn(true);
        when(browser.readLinkDestination(anyString(), any(), anyInt()))
                .thenReturn("https://login.taobao.com/member/login.jhtml");
        var cache = new HashMap<String, String>();
        OrderProductLinkReader.resolve(browser, item, PriceCodes.TB, 8, cache);
        assertTrue(item.getSkuId() == null || item.getSkuId().isBlank());
        assertEquals("PRODUCT_LOGIN_REQUIRED", item.getExtensions().get("productIdentityError"));
        assertTrue(cache.isEmpty());
    }
}
