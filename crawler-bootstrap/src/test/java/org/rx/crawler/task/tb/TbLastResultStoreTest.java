package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.ProductInfoDto;
import org.rx.crawler.task.common.PromotionUrlResult;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TbLastResultStoreTest {
    @TempDir
    Path root;

    @Test
    void failedDetailKeepsEmptyFieldsAndReturnsPreviousSuccess() {
        TbLastResultStore store = new TbLastResultStore(new ObjectMapper(), root);
        TbProductDetailResult success = detail("828661576821", "礼品杯", "礼品旗舰店", "29.50");
        success.setStatus(CustomCrawlStatus.SUCCESS);
        TbImageInfo image = new TbImageInfo();
        image.setUrl("https://img.alicdn.com/a.jpg");
        image.setLocalPath("D:/private/a.jpg");
        success.getMainImages().add(image);
        store.finishDetail(success);
        assertNull(success.getFallback());

        TbProductDetailResult failed = detail("828661576821", "半截标题", "半截店", "2?");
        failed.setStatus(CustomCrawlStatus.PAGE_CHANGED);
        failed.setMessage("missing price");
        store.finishDetail(failed);

        assertEquals(CustomCrawlStatus.PAGE_CHANGED, failed.getStatus());
        assertEquals("", failed.getTitle());
        assertEquals("", failed.getPrice());
        assertEquals("", failed.getShopName());
        assertTrue(failed.getMainImages().isEmpty());
        assertEquals("礼品杯", failed.getFallback().getTitle());
        assertEquals("29.50", failed.getFallback().getPrice());
        assertEquals("礼品旗舰店", failed.getFallback().getShopName());
        assertEquals("https://img.alicdn.com/a.jpg", failed.getFallback().getMainImages().get(0).getUrl());
        assertNull(failed.getFallback().getMainImages().get(0).getLocalPath());
        assertNull(failed.getFallback().getFallback());
    }

    @Test
    void failedPromotionWithoutHistoryHasEmptyFallback() {
        TbLastResultStore store = new TbLastResultStore(new ObjectMapper(), root);
        PromotionUrlResult failed = new PromotionUrlResult();
        failed.setStatus(CustomCrawlStatus.FAILED);
        failed.setKeyword("828661576821");
        failed.setPromotionUrl("https://s.click.taobao.com/partial");
        store.finishPromotion(failed);
        assertNull(failed.getPromotionUrl());
        assertNull(failed.getFallback());
    }

    @Test
    void promotionFallbackMatchesNumericIdOrAllianceId() {
        TbLastResultStore store = new TbLastResultStore(new ObjectMapper(), root);
        PromotionUrlResult success = new PromotionUrlResult();
        success.setStatus(CustomCrawlStatus.SUCCESS);
        success.setKeyword("抽纸");
        success.setItemId("1001");
        success.setMktItemId("QYm3dxmT9CB0baMP4WhWwUatQ-YOtBxzdAZMghA");
        success.setPromotionUrl("https://s.click.taobao.com/ok");
        ProductInfoDto info = new ProductInfoDto();
        info.setProductName("抽纸");
        success.setProductInfo(info);
        store.finishPromotion(success);

        PromotionUrlResult byMkt = new PromotionUrlResult();
        byMkt.setStatus(CustomCrawlStatus.FAILED);
        byMkt.setKeyword("QYm3dxmT9CB0baMP4WhWwUatQ-YOtBxzdAZMghA");
        byMkt.setPromotionUrl("https://s.click.taobao.com/bad");
        store.finishPromotion(byMkt);
        assertNull(byMkt.getPromotionUrl());
        assertEquals("https://s.click.taobao.com/ok", byMkt.getFallback().getPromotionUrl());
        assertEquals("1001", byMkt.getFallback().getItemId());
        assertEquals("抽纸", byMkt.getFallback().getProductInfo().getProductName());
    }

    private static TbProductDetailResult detail(String itemId, String title, String shop, String price) {
        TbProductDetailResult result = new TbProductDetailResult();
        result.setItemId(itemId);
        result.setTitle(title);
        result.setShopName(shop);
        result.setPrice(price);
        return result;
    }
}
