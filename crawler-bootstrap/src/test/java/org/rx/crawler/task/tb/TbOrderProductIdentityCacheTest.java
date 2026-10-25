package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.crawler.task.order.OrderStore;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TbOrderProductIdentityCacheTest {
    @Test
    void storedNumericIdSkipsAllProductTabOperations() throws Exception {
        AppConfig app = new AppConfig();
        TbPromotionOrdersTask task = new TbPromotionOrdersTask(app, null, null, new ObjectMapper());
        OrderStore store = mock(OrderStore.class);
        Field field = TbPromotionOrdersTask.class.getDeclaredField("orderStore");
        field.setAccessible(true);
        field.set(task, store);
        PromotionOrderItem item = new PromotionOrderItem();
        item.setOrderNo("5127891589169007941");
        item.setProductLink("https://uland.taobao.com/item/edetail?id=encrypted");
        List<PromotionOrderItem> page = List.of(item);
        when(store.restoreTbProductIds("tb-main", page)).thenAnswer(invocation -> {
            item.setSkuId("786248509602");
            return 1;
        });
        Browser browser = mock(Browser.class);
        TbPromotionOrdersRequest request = new TbPromotionOrdersRequest();
        request.setSourceId("tb-main");
        TbPromotionOrdersResult result = new TbPromotionOrdersResult();
        task.resolveProductIdentities(browser, request, app.getCustom().getTbPromotion(), result, page);
        assertEquals("786248509602", item.getSkuId());
        assertEquals(1, result.getDiagnostics().get("productIdsReused"));
        verify(store).restoreTbProductIds("tb-main", page);
        verifyNoInteractions(browser);
    }
}
