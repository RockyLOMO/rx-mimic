package org.rx.crawler.task.jd;

import org.rx.core.Strings;
import org.rx.crawler.task.common.ProductInfoDto;
import org.rx.crawler.task.common.PromotionOrderItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 接口和页面兜底共用的结果校验。成功状态只在必填字段都有值时成立。
 */
final class JdResultChecks {
    private JdResultChecks() {
    }

    static List<JdUnionProductItem> completeSearchItems(List<JdUnionProductItem> items, List<String> errors) {
        List<JdUnionProductItem> kept = new ArrayList<JdUnionProductItem>();
        if (items == null) {
            errors.add("items is null");
            return kept;
        }
        for (int i = 0; i < items.size(); i++) {
            List<String> rowErrors = searchItem(items.get(i), i);
            if (rowErrors.isEmpty()) {
                kept.add(items.get(i));
            } else {
                errors.addAll(rowErrors);
            }
        }
        return kept;
    }

    static List<String> searchItem(JdUnionProductItem item, int index) {
        String prefix = "items[" + index + "]";
        List<String> errors = new ArrayList<String>();
        if (item == null) {
            errors.add(prefix + " is null");
            return errors;
        }
        requireMatch(errors, prefix + ".skuId", item.getSkuId(), "\\d+");
        requireText(errors, prefix + ".productName", item.getProductName());
        requireContains(errors, prefix + ".productLink", item.getProductLink(), item.getSkuId());
        requireNumber(errors, prefix + ".price", item.getPrice());
        requireNumber(errors, prefix + ".commissionRate", item.getCommissionRate());
        requireText(errors, prefix + ".storeName", item.getStoreName());
        requireHttp(errors, prefix + ".imageUrl", item.getImageUrl());
        return errors;
    }

    static List<String> promotion(String promotionUrl, ProductInfoDto info, String keyword) {
        List<String> errors = new ArrayList<String>();
        requireHttp(errors, "promotionUrl", promotionUrl);
        if (info == null) {
            errors.add("productInfo is null");
            return errors;
        }
        requireText(errors, "productInfo.productName", info.getProductName());
        requireHttp(errors, "productInfo.productLink", info.getProductLink());
        if (keyword != null && keyword.matches("\\d+") && (info.getProductLink() == null
                || !info.getProductLink().contains(keyword))) {
            errors.add("productInfo.productLink must contain sku " + keyword);
        }
        requireNumber(errors, "productInfo.price", info.getPrice());
        requireNumber(errors, "productInfo.commissionRate", info.getCommissionRate());
        requireText(errors, "productInfo.storeName", info.getStoreName());
        requireHttp(errors, "productInfo.imageUrl", info.getImageUrl());
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

    static List<JdSearchItem> completeMallItems(List<JdSearchItem> items, List<String> errors) {
        List<JdSearchItem> kept = new ArrayList<JdSearchItem>();
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

    static List<String> mallItem(JdSearchItem item, int index) {
        String prefix = "items[" + index + "]";
        List<String> errors = new ArrayList<String>();
        if (item == null) {
            errors.add(prefix + " is null");
            return errors;
        }
        requireMatch(errors, prefix + ".skuId", item.getSkuId(), "\\d+");
        requireText(errors, prefix + ".title", item.getTitle());
        requireNumber(errors, prefix + ".price", item.getPrice());
        requireContains(errors, prefix + ".productUrl", item.getProductUrl(), item.getSkuId());
        requireText(errors, prefix + ".shopName", item.getShopName());
        requireHttp(errors, prefix + ".imageUrl", item.getImageUrl());
        return errors;
    }

    static List<String> detail(JdProductDetailResult result, boolean includeSpecs) {
        List<String> errors = new ArrayList<String>();
        if (result == null) {
            errors.add("detail is null");
            return errors;
        }
        requireMatch(errors, "skuId", result.getSkuId(), "\\d+");
        requireText(errors, "title", result.getTitle());
        requireNumber(errors, "price", result.getPrice());
        requireText(errors, "shopName", result.getShopName());
        requireContains(errors, "productUrl", result.getProductUrl(), result.getSkuId());
        boolean hasMainImage = false;
        if (result.getMainImages() != null) {
            for (JdImageInfo image : result.getMainImages()) {
                if (image != null && isHttp(image.getUrl())) {
                    hasMainImage = true;
                    break;
                }
            }
        }
        if (!hasMainImage) {
            errors.add("mainImages must contain an http url");
        }
        if (includeSpecs) {
            boolean hasSpec = false;
            if (result.getSpecs() != null) {
                for (JdProductSpecItem spec : result.getSpecs()) {
                    if (spec != null && !Strings.isEmpty(spec.getName()) && !Strings.isEmpty(spec.getValue())) {
                        hasSpec = true;
                        break;
                    }
                }
            }
            if (!hasSpec) {
                errors.add("specs must contain a name and value");
            }
        }
        return errors;
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
