package org.rx.crawler.task.jd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.*;
import org.rx.crawler.task.price.PriceCodes;
import org.rx.crawler.task.price.PriceRules;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JdPagePriceTest {
    @Test void labelledFinalPriceCannotBeConfusedWithCommissionOrOldListPrice() {
        var price=JdPagePrice.read("佣金 ￥9.99 京东价 ￥199 到手价 ￥48.84 我要推广","9.99");
        assertEquals("48.84",price.amount());assertEquals("finalPrice",price.field());
        assertEquals(PriceCodes.KIND_FINAL,PriceRules.kind(price.field()));
        assertEquals("wlPrice",JdPagePrice.read("售价 ￥129",null).field());
    }
    @Test void rangeAndUnknownCurrencyCannotBecomeComparableFinalPrices() {
        assertNull(PriceRules.amount(JdPagePrice.read("到手价 ￥99 - ￥199",null).amount()));
        var unknown=JdPagePrice.read("佣金 ￥5","5");
        assertEquals(PriceCodes.KIND_UNKNOWN,PriceRules.kind(unknown.field()));
        assertEquals("jdUnionDisplayedPrice",unknown.field());
    }
    @Test void promotionAndSearchPageFallbackBothCarryTheDetectedSource() {
        var task=new JdUnionPromotionTask(new AppConfig(),mock(BrowserProfileManager.class),mock(CrawlEntryService.class),new ObjectMapper());
        var browser=mock(Browser.class);
        var payload=Map.of("skuId","123456789","productName","sample","productLink","https://item.jd.com/123456789.html",
                "price","5","priceText","佣金 ￥5 到手价 ￥48.84","commissionRate","5%");
        when(browser.executeScript(anyString(),anyInt())).thenReturn(List.of(payload));
        List<JdUnionProductItem> items=ReflectionTestUtils.invokeMethod(task,"readPromotionCards",browser,2);
        assertEquals("48.84",items.getFirst().getPrice());assertEquals("finalPrice",items.getFirst().getPriceField());
        var promotion=new java.util.HashMap<String,Object>(payload);promotion.remove("skuId");
        when(browser.executeScript(anyString())).thenReturn(promotion);
        ProductInfoDto info=ReflectionTestUtils.invokeMethod(task,"readProductInfo",browser);
        assertEquals("48.84",info.getPrice());assertEquals("finalPrice",info.getPriceField());
    }
}
