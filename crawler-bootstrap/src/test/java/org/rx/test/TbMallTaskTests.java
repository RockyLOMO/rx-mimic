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
import org.rx.crawler.task.tb.TbImageInfo;
import org.rx.crawler.task.tb.TbMallConfig;
import org.rx.crawler.task.tb.TbMallTask;
import org.rx.crawler.task.tb.TbProductDetailRequest;
import org.rx.crawler.task.tb.TbProductDetailResult;
import org.rx.crawler.task.tb.TbProductSpecItem;
import org.rx.crawler.task.tb.TbSearchItem;
import org.rx.crawler.task.tb.TbSearchRequest;
import org.rx.crawler.task.tb.TbSearchResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TbMallTaskTests {
    @TempDir
    Path tempDir;

    private TbMallTask newTask(AppConfig config) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new TbMallTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), objectMapper);
    }

    @Test
    public void tbMallConfigShouldPointToMallSite() {
        AppConfig config = new AppConfig();
        TbMallConfig tbMall = config.getCustom().getTbMall();

        assertEquals("common", tbMall.getProfileName());
        assertEquals("https://www.taobao.com", tbMall.getHomeUrl());
        assertEquals("https://item.taobao.com/item.htm?id={itemId}", tbMall.getItemUrlTemplate());
        assertEquals("https://login.taobao.com", tbMall.getLoginUrlPrefix());
        assertEquals(3, tbMall.getSliderMaxAutoAttempts());
        assertEquals(60, tbMall.getDefaultMaxResults());
        assertEquals(100, tbMall.getMaxResultsLimit());
        assertEquals(8, tbMall.getMaxSearchPages());
        assertEquals(120, tbMall.getMaxDetailImages());
        assertEquals("./data/tb-mall/images", tbMall.getImageSaveDir());
    }

    @Test
    public void tbMallTaskTypeShouldBeProductDetail() {
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);

        assertEquals("tbProductDetail", newTask(config).taskType());
    }

    @Test
    public void searchRequestShouldApplyDefaultsAndCap() throws Exception {
        TbMallTask task = newTask(new AppConfig());
        Method normalize = TbMallTask.class.getDeclaredMethod("normalizeSearchRequest",
                TbSearchRequest.class, TbMallConfig.class);
        normalize.setAccessible(true);
        TbMallConfig config = new TbMallConfig();

        TbSearchRequest request = new TbSearchRequest();
        request.setKeyword("抽纸");
        TbSearchRequest normalized = (TbSearchRequest) normalize.invoke(task, request, config);
        assertEquals(60, normalized.getMaxResults());
        assertEquals("common", normalized.getProfileName());

        TbSearchRequest capped = new TbSearchRequest();
        capped.setKeyword("抽纸");
        capped.setMaxResults(500);
        capped = (TbSearchRequest) normalize.invoke(task, capped, config);
        assertEquals(100, capped.getMaxResults());
    }

    @Test
    public void tbMallRequestNormalizeShouldParseItemIdAndUrl() throws Exception {
        TbMallTask task = newTask(new AppConfig());
        Method normalize = TbMallTask.class.getDeclaredMethod("normalizeDetailRequest",
                TbProductDetailRequest.class, TbMallConfig.class);
        normalize.setAccessible(true);

        TbMallConfig config = new TbMallConfig();

        // 1. 根据 itemId 自动拼装 URL
        TbProductDetailRequest req1 = new TbProductDetailRequest();
        req1.setItemId("601440653278");
        TbProductDetailRequest res1 = (TbProductDetailRequest) normalize.invoke(task, req1, config);
        assertEquals("601440653278", res1.getItemId());
        assertEquals("https://item.taobao.com/item.htm?id=601440653278", res1.getProductUrl());
        assertEquals("common", res1.getProfileName());
        assertFalse(res1.getDownloadImages());
        assertTrue(res1.getIncludeSpecs());

        // 2. 根据 URL 提取 itemId
        TbProductDetailRequest req2 = new TbProductDetailRequest();
        req2.setProductUrl("https://detail.tmall.com/item.htm?id=5201314&spm=a2141");
        TbProductDetailRequest res2 = (TbProductDetailRequest) normalize.invoke(task, req2, config);
        assertEquals("5201314", res2.getItemId());
        assertEquals("https://detail.tmall.com/item.htm?id=5201314&spm=a2141", res2.getProductUrl());
    }

    @Test
    public void mockBrowserParseProductBase() throws Exception {
        TbMallTask task = newTask(new AppConfig());
        Browser browser = mock(Browser.class);

        Map<String, Object> baseMap = new LinkedHashMap<String, Object>();
        baseMap.put("title", "2026新款纯棉透气圆领T恤短袖男");
        baseMap.put("price", "89.90");
        baseMap.put("shopName", "淘宝男装官方品牌店");
        baseMap.put("mainImages", Arrays.asList(
                "https://img.alicdn.com/imgextra/i1/123/O1CN01a.jpg",
                "https://img.alicdn.com/imgextra/i2/123/O1CN01b.jpg"
        ));

        when(browser.executeScript(anyString())).thenReturn(baseMap);

        Method readBase = TbMallTask.class.getDeclaredMethod("readProductBase", Browser.class);
        readBase.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) readBase.invoke(task, browser);

        assertNotNull(result);
        assertEquals("2026新款纯棉透气圆领T恤短袖男", result.get("title"));
        assertEquals("89.90", result.get("price"));
        assertEquals("淘宝男装官方品牌店", result.get("shopName"));
        @SuppressWarnings("unchecked")
        List<String> mainImages = (List<String>) result.get("mainImages");
        assertEquals(2, mainImages.size());
        assertEquals("https://img.alicdn.com/imgextra/i1/123/O1CN01a.jpg", mainImages.get(0));
    }

    @Test
    public void mockBrowserParseSpecs() throws Exception {
        TbMallTask task = newTask(new AppConfig());
        Browser browser = mock(Browser.class);

        List<Map<String, Object>> mockSpecs = new ArrayList<Map<String, Object>>();
        Map<String, Object> s1 = new LinkedHashMap<String, Object>();
        s1.put("name", "材质成分");
        s1.put("value", "棉100%");
        mockSpecs.add(s1);

        Map<String, Object> s2 = new LinkedHashMap<String, Object>();
        s2.put("name", "版型");
        s2.put("value", "宽松型");
        mockSpecs.add(s2);

        when(browser.executeScript(anyString())).thenReturn(mockSpecs);

        Method readSpecs = TbMallTask.class.getDeclaredMethod("readProductSpecs", Browser.class);
        readSpecs.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<TbProductSpecItem> specs = (List<TbProductSpecItem>) readSpecs.invoke(task, browser);

        assertNotNull(specs);
        assertEquals(2, specs.size());
        assertEquals("材质成分", specs.get(0).getName());
        assertEquals("棉100%", specs.get(0).getValue());
        assertEquals("版型", specs.get(1).getName());
        assertEquals("宽松型", specs.get(1).getValue());
    }

    @Test
    public void detailScrollProbeShouldStopAtDescriptionBeforeRecommend() throws Exception {
        Field field = TbMallTask.class.getDeclaredField("DETAIL_SCROLL_PROBE_SCRIPT");
        field.setAccessible(true);
        String script = (String) field.get(null);
        assertTrue(script.contains("#imageTextInfo-content"), "detail scroll must stop at the description");
        assertTrue(script.contains("recommendsWrap"), "detail scroll must stay above the recommend feed");
        assertTrue(script.contains("before-related"), "detail scroll must name the recommend boundary");
        assertFalse(script.contains("看了又看"), "the top 看了又看 tab is not the scroll stop");
    }

    @Test
    public void tbMallSearchIntegrationShouldPrintItems() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("tb.mall.search.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(true);
        org.rx.crawler.task.common.ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        Path debugDir = Paths.get("target", "tb-mall-search-debug").toAbsolutePath();
        config.getCustom().getTbMall().setDebugOutputDir(debugDir.toString());
        config.getCustom().getTbMall().setLoginWaitSeconds(
                Integer.parseInt(System.getProperty("tb.mall.loginWaitSeconds", "180")));
        config.getCustom().getTbMall().setPreflightEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.tbMall.preflightEnabled", "false")));
        config.getCustom().getTbMall().setForcePreflight(false);

        TbMallTask task = newTask(config);
        TbSearchRequest request = new TbSearchRequest();
        request.setKeyword(System.getProperty("tb.mall.keyword", "抽纸"));
        request.setMaxResults(Integer.getInteger("tb.mall.maxResults", 60));
        request.setProfileName(System.getProperty("tb.mall.profileName", "common"));
        request.setDebugEnabled(true);
        request.setDebugOutputDir(debugDir.toString());

        TbSearchResult result = task.search(request);
        System.out.println("TB_MALL_RESULT=" + objectMapper.writeValueAsString(result));
        System.out.println("TB_MALL_PAGES pageSize=" + result.getDiagnostics().get("pageSize")
                + ", pagesNeeded=" + result.getDiagnostics().get("pagesNeeded")
                + ", pagesFetched=" + result.getDiagnostics().get("pagesFetched")
                + ", resultCount=" + result.getResultCount()
                + ", channel=" + result.getDiagnostics().get("channel"));
        System.out.println("TB_MALL_DEBUG_DIR=" + result.getDiagnostics().get("debugOutputDir"));
        assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus(), result.getMessage());
        assertTrue(result.getResultCount() > 0, "search should return items");
        assertTrue(result.getResultCount() <= request.getMaxResults(), "result should stay within maxResults");
        int pageSize = diagInt(result.getDiagnostics(), "pageSize");
        int pagesFetched = diagInt(result.getDiagnostics(), "pagesFetched");
        assertTrue(pageSize > 0, "pageSize should come from the first page");
        assertTrue(pagesFetched >= 1, "at least one page should be fetched");
        if (request.getMaxResults() > pageSize) {
            assertTrue(pagesFetched >= 2, "expected a second page when maxResults exceeds pageSize");
            assertTrue(result.getResultCount() > pageSize, "result should exceed one page");
        }
        for (TbSearchItem item : result.getItems()) {
            assertTrue(item.getItemId() != null && item.getItemId().matches("\\d+"));
            assertNotNull(item.getTitle());
            assertNotNull(item.getPrice());
            assertFalse(item.getPrice().contains("?"), "price should not be masked");
        }
    }

    private static int diagInt(Map<String, Object> diagnostics, String key) {
        Object value = diagnostics.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    @Test
    public void tbProductDetailIntegrationShouldDownloadImages() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("tb.mall.detail.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        org.rx.crawler.task.common.ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());

        Path debugDir = Paths.get("target", "tb-mall-detail-debug").toAbsolutePath();
        Path imageDir = Paths.get("target", "tb-mall-detail-images").toAbsolutePath();
        config.getCustom().setDebugEnabled(true);
        config.getCustom().getTbMall().setDebugOutputDir(debugDir.toString());
        config.getCustom().getTbMall().setImageSaveDir(imageDir.toString());
        config.getCustom().getTbMall().setForcePreflight(
                Boolean.parseBoolean(System.getProperty("app.custom.tbMall.forcePreflight", "false")));
        config.getCustom().getTbMall().setPreflightEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.tbMall.preflightEnabled", "true")));
        config.getCustom().getTbMall().setLoginWaitSeconds(
                Integer.parseInt(System.getProperty("tb.mall.loginWaitSeconds", "180")));

        TbMallTask task = newTask(config);
        TbProductDetailRequest request = new TbProductDetailRequest();
        request.setItemId(System.getProperty("tb.mall.itemId", "601440653278"));
        request.setProfileName(System.getProperty("tb.mall.profileName", "common"));
        request.setDebugEnabled(true);
        request.setDebugOutputDir(debugDir.toString());
        request.setDownloadImages(Boolean.parseBoolean(
                System.getProperty("tb.mall.downloadImages", "true")));

        TbProductDetailResult result = task.productDetail(request);
        System.out.println("TB_MALL_DETAIL_RESULT=" + objectMapper.writeValueAsString(result));
        System.out.println("TB_MALL_DEBUG_DIR=" + result.getDiagnostics().get("debugOutputDir"));
        assertNotNull(result.getStatus());
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            assertNotNull(result.getTitle(), "title should not be null");
            assertNotNull(result.getShopName(), "shopName should not be null");
            assertNotNull(result.getSpecs(), "specs should not be null");
            assertFalse(result.getMainImages().isEmpty(), "detail should return main gallery images");
            if (Boolean.TRUE.equals(request.getDownloadImages())) {
                List<Path> downloaded = new ArrayList<Path>();
                if (Files.exists(imageDir.resolve(request.getItemId()))) {
                    try (java.util.stream.Stream<Path> stream = Files.walk(imageDir.resolve(request.getItemId()))) {
                        stream.filter(Files::isRegularFile).forEach(downloaded::add);
                    }
                }
                System.out.println("TB_MALL_DOWNLOADED_IMAGES=" + downloaded.size());
                assertFalse(downloaded.isEmpty(), "images should be downloaded to " + imageDir);
                for (TbImageInfo info : result.getMainImages()) {
                    if (info.getLocalPath() != null) {
                        assertTrue(Files.exists(Paths.get(info.getLocalPath())),
                                "local file must exist: " + info.getLocalPath());
                    }
                }
            }
        }
        if (result.getStatus() != CustomCrawlStatus.LOGIN_REQUIRED) {
            task.closeProfile(request.getProfileName());
        }
    }
}
