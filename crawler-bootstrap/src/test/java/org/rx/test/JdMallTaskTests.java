package org.rx.test;

import com.fasterxml.jackson.core.type.TypeReference;
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
import org.rx.crawler.task.common.ResultWriter;
import org.rx.crawler.task.jd.JdImageInfo;
import org.rx.crawler.task.jd.JdMallConfig;
import org.rx.crawler.task.jd.JdMallTask;
import org.rx.crawler.task.jd.JdProductDetailRequest;
import org.rx.crawler.task.jd.JdProductDetailResult;
import org.rx.crawler.task.jd.JdProductSpecItem;
import org.rx.crawler.task.jd.JdSearchItem;
import org.rx.crawler.task.jd.JdSearchRequest;
import org.rx.crawler.task.jd.JdSearchResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class JdMallTaskTests {
    @TempDir
    Path tempDir;

    private JdMallTask newTask(AppConfig config) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new JdMallTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);
    }

    @Test
    public void jdMallConfigShouldPointToMallSite() {
        AppConfig config = new AppConfig();
        JdMallConfig jdMall = config.getCustom().getJdMall();

        assertEquals("common", jdMall.getProfileName());
        assertEquals("https://www.jd.com", jdMall.getHomeUrl());
        assertEquals("https://search.jd.com/Search?keyword={keyword}&enc=utf-8", jdMall.getSearchUrlTemplate());
        assertEquals("https://item.jd.com/{skuId}.html", jdMall.getItemUrlTemplate());
        assertEquals("https://passport.jd.com", jdMall.getLoginUrlPrefix());
        assertEquals(60, jdMall.getDefaultMaxResults());
        assertEquals(3, jdMall.getCaptchaMaxAutoAttempts());
        assertEquals(120, jdMall.getCaptchaHumanWaitSeconds());
        assertEquals(120, jdMall.getMaxDetailImages());
        assertEquals("./data/jd-mall/images", jdMall.getImageSaveDir());
        assertEquals("./data/jd-mall/output.jsonl", jdMall.getDefaultOutputPath());
    }

    @Test
    public void jdMallTaskTypeShouldBeSearchProduct() {
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);

        assertEquals("jdSearchProduct", newTask(config).taskType());
    }

    @Test
    public void jdMallPageUrlShouldBeRecognized() {
        JdMallTask task = newTask(new AppConfig());

        assertTrue(task.isJdMallPageUrl("https://www.jd.com/"));
        assertTrue(task.isJdMallPageUrl("https://search.jd.com/Search?keyword=%E5%B0%8F%E7%B1%B3&enc=utf-8"));
        assertTrue(task.isJdMallPageUrl("https://item.jd.com/100059484008.html"));
        assertFalse(task.isJdMallPageUrl("https://passport.jd.com/new/login.aspx"));
        assertFalse(task.isJdMallPageUrl("https://union.jd.com/overview"));
        assertFalse(task.isJdMallPageUrl(null));
    }

    @Test
    public void loginBodyShouldRequireTwoStrongSignals() {
        JdMallTask task = newTask(new AppConfig());

        // 登录页：多特征共存
        assertTrue(task.looksLikeJdMallLoginBody("扫码登录\n密码登录\n忘记密码"));
        assertTrue(task.looksLikeJdMallLoginBody("短信验证码登录\n忘记密码"));
        // 首页未登录态只有「你好，请登录」入口文案，绝不能误判为登录页
        assertFalse(task.looksLikeJdMallLoginBody("你好，请登录 免费注册\n我的订单 我的京东"));
        // 单特征不算（搜索页头部可能渲染一个登录 tab）
        assertFalse(task.looksLikeJdMallLoginBody("扫码登录"));
        assertFalse(task.looksLikeJdMallLoginBody(""));
    }

    @Test
    public void searchRequestShouldApplyDefaultsAndCap() throws Exception {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        JdMallTask task = newTask(config);
        Method normalize = JdMallTask.class.getDeclaredMethod("normalizeSearchRequest",
                JdSearchRequest.class, JdMallConfig.class);
        normalize.setAccessible(true);

        JdSearchRequest request = (JdSearchRequest) normalize.invoke(task, new JdSearchRequest(),
                config.getCustom().getJdMall());
        assertEquals(60, request.getMaxResults());
        assertEquals("common", request.getProfileName());
        assertEquals("./data/jd-mall/output.jsonl", request.getOutputPath());

        JdSearchRequest capped = new JdSearchRequest();
        capped.setMaxResults(500);
        capped = (JdSearchRequest) normalize.invoke(task, capped, config.getCustom().getJdMall());
        assertEquals(100, capped.getMaxResults());
    }

    @Test
    public void detailRequestShouldFillSkuAndUrlBothWays() throws Exception {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        JdMallTask task = newTask(config);
        Method normalize = JdMallTask.class.getDeclaredMethod("normalizeDetailRequest",
                JdProductDetailRequest.class, JdMallConfig.class);
        normalize.setAccessible(true);

        // 只给 URL：从 item.jd.com/{sku}.html 提取 skuId
        JdProductDetailRequest byUrl = new JdProductDetailRequest();
        byUrl.setProductUrl("https://item.jd.com/100059484008.html");
        byUrl = (JdProductDetailRequest) normalize.invoke(task, byUrl, config.getCustom().getJdMall());
        assertEquals("100059484008", byUrl.getSkuId());
        // 只给 skuId：拼出标准详情 URL
        JdProductDetailRequest bySku = new JdProductDetailRequest();
        bySku.setSkuId("100002715968");
        bySku = (JdProductDetailRequest) normalize.invoke(task, bySku, config.getCustom().getJdMall());
        assertEquals("https://item.jd.com/100002715968.html", bySku.getProductUrl());
        assertTrue(bySku.getDownloadImages());
        assertTrue(bySku.getIncludeSpecs());
        // 都缺：拒绝
        assertThrows(Exception.class, () -> normalize.invoke(task, new JdProductDetailRequest(),
                config.getCustom().getJdMall()));
    }

    @Test
    public void searchItemScriptShouldTargetJdResultCards() throws Exception {
        AppConfig config = new AppConfig();
        JdMallTask task = newTask(config);

        Map<String, Object> rawItem = new LinkedHashMap<String, Object>();
        rawItem.put("skuId", "100059484008");
        rawItem.put("title", "小米（MI）小米15 手机");
        rawItem.put("price", "4499.00");
        rawItem.put("imageUrl", "https://img14.360buyimg.com/n0/jfs/t1/demo.jpg");
        rawItem.put("shopName", "小米京东自营旗舰店");
        rawItem.put("productUrl", "https://item.jd.com/100059484008.html");

        Browser browser = mock(Browser.class);
        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        when(browser.executeScript(scriptCaptor.capture(), any())).thenReturn(Arrays.asList(rawItem));

        Method read = JdMallTask.class.getDeclaredMethod("readSearchItems", Browser.class, int.class);
        read.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<JdSearchItem> items = (List<JdSearchItem>) read.invoke(task, browser, 60);

        assertEquals(1, items.size());
        assertEquals("100059484008", items.get(0).getSkuId());
        assertEquals("小米京东自营旗舰店", items.get(0).getShopName());
        String script = scriptCaptor.getValue();
        assertTrue(script.contains("li.gl-item"), "script must target JD result card li.gl-item");
        assertTrue(script.contains("data-sku"), "script must read data-sku attribute");
        assertTrue(script.contains(".p-name"), "script must read .p-name title");
        assertTrue(script.contains("data-lazy-img"), "script must fall back to lazy image attribute");
    }

    @Test
    public void productBaseAndDetailScriptsShouldCoverSpecAndDescriptionFrame() throws Exception {
        String baseScript = readStaticScript("READ_PRODUCT_BASE_SCRIPT");
        assertTrue(baseScript.contains("spec-list"), "base script must read main gallery #spec-list");
        assertTrue(baseScript.contains("data-origin"), "base script must prefer data-origin HD image");
        assertTrue(baseScript.contains("sku-name"), "base script must read .sku-name title");
        assertTrue(baseScript.contains("summary-price"), "base script must read summary price");

        String detailScript = readStaticScript("READ_DETAIL_IMAGES_SCRIPT");
        assertTrue(detailScript.contains("descriptionFrameUrl"), "detail script must probe cross-origin description iframe");
        assertTrue(detailScript.contains("data-lazyload"), "detail script must read lazyload attributes");
        assertTrue(detailScript.contains("360buyimg.com"), "detail script must filter 360buyimg.com images");

        String specsScript = readSpecsScript();
        assertTrue(specsScript.contains(".Ptable"), "specs script must read .Ptable spec table");
    }

    @Test
    public void dedupeDetailImagesShouldDropThumbsAndDuplicateJfsPaths() throws Exception {
        JdMallTask task = newTask(new AppConfig());
        Method dedupe = JdMallTask.class.getDeclaredMethod("dedupeDetailImages",
                List.class, List.class, int.class);
        dedupe.setAccessible(true);

        List<String> mainImages = Arrays.asList(
                "https://img14.360buyimg.com/n1/jfs/t1/MAIN.jpg");
        List<String> detailUrls = Arrays.asList(
                "https://img14.360buyimg.com/n0/s54x54_jfs/t1/THUMB.jpg",     // 主图缩略规格，剔除
                "https://img14.360buyimg.com/n0/jfs/t1/MAIN.jpg",              // 与主图同 jfs 路径，剔除
                "https://img14.360buyimg.com/n0/jfs/t1/DETAIL-1.jpg",          // 保留
                "https://img14.360buyimg.com/n0/jfs/t1/DETAIL-1.jpg!q70.webp", // 同 jfs 路径重复，剔除
                "https://img14.360buyimg.com/n0/jfs/t1/DETAIL-2.jpg");         // 保留
        @SuppressWarnings("unchecked")
        List<String> deduped = (List<String>) dedupe.invoke(task, detailUrls, mainImages, 1);
        assertEquals(1, deduped.size());
        assertEquals("https://img14.360buyimg.com/n0/jfs/t1/DETAIL-1.jpg", deduped.get(0));
    }

    @Test
    public void resultJsonShouldRoundTripForQueueDeserialization() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        String searchJson = "{\"status\":\"SUCCESS\",\"taskType\":\"jdSearchProduct\",\"keyword\":\"小米手机\","
                + "\"profileName\":\"common\",\"items\":[{\"skuId\":\"100059484008\",\"title\":\"小米15\","
                + "\"price\":\"4499.00\",\"imageUrl\":\"https://img14.360buyimg.com/n0/jfs/t1/a.jpg\","
                + "\"shopName\":\"小米京东自营旗舰店\",\"productUrl\":\"https://item.jd.com/100059484008.html\"}],"
                + "\"resultCount\":1,\"currentUrl\":\"https://search.jd.com/Search?keyword=%E5%B0%8F%E7%B1%B3\","
                + "\"loginRequired\":false,\"fingerprintPassed\":true,\"message\":\"ok\","
                + "\"createdAt\":1760000000000,\"diagnostics\":{\"debugEnabled\":true}}";
        JdSearchResult searchResult = objectMapper.readValue(searchJson, JdSearchResult.class);
        assertEquals(CustomCrawlStatus.SUCCESS, searchResult.getStatus());
        assertEquals(1, searchResult.getResultCount());
        assertEquals("小米15", searchResult.getItems().get(0).getTitle());

        String detailJson = "{\"status\":\"SUCCESS\",\"taskType\":\"jdProductDetail\",\"skuId\":\"100059484008\","
                + "\"productUrl\":\"https://item.jd.com/100059484008.html\",\"title\":\"小米15 手机\","
                + "\"price\":\"4499.00\",\"shopName\":\"小米京东自营旗舰店\","
                + "\"mainImages\":[{\"url\":\"https://img14.360buyimg.com/n1/jfs/t1/main.jpg\","
                + "\"localPath\":\"./data/jd-mall/images/100059484008/main/01.jpg\",\"fileName\":\"01.jpg\"}],"
                + "\"detailImages\":[{\"url\":\"https://img14.360buyimg.com/n0/jfs/t1/d1.jpg\","
                + "\"localPath\":\"./data/jd-mall/images/100059484008/detail/01.jpg\",\"fileName\":\"01.jpg\"}],"
                + "\"specs\":[{\"name\":\"商品名称\",\"value\":\"小米15\"}],"
                + "\"loginRequired\":false,\"fingerprintPassed\":true,\"message\":\"ok\","
                + "\"createdAt\":1760000000000,\"diagnostics\":{\"imageDownloadFailures\":0}}";
        JdProductDetailResult detailResult = objectMapper.readValue(detailJson, JdProductDetailResult.class);
        assertEquals(CustomCrawlStatus.SUCCESS, detailResult.getStatus());
        assertEquals(1, detailResult.getMainImages().size());
        assertEquals("01.jpg", detailResult.getMainImages().get(0).getFileName());
        assertEquals("商品名称", detailResult.getSpecs().get(0).getName());
        assertEquals("./data/jd-mall/images/100059484008/detail/01.jpg",
                detailResult.getDetailImages().get(0).getLocalPath());
        // 队列反序列化路径同样按 List 元素映射
        List<JdProductSpecItem> specs = objectMapper.convertValue(
                objectMapper.readTree(detailJson).get("specs"), new TypeReference<List<JdProductSpecItem>>() {
                });
        assertEquals(1, specs.size());
    }

    @Test
    public void jdMallSearchIntegrationShouldPrintItems() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("jd.mall.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.debugEnabled", "true")));
        org.rx.crawler.task.common.ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        Path debugDir = Paths.get(System.getProperty("app.custom.jdMall.debugOutputDir",
                "data/jd-mall/debug")).toAbsolutePath();
        config.getCustom().getJdMall().setDebugOutputDir(debugDir.toString());
        config.getCustom().getJdMall().setDefaultOutputPath(tempDir.resolve("jd-mall-search.jsonl").toString());
        config.getCustom().getJdMall().setLoginWaitSeconds(
                Integer.parseInt(System.getProperty("jd.mall.loginWaitSeconds", "180")));

        JdMallTask task = newTask(config);
        JdSearchRequest request = new JdSearchRequest();
        request.setKeyword(System.getProperty("jd.mall.keyword", "小米手机"));
        request.setMaxResults(Integer.getInteger("jd.mall.maxResults", 30));
        request.setProfileName(System.getProperty("jd.mall.profileName", "common"));
        request.setDebugEnabled(true);
        request.setDebugOutputDir(debugDir.toString());

        JdSearchResult result = task.search(request);
        System.out.println("JD_MALL_RESULT=" + objectMapper.writeValueAsString(result));
        System.out.println("JD_MALL_DEBUG_DIR=" + result.getDiagnostics().get("debugOutputDir"));
        assertNotNull(result.getStatus());
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            assertTrue(result.getResultCount() > 0, "search should return items");
            for (JdSearchItem item : result.getItems()) {
                assertTrue(item.getSkuId() != null && item.getSkuId().matches("\\d+"));
            }
        }
    }

    @Test
    public void jdMallDetailIntegrationShouldDownloadImages() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("jd.mall.detail.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.debugEnabled", "true")));
        org.rx.crawler.task.common.ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        Path debugDir = Paths.get(System.getProperty("app.custom.jdMall.debugOutputDir",
                "data/jd-mall/debug")).toAbsolutePath();
        Path imageDir = Paths.get(System.getProperty("app.custom.jdMall.imageSaveDir",
                "data/jd-mall/images")).toAbsolutePath();
        config.getCustom().getJdMall().setDebugOutputDir(debugDir.toString());
        config.getCustom().getJdMall().setImageSaveDir(imageDir.toString());
        config.getCustom().getJdMall().setDefaultOutputPath(tempDir.resolve("jd-mall-detail.jsonl").toString());
        config.getCustom().getJdMall().setLoginWaitSeconds(
                Integer.parseInt(System.getProperty("jd.mall.loginWaitSeconds", "180")));

        JdMallTask task = newTask(config);
        JdProductDetailRequest request = new JdProductDetailRequest();
        request.setSkuId(System.getProperty("jd.mall.skuId", "100059484008"));
        request.setProfileName(System.getProperty("jd.mall.profileName", "common"));
        request.setDebugEnabled(true);
        request.setDebugOutputDir(debugDir.toString());
        request.setDownloadImages(Boolean.parseBoolean(
                System.getProperty("jd.mall.downloadImages", "true")));

        JdProductDetailResult result = task.productDetail(request);
        System.out.println("JD_MALL_DETAIL_RESULT=" + objectMapper.writeValueAsString(result));
        System.out.println("JD_MALL_DEBUG_DIR=" + result.getDiagnostics().get("debugOutputDir"));
        assertNotNull(result.getStatus());
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
            assertNotNull(result.getTitle(), "title should not be null");
            assertNotNull(result.getShopName(), "shopName should not be null");
            assertNotNull(result.getSpecs(), "specs should not be null");
            assertFalse(result.getMainImages().isEmpty(), "detail should return main gallery images");
            if (Boolean.TRUE.equals(request.getDownloadImages())) {
                List<Path> downloaded = new ArrayList<Path>();
                if (Files.exists(imageDir.resolve(request.getSkuId()))) {
                    try (java.util.stream.Stream<Path> stream = Files.walk(imageDir.resolve(request.getSkuId()))) {
                        stream.filter(Files::isRegularFile).forEach(downloaded::add);
                    }
                }
                System.out.println("JD_MALL_DOWNLOADED_IMAGES=" + downloaded.size());
                assertFalse(downloaded.isEmpty(), "images should be downloaded to " + imageDir);
                for (JdImageInfo info : result.getMainImages()) {
                    if (info.getLocalPath() != null) {
                        assertTrue(Files.exists(Paths.get(info.getLocalPath())),
                                "local file must exist: " + info.getLocalPath());
                    }
                }
            }
        }
    }

    private String readStaticScript(String fieldName) throws Exception {
        Field field = JdMallTask.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private String readSpecsScript() {
        // 规格脚本是 readProductSpecs 内联字符串，从字节码读不到，直接以类常量方式断言选择器存在性
        // 这里返回方法体可读形式：通过 mock browser 捕获实际执行的脚本
        try {
            AppConfig config = new AppConfig();
            JdMallTask task = newTask(config);
            Browser browser = mock(Browser.class);
            ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
            when(browser.executeScript(scriptCaptor.capture())).thenReturn(new ArrayList<Object>());
            Method read = JdMallTask.class.getDeclaredMethod("readProductSpecs", Browser.class);
            read.setAccessible(true);
            read.invoke(task, browser);
            return scriptCaptor.getValue();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
