package org.rx.crawler.task.tb;

import org.rx.core.Strings;
import org.rx.crawler.task.common.PromotionOrderItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 接口和页面兜底共用的结果校验。成功状态只在必填字段都有值时成立。
 * 淘宝风控降级时价格会被打码成 "2?"，所以价格要求是完整数字。
 */
final class TbResultChecks {
    private static final String PRICE_PATTERN = "[￥¥]?\\d+(\\.\\d+)?";

    private TbResultChecks() {
    }

    static List<TbUnionProductItem> completeUnionItems(List<TbUnionProductItem> items, List<String> errors) {
        List<TbUnionProductItem> kept = new ArrayList<TbUnionProductItem>();
        if (items == null) {
            errors.add("items is null");
            return kept;
        }
        for (int i = 0; i < items.size(); i++) {
            List<String> rowErrors = unionItem(items.get(i), i);
            if (rowErrors.isEmpty()) {
                kept.add(items.get(i));
            } else {
                errors.addAll(rowErrors);
            }
        }
        return kept;
    }

    static List<String> unionItem(TbUnionProductItem item, int index) {
        String prefix = "items[" + index + "]";
        List<String> errors = new ArrayList<String>();
        if (item == null) {
            errors.add(prefix + " is null");
            return errors;
        }
        requireText(errors, prefix + ".itemId", item.getItemId());
        requireText(errors, prefix + ".productName", item.getProductName());
        requireHttp(errors, prefix + ".productLink", item.getProductLink());
        requirePrice(errors, prefix + ".price", item.getPrice());
        requireNumber(errors, prefix + ".commissionRate", item.getCommissionRate());
        requireText(errors, prefix + ".storeName", item.getStoreName());
        requireHttp(errors, prefix + ".imageUrl", item.getImageUrl());
        return errors;
    }

    static List<String> promotion(String promotionUrl, String itemId, String mktItemId) {
        List<String> errors = new ArrayList<String>();
        requireMatch(errors, "itemId", itemId, "\\d+");
        if (!TbUnionPageApi.isMktId(mktItemId)) {
            errors.add("mktItemId must be an alliance id");
        }
        requireHttp(errors, "promotionUrl", promotionUrl);
        return errors;
    }

    static List<String> orders(List<PromotionOrderItem> orders) {
        List<String> errors = new ArrayList<String>();
        if (orders == null) {
            errors.add("orders is null");
            return errors;
        }
        for (int i = 0; i < orders.size(); i++) {
            PromotionOrderItem item = orders.get(i);
            String prefix = "orders[" + i + "]";
            if (item == null) {
                errors.add(prefix + " is null");
                continue;
            }
            requireText(errors, prefix + ".productName", item.getProductName());
            requireText(errors, prefix + ".storeName", item.getStoreName());
            requireMatch(errors, prefix + ".orderNo", item.getOrderNo(), "\\d{8,}");
            requireText(errors, prefix + ".orderStatus", item.getOrderStatus());
            requireText(errors, prefix + ".orderTime", item.getOrderTime());
        }
        return errors;
    }

    static List<TbSearchItem> completeMallItems(List<TbSearchItem> items, List<String> errors) {
        List<TbSearchItem> kept = new ArrayList<TbSearchItem>();
        if (items == null) {
            errors.add("items is null");
            return kept;
        }
        for (int i = 0; i < items.size(); i++) {
            List<String> rowErrors = mallItem(items.get(i), i);
            if (rowErrors.isEmpty()) {
                kept.add(items.get(i));
            } else {
                errors.addAll(rowErrors);
            }
        }
        return kept;
    }

    static List<String> mallItem(TbSearchItem item, int index) {
        String prefix = "items[" + index + "]";
        List<String> errors = new ArrayList<String>();
        if (item == null) {
            errors.add(prefix + " is null");
            return errors;
        }
        requireMatch(errors, prefix + ".itemId", item.getItemId(), "\\d+");
        requireText(errors, prefix + ".title", item.getTitle());
        requirePrice(errors, prefix + ".price", item.getPrice());
        requireContains(errors, prefix + ".productUrl", item.getProductUrl(), item.getItemId());
        requireText(errors, prefix + ".shopName", item.getShopName());
        requireHttp(errors, prefix + ".imageUrl", item.getImageUrl());
        return errors;
    }

    static List<String> detail(TbProductDetailResult result) {
        List<String> errors = new ArrayList<String>();
        if (result == null) {
            errors.add("detail is null");
            return errors;
        }
        requireMatch(errors, "itemId", result.getItemId(), "\\d+");
        requireText(errors, "title", result.getTitle());
        requirePrice(errors, "price", result.getPrice());
        requireText(errors, "shopName", result.getShopName());
        return errors;
    }

    static boolean isCleanPrice(String value) {
        return value != null && value.trim().matches(PRICE_PATTERN);
    }

    private static void requireText(List<String> errors, String name, String value) {
        if (Strings.isEmpty(value) || Strings.isEmpty(value.trim())) {
            errors.add(name + " is blank");
        }
    }

    private static void requireMatch(List<String> errors, String name, String value, String pattern) {
        if (value == null || !value.trim().matches(pattern)) {
            errors.add(name + " must match " + pattern);
        }
    }

    private static void requirePrice(List<String> errors, String name, String value) {
        if (!isCleanPrice(value)) {
            errors.add(name + " must be a plain number");
        }
    }

    private static void requireNumber(List<String> errors, String name, String value) {
        if (value == null || !value.matches(".*\\d.*")) {
            errors.add(name + " must contain a number");
        }
    }

    private static void requireHttp(List<String> errors, String name, String value) {
        if (!isHttp(value)) {
            errors.add(name + " must be an http url");
        }
    }

    private static void requireContains(List<String> errors, String name, String value, String token) {
        requireHttp(errors, name, value);
        if (!Strings.isEmpty(token) && (value == null || !value.contains(token))) {
            errors.add(name + " must contain " + token);
        }
    }

    private static boolean isHttp(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("https://") || trimmed.startsWith("http://");
    }
}
