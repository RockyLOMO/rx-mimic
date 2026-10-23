package org.rx.crawler.task.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rx.crawler.task.jd.JdImageInfo;
import org.rx.crawler.task.jd.JdProductDetailResult;
import org.rx.crawler.task.price.PriceCodes;
import org.rx.crawler.task.price.PriceWatchStore;
import org.rx.crawler.task.tb.TbImageInfo;
import org.rx.crawler.task.tb.TbProductDetailResult;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlFallbackServiceTest {
    private EntityDatabase database;
    private CrawlFallbackService service;

    @BeforeEach
    void setUp() {
        database = new EntityDatabaseImpl("jdbc:h2:mem:fallback-test-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", null, 4, true);
        PriceWatchStore priceWatchStore = new PriceWatchStore(database);
        priceWatchStore.init();

        service = new CrawlFallbackService(database, new ObjectMapper());
        service.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void tbProductDetailFallbackFromDirectSnapshot() {
        TbProductDetailResult success = new TbProductDetailResult();
        success.setStatus(CustomCrawlStatus.SUCCESS);
        success.setItemId("12345678");
        success.setTitle("淘宝真丝衬衫");
        success.setPrice("199.00");
        success.setShopName("丝绸专卖店");
        TbImageInfo img = new TbImageInfo();
        img.setUrl("https://img.alicdn.com/shirt.jpg");
        img.setLocalPath("C:/temp/shirt.jpg");
        success.getMainImages().add(img);

        service.finishTbDetail(success);
        assertNull(success.getFallback());

        TbProductDetailResult failed = new TbProductDetailResult();
        failed.setStatus(CustomCrawlStatus.FAILED);
        failed.setItemId("12345678");
        failed.setMessage("slider verify timeout");

        service.finishTbDetail(failed);
        assertEquals(CustomCrawlStatus.FAILED, failed.getStatus());
        assertEquals("", failed.getTitle());
        assertEquals("", failed.getPrice());
        assertNotNull(failed.getFallback());
        assertEquals("淘宝真丝衬衫", failed.getFallback().getTitle());
        assertEquals("199.00", failed.getFallback().getPrice());
        assertEquals("丝绸专卖店", failed.getFallback().getShopName());
        assertEquals("https://img.alicdn.com/shirt.jpg", failed.getFallback().getMainImages().get(0).getUrl());
        assertNull(failed.getFallback().getMainImages().get(0).getLocalPath());
    }

    @Test
    void jdProductDetailFallbackFromDirectSnapshot() {
        JdProductDetailResult success = new JdProductDetailResult();
        success.setStatus(CustomCrawlStatus.SUCCESS);
        success.setSkuId("1000123456");
        success.setTitle("京东自营机械键盘");
        success.setPrice("399.00");
        success.setShopName("京东自营");
        success.setGoodRate("98%好评");
        JdImageInfo img = new JdImageInfo();
        img.setUrl("https://img10.360buyimg.com/kb.jpg");
        img.setLocalPath("D:/images/kb.jpg");
        success.getMainImages().add(img);

        service.finishJdDetail(success);
        assertNull(success.getFallback());

        JdProductDetailResult failed = new JdProductDetailResult();
        failed.setStatus(CustomCrawlStatus.TIMEOUT);
        failed.setSkuId("1000123456");
        failed.setMessage("page load timeout");

        service.finishJdDetail(failed);
        assertEquals(CustomCrawlStatus.TIMEOUT, failed.getStatus());
        assertEquals("", failed.getTitle());
        assertEquals("", failed.getPrice());
        assertNotNull(failed.getFallback());
        assertEquals("京东自营机械键盘", failed.getFallback().getTitle());
        assertEquals("399.00", failed.getFallback().getPrice());
        assertEquals("京东自营", failed.getFallback().getShopName());
        assertEquals("98%好评", failed.getFallback().getGoodRate());
        assertEquals("https://img10.360buyimg.com/kb.jpg", failed.getFallback().getMainImages().get(0).getUrl());
        assertNull(failed.getFallback().getMainImages().get(0).getLocalPath());
    }

    @Test
    void promotionalMaterialsNeverEnterFallbackDatabase() throws Exception {
        for (int platform : new int[]{PriceCodes.JD, PriceCodes.TB}) {
            PromotionUrlResult success = new PromotionUrlResult();
            success.setStatus(CustomCrawlStatus.SUCCESS);
            success.setKeyword("ENCRYPTED_MATERIAL_123456789");
            success.setEncryptedProductId("ENCRYPTED_MATERIAL_123456789");
            success.setPromotionUrl("https://u.jd.com/transient");
            service.finishPromotion(platform, success);
            assertNull(success.getFallback());
            PromotionUrlResult failed = new PromotionUrlResult();
            failed.setStatus(CustomCrawlStatus.FAILED);
            failed.setKeyword("ENCRYPTED_MATERIAL_123456789");
            failed.setPromotionUrl("https://u.jd.com/invalid");
            service.finishPromotion(platform, failed);
            assertNull(failed.getFallback());
            assertNull(failed.getPromotionUrl());
        }
        database.withConnection(connection -> {
            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery("select count(*) from crawl_fallback_record")) {
                rs.next(); assertEquals(0, rs.getInt(1));
            }
            return null;
        });
    }

    @Test
    void failedTaskWithoutHistoryReturnsNullFallback() {
        PromotionUrlResult failed = new PromotionUrlResult();
        failed.setStatus(CustomCrawlStatus.FAILED);
        failed.setKeyword("non_existent_key");

        service.finishPromotion(PriceCodes.TB, failed);
        assertNull(failed.getFallback());
        assertNull(failed.getPromotionUrl());

        JdProductDetailResult failedJd = new JdProductDetailResult();
        failedJd.setStatus(CustomCrawlStatus.FAILED);
        failedJd.setSkuId("999999999");

        service.finishJdDetail(failedJd);
        assertNull(failedJd.getFallback());
    }
}
