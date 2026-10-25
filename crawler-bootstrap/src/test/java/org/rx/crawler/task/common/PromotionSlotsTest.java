package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.rx.crawler.task.jd.JdUnionConfig;
import org.rx.crawler.task.tb.TbPromotionConfig;
import org.rx.exception.InvalidException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PromotionSlotsTest {
    @Test
    void resolveExactIdsWithoutConfusingMediaAndAdzone() {
        var tb = PromotionSlots.taobaoDefaults();
        assertEquals("0", PromotionSlots.nameById(tb, "116337200060"));
        assertEquals("1", PromotionSlots.nameById(tb, "mm_10299978403_3442650231_116333200319"));
        assertEquals("", PromotionSlots.nameById(tb, "3442650231"));
        assertEquals("", PromotionSlots.nameById(tb, "unknown"));
        assertEquals("3", PromotionSlots.nameById(PromotionSlots.jdDefaults(), "3100287107"));
        assertEquals("", PromotionSlots.nameById(PromotionSlots.jdDefaults(), "3100287108"));
        var explicit = new PromotionSlot("2", "full-pid");
        explicit.setAdzoneId("custom-zone");
        assertEquals("2", PromotionSlots.nameById(java.util.List.of(explicit), "custom-zone"));
        assertEquals("", PromotionSlots.nameById(java.util.List.of(
                new PromotionSlot("0", "duplicate"), new PromotionSlot("1", "duplicate")), "duplicate"));
    }

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
