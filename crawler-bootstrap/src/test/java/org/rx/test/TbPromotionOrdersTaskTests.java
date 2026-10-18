package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;
import org.rx.crawler.task.tb.TbPromotionOrdersTask;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TbPromotionOrdersTaskTests {
    @TempDir
    Path tempDir;

    @Test
    public void tbPromotionConfigShouldUseCommonProfile() {
        AppConfig config = new AppConfig();

        assertEquals("common", config.getCustom().getTbPromotion().getProfileName());
        assertEquals("https://pub.alimama.com/portal/v2/home/plus/index.htm", config.getCustom().getTbPromotion().getHomeUrl());
        assertEquals("https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm",
                config.getCustom().getTbPromotion().getPromotionGoodsUrl());
        assertEquals("https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm",
                config.getCustom().getTbPromotion().getOrderUrl());
        assertEquals(180, config.getCustom().getTbPromotion().getInitialPageTimeoutSeconds());
        assertEquals(180, config.getCustom().getTbPromotion().getLoginWaitSeconds());
        assertEquals(400, config.getCustom().getTbPromotion().getStepDelayRandomMillis());
        assertEquals(80, config.getBrowser().getOperationRandomMinDelayMillis());
        assertEquals(320, config.getBrowser().getOperationRandomMaxDelayMillis());
        int nextDelay = config.getCustom().getTbPromotion().nextStepDelayMillis();
        assertTrue(nextDelay >= 700 && nextDelay <= 1100);
    }

    @Test
    public void tbForwardLandingUrlShouldBeRecognizedAsLoggedIn() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        TbPromotionOrdersTask task = new TbPromotionOrdersTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        Method method = TbPromotionOrdersTask.class.getDeclaredMethod("isLoggedInUrl", String.class,
                org.rx.crawler.task.tb.TbPromotionConfig.class);
        method.setAccessible(true);
        assertTrue((Boolean) method.invoke(task,
                "https://pub.alimama.com/?forward=http%3A%2F%2Fpub.alimama.com%2Fportal%2Fv2%2Fhome%2Fplus%2Findex.htm",
                config.getCustom().getTbPromotion()));
    }

    @Test
    public void tbLoginJumpUrlShouldRequireLogin() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        TbPromotionOrdersTask task = new TbPromotionOrdersTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        Method loginRequired = TbPromotionOrdersTask.class.getDeclaredMethod("isLoginRequired", String.class,
                org.rx.crawler.task.tb.TbPromotionConfig.class);
        Method loggedIn = TbPromotionOrdersTask.class.getDeclaredMethod("isLoggedInUrl", String.class,
                org.rx.crawler.task.tb.TbPromotionConfig.class);
        loginRequired.setAccessible(true);
        loggedIn.setAccessible(true);
        String url = "https://pub.alimama.com/portal/v2/home/plus/index.htm/_____tmd_____/page/login_jump?rand=abc";
        assertTrue((Boolean) loginRequired.invoke(task, url, config.getCustom().getTbPromotion()));
        assertFalse((Boolean) loggedIn.invoke(task, url, config.getCustom().getTbPromotion()));
    }

    @Test
    public void tbPromotionOrdersBrowserShouldStartMaximized() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        TbPromotionOrdersTask task = new TbPromotionOrdersTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);
        TbPromotionOrdersRequest request = new TbPromotionOrdersRequest();
        request.setProfileName("common");

        Method method = TbPromotionOrdersTask.class.getDeclaredMethod("createBrowserConfig",
                TbPromotionOrdersRequest.class, org.rx.crawler.task.tb.TbPromotionConfig.class);
        method.setAccessible(true);
        WebBrowserConfig browserConfig = (WebBrowserConfig) method.invoke(task, request,
                config.getCustom().getTbPromotion());
        BrowserWindowRect rect = browserConfig.getWindowRectangle();

        assertNotNull(rect);
        assertTrue(rect.getWidth() <= 0);
        assertTrue(rect.getHeight() <= 0);
    }

    @Test
    public void tbPromotionOrdersTimeRangeShouldRequireStartBeforeEnd() {
        TbPromotionOrdersRequest request = new TbPromotionOrdersRequest();
        request.setStartTime("2026-04-15");
        request.setEndTime("2026-05-08");
        assertTrue(request.isTimeRangeValid());

        request.setStartTime("2026-05-09");
        request.setEndTime("2026-05-08");
        assertFalse(request.isTimeRangeValid());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void tbPromotionOrderHtmlParserShouldReadRows() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        TbPromotionOrdersTask task = new TbPromotionOrdersTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        String html = "<div class='next-table'><div class='next-table-row'>"
                + "<div class='next-table-cell'>订单信息</div><div class='next-table-cell'>订单状态</div>"
                + "<div class='next-table-cell'>总提成率</div><div class='next-table-cell'>付款预估收入</div>"
                + "<div class='next-table-cell'>结算预估收入</div>"
                + "</div><div class='next-table-row'>"
                + "<div class='next-table-cell'><a href='https://item.taobao.com/item.htm?id=1'>测试商品A</a>"
                + "<div>老王旗舰店</div><div>主单号：1234567890123456</div><div>订单号：2234567890123456</div>"
                + "<div>￥100.00 2026-05-01 10:11:12</div></div>"
                + "<div class='next-table-cell'>订单付款</div>"
                + "<div class='next-table-cell'>12.50%</div>"
                + "<div class='next-table-cell'><div>预估佣金 ￥1.20</div><div>服务费 ￥0.30</div></div>"
                + "<div class='next-table-cell'><div>实际佣金 ￥1.00</div><div>补贴 ￥0.20</div></div>"
                + "</div><div class='next-table-row'>"
                + "<div class='next-table-cell'><a href='https://item.taobao.com/item.htm?id=2'>测试商品B</a>"
                + "<div>店铺名：老王专卖店</div><div>父订单编号：3234567890123456</div><div>子订单编号：4234567890123456</div>"
                + "<div>￥80.00 2026-05-02 11:12:13</div></div>"
                + "<div class='next-table-cell'>订单结算</div>"
                + "<div class='next-table-cell'>10%</div>"
                + "<div class='next-table-cell'><div>预估佣金 ￥2.00</div><div>服务费 ￥0.50</div></div>"
                + "<div class='next-table-cell'>--</div>"
                + "</div></div>";

        Method method = TbPromotionOrdersTask.class.getDeclaredMethod("parseOrderRowsFromHtml", String.class);
        method.setAccessible(true);
        List<PromotionOrderItem> items = (List<PromotionOrderItem>) method.invoke(task, html);

        assertEquals(2, items.size());
        assertEquals("测试商品A", items.get(0).getProductName());
        assertEquals("https://item.taobao.com/item.htm?id=1", items.get(0).getProductLink());
        assertEquals("老王旗舰店", items.get(0).getStoreName());
        assertEquals("1234567890123456", items.get(0).getMainOrderNo());
        assertEquals("2234567890123456", items.get(0).getOrderNo());
        assertEquals("￥100.00", items.get(0).getEstimatedBillingAmount());
        assertEquals("2026-05-01 10:11:12", items.get(0).getOrderTime());
        assertEquals("订单付款", items.get(0).getOrderStatus());
        assertEquals("12.50%", items.get(0).getCommissionRate());
        assertEquals("￥1.50", items.get(0).getEstimatedCommission());
        assertEquals("￥1.20", items.get(0).getActualCommission());
        assertEquals("￥100.00", items.get(0).getActualBillingAmount());
        assertEquals("老王专卖店", items.get(1).getStoreName());
        assertEquals("3234567890123456", items.get(1).getMainOrderNo());
        assertEquals("4234567890123456", items.get(1).getOrderNo());
        assertNull(items.get(1).getActualCommission());
        assertNull(items.get(1).getActualBillingAmount());
    }

    @Test
    public void tbSliderVerifyPageDetectionShouldMatchKeywords() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        TbPromotionOrdersTask task = new TbPromotionOrdersTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        Method isSlider = TbPromotionOrdersTask.class.getDeclaredMethod("isSliderVerifyPage",
                org.rx.crawler.service.Browser.class);
        isSlider.setAccessible(true);

        // 直接测 containsAny 逻辑（isSliderVerifyPage 依赖 browser，这里用反射验证关键词覆盖）
        Method containsAny = TbPromotionOrdersTask.class.getDeclaredMethod("containsAny", String.class, String[].class);
        containsAny.setAccessible(true);

        // 验证页特征文字应被识别
        assertTrue((Boolean) containsAny.invoke(task, "亲，请拖动下方滑块完成验证\n通过验证以确保正常访问",
                new String[]{"请拖动下方滑块完成验证", "拖动滑块", "拖到最右边", "按住滑块"}));
        assertTrue((Boolean) containsAny.invoke(task, "请按住滑块，拖到最右边",
                new String[]{"请拖动下方滑块完成验证", "拖动滑块", "拖到最右边", "按住滑块"}));
        // 正常订单页文字不应被误识别
        assertFalse((Boolean) containsAny.invoke(task, "订单状态 佣金比例 预估佣金 实际佣金",
                new String[]{"请拖动下方滑块完成验证", "拖动滑块", "拖到最右边", "按住滑块"}));
    }

    @Test
    public void sliderVerifyHandlerShouldRecognizeBaxiaIframeAndDirectMarkup() {
        org.rx.crawler.task.tb.SliderVerifyHandler handler = new org.rx.crawler.task.tb.SliderVerifyHandler();
        org.rx.crawler.service.Browser iframeBrowser = stubBrowser(
                "https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm",
                "<html><body><div class='baxia-dialog'><iframe id='baxia-dialog-content' src='/punish?x5sec=1'></iframe></div></body></html>",
                "");
        assertTrue(handler.isSliderVerifyPage(iframeBrowser));

        org.rx.crawler.service.Browser directBrowser = stubBrowser(
                "https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm",
                "<html><body><div id='baxia-punish'><div id='nocaptcha' class='nc-container'>"
                        + "<div id='nc_1_wrapper' class='nc_wrapper'><div id='nc_1_n1t' class='nc_scale'>"
                        + "<span id='nc_1_n1z' class='nc_iconfont btn_slide' role='button'></span>"
                        + "<div class='scale_text slidetounlock'><span data-nc-lang='SLIDE'>请按住滑块，拖动到最右边</span></div>"
                        + "</div></div></div></div></body></html>",
                "请按住滑块，拖动到最右边");
        assertTrue(handler.isSliderVerifyPage(directBrowser));

        org.rx.crawler.service.Browser normalBrowser = stubBrowser(
                "https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm",
                "<html><body><div>订单信息 订单状态 佣金比例 预估佣金</div></body></html>",
                "订单信息 订单状态 佣金比例 预估佣金");
        assertFalse(handler.isSliderVerifyPage(normalBrowser));
    }

    private org.rx.crawler.service.Browser stubBrowser(String url, String outerHtml, String bodyText) {
        return new StubBrowser(url, outerHtml, bodyText);
    }

    /** 仅用于单测：模拟 isSliderVerifyPage 所依赖的 currentUrl + bodyText + 关键 DOM 关键词。 */
    static class StubBrowser implements org.rx.crawler.service.Browser {
        private final String url;
        private final String outerHtml;
        private final String bodyText;

        StubBrowser(String url, String outerHtml, String bodyText) {
            this.url = url;
            this.outerHtml = outerHtml;
            this.bodyText = bodyText;
        }

        @Override public String getCurrentUrl() { return url; }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T executeScript(String script, Object... args) {
            if (script.contains("checkDoc")) {
                if (containsSliderMarker(outerHtml) || containsSliderText(bodyText)) {
                    return (T) Boolean.TRUE;
                }
                return (T) Boolean.FALSE;
            }
            if (script.contains("document.body")) {
                return (T) bodyText;
            }
            return null;
        }

        private boolean containsSliderMarker(String html) {
            if (html == null) { return false; }
            return html.contains("baxia") || html.contains("nc_1_n1z") || html.contains("btn_slide")
                    || html.contains("nc_scale") || html.contains("nc-container") || html.contains("baxia-punish")
                    || html.contains("nocaptcha") || html.contains("punish-component");
        }

        private boolean containsSliderText(String body) {
            if (body == null) { return false; }
            return body.contains("请拖动下方滑块完成验证") || body.contains("拖动滑块") || body.contains("拖到最右边")
                    || body.contains("按住滑块") || body.contains("滑块验证") || body.contains("安全验证")
                    || body.contains("访问验证") || body.contains("行为验证") || body.contains("请完成验证")
                    || body.contains("请按住滑块");
        }

        @Override public org.rx.crawler.service.BrowserType getType() { return null; }
        @Override public void setCookieRegion(String cookieRegion) {}
        @Override public long getWaitMillis() { return 0; }
        @Override public void navigateUrl(String url) {}
        @Override public void navigateUrl(String url, String locatorSelector) {}
        @Override public void navigateUrl(String url, String locatorSelector, int timeoutSeconds) {}
        @Override public void nativeGet(String url) {}
        @Override public String saveCookies(boolean reset) { return ""; }
        @Override public void clearCookies(boolean onlyBrowser) {}
        @Override public void setRawCookie(String rawCookie) {}
        @Override public String getRawCookie() { return ""; }
        @Override public boolean hasElement(String selector) { return false; }
        @Override public String elementText(String selector) { return ""; }
        @Override public org.rx.core.Linq<String> elementsText(String selector) { return org.rx.core.Linq.from(java.util.Collections.emptyList()); }
        @Override public String elementVal(String selector) { return ""; }
        @Override public org.rx.core.Linq<String> elementsVal(String selector) { return org.rx.core.Linq.from(java.util.Collections.emptyList()); }
        @Override public String elementAttr(String selector, String... attrArgs) { return ""; }
        @Override public org.rx.core.Linq<String> elementsAttr(String selector, String... attrArgs) { return org.rx.core.Linq.from(java.util.Collections.emptyList()); }
        @Override public void elementClick(String selector) {}
        @Override public void elementClick(String selector, boolean waitElementLocated) {}
        @Override public void elementPress(String selector, String keys) {}
        @Override public void elementPress(String selector, String keys, boolean waitElementLocated) {}
        @Override public void waitElementLocated(String selector) {}
        @Override public void waitElementLocated(String selector, int timeoutSeconds) {}
        @Override public void injectScript(String script) {}
        @Override public <T> T injectAndExecuteScript(String injectScript, String script, Object... args) { return null; }
        @Override public <T> T executeConfigureScript(String scriptName, Object... args) { return null; }
        @Override public byte[] screenshotAsBytes(String selector) { return new byte[0]; }
        @Override public void focus() {}
        @Override public void maximize() {}
        @Override public void normalize() {}
        @Override public void close() {}
    }

    @Test
    public void tbPromotionOrdersIntegrationShouldSaveReadableDebugSnapshot() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("tb.promotion.orders.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        org.rx.crawler.task.common.ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        Path debugDir = Paths.get("target", "tb-promotion-debug").toAbsolutePath();
        config.getCustom().setDebugEnabled(true);
        config.getCustom().getTbPromotion().setDebugOutputDir(debugDir.toString());
        config.getCustom().getTbPromotion().setDefaultOutputPath(tempDir.resolve("tb-orders-output.jsonl").toString());
        // 默认 false：复用 Sannysoft 预检缓存，与 application.yml 一致；强制探测再 -Dapp.custom.tbPromotion.forcePreflight=true
        config.getCustom().getTbPromotion().setForcePreflight(
                Boolean.parseBoolean(System.getProperty("app.custom.tbPromotion.forcePreflight", "false")));
        config.getCustom().getTbPromotion().setPreflightEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.tbPromotion.preflightEnabled", "true")));

        TbPromotionOrdersTask task = new TbPromotionOrdersTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);
        // 默认近 30 天（相对今天），避免写死历史区间导致日期面板跨年乱跳；可用 -D 覆盖
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(30);
        TbPromotionOrdersRequest request = new TbPromotionOrdersRequest();
        request.setStartTime(System.getProperty("tb.promotion.orders.startTime", startDate.toString()));
        request.setEndTime(System.getProperty("tb.promotion.orders.endTime", endDate.toString()));
        System.out.println("TB_PROMOTION_ORDERS_RANGE=" + request.getStartTime() + " .. " + request.getEndTime());

        TbPromotionOrdersResult result = task.getPromotionOrders(request);
        System.out.println("TB_PROMOTION_ORDERS_RESULT=" + objectMapper.writeValueAsString(result));
        assertNotNull(result.getStatus());

        Optional<Path> snapshot = Files.walk(debugDir)
                .filter(path -> path.getFileName().toString().endsWith(".html"))
                .sorted(Comparator.comparing(Path::toString))
                .findFirst();
        assertTrue(snapshot.isPresent());
        String html = new String(Files.readAllBytes(snapshot.get()), StandardCharsets.UTF_8);
        assertTrue(html.contains("<html") || html.contains("<body") || html.contains("阿里妈妈") || html.contains("淘宝"));
        if (result.getStatus() != CustomCrawlStatus.LOGIN_REQUIRED) {
            task.closeProfile(request.getProfileName());
        }
    }
}
