package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.crawler.task.common.OrderProductLinkReader;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.price.PriceCodes;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.rx.crawler.task.tb.TbPromotionOrdersTask;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromotionOrderDomFieldTests {
    @Test
    @SuppressWarnings("unchecked")
    void naturalResponseMustMatchTheVisiblePageAndNeverRequestsData() throws Exception {
        Browser browser = mock(Browser.class);
        String oldPage = "{\"data\":{\"result\":[{\"tradeId\":\"512771454149502540\",\"itemTitle\":\"旧商品\"}]}}";
        String currentPage = "{\"data\":{\"result\":[{\"tradeId\":\"512771454149502550\",\"itemTitle\":\"当前商品\",\"siteName\":\"12345\"}]}}";
        when(browser.drainResponseCapture("report.getTbkOrderDetails.json")).thenReturn(List.of(oldPage, currentPage));
        PromotionOrderItem visible = new PromotionOrderItem();
        visible.setOrderNo("512771454149502550");
        visible.setProductLink("https://item.taobao.com/item.htm?id=123456");
        Method parser = TbPromotionOrdersTask.class.getDeclaredMethod("readCapturedOrderRows", Browser.class, List.class);
        parser.setAccessible(true);
        List<PromotionOrderItem> items = (List<PromotionOrderItem>) parser.invoke(tb(), browser, List.of(visible));
        assertEquals(1, items.size());
        assertEquals("当前商品", items.get(0).getProductName());
        assertEquals("https://item.taobao.com/item.htm?id=123456", items.get(0).getProductLink());
        when(browser.drainResponseCapture("report.getTbkOrderDetails.json")).thenReturn(List.of(oldPage));
        List<PromotionOrderItem> fallback = (List<PromotionOrderItem>) parser.invoke(tb(), browser, List.of(visible));
        assertSame(visible, fallback.get(0));
        verify(browser, never()).executeScript(anyString(), any());
    }

    @Test
    void missingProductIdUsesNativePopupAndSkipsAlreadyKnownIds() {
        Browser browser = mock(Browser.class);
        when(browser.<Boolean>executeScript(anyString(), any())).thenReturn(true);
        when(browser.readLinkDestination(anyString(), any(), anyInt()))
                .thenReturn("https://item.jd.com/100059484008.html");
        PromotionOrderItem row = new PromotionOrderItem();
        row.setOrderNo("365446701560385");
        row.setProductLink("javascript:void(0)");
        OrderProductLinkReader.resolve(browser, row, PriceCodes.JD, 5);
        assertEquals("100059484008", row.getSkuId());
        assertEquals("https://item.jd.com/100059484008.html", row.getProductLink());
        OrderProductLinkReader.resolve(browser, row, PriceCodes.JD, 5);
        verify(browser, times(1)).readLinkDestination(anyString(), any(), eq(5));
    }

    @Test
    void popupLoginNeverInventsAProductId() {
        Browser browser = mock(Browser.class);
        when(browser.<Boolean>executeScript(anyString(), any())).thenReturn(true);
        when(browser.readLinkDestination(anyString(), any(), anyInt()))
                .thenReturn("https://login.taobao.com/member/login.jhtml");
        PromotionOrderItem row = new PromotionOrderItem();
        row.setOrderNo("512771454149502550");
        OrderProductLinkReader.resolve(browser, row, PriceCodes.TB, 5);
        assertNull(row.getSkuId());
        assertEquals("PRODUCT_LOGIN_REQUIRED", row.getExtensions().get("productIdentityError"));
    }

    @Test
    void jdScreenshotFieldsRemainIndependentAndFifteenDigitOrdersSurvive() throws Exception {
        List<PromotionOrderItem> rows = parse(jd(), "<table class='el-table__body'><tbody>"
                + jdRow("3661467018074373", "已付款", "2", "0", "0", "--", "--")
                + jdRow("3656467016410282", "已完成", "1", "0", "0", "2026-10-20 10:19:15", "2026-11-20 00:00:00")
                + jdRow("365446701560385", "已完成", "1", "0", "0", "2026-10-17 15:36:26", "2026-11-20")
                + "</tbody></table>");
        assertEquals(3, rows.size());
        assertEquals("365446701560385", rows.get(2).getOrderNo());
        assertEquals(List.of("2", "1", "1"), rows.stream().map(PromotionOrderItem::getProductQuantity).toList());
        for (PromotionOrderItem row : rows) {
            assertEquals("0", row.getAfterSaleQuantity());
            assertEquals("0", row.getReturnQuantity());
            assertNotEquals("已失效", row.getOrderStatus());
            assertEquals("0", row.getPromotionPosition());
            assertEquals("100059484008", row.getSkuId());
            assertEquals("￥51.77", row.getEstimatedBillingAmount());
            assertEquals("￥2.59", row.getEstimatedCommission());
            assertEquals("￥48.00", row.getActualBillingAmount());
            assertEquals("￥2.40", row.getActualCommission());
        }
        assertEquals("2026-10-19 15:39:45", rows.get(1).getOrderTime());
        assertEquals("2026-10-20 10:19:15", rows.get(1).getFinishTime());
        assertEquals("2026-11-20", rows.get(1).getSettleTime());
        assertNull(rows.get(0).getFinishTime());
    }

    @Test
    void jdReturnsAndAfterSalesInvalidateIndependently() throws Exception {
        List<PromotionOrderItem> rows = parse(jd(), "<table><tbody>"
                + jdRow("365446701560385", "已完成", "1", "1", "0", "2026-10-19", "2026-11-20")
                + jdRow("365446701560386", "已完成", "2", "0", "1", "2026-10-19", "2026-11-20")
                + "</tbody></table>");
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(row -> "已完成".equals(row.getOrderStatus())));
        assertTrue(rows.stream().allMatch(row -> "INVALID".equals(row.getNormalizedStatus())));
        assertEquals("已完成", rows.get(0).getExtensions().get("rawOrderStatus"));
    }

    @Test
    void jdMissingSettlementDateUsesReceiptMonthAndMarksItsSource() throws Exception {
        PromotionOrderItem row = parse(jd(), "<table><tbody>"
                + jdRow("365446701560385", "已完成", "1", "0", "0", "2026-11-01 10:00:00", "--")
                + "</tbody></table>").get(0);
        assertEquals("2026-12-20", row.getSettleTime());
        assertEquals("receipt-next-month-20", row.getExtensions().get("settlementDateSource"));
    }

    @Test
    void tbScreenshotAmountsAndTimesAreSeparateAndSubsidyIsNotCommission() throws Exception {
        List<PromotionOrderItem> rows = parse(tb(), "<table><tbody><tr>"
                + td("<a href='https://item.taobao.com/item.htm?id=123456'>测试鞋</a>"
                + "<div>店铺名：测试店铺</div><div>父订单编号：512771454149502549</div>"
                + "<div>子订单编号：512771454149502550</div><div>付款金额：￥27.55</div>"
                + "<div>结算金额：￥20.00</div><div>2026-09-01 16:04:09付款</div>"
                + "<div>2026-09-11 16:52:30结算</div><div>商品数量：2</div>")
                + td("已结算") + td("7.19%")
                + td("付款预估佣金收入：￥1.98 付款预估补贴收入：￥0.21")
                + td("结算预估佣金收入：￥1.44 结算预估补贴收入：￥0.35")
                + "</tr></tbody></table>");
        assertEquals(1, rows.size());
        PromotionOrderItem row = rows.get(0);
        assertEquals("￥27.55", row.getEstimatedBillingAmount());
        assertEquals("￥20.00", row.getActualBillingAmount());
        assertEquals("￥1.98", row.getEstimatedCommission());
        assertEquals("￥1.44", row.getActualCommission());
        assertNull(row.getOrderTime());
        assertEquals("2026-09-01 16:04:09", row.getPaidTime());
        assertEquals("2026-09-11 16:52:30", row.getFinishTime());
        assertEquals("2026-10-20", row.getSettleTime());
        assertEquals("2", row.getProductQuantity());
        assertEquals("123456", row.getSkuId());
        assertEquals("512771454149502550", row.getOrderNo());
        assertEquals("512771454149502549", row.getExtensions().get("parentOrderNo"));
    }

    @Test
    void tbMissingActualAmountRemainsUnknownAndEncryptedProductIdIsNotGuessed() throws Exception {
        List<PromotionOrderItem> rows = parse(tb(), "<table><tbody><tr>"
                + td("<a href='https://item.taobao.com/item.htm?id=aEncrypted12'>测试商品</a>"
                + "<div>子订单编号：5127533714054026305</div><div>付款金额：￥14.08</div>"
                + "<div>2026-10-16 18:26:25付款</div>")
                + td("已付款") + td("2.98%")
                + td("付款预估佣金收入：￥0.42 付款预估补贴收入：￥0.01")
                + td("结算预估佣金收入：-- 结算预估补贴收入：￥0.00")
                + "</tr></tbody></table>");
        PromotionOrderItem row = rows.get(0);
        assertEquals("￥0.42", row.getEstimatedCommission());
        assertNull(row.getActualBillingAmount());
        assertNull(row.getActualCommission());
        assertNull(row.getFinishTime());
        assertTrue(row.getSettleTime() == null || row.getSettleTime().isBlank());
        assertNull(row.getSkuId());
    }

    private String jdRow(String id, String status, String qty, String after, String returned, String finish, String settle) {
        return "<tr>" + td("<a class='sku-name' href='https://item.jd.com/100059484008.html'>商品</a><span class='shop-name'>店铺</span>")
                + td(id) + td(status)
                + td("下单时间：2026-10-19 15:39:45 完成时间：" + finish + " 结算时间：" + settle)
                + td("￥51.77") + td("￥2.59") + td("5%") + td("100%") + td("￥48.00") + td("￥2.40")
                + td("商品数量：" + qty + " 售后数量：" + after + " 退货数量：" + returned)
                + td("推广位id：0 推广位名称： pid：") + "</tr>";
    }

    private String td(String value) { return "<td>" + value + "</td>"; }

    private JdUnionPromotionTask jd() {
        AppConfig config = new AppConfig();
        return new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ObjectMapper());
    }

    private TbPromotionOrdersTask tb() {
        AppConfig config = new AppConfig();
        return new TbPromotionOrdersTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private List<PromotionOrderItem> parse(Object task, String html) throws Exception {
        Method parser = task.getClass().getDeclaredMethod("parseOrderRowsFromHtml", String.class);
        parser.setAccessible(true);
        return (List<PromotionOrderItem>) parser.invoke(task, html);
    }
}
