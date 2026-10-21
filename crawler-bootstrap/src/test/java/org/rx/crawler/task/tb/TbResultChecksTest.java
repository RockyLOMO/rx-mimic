package org.rx.crawler.task.tb;

import org.junit.jupiter.api.Test;
import org.rx.crawler.task.common.ProductInfoDto;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TbResultChecksTest {
    @Test
    void detailRequiresIdNameShopAndPriceOnly() {
        TbProductDetailResult detail = new TbProductDetailResult();
        detail.setItemId("828661576821");
        detail.setTitle("礼品杯");
        detail.setShopName("礼品旗舰店");
        detail.setPrice("2?");
        assertTrue(TbResultChecks.detail(detail).stream().anyMatch(error -> error.contains("price")));
        detail.setPrice("29.50");
        assertTrue(TbResultChecks.detail(detail).isEmpty());
        detail.setShopName(" ");
        assertTrue(TbResultChecks.detail(detail).stream().anyMatch(error -> error.contains("shopName")));
    }

    @Test
    void promotionRequiresNumericIdAllianceIdAndUrl() {
        assertTrue(TbResultChecks.promotion("https://s.click.taobao.com/a", "828661576821",
                "QYm3dxmT9CB0baMP4WhWwUatQ-YOtBxzdAZMghA").isEmpty());
        assertFalse(TbResultChecks.promotion("https://s.click.taobao.com/a", "828661576821", "5").isEmpty());
        assertFalse(TbResultChecks.promotion("", "828661576821",
                "QYm3dxmT9CB0baMP4WhWwUatQ-YOtBxzdAZMghA").isEmpty());
        assertFalse(TbResultChecks.promotion("https://s.click.taobao.com/a", "mkt-not-numeric",
                "QYm3dxmT9CB0baMP4WhWwUatQ-YOtBxzdAZMghA").isEmpty());
        ProductInfoDto info = new ProductInfoDto();
        info.setProductName("礼品杯");
        assertTrue(info.getProductName().equals("礼品杯"));
    }

    @Test
    void emptyImageListDoesNotBlockDetail() {
        TbProductDetailResult detail = readyDetail();
        detail.setMainImages(Collections.emptyList());
        detail.setDetailImages(Collections.emptyList());
        detail.setSpecs(Collections.emptyList());
        assertTrue(TbResultChecks.detail(detail).isEmpty());
    }

    private static TbProductDetailResult readyDetail() {
        TbProductDetailResult detail = new TbProductDetailResult();
        detail.setItemId("1");
        detail.setTitle("抽纸");
        detail.setShopName("清风官方旗舰店");
        detail.setPrice("￥29.50");
        return detail;
    }
}
