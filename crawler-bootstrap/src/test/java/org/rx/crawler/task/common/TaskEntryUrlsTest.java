package org.rx.crawler.task.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TaskEntryUrlsTest {
    private final AppConfig config = new AppConfig();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void mallSearchStartsAtHomeAndDetailUsesSku() {
        assertEquals("https://www.jd.com",
                TaskEntryUrls.resolve("jdSearchProduct", "{\"keyword\":\"抽纸\"}", config, mapper));
        assertEquals("https://item.jd.com/8622236.html",
                TaskEntryUrls.resolve("jdProductDetail", "{\"skuId\":\"8622236\"}", config, mapper));
        assertEquals("https://www.taobao.com",
                TaskEntryUrls.resolve("tbSearchProduct", "{\"keyword\":\"抽纸\"}", config, mapper));
        assertEquals("https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm",
                TaskEntryUrls.resolve("getTbPromotionOrders", "{}", config, mapper));
        assertEquals("https://union.jd.com/entire",
                TaskEntryUrls.resolve("getPromotionOrders", "{}", config, mapper));
    }

    @Test
    public void unknownActionOrBrokenJsonDoesNotThrow() {
        assertNull(TaskEntryUrls.resolve("not-a-task", "{}", config, mapper));
        assertEquals("https://union.jd.com/entire",
                TaskEntryUrls.resolve("jdUnionSearchProducts", "[", config, mapper));
        assertNull(TaskEntryUrls.resolve("jdProductDetail", "{}", config, mapper));
    }
}
