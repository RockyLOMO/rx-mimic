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
    void tbPromotionFallbackFromDirectSnapshot() {
        PromotionUrlResult success = new PromotionUrlResult();
        success.setStatus(CustomCrawlStatus.SUCCESS);
        success.setKeyword("抽纸");
        success.setItemId("88889999");
        success.setMktItemId("MKT_TB_SEC_999");
        success.setPromotionUrl("https://s.click.taobao.com/direct");
        success.setAdSiteName("网站0");
        ProductInfoDto info = new ProductInfoDto();
        info.setProductName("清风抽纸三层");
        info.setStoreName("清风官方旗舰店");
        info.setImageUrl("https://img.alicdn.com/paper.jpg");
        info.setPrice("19.90");
        success.setProductInfo(info);

        service.finishPromotion(PriceCodes.TB, success);
        assertNull(success.getFallback());

        // 尝试用联盟加密 ID 兜底
        PromotionUrlResult failedByMkt = new PromotionUrlResult();
        failedByMkt.setStatus(CustomCrawlStatus.FAILED);
        failedByMkt.setKeyword("MKT_TB_SEC_999");
        failedByMkt.setPromotionUrl("https://s.click.taobao.com/bad");

        service.finishPromotion(PriceCodes.TB, failedByMkt);
        assertNull(failedByMkt.getPromotionUrl());
        assertNotNull(failedByMkt.getFallback());
        assertEquals("https://s.click.taobao.com/direct", failedByMkt.getFallback().getPromotionUrl());
        assertEquals("88889999", failedByMkt.getFallback().getItemId());
        assertEquals("清风抽纸三层", failedByMkt.getFallback().getProductInfo().getProductName());

        // 尝试用数字 ID 兜底
        PromotionUrlResult failedById = new PromotionUrlResult();
        failedById.setStatus(CustomCrawlStatus.FAILED);
        failedById.setItemId("88889999");

        service.finishPromotion(PriceCodes.TB, failedById);
        assertNotNull(failedById.getFallback());
        assertEquals("https://s.click.taobao.com/direct", failedById.getFallback().getPromotionUrl());
    }

    @Test
    void jdPromotionFallbackFromDirectSnapshot() {
        PromotionUrlResult success = new PromotionUrlResult();
        success.setStatus(CustomCrawlStatus.SUCCESS);
        success.setKeyword("https://item.jd.com/10001234.html");
        success.setItemId("10001234");
        success.setPromotionUrl("https://u.jd.com/promo1234");
        success.setAdSiteName("京东导购0");
        ProductInfoDto info = new ProductInfoDto();
        info.setProductName("飞利浦电动牙刷");
        info.setStoreName("飞利浦京东自营店");
        info.setImageUrl("https://img10.360buyimg.com/brush.jpg");
        info.setPrice("299.00");
        success.setProductInfo(info);

        service.finishPromotion(PriceCodes.JD, success);
        assertNull(success.getFallback());

        PromotionUrlResult failed = new PromotionUrlResult();
        failed.setStatus(CustomCrawlStatus.FAILED);
        failed.setKeyword("https://item.jd.com/10001234.html");
        failed.setItemId("10001234");

        service.finishPromotion(PriceCodes.JD, failed);
        assertNull(failed.getPromotionUrl());
        assertNotNull(failed.getFallback());
        assertEquals("https://u.jd.com/promo1234", failed.getFallback().getPromotionUrl());
        assertEquals("10001234", failed.getFallback().getItemId());
        assertEquals("飞利浦电动牙刷", failed.getFallback().getProductInfo().getProductName());
        assertEquals("299.00", failed.getFallback().getProductInfo().getPrice());
    }

    @Test
    void fallbackReconstructedFromPriceWatchTablesWhenNoDirectSnapshot() {
        // 模拟直接从价格监控表插入的历史商品和转链数据（未经过 crawl_fallback_record）
        Timestamp now = Timestamp.from(Instant.now());
        database.executeUpdate("insert into price_product (platform, platform_product_id, title, shop_name, image_url, identity_status, created_at, last_seen_at) "
                + "values (" + PriceCodes.JD + ", '20005678', '戴尔显示器', '戴尔官方旗舰店', 'https://img.jd.com/dell.jpg', 1, current_timestamp, current_timestamp)");
        long productId = 1L;

        database.executeUpdate("insert into price_product_alias (product_id, platform, account_context, union_product_id, promotion_url, resolve_attempts, created_at) "
                + "values (" + productId + ", " + PriceCodes.JD + ", 'common', 'dell-keyword', 'https://u.jd.com/dell-promo', 0, current_timestamp)");

        database.executeUpdate("insert into price_promotion_record (owner_id, product_id, alias_id, task_id, item_index, ad_site_name, promotion_url, created_at) "
                + "values ('owner1', " + productId + ", 1, 999, 0, 'JD-SITE-1', 'https://u.jd.com/dell-promo', current_timestamp)");

        database.executeUpdate("insert into price_snapshot (product_id, raw_price, price_amount, source, price_kind, comparable_key, comparable, observed_at, task_id, item_index, fetch_status, created_at) "
                + "values (" + productId + ", '1299.00', 1299.00, 1, 1, 'dell-key', 1, current_timestamp, 999, 0, 1, current_timestamp)");

        // 此时发起转链失败任务，测试从价格监控表自动兜底
        PromotionUrlResult failed = new PromotionUrlResult();
        failed.setStatus(CustomCrawlStatus.FAILED);
        failed.setKeyword("dell-keyword");
        failed.setItemId("20005678");

        service.finishPromotion(PriceCodes.JD, failed);
        assertNotNull(failed.getFallback());
        assertEquals("20005678", failed.getFallback().getItemId());
        assertEquals("https://u.jd.com/dell-promo", failed.getFallback().getPromotionUrl());
        assertEquals("JD-SITE-1", failed.getFallback().getAdSiteName());
        assertNotNull(failed.getFallback().getProductInfo());
        assertEquals("戴尔显示器", failed.getFallback().getProductInfo().getProductName());
        assertEquals("戴尔官方旗舰店", failed.getFallback().getProductInfo().getStoreName());
        assertEquals("1299.00", failed.getFallback().getProductInfo().getPrice());

        // 测试详情也从价格监控表兜底
        JdProductDetailResult failedDetail = new JdProductDetailResult();
        failedDetail.setStatus(CustomCrawlStatus.FAILED);
        failedDetail.setSkuId("20005678");

        service.finishJdDetail(failedDetail);
        assertNotNull(failedDetail.getFallback());
        assertEquals("20005678", failedDetail.getFallback().getSkuId());
        assertEquals("戴尔显示器", failedDetail.getFallback().getTitle());
        assertEquals("戴尔官方旗舰店", failedDetail.getFallback().getShopName());
        assertEquals("1299.00", failedDetail.getFallback().getPrice());
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
