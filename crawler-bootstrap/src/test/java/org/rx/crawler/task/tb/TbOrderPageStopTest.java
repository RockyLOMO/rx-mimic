package org.rx.crawler.task.tb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TbOrderPageStopTest {
    @Test
    public void firstPageIsKeptEvenWhenEmpty() {
        assertFalse(TbPromotionOrdersTask.repeatedOrderPage(1, 0));
        assertFalse(TbPromotionOrdersTask.repeatedOrderPage(1, 4));
    }

    @Test
    public void laterPageWithNoNewOrdersStops() {
        assertTrue(TbPromotionOrdersTask.repeatedOrderPage(2, 0));
        assertTrue(TbPromotionOrdersTask.repeatedOrderPage(28, 0));
        assertFalse(TbPromotionOrdersTask.repeatedOrderPage(3, 1));
    }
}
