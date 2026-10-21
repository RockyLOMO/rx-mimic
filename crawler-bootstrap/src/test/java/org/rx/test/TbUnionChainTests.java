package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.crawler.task.tb.TbMallTask;
import org.rx.crawler.task.tb.TbProductDetailRequest;
import org.rx.crawler.task.tb.TbProductDetailResult;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;
import org.rx.crawler.task.tb.TbPromotionOrdersTask;
import org.rx.crawler.task.tb.TbPromotionUrlTask;
import org.rx.crawler.task.tb.TbSearchItem;
import org.rx.crawler.task.tb.TbSearchRequest;
import org.rx.crawler.task.tb.TbSearchResult;
import org.rx.crawler.task.tb.TbUnionProductItem;
import org.rx.crawler.task.tb.TbUnionProductSearchRequest;
import org.rx.crawler.task.tb.TbUnionProductSearchResult;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 淘宝闭环链路测试，对标京东的 jdUnionChainShouldSearchPromoteAndOpenDetail。
 * <p>
 * 链路：联盟搜索可转链商品 → 随机取一个转链 → 查近 30 天订单 → 主站搜索 → 主站商品详情。
 * 每步断言 status=SUCCESS，检查必填字段，优先走 AJAX 通道。
 * <p>
 * 开关：{@code -Dtb.union.chain.integration=true}
 */
public class TbUnionChainTests {
    @TempDir
    Path tempDir;

    @Test
    public void tbUnionChainShouldSearchPromoteAndQueryOrders() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("tb.union.chain.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(true);
        org.rx.crawler.task.common.ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        Path debugDir = Paths.get("target", "tb-union-chain").toAbsolutePath();

        // 显式非 headless 模式，确保浏览器窗口桌面可见且最大化
        config.getBrowser().setHeadless(false);
        config.getBrowser().setFingerprintHeadless(false);
        config.getCustom().getChrome().setCloseBrowserAfterTask(false);
        config.getCustom().getChrome().setMinIdle(1);
        config.getCustom().getTbPromotion().setHeadless(false);
        config.getCustom().getTbMall().setHeadless(false);

        // 联盟配置
        config.getCustom().getTbPromotion().setDebugOutputDir(debugDir.resolve("union").toString());
        config.getCustom().getTbPromotion().setDefaultOutputPath(tempDir.resolve("tb-chain-union.jsonl").toString());
        config.getCustom().getTbPromotion().setForcePreflight(true);
        config.getCustom().getTbPromotion().setLoginWaitSeconds(30);

        // 主站配置
        config.getCustom().getTbMall().setDebugOutputDir(debugDir.resolve("mall").toString());
        config.getCustom().getTbMall().setDefaultOutputPath(tempDir.resolve("tb-chain-mall.jsonl").toString());
        config.getCustom().getTbMall().setForcePreflight(true);
        config.getCustom().getTbMall().setLoginWaitSeconds(30);

        String profile = System.getProperty("tb.union.profileName", "common");
        String keyword = System.getProperty("tb.union.chain.keyword", "抽纸");

        BrowserProfileManager profileManager = new BrowserProfileManager(config);
        CrawlEntryService entryService = new CrawlEntryService(new BrowserPreflightService());
        ResultWriter writer = new ResultWriter(objectMapper);
        ObjectMapper prettyMapper = new ObjectMapper()
                .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

        try {
            // ==================== 1. 联盟搜索可转链商品 ====================
            TbPromotionUrlTask unionTask = new TbPromotionUrlTask(config, profileManager, entryService, writer, objectMapper);
            TbUnionProductSearchRequest searchRequest = new TbUnionProductSearchRequest();
            searchRequest.setKeyword(keyword);
            searchRequest.setMaxResults(Integer.getInteger("tb.union.chain.maxResults", 10));
            searchRequest.setProfileName(profile);
            searchRequest.setForcePreflight(true);
            searchRequest.setDebugEnabled(true);
            searchRequest.setDebugOutputDir(debugDir.resolve("union").toString());

            System.out.println("\n================================================================================");
            System.out.println("【步骤 1/5】阿里妈妈选品广场搜索 (tbUnionSearchProducts)");
            System.out.println("--- 入参 (Request) ---");
            System.out.println(prettyMapper.writeValueAsString(searchRequest));

            TbUnionProductSearchResult searched = unionTask.searchProducts(searchRequest);

            System.out.println("--- 返回对象 (Result) ---");
            System.out.println(prettyMapper.writeValueAsString(searched));
            System.out.println("================================================================================\n");

            assertEquals(CustomCrawlStatus.SUCCESS, searched.getStatus(), searched.getMessage());
            assertEquals("ajax", String.valueOf(searched.getDiagnostics().get("channel")));
            assertTrue(searched.getResultCount() > 0, "union search should return items");
            TbUnionProductItem picked = searched.getItems().get(
                    ThreadLocalRandom.current().nextInt(searched.getItems().size()));
            assertNotNull(picked.getItemId(), "itemId must not be null");
            assertFalse(picked.getItemId().trim().isEmpty(), "itemId must not be blank");
            assertNotNull(picked.getProductName(), "productName must not be null");
            assertFalse(picked.getProductName().trim().isEmpty(), "productName must not be blank");
            assertTrue(picked.getProductLink() != null && picked.getProductLink().startsWith("http"),
                    "productLink must be http url");
            assertTrue(picked.getPrice() != null && picked.getPrice().matches(".*\\d.*"),
                    "price must contain a number");
            assertTrue(picked.getCommissionRate() != null && picked.getCommissionRate().matches(".*\\d.*"),
                    "commissionRate must contain a number");
            assertNotNull(picked.getStoreName(), "storeName must not be null");
            assertFalse(picked.getStoreName().trim().isEmpty(), "storeName must not be blank");
            assertTrue(picked.getImageUrl() != null && picked.getImageUrl().startsWith("http"),
                    "imageUrl must be http url");
            System.out.println(">> 已选取商品: itemId=" + picked.getItemId() + ", name=" + picked.getProductName());
            unionTask.closeProfile(profile);

            // ==================== 2. 转链 ====================
            PromotionUrlRequest promotionRequest = new PromotionUrlRequest();
            promotionRequest.setKeyword(picked.getItemId());
            promotionRequest.setAdSiteName(System.getProperty("tb.union.adSiteName", "0"));
            promotionRequest.setProfileName(profile);
            promotionRequest.setForcePreflight(true);
            promotionRequest.setDebugEnabled(true);
            promotionRequest.setDebugOutputDir(debugDir.resolve("union").toString());

            System.out.println("\n================================================================================");
            System.out.println("【步骤 2/5】阿里妈妈生成推广链接 (getTbPromotionUrl)");
            System.out.println("--- 入参 (Request) ---");
            System.out.println(prettyMapper.writeValueAsString(promotionRequest));

            PromotionUrlResult promoted = unionTask.getPromotionUrl(promotionRequest);

            System.out.println("--- 返回对象 (Result) ---");
            System.out.println(prettyMapper.writeValueAsString(promoted));
            System.out.println("================================================================================\n");

            assertEquals(CustomCrawlStatus.SUCCESS, promoted.getStatus(), promoted.getMessage());
            assertEquals("ajax", String.valueOf(promoted.getDiagnostics().get("channel")));
            assertNotNull(promoted.getPromotionUrl());
            assertTrue(promoted.getPromotionUrl().startsWith("http"), "promotionUrl must be http");
            assertNotNull(promoted.getProductInfo(), "productInfo must not be null");
            assertNotNull(promoted.getProductInfo().getProductName(), "productInfo.productName required");
            assertFalse(promoted.getProductInfo().getProductName().trim().isEmpty());
            assertTrue(promoted.getProductInfo().getProductLink() != null
                    && promoted.getProductInfo().getProductLink().startsWith("http"),
                    "productInfo.productLink must be http");
            assertTrue(promoted.getProductInfo().getPrice() != null
                    && promoted.getProductInfo().getPrice().matches(".*\\d.*"),
                    "productInfo.price must contain number");
            assertTrue(promoted.getProductInfo().getCommissionRate() != null
                    && promoted.getProductInfo().getCommissionRate().matches(".*\\d.*"),
                    "productInfo.commissionRate must contain number");
            assertNotNull(promoted.getProductInfo().getStoreName(), "productInfo.storeName required");
            assertFalse(promoted.getProductInfo().getStoreName().trim().isEmpty());
            assertTrue(promoted.getProductInfo().getImageUrl() != null
                    && promoted.getProductInfo().getImageUrl().startsWith("http"),
                    "productInfo.imageUrl must be http");
            unionTask.closeProfile(profile);

            // ==================== 3. 查转链订单（近 30 天） ====================
            TbPromotionOrdersTask ordersTask = new TbPromotionOrdersTask(config, profileManager, entryService,
                    writer, objectMapper);
            TbPromotionOrdersRequest ordersRequest = new TbPromotionOrdersRequest();
            ordersRequest.setStartTime(System.getProperty("tb.union.orders.startTime",
                    LocalDate.now().minusDays(30).toString()));
            ordersRequest.setEndTime(System.getProperty("tb.union.orders.endTime",
                    LocalDate.now().toString()));
            ordersRequest.setProfileName(profile);
            ordersRequest.setForcePreflight(true);

            System.out.println("\n================================================================================");
            System.out.println("【步骤 3/5】阿里妈妈推客订单查询 (getTbPromotionOrders)");
            System.out.println("--- 入参 (Request) ---");
            System.out.println(prettyMapper.writeValueAsString(ordersRequest));

            TbPromotionOrdersResult orders = ordersTask.getPromotionOrders(ordersRequest);

            System.out.println("--- 返回对象 (Result) ---");
            System.out.println(prettyMapper.writeValueAsString(orders));
            System.out.println("================================================================================\n");

            assertEquals(CustomCrawlStatus.SUCCESS, orders.getStatus(), orders.getMessage());
            assertEquals("ajax", String.valueOf(orders.getDiagnostics().get("channel")));
            assertNotNull(orders.getOrders(), "orders list must not be null");
            for (int i = 0; i < orders.getOrders().size(); i++) {
                PromotionOrderItem item = orders.getOrders().get(i);
                assertNotNull(item.getProductName(), "orders[" + i + "].productName required");
                assertFalse(item.getProductName().trim().isEmpty());
                assertNotNull(item.getStoreName(), "orders[" + i + "].storeName required");
                assertFalse(item.getStoreName().trim().isEmpty());
                assertTrue(item.getOrderNo() != null && item.getOrderNo().matches("\\d{8,}"),
                        "orders[" + i + "].orderNo must be 8+ digit number");
                assertNotNull(item.getOrderStatus(), "orders[" + i + "].orderStatus required");
                assertFalse(item.getOrderStatus().trim().isEmpty());
                assertNotNull(item.getOrderTime(), "orders[" + i + "].orderTime required");
                assertFalse(item.getOrderTime().trim().isEmpty());
            }
            ordersTask.closeProfile(profile);

            // ==================== 4. 主站搜索 ====================
            TbMallTask mallTask = new TbMallTask(config, profileManager, entryService, writer, objectMapper);
            TbSearchRequest mallSearchRequest = new TbSearchRequest();
            mallSearchRequest.setKeyword(keyword);
            mallSearchRequest.setMaxResults(Integer.getInteger("tb.union.chain.mallMaxResults", 10));
            mallSearchRequest.setProfileName(profile);
            mallSearchRequest.setForcePreflight(true);
            mallSearchRequest.setDebugEnabled(true);
            mallSearchRequest.setDebugOutputDir(debugDir.resolve("mall").toString());

            System.out.println("\n================================================================================");
            System.out.println("【步骤 4/5】淘宝主站关键字搜索 (tbSearchProduct)");
            System.out.println("--- 入参 (Request) ---");
            System.out.println(prettyMapper.writeValueAsString(mallSearchRequest));

            TbSearchResult mallSearched = mallTask.search(mallSearchRequest);

            System.out.println("--- 返回对象 (Result) ---");
            System.out.println(prettyMapper.writeValueAsString(mallSearched));
            System.out.println("================================================================================\n");

            assertEquals(CustomCrawlStatus.SUCCESS, mallSearched.getStatus(), mallSearched.getMessage());
            assertTrue(mallSearched.getResultCount() > 0, "mall search should return items");
            TbSearchItem mallPicked = mallSearched.getItems().get(
                    ThreadLocalRandom.current().nextInt(mallSearched.getItems().size()));
            assertTrue(mallPicked.getItemId() != null && mallPicked.getItemId().matches("\\d+"),
                    "itemId must be numeric");
            assertNotNull(mallPicked.getTitle(), "title required");
            assertFalse(mallPicked.getTitle().trim().isEmpty());
            assertTrue(mallPicked.getPrice() != null && mallPicked.getPrice().matches(".*\\d.*"),
                    "price must contain number");
            assertTrue(mallPicked.getProductUrl() != null && mallPicked.getProductUrl().contains(mallPicked.getItemId()),
                    "productUrl must contain itemId");
            assertNotNull(mallPicked.getShopName(), "shopName required");
            assertFalse(mallPicked.getShopName().trim().isEmpty());
            assertTrue(mallPicked.getImageUrl() != null && mallPicked.getImageUrl().startsWith("http"),
                    "imageUrl must be http");
            System.out.println(">> 已选取主站商品: itemId=" + mallPicked.getItemId() + ", title=" + mallPicked.getTitle());
            mallTask.closeProfile(profile);

            // ==================== 5. 主站商品详情 ====================
            TbProductDetailRequest detailRequest = new TbProductDetailRequest();
            detailRequest.setItemId(mallPicked.getItemId());
            detailRequest.setProfileName(profile);
            detailRequest.setDownloadImages(false);
            detailRequest.setIncludeSpecs(true);
            detailRequest.setForcePreflight(true);
            detailRequest.setDebugEnabled(true);
            detailRequest.setDebugOutputDir(debugDir.resolve("mall").toString());

            System.out.println("\n================================================================================");
            System.out.println("【步骤 5/5】淘宝主站商品详情 (tbProductDetail)");
            System.out.println("--- 入参 (Request) ---");
            System.out.println(prettyMapper.writeValueAsString(detailRequest));

            TbProductDetailResult detail = mallTask.productDetail(detailRequest);

            System.out.println("--- 返回对象 (Result) ---");
            System.out.println(prettyMapper.writeValueAsString(detail));
            System.out.println("================================================================================\n");

            assertEquals(CustomCrawlStatus.SUCCESS, detail.getStatus(), detail.getMessage());
            assertTrue(detail.getItemId() != null && detail.getItemId().matches("\\d+"),
                    "itemId must be numeric");
            assertNotNull(detail.getTitle(), "title required");
            assertFalse(detail.getTitle().trim().isEmpty());
            assertTrue(detail.getPrice() != null && detail.getPrice().matches(".*\\d.*"),
                    "price must contain number");
            assertNotNull(detail.getShopName(), "shopName required");
            assertFalse(detail.getShopName().trim().isEmpty());
            assertTrue(detail.getProductUrl() != null && detail.getProductUrl().contains(detail.getItemId()),
                    "productUrl must contain itemId");
            assertFalse(detail.getMainImages() == null || detail.getMainImages().isEmpty(),
                    "mainImages must not be empty");
            assertTrue(detail.getMainImages().get(0).getUrl() != null
                    && detail.getMainImages().get(0).getUrl().startsWith("http"),
                    "first mainImage must be http url");
            assertFalse(detail.getSpecs() == null || detail.getSpecs().isEmpty(),
                    "specs must not be empty when includeSpecs=true");
            assertTrue(detail.getSpecs().get(0).getName() != null && !detail.getSpecs().get(0).getName().trim().isEmpty(),
                    "first spec must have name");
            assertTrue(detail.getSpecs().get(0).getValue() != null && !detail.getSpecs().get(0).getValue().trim().isEmpty(),
                    "first spec must have value");
            mallTask.closeProfile(profile);

            System.out.println("TB_CHAIN_COMPLETE keyword=" + keyword
                    + ", unionItems=" + searched.getResultCount()
                    + ", promotionUrl=" + (promoted.getPromotionUrl() != null ? promoted.getPromotionUrl().substring(0, Math.min(60, promoted.getPromotionUrl().length())) + "..." : "null")
                    + ", orders=" + orders.getOrders().size()
                    + ", mallItems=" + mallSearched.getResultCount()
                    + ", detailTitle=" + detail.getTitle());
        } finally {
            profileManager.closeSession(profile);
        }
    }
}
