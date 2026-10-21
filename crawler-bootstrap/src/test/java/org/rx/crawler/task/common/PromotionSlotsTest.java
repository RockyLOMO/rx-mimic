package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.rx.crawler.task.jd.JdUnionConfig;
import org.rx.crawler.task.tb.TbPromotionConfig;
import org.rx.exception.InvalidException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PromotionSlotsTest {
    @Test
    void taobaoDefaultsUseRockyLomoSlots() {
        TbPromotionConfig config = new TbPromotionConfig();
        assertEquals("RockyLOMO", config.getDefaultMediaName());
        assertEquals("0", config.getDefaultAdSiteName());
        assertEquals("mm_10299978403_3442650231_116337200060", PromotionSlots.idOf(config.getAdSites(), "0"));
        assertEquals("mm_10299978403_3442650231_116333200319", PromotionSlots.idOf(config.getAdSites(), "1"));
        assertEquals("mm_10299978403_3442650231_116335500196", PromotionSlots.idOf(config.getAdSites(), "2"));
        assertEquals("mm_10299978403_3442650231_116332950344", PromotionSlots.idOf(config.getAdSites(), "3"));
    }

    @Test
    void jdDefaultsUseWxSlots() {
        JdUnionConfig config = new JdUnionConfig();
        assertEquals("wx", config.getDefaultMediaName());
        assertEquals("4101055439", config.getDefaultMediaId());
        assertEquals("0", config.getDefaultAdSiteName());
        assertEquals("3100287104", PromotionSlots.idOf(config.getAdSites(), "0"));
        assertEquals("3100287105", PromotionSlots.idOf(config.getAdSites(), "1"));
        assertEquals("3100287106", PromotionSlots.idOf(config.getAdSites(), "2"));
        assertEquals("3100287107", PromotionSlots.idOf(config.getAdSites(), "3"));
    }

    @Test
    void requireRejectsSlotsOutsideZeroToThree() {
        assertThrows(InvalidException.class, () -> PromotionSlots.require(PromotionSlots.taobaoDefaults(), "5"));
        assertThrows(InvalidException.class, () -> PromotionSlots.require(PromotionSlots.jdDefaults(), "4"));
        assertThrows(InvalidException.class, () -> PromotionSlots.require(PromotionSlots.jdDefaults(), "03"));
        PromotionSlots.require(PromotionSlots.taobaoDefaults(), "2");
    }
}
