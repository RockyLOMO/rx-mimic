package org.rx.crawler.task.price;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 价格文本、比较序列和降价阈值。不把券额再减一次。 */
public final class PriceRules {
    private static final BigDecimal LOW = new BigDecimal("0.50");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private PriceRules() {
    }

    public static int kind(String field) {
        if (field == null) {
            return PriceCodes.KIND_UNKNOWN;
        }
        switch (field) {
            case "finalPrice":
            case "purchasePrice":
            case "unionPromotionPrice":
            case "finalPromotionPrice":
            case "zkFinalPrice":
            case "promotionPrice":
                return PriceCodes.KIND_FINAL;
            case "wlPrice":
            case "price":
                return PriceCodes.KIND_LIST;
            default:
                return PriceCodes.KIND_UNKNOWN;
        }
    }

    /** 问号、区间、空值和无法说明的文本返回 null。0 与异常低价由 {@link #comparable} 排除。 */
    public static BigDecimal amount(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty() || text.contains("?") || text.contains("？") || text.contains("~")
                || text.contains("～") || text.contains("登录") || text.contains("待")) {
            return null;
        }
        if (text.indexOf('-') > 0) {
            return null;
        }
        text = text.replace("¥", "").replace("￥", "").replace(",", "").replace("元", "").trim();
        if (!text.matches("\\d+(\\.\\d+)?")) {
            return null;
        }
        return new BigDecimal(text).setScale(2, RoundingMode.HALF_UP);
    }

    public static boolean comparable(int kind, BigDecimal amount) {
        return kind != PriceCodes.KIND_UNKNOWN && amount != null && amount.compareTo(LOW) >= 0;
    }

    public static String comparableKey(int platform, int source, int kind, String field,
            String platformProductId, String sku) {
        return platform + "|src=" + source + "|kind=" + kind + "|field=" + empty(field)
                + "|id=" + empty(platformProductId) + "|sku=" + empty(sku);
    }

    /**
     * 首次相对当前序列基线；再次提醒相对上次已提醒价。两者都要达到降额和降幅。
     * 当前价第一次跨过目标价时也可以提醒。
     */
    public static boolean alert(BigDecimal baseline, BigDecimal lastNotified, BigDecimal current,
            BigDecimal target, BigDecimal minAmount, BigDecimal minPercent) {
        if (baseline == null || current == null || minAmount == null || minPercent == null) {
            return false;
        }
        if (current.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (target != null && current.compareTo(target) <= 0
                && (lastNotified == null || lastNotified.compareTo(target) > 0)) {
            return true;
        }
        BigDecimal reference = lastNotified == null ? baseline : lastNotified;
        if (reference.compareTo(BigDecimal.ZERO) <= 0 || current.compareTo(reference) >= 0) {
            return false;
        }
        if (current.compareTo(baseline) > 0) {
            return false;
        }
        BigDecimal drop = reference.subtract(current);
        if (drop.compareTo(minAmount) < 0) {
            return false;
        }
        BigDecimal percent = drop.multiply(HUNDRED).divide(reference, 4, RoundingMode.HALF_UP);
        return percent.compareTo(minPercent) >= 0;
    }

    public static String money(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
