package org.rx.crawler.task.jd;

import org.junit.jupiter.api.Test;
import org.rx.crawler.task.common.ProductInfoDto;
import org.rx.crawler.task.common.PromotionOrderItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdResultChecksTest {
    @Test
    void searchDropsIncompleteCards() {
        List<String> errors = new ArrayList<String>();
        List<JdUnionProductItem> kept = JdResultChecks.completeSearchItems(
                java.util.Arrays.asList(searchItem("1001"), blankSearchItem()), errors);
        assertEquals(1, kept.size());
        assertEquals("1001", kept.get(0).getSkuId());
        assertTrue(errors.stream().anyMatch(error -> error.contains("items[1].productName")));
    }

    @Test
    void promotionRequiresUrlAndProductFields() {
        ProductInfoDto info = new ProductInfoDto();
        info.setProductName("抽纸");
        info.setProductLink("https://item.jd.com/1001.html");
        info.setPrice("9.9");
        info.setCommissionRate("1%");
        info.setStoreName("京东自营");
        List<String> errors = JdResultChecks.promotion("https://u.jd.com/abc", info, "1001");
        assertTrue(errors.stream().anyMatch(error -> error.contains("imageUrl")));

        info.setImageUrl("https://img14.360buyimg.com/n1/jfs/a.jpg");
        assertTrue(JdResultChecks.promotion("https://u.jd.com/abc", info, "1001").isEmpty());
    }

    @Test
    void emptyOrdersStayValidAndIncompleteRowsDoNot() {
        assertTrue(JdResultChecks.orders(Collections.<PromotionOrderItem>emptyList()).isEmpty());
        PromotionOrderItem row = new PromotionOrderItem();
        row.setProductName("抽纸");
        row.setStoreName("京东自营");
        row.setOrderNo("1234567890123456");
        row.setPromotionPosition("默认推广位");
        row.setOrderStatus("已付款");
        List<String> errors = JdResultChecks.orders(Collections.singletonList(row));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("orderTime"));
    }

    @Test
    void detailRequiresTitleAndMallCardPreservesProductWithoutDisplayedShop() {
        JdProductDetailResult detail = new JdProductDetailResult();
        detail.setSkuId("1001");
        detail.setPrice("9.9");
        detail.setShopName("京东自营");
        detail.setProductUrl("https://item.jd.com/1001.html");
        JdImageInfo image = new JdImageInfo();
        image.setUrl("https://img14.360buyimg.com/n1/a.jpg");
        detail.setMainImages(Collections.singletonList(image));
        assertTrue(JdResultChecks.detail(detail, false).stream().anyMatch(error -> error.contains("title")));
        detail.setTitle("抽纸");
        detail.setGoodRate("100%好评");
        assertTrue(JdResultChecks.detail(detail, false).isEmpty());
        detail.setGoodRate("很好");
        assertTrue(JdResultChecks.detail(detail, false).stream().anyMatch(error -> error.contains("goodRate")));

        JdSearchItem card = new JdSearchItem();
        card.setSkuId("1001");
        card.setTitle("抽纸");
        card.setPrice("9.9");
        card.setProductUrl("https://item.jd.com/1001.html");
        card.setImageUrl("https://img14.360buyimg.com/n1/a.jpg");
        List<String> errors = new ArrayList<String>();
        assertEquals(1, JdResultChecks.completeMallItems(Collections.singletonList(card), errors).size());
        assertTrue(errors.isEmpty());
    }

    private static JdUnionProductItem searchItem(String skuId) {
        JdUnionProductItem item = new JdUnionProductItem();
        item.setSkuId(skuId);
        item.setProductName("抽纸");
        item.setProductLink("https://item.jd.com/" + skuId + ".html");
        item.setPrice("9.9");
        item.setCommissionRate("1%");
        item.setStoreName("京东自营");
        item.setImageUrl("https://img14.360buyimg.com/n1/a.jpg");
        return item;
    }

    private static JdUnionProductItem blankSearchItem() {
        JdUnionProductItem item = new JdUnionProductItem();
        item.setSkuId("1002");
        item.setProductLink("https://item.jd.com/1002.html");
        return item;
    }
}
