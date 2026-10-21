package org.rx.crawler.task.common;

import org.rx.core.Strings;
import org.rx.exception.InvalidException;

import java.util.ArrayList;
import java.util.List;

/**
 * 京东和淘宝都只允许推广位 0、1、2、3。名称必须在配置列表里，不能临时新建。
 */
public final class PromotionSlots {
    public static final String DEFAULT_NAME = "0";

    private PromotionSlots() {
    }

    public static List<PromotionSlot> jdDefaults() {
        List<PromotionSlot> slots = new ArrayList<PromotionSlot>();
        slots.add(new PromotionSlot("0", "3100287104"));
        slots.add(new PromotionSlot("1", "3100287105"));
        slots.add(new PromotionSlot("2", "3100287106"));
        slots.add(new PromotionSlot("3", "3100287107"));
        return slots;
    }

    public static List<PromotionSlot> taobaoDefaults() {
        List<PromotionSlot> slots = new ArrayList<PromotionSlot>();
        slots.add(new PromotionSlot("0", "mm_10299978403_3442650231_116337200060"));
        slots.add(new PromotionSlot("1", "mm_10299978403_3442650231_116333200319"));
        slots.add(new PromotionSlot("2", "mm_10299978403_3442650231_116335500196"));
        slots.add(new PromotionSlot("3", "mm_10299978403_3442650231_116332950344"));
        return slots;
    }

    public static void require(List<PromotionSlot> slots, String name) {
        if (name == null || !name.matches("[0-3]")) {
            throw new InvalidException("adSiteName must be 0, 1, 2, or 3");
        }
        if (find(slots, name) == null) {
            throw new InvalidException("adSiteName " + name + " is not configured");
        }
    }

    public static PromotionSlot find(List<PromotionSlot> slots, String name) {
        if (slots == null || Strings.isEmpty(name)) {
            return null;
        }
        for (PromotionSlot slot : slots) {
            if (slot != null && name.equals(slot.getName())) {
                return slot;
            }
        }
        return null;
    }

    public static String idOf(List<PromotionSlot> slots, String name) {
        PromotionSlot slot = find(slots, name);
        if (slot == null || Strings.isEmpty(slot.getId())) {
            return "";
        }
        return slot.getId();
    }
}
