package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.CrawlResultValidator;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.ProductInfoDto;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersRequest;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.rx.crawler.task.common.CustomCrawlStatus;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
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

public class JdUnionPromotionTaskTests {
    @TempDir
    Path tempDir;

    @Test
    public void commonChromeProfileShouldBeDefault() {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        BrowserProfileManager manager = new BrowserProfileManager(config);

        assertEquals("common", manager.defaultProfileName());
        assertEquals(tempDir.resolve("common").toString(), manager.resolveProfileDataPath(null));
        assertEquals("jd-union-5", manager.normalizeProfileName("jd union/5"));
    }

    @Test
    public void resultWriterShouldAppendJsonLine() throws Exception {
        ResultWriter writer = new ResultWriter(new ObjectMapper());
        PromotionUrlResult result = new PromotionUrlResult();
        result.setKeyword("100059484008");
        result.setAdSiteName("5");

        Path output = tempDir.resolve("output.jsonl");
        writer.appendJsonLine(output.toString(), result);

        String content = new String(java.nio.file.Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertTrue(content.contains("100059484008"));
        assertTrue(content.endsWith(System.lineSeparator()));
    }

    @Test
    public void jdUnionConfigShouldUseOverviewAsLoginEntry() {
        AppConfig config = new AppConfig();

        assertEquals("https://union.jd.com/overview", config.getCustom().getJdUnion().getOverviewUrl());
        assertEquals("https://union.jd.com/entire", config.getCustom().getJdUnion().getEntireUrl());
        assertEquals("https://union.jd.com/order", config.getCustom().getJdUnion().getOrderUrl());
        assertEquals("https://union.jd.com/overview", config.getCustom().getJdUnion().getLoginCheckUrl());
    }

    @Test
    public void jdUnionTaskTypeShouldBeGetPromotionUrl() {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        assertEquals("getPromotionUrl", task.taskType());
    }

    @Test
    public void overviewUrlShouldBeRecognizedAsLoggedInPage() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        Method isLoggedInUrl = JdUnionPromotionTask.class.getDeclaredMethod("isLoggedInUrl", String.class);
        isLoggedInUrl.setAccessible(true);
        assertTrue((Boolean) isLoggedInUrl.invoke(task, "https://union.jd.com/overview"));
        assertTrue((Boolean) isLoggedInUrl.invoke(task, "https://union.jd.com/proManager/index?pageNo=1"));
        assertFalse((Boolean) isLoggedInUrl.invoke(task, "https://union.jd.com/index?returnUrl=abc"));
    }

    @Test
    public void loginBodyShouldBeDetectedEvenOnWorkbenchUrl() {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        String marketingLogin = "账号登录扫码登录\n请使用京东商城账号登录\n跳过登录，先逛逛>";
        String passportLogin = "密码登录\n短信登录\n个人用户登录\n企业用户登录\n手机扫码安全登录";
        String loggedIn = "京东联盟\n我要推广\n商品推广\n推广管理";

        assertTrue(task.looksLikeJdLoginBody(marketingLogin));
        assertTrue(task.looksLikeJdLoginBody(passportLogin));
        assertFalse(task.looksLikeJdLoginBody(loggedIn));
        assertTrue(task.looksLikeJdLoggedInBody(loggedIn));
        assertFalse(task.looksLikeJdLoggedInBody(marketingLogin));
    }

    @Test
    public void crawlEntryNeedsLoginByBodyWhenUrlLooksLoggedIn() {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setLoginUrlPrefix("https://union.jd.com/index?returnUrl=");
        options.setLoginRequiredUrlMatcher(url -> url != null && url.contains("index?returnUrl="));
        options.setLoginRequiredBodyMatcher(body -> body != null && body.contains("请使用京东商城账号登录"));
        options.setLoggedInUrlMatcher(url -> url != null && url.contains("promanager"));

        String workbenchUrl = "https://union.jd.com/proManager/index?pageNo=1";
        String loginBody = "请使用京东商城账号登录\n扫码登录";
        assertTrue(org.rx.crawler.task.common.CrawlEntryService.needsLogin(workbenchUrl, loginBody, options),
                "workbench URL + login body must require login wait");
        assertFalse(org.rx.crawler.task.common.CrawlEntryService.needsLogin(workbenchUrl, "我要推广\n商品推广", options));
        assertTrue(org.rx.crawler.task.common.CrawlEntryService.needsLogin(
                "https://union.jd.com/index?returnUrl=https%3A%2F%2Funion.jd.com%2Foverview", "", options));
    }

    @Test
    public void promotionOrdersTimeRangeShouldRequireStartBeforeEnd() {
        JdUnionPromotionOrdersRequest request = new JdUnionPromotionOrdersRequest();
        request.setStartTime("2026-04-15");
        request.setEndTime("2026-05-08");
        assertTrue(request.isTimeRangeValid());

        request.setStartTime("2026-05-09");
        request.setEndTime("2026-05-08");
        assertFalse(request.isTimeRangeValid());
    }

    @Test
    public void productInfoValidationShouldRejectBlankRequiredFields() {
        ProductInfoDto dto = new ProductInfoDto();
        dto.setProductName("测试商品");
        dto.setProductLink("");
        dto.setCommissionRate("1.50%");
        dto.setStoreName("测试店铺");

        List<String> errors = CrawlResultValidator.validateRequired("productInfo", dto);

        assertFalse(errors.isEmpty());
        assertTrue(errors.toString().contains("productLink"));
    }

    @Test
    public void jdProductInfoShouldPreferCardTitleAnchorName() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("productName", "韩束白蛮腰美白防晒霜京东自营防紫外线防晒隔离三合一520礼物30ml");
        raw.put("productLink", "https://item.jd.com/100059484008.html");
        raw.put("commissionRate", "4.1%");
        raw.put("storeName", "KANS韩束京东自营旗舰店");
        raw.put("price", "35.91");
        raw.put("imageUrl", "https://img14.360buyimg.com/demo.jpg");

        Browser browser = mock(Browser.class);
        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        when(browser.<Map<String, Object>>executeScript(scriptCaptor.capture())).thenReturn(raw);

        Method method = JdUnionPromotionTask.class.getDeclaredMethod("readProductInfo", Browser.class);
        method.setAccessible(true);
        ProductInfoDto dto = (ProductInfoDto) method.invoke(task, browser);

        assertEquals(raw.get("productName"), dto.getProductName());
        assertEquals(raw.get("productLink"), dto.getProductLink());
        String script = scriptCaptor.getValue();
        assertTrue(script.contains("p.two a[href][title]"));
        assertTrue(script.contains("anchorName"));
        assertTrue(script.contains("奖励活动|去报名"));
    }

    @Test
    public void jdUnionAuthorizedIntegration() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("jd.union.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        // 集成验证默认开 debug（HTML 步骤快照），可用 -Dapp.custom.debugEnabled=false 关闭
        config.getCustom().setDebugEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.debugEnabled", "true")));
        config.getCustom().getChrome().setProfileBasePath(
                System.getProperty("app.custom.chrome.profileBasePath", "./data/chrome"));
        Path debugDir = Paths.get(System.getProperty("app.custom.jdUnion.debugOutputDir",
                "data/jd-union/debug")).toAbsolutePath();
        config.getCustom().getJdUnion().setDebugOutputDir(debugDir.toString());
        config.getCustom().getJdUnion().setDefaultOutputPath(tempDir.resolve("jd-output.jsonl").toString());
        // 默认不强制预检（有缓存则快）；需要每次强制时再开 forcePreflight=true
        config.getCustom().getJdUnion().setForcePreflight(
                Boolean.parseBoolean(System.getProperty("app.custom.jdUnion.forcePreflight", "false")));
        config.getCustom().getJdUnion().setPreflightEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.jdUnion.preflightEnabled", "true")));
        config.getCustom().getJdUnion().setLoginWaitSeconds(
                Integer.parseInt(System.getProperty("jd.union.loginWaitSeconds", "180")));
        config.getCustom().getJdUnion().setKeepBrowserOpenOnLoginRequired(
                Boolean.parseBoolean(System.getProperty("jd.union.keepBrowserOpenOnLoginRequired", "true")));
        config.getCustom().getJdUnion().setKeepBrowserOpenSecondsOnLoginRequired(
                Integer.parseInt(System.getProperty("jd.union.keepBrowserOpenSecondsOnLoginRequired", "180")));

        JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);
        PromotionUrlRequest request = new PromotionUrlRequest();
        request.setKeyword(System.getProperty("jd.union.keyword",
                System.getProperty("jd.union.skuId", "100059484008")));
        request.setAdSiteName(System.getProperty("jd.union.adSiteName", "5"));
        request.setProfileName(System.getProperty("jd.union.profileName", "common"));
        request.setDebugEnabled(true);
        request.setDebugOutputDir(debugDir.toString());

        PromotionUrlResult result = task.getPromotionUrl(request);
        System.out.println("JD_UNION_RESULT=" + objectMapper.writeValueAsString(result));
        System.out.println("JD_UNION_DEBUG_DIR=" + result.getDiagnostics().get("debugOutputDir"));
        assertNotNull(result.getStatus());
        if (result.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED) {
            Thread.sleep(Long.parseLong(System.getProperty("jd.union.loginWaitMillis", "300000")));
            if (Boolean.parseBoolean(System.getProperty("jd.union.retryAfterLogin", "true"))) {
                result = task.getPromotionUrl(request);
                System.out.println("JD_UNION_RESULT_RETRY=" + objectMapper.writeValueAsString(result));
                System.out.println("JD_UNION_DEBUG_DIR_RETRY=" + result.getDiagnostics().get("debugOutputDir"));
                assertNotNull(result.getStatus());
            }
        }
        if (result.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED) {
            return;
        }
        task.closeProfile(result.getProfileName());
    }

    @Test
    public void jdUnionPromotionUrlsIntegrationShouldPrintBatchResults() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("jd.union.batch.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(true);
        config.getCustom().getChrome().setProfileBasePath(
                System.getProperty("app.custom.chrome.profileBasePath", "./data/chrome"));
        Path debugDir = Paths.get("target", "jd-union-debug").toAbsolutePath();
        config.getCustom().getJdUnion().setDebugOutputDir(debugDir.toString());
        config.getCustom().getJdUnion().setDefaultOutputPath(tempDir.resolve("jd-batch-output.jsonl").toString());
        config.getCustom().getJdUnion().setForcePreflight(
                Boolean.parseBoolean(System.getProperty("app.custom.jdUnion.forcePreflight", "true")));
        config.getCustom().getJdUnion().setPreflightEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.jdUnion.preflightEnabled", "true")));
        config.getCustom().getJdUnion().setLoginWaitSeconds(
                Integer.parseInt(System.getProperty("jd.union.loginWaitSeconds", "180")));
        config.getCustom().getJdUnion().setKeepBrowserOpenOnLoginRequired(
                Boolean.parseBoolean(System.getProperty("jd.union.keepBrowserOpenOnLoginRequired", "true")));
        config.getCustom().getJdUnion().setKeepBrowserOpenSecondsOnLoginRequired(
                Integer.parseInt(System.getProperty("jd.union.keepBrowserOpenSecondsOnLoginRequired", "180")));

        JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);
        List<String> keywords = Arrays.asList(
                System.getProperty("jd.union.batch.keyword1", "100059484008"),
                System.getProperty("jd.union.batch.keyword2", "100002715968"));

        List<PromotionUrlResult> results = task.getPromotionUrls(keywords);
        System.out.println("JD_UNION_BATCH_RESULT=" + objectMapper.writeValueAsString(results));
        assertEquals(keywords.size(), results.size());
        for (PromotionUrlResult result : results) {
            assertNotNull(result.getStatus());
        }
        if (!results.isEmpty()) {
            task.closeProfile(results.get(0).getProfileName());
        }
    }

    @Test
    public void jdUnionPromotionOrdersIntegrationShouldSaveReadableDebugSnapshot() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("jd.union.orders.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().getChrome().setProfileBasePath(
                System.getProperty("app.custom.chrome.profileBasePath", "./data/chrome"));
        Path debugDir = Paths.get("target", "jd-union-debug").toAbsolutePath();
        config.getCustom().setDebugEnabled(true);
        config.getCustom().getJdUnion().setDebugOutputDir(debugDir.toString());
        config.getCustom().getJdUnion().setDefaultOutputPath(tempDir.resolve("jd-orders-output.jsonl").toString());
        config.getCustom().getJdUnion().setForcePreflight(
                Boolean.parseBoolean(System.getProperty("app.custom.jdUnion.forcePreflight", "true")));
        config.getCustom().getJdUnion().setPreflightEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.jdUnion.preflightEnabled", "true")));

        JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);
        JdUnionPromotionOrdersRequest request = new JdUnionPromotionOrdersRequest();
        request.setStartTime(System.getProperty("jd.union.orders.startTime", LocalDate.now().minusMonths(1).toString()));
        request.setEndTime(System.getProperty("jd.union.orders.endTime", LocalDate.now().toString()));

        Object result = task.getPromotionOrders(request);
        System.out.println("JD_UNION_ORDERS_RESULT=" + objectMapper.writeValueAsString(result));

        Optional<Path> snapshot = Files.walk(debugDir)
                .filter(path -> path.getFileName().toString().endsWith(".html"))
                .sorted(Comparator.comparing(Path::toString))
                .findFirst();
        assertTrue(snapshot.isPresent());
        String html = new String(Files.readAllBytes(snapshot.get()), StandardCharsets.UTF_8);
        assertTrue(html.contains("<html") || html.contains("<body") || html.contains("京东联盟"));
        task.closeProfile(request.getProfileName());
    }
}
