package org.rx.crawler.task.order;

import org.rx.crawler.task.common.PromotionOrderItem;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared order semantics. Platform raw fields remain available in extensions. */
public final class OrderFields {
    private OrderFields() { }

    public static Map<String, Object> extensions(PromotionOrderItem item, String platform) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (item.getExtensions() != null) values.putAll(item.getExtensions());
        putMissing(values, "rawOrderStatus", item.getOrderStatus());
        putMissing(values, "parentOrderNo", item.getMainOrderNo());
        if ("jd".equalsIgnoreCase(platform)) {
            putMissing(values, "afterSaleQuantity", strictQuantity(item.getAfterSaleQuantity()));
            putMissing(values, "returnQuantity", strictQuantity(item.getReturnQuantity()));
            putMissing(values, "shareRate", item.getShareRate());
        }
        return values;
    }

    private static void putMissing(Map<String, Object> values, String key, Object value) {
        if (value != null && (!(value instanceof String s) || !s.isBlank())) values.putIfAbsent(key, value);
    }

    /** Do not extract counts from arbitrary display text or monetary values. */
    public static Integer strictQuantity(Object value) {
        if (value == null) return null;
        try {
            BigDecimal number = new BigDecimal(value.toString().trim());
            int result = number.intValueExact();
            return result >= 0 ? result : null;
        } catch (RuntimeException ignored) { return null; }
    }

    public static String normalizedStatus(String platform, String rawStatus, Map<String, Object> extensions) {
        if ("jd".equalsIgnoreCase(platform) && (positive(extensions.get("afterSaleQuantity"))
                || positive(extensions.get("returnQuantity")))) return "INVALID";
        if (rawStatus == null || rawStatus.isBlank()) return "UNKNOWN";
        String value = rawStatus.trim();
        if ("tb".equalsIgnoreCase(platform) && "已结算".equals(value)) return "COMPLETED";
        return switch (value) {
            case "已付款", "已支付", "PAID" -> "PAID";
            case "已完成", "已签收", "确认收货", "COMPLETED" -> "COMPLETED";
            case "已结算", "佣金已结算", "SETTLED" -> "SETTLED";
            case "已失效", "无效", "已取消", "已退款", "INVALID" -> "INVALID";
            case "待付款", "未付款", "已下单", "PENDING" -> "PENDING";
            default -> "UNKNOWN";
        };
    }

    private static boolean positive(Object value) {
        Integer count = strictQuantity(value);
        return count != null && count > 0;
    }

    public static String settlementDate(String raw) {
        String value = org.rx.crawler.task.common.OrderTimeFields.settlementDate(raw);
        return value.isBlank() ? null : value;
    }
}
