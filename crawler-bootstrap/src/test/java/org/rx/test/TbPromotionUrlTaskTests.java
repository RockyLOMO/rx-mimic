package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.ProductInfoDto;
import org.rx.crawler.task.tb.TbPromotionConfig;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.tb.TbPromotionUrlTask;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TbPromotionUrlTaskTests {
    @TempDir
    Path tempDir;

    @Test
    public void tbPromotionUrlTaskTypeShouldBeGetTbPromotionUrl() {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), objectMapper);

        assertEquals("getTbPromotionUrl", task.taskType());
    }

    @Test
    public void tbPromotionGoodsUrlShouldBeRecognizedAsLoggedIn() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), objectMapper);

        Method method = TbPromotionUrlTask.class.getDeclaredMethod("isLoggedInUrl", String.class,
                TbPromotionConfig.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(task,
                "https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm",
                config.getCustom().getTbPromotion()));
    }

    @Test
    public void tbPromotionUrlLoginJumpUrlShouldRequireLogin() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), objectMapper);

        Method loginRequired = TbPromotionUrlTask.class.getDeclaredMethod("isLoginRequired", String.class,
                TbPromotionConfig.class);
        Method loggedIn = TbPromotionUrlTask.class.getDeclaredMethod("isLoggedInUrl", String.class,
                TbPromotionConfig.class);
        loginRequired.setAccessible(true);
        loggedIn.setAccessible(true);
        String url = "https://pub.alimama.com/portal/v2/home/plus/index.htm/_____tmd_____/page/login_jump?rand=abc";

        assertTrue((Boolean) loginRequired.invoke(task, url, config.getCustom().getTbPromotion()));
        assertFalse((Boolean) loggedIn.invoke(task, url, config.getCustom().getTbPromotion()));
    }

    @Test
    public void tbPromotionUrlRequestShouldNormalizeDefaults() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        config.getCustom().getTbPromotion().setDefaultMediaName("laowang");
        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), objectMapper);

        PromotionUrlRequest request = new PromotionUrlRequest();
        request.setKeyword("西麦纯燕麦片3kg");
        request.setAdSiteName("0");

        Method method = TbPromotionUrlTask.class.getDeclaredMethod("normalizeRequest",
                PromotionUrlRequest.class, TbPromotionConfig.class);
        method.setAccessible(true);
        PromotionUrlRequest normalized = (PromotionUrlRequest) method.invoke(task, request,
                config.getCustom().getTbPromotion());

        assertEquals("common", normalized.getProfileName());
        assertEquals("laowang", normalized.getMediaName());
    }

    @Test
    public void tbPromotionUrlRequestShouldRejectSlotFive() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), objectMapper);
        PromotionUrlRequest request = new PromotionUrlRequest();
        request.setKeyword("西麦纯燕麦片3kg");
        request.setAdSiteName("5");
        Method method = TbPromotionUrlTask.class.getDeclaredMethod("normalizeRequest",
                PromotionUrlRequest.class, TbPromotionConfig.class);
        method.setAccessible(true);
        try {
            method.invoke(task, request, config.getCustom().getTbPromotion());
            assertTrue(false, "slot 5 must be rejected");
        } catch (java.lang.reflect.InvocationTargetException ex) {
            assertTrue(ex.getCause() instanceof org.rx.exception.InvalidException);
        }
    }

    @Test
    public void tbPromotionProductInfoShouldPreferTitleAnchorLink() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), objectMapper);

        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("imageUrl", "https://img.alicdn.com/demo.jpg");
        raw.put("productName", "西麦纯燕麦片3kg高蛋白质0添加蔗糖即食谷物速食懒人代餐冲饮早餐");
        raw.put("productLink", "https://pub.alimama.com/portal/v2/pages/promo/goods/detail.htm?itemId=ygoRdzTAC2R2dpPWOFrD9tvtA-ZdDBpWPUGDgXBd5JfN");
        raw.put("commissionRate", "1.80%");
        raw.put("price", "44.90");
        raw.put("storeName", "seamild西麦旗舰店");

        Browser browser = mock(Browser.class);
        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        when(browser.<Map<String, Object>>executeScript(scriptCaptor.capture())).thenReturn(raw);

        Method method = TbPromotionUrlTask.class.getDeclaredMethod("readProductInfo", Browser.class);
        method.setAccessible(true);
        ProductInfoDto dto = (ProductInfoDto) method.invoke(task, browser);

        assertEquals(raw.get("productLink"), dto.getProductLink());
        String script = scriptCaptor.getValue();
        assertTrue(script.contains("a[href][data-spm-click*=\\\"d_good_select_list_title_click\\\"]"));
        assertTrue(script.contains(".union-good-card-block-title-wrap"));
    }

    @Test
    public void tbPromotionUrlIntegrationShouldSaveReadableDebugSnapshot() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("tb.promotion.url.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        // 默认 common profile + debug（HTML 步骤快照）
        config.getCustom().setDebugEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.debugEnabled", "true")));
        org.rx.crawler.task.common.ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        Path debugDir = Paths.get(System.getProperty("app.custom.tbPromotion.debugOutputDir",
                "data/tb-promotion/debug")).toAbsolutePath();
        config.getCustom().getTbPromotion().setDebugOutputDir(debugDir.toString());
        config.getCustom().getTbPromotion().setForcePreflight(
                Boolean.parseBoolean(System.getProperty("app.custom.tbPromotion.forcePreflight", "false")));
        config.getCustom().getTbPromotion().setPreflightEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.tbPromotion.preflightEnabled", "true")));
        config.getCustom().getTbPromotion().setLoginWaitSeconds(
                Integer.parseInt(System.getProperty("tb.promotion.url.loginWaitSeconds", "180")));

        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), objectMapper);
        PromotionUrlRequest request = new PromotionUrlRequest();
        request.setKeyword(System.getProperty("tb.promotion.url.productInfo",
                System.getProperty("tb.promotion.url.keyword",
                        "西麦纯燕麦片3kg高蛋白质0添加蔗糖即食谷物速食懒人代餐冲饮早餐")));
        request.setAdSiteName(System.getProperty("tb.promotion.url.adSiteName", "0"));
        request.setProfileName(System.getProperty("tb.promotion.url.profileName", "common"));
        request.setDebugEnabled(true);
        request.setDebugOutputDir(debugDir.toString());

        PromotionUrlResult result = task.getPromotionUrl(request);
        System.out.println("TB_PROMOTION_URL_RESULT=" + objectMapper.writeValueAsString(result));
        System.out.println("TB_PROMOTION_URL_DEBUG_DIR=" + result.getDiagnostics().get("debugOutputDir"));
        if (result.getProductInfo() != null) {
            System.out.println("TB_PROMOTION_URL_SUMMARY status=" + result.getStatus()
                    + ", promotionUrl=" + result.getPromotionUrl()
                    + ", productName=" + result.getProductInfo().getProductName()
                    + ", productLink=" + result.getProductInfo().getProductLink()
                    + ", commissionRate=" + result.getProductInfo().getCommissionRate()
                    + ", price=" + result.getProductInfo().getPrice()
                    + ", storeName=" + result.getProductInfo().getStoreName());
        } else {
            System.out.println("TB_PROMOTION_URL_SUMMARY status=" + result.getStatus()
                    + ", promotionUrl=" + result.getPromotionUrl()
                    + ", message=" + result.getMessage()
                    + ", loginRequired=" + result.isLoginRequired());
        }
        assertNotNull(result.getStatus());
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            assertNotNull(result.getProductInfo());
            assertTrue(result.getProductInfo().getProductLink() != null
                    && !result.getProductInfo().getProductLink().trim().isEmpty());
        }

        if (Boolean.TRUE.equals(result.getDiagnostics().get("debugEnabled"))
                || Files.isDirectory(debugDir)) {
            Optional<Path> snapshot = Files.walk(debugDir)
                    .filter(path -> path.getFileName().toString().endsWith(".html"))
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .findFirst();
            assertTrue(snapshot.isPresent(), "debug html snapshot should exist under " + debugDir);
            String html = new String(Files.readAllBytes(snapshot.get()), StandardCharsets.UTF_8);
            assertTrue(html.contains("<html") || html.contains("<body") || html.contains("阿里妈妈") || html.contains("淘宝"));
        }
        if (result.getStatus() != CustomCrawlStatus.LOGIN_REQUIRED) {
            task.closeProfile(request.getProfileName());
        }
    }

    @Test
    public void tbPromotionUrlsIntegrationShouldPrintBatchResults() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("tb.promotion.url.batch.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        org.rx.crawler.task.common.ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        Path debugDir = Paths.get("target", "tb-promotion-url-debug").toAbsolutePath();
        config.getCustom().setDebugEnabled(true);
        config.getCustom().getTbPromotion().setDebugOutputDir(debugDir.toString());
        config.getCustom().getTbPromotion().setForcePreflight(
                Boolean.parseBoolean(System.getProperty("app.custom.tbPromotion.forcePreflight", "true")));
        config.getCustom().getTbPromotion().setPreflightEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.tbPromotion.preflightEnabled", "true")));

        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), objectMapper);
        List<String> keywords = Arrays.asList(
                System.getProperty("tb.promotion.url.batch.keyword1", "钱包"),
                System.getProperty("tb.promotion.url.batch.keyword2", "衣服"));

        List<PromotionUrlResult> results = task.getPromotionUrls(keywords);
        System.out.println("TB_PROMOTION_URL_BATCH_RESULT=" + objectMapper.writeValueAsString(results));
        for (PromotionUrlResult result : results) {
            if (result.getProductInfo() != null) {
                System.out.println("TB_PROMOTION_URL_BATCH_SUMMARY keyword=" + result.getKeyword()
                        + ", status=" + result.getStatus()
                        + ", promotionUrl=" + result.getPromotionUrl()
                        + ", productName=" + result.getProductInfo().getProductName()
                        + ", productLink=" + result.getProductInfo().getProductLink()
                        + ", commissionRate=" + result.getProductInfo().getCommissionRate()
                        + ", price=" + result.getProductInfo().getPrice()
                        + ", storeName=" + result.getProductInfo().getStoreName());
            } else {
                System.out.println("TB_PROMOTION_URL_BATCH_SUMMARY keyword=" + result.getKeyword()
                        + ", status=" + result.getStatus()
                        + ", promotionUrl=" + result.getPromotionUrl()
                        + ", message=" + result.getMessage());
            }
        }
        assertEquals(keywords.size(), results.size());
        for (PromotionUrlResult result : results) {
            assertNotNull(result.getStatus());
        }
        if (!results.isEmpty()) {
            task.closeProfile(results.get(0).getProfileName());
        }
    }
}
