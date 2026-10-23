package org.rx.crawler.controller;

import lombok.RequiredArgsConstructor;
import org.rx.core.Strings;
import org.rx.crawler.dto.Result;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.CustomCrawlQueueService;
import org.rx.crawler.task.common.LoginKeepAliveResult;
import org.rx.crawler.task.common.LoginKeepAliveService;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.jd.JdUnionProductSearchRequest;
import org.rx.crawler.task.jd.JdUnionProductSearchResult;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersResult;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.tb.TbProductDetailRequest;
import org.rx.crawler.task.tb.TbProductDetailResult;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;
import org.rx.crawler.task.tb.TbUnionProductSearchRequest;
import org.rx.crawler.task.tb.TbUnionProductSearchResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.rx.crawler.task.common.PromotionOrderItem;
import org.rx.crawler.task.order.OrderPage;
import org.rx.crawler.task.order.OrderQueryFilter;
import org.rx.crawler.task.order.OrderRecord;
import org.rx.crawler.task.order.OrderItemRecord;
import org.rx.crawler.task.order.OrderStore;
import org.rx.crawler.task.order.OrderSyncState;
import org.springframework.beans.factory.annotation.Autowired;

@Validated
@RestController
@RequestMapping("/custom")
public class CustomCrawlController {
    private final CustomCrawlQueueService taskQueueService;
    private final LoginKeepAliveService loginKeepAliveService;
    @Autowired(required = false)
    private OrderStore orderStore;

    public CustomCrawlController(CustomCrawlQueueService taskQueueService, LoginKeepAliveService loginKeepAliveService) {
        this.taskQueueService = taskQueueService;
        this.loginKeepAliveService = loginKeepAliveService;
    }

    public CustomCrawlController(CustomCrawlQueueService taskQueueService, LoginKeepAliveService loginKeepAliveService, OrderStore orderStore) {
        this.taskQueueService = taskQueueService;
        this.loginKeepAliveService = loginKeepAliveService;
        this.orderStore = orderStore;
    }

    @PostMapping("/jd-union/searchProducts")
    public Result<JdUnionProductSearchResult> searchProducts(@Valid @RequestBody JdUnionProductSearchRequest request) {
        JdUnionProductSearchResult result = taskQueueService.submitAndWaitJdUnionSearch(request);
        return wrap(result);
    }

    @PostMapping("/jd-union/getPromotionUrl")
    public Result<PromotionUrlResult> getPromotionUrl(@Valid @RequestBody PromotionUrlRequest request) {
        PromotionUrlResult result = taskQueueService.submitAndWait("getPromotionUrl", request, PromotionUrlResult.class);
        return wrap(result);
    }

    @PostMapping("/jd-union/getPromotionUrls")
    public Result<List<PromotionUrlResult>> getPromotionUrls(@RequestBody List<String> keywords) {
        List<PromotionUrlResult> results = taskQueueService.submitAndWaitJdPromotionUrls(keywords);
        return Result.success(results);
    }

    @PostMapping("/jd-union/getPromotionOrders")
    public Result<JdUnionPromotionOrdersResult> getPromotionOrders(@Valid @RequestBody JdUnionPromotionOrdersRequest request) {
        if (orderStore != null) {
            OrderSyncState state = orderStore.getSyncState("jd-main");
            if (state != null && state.isInitialized()) {
                OrderQueryFilter filter = new OrderQueryFilter();
                filter.setAuthorizedSources(List.of("jd-main"));
                filter.setPlatform("jd");
                filter.setStartDate(request.getStartTime());
                filter.setEndDate(request.getEndTime());
                filter.setPage(1);
                filter.setPageSize(500);
                OrderPage page = orderStore.queryOrders(filter);
                JdUnionPromotionOrdersResult result = new JdUnionPromotionOrdersResult();
                result.setStatus(CustomCrawlStatus.SUCCESS);
                result.setTaskType("getPromotionOrders");
                result.setStartTime(request.getStartTime());
                result.setEndTime(request.getEndTime());
                result.setProfileName(request.getProfileName());
                result.setSourceId("jd-main");
                result.setComplete(true);
                result.setHasMore(false);
                result.setTotalCount((int) page.getTotal());
                List<PromotionOrderItem> items = new ArrayList<>();
                for (OrderRecord rec : page.getItems()) {
                    List<OrderItemRecord> orderItems = orderStore.getOrderItems(rec.getId());
                    for (OrderItemRecord row : orderItems) {
                        items.add(toPromotionOrderItem(rec, row));
                    }
                }
                result.setOrders(items);
                return Result.success(result);
            }
        }
        JdUnionPromotionOrdersResult result = taskQueueService.submitAndWaitOrders("getPromotionOrders", request);
        return wrap(result);
    }

    @PostMapping({"/tb/searchProducts", "/tb-union/searchProducts"})
    public Result<TbUnionProductSearchResult> searchTbProducts(@Valid @RequestBody TbUnionProductSearchRequest request) {
        TbUnionProductSearchResult result = taskQueueService.submitAndWaitTbUnionSearch(request);
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }

    @PostMapping({"/tb/getPromotionOrders", "/tb-union/getPromotionOrders"})
    public Result<TbPromotionOrdersResult> getTbPromotionOrders(@Valid @RequestBody TbPromotionOrdersRequest request) {
        if (orderStore != null) {
            OrderSyncState state = orderStore.getSyncState("tb-main");
            if (state != null && state.isInitialized()) {
                OrderQueryFilter filter = new OrderQueryFilter();
                filter.setAuthorizedSources(List.of("tb-main"));
                filter.setPlatform("tb");
                filter.setStartDate(request.getStartTime());
                filter.setEndDate(request.getEndTime());
                filter.setPage(1);
                filter.setPageSize(500);
                OrderPage page = orderStore.queryOrders(filter);
                TbPromotionOrdersResult result = new TbPromotionOrdersResult();
                result.setStatus(CustomCrawlStatus.SUCCESS);
                result.setTaskType("getTbPromotionOrders");
                result.setStartTime(request.getStartTime());
                result.setEndTime(request.getEndTime());
                result.setProfileName(request.getProfileName());
                result.setSourceId("tb-main");
                result.setComplete(true);
                result.setHasMore(false);
                result.setTotalCount((int) page.getTotal());
                List<PromotionOrderItem> items = new ArrayList<>();
                for (OrderRecord rec : page.getItems()) {
                    List<OrderItemRecord> orderItems = orderStore.getOrderItems(rec.getId());
                    for (OrderItemRecord row : orderItems) {
                        items.add(toPromotionOrderItem(rec, row));
                    }
                }
                result.setOrders(items);
                return Result.success(result);
            }
        }
        TbPromotionOrdersResult result = taskQueueService.submitAndWaitTbOrders("getTbPromotionOrders", request);
        return wrap(result);
    }

    private PromotionOrderItem toPromotionOrderItem(OrderRecord order, OrderItemRecord item) {
        PromotionOrderItem p = new PromotionOrderItem();
        p.setOrderNo(order.getPlatformOrderNo());
        p.setMainOrderNo(order.getMainOrderNo());
        p.setProductName(item.getProductName());
        p.setProductLink(item.getProductLink());
        p.setProductPrice(item.getProductPrice() != null ? item.getProductPrice().toPlainString() : "");
        p.setStoreName(item.getStoreName());
        p.setOrderStatus(item.getOrderStatus() != null ? item.getOrderStatus() : order.getOrderStatus());
        p.setOrderTime(order.getOrderTime());
        p.setFinishTime(order.getFinishTime());
        p.setSettleTime(order.getSettleTime());
        p.setQuantity(String.valueOf(item.getQuantity()));
        p.setProductQuantity(String.valueOf(item.getQuantity()));
        p.setEstimatedBillingAmount(item.getEstimatedBillingAmount() != null ? item.getEstimatedBillingAmount().toPlainString() : "");
        p.setEstimatedCommission(item.getEstimatedCommission() != null ? item.getEstimatedCommission().toPlainString() : "");
        p.setActualBillingAmount(item.getActualBillingAmount() != null ? item.getActualBillingAmount().toPlainString() : "");
        p.setActualCommission(item.getActualCommission() != null ? item.getActualCommission().toPlainString() : "");
        p.setCommissionRate(item.getCommissionRate());
        p.setSkuId(item.getSkuId());
        p.setSourceLineId(item.getSourceLineId());
        p.setSourceId(item.getSourceId());
        p.setRawJson(item.getRawJson());
        return p;
    }

    @PostMapping({"/tb/getPromotionUrl", "/tb-union/getPromotionUrl"})
    public Result<PromotionUrlResult> getTbPromotionUrl(@Valid @RequestBody PromotionUrlRequest request) {
        PromotionUrlResult result = taskQueueService.submitAndWaitTbPromotionUrl("getTbPromotionUrl", request);
        return wrap(result);
    }

    @PostMapping({"/tb/getPromotionUrls", "/tb-union/getPromotionUrls"})
    public Result<List<PromotionUrlResult>> getTbPromotionUrls(@RequestBody List<String> keywords) {
        List<PromotionUrlResult> results = taskQueueService.submitAndWaitTbPromotionUrls(keywords);
        return Result.success(results);
    }

    @PostMapping("/tb-union/product/detail")
    public Result<TbProductDetailResult> tbProductDetail(@Valid @RequestBody TbProductDetailRequest request) {
        TbProductDetailResult result = taskQueueService.submitAndWaitTbProductDetail("tbProductDetail", request);
        return wrap(result);
    }

    @PostMapping("/jd-union/login/check")
    public Result<PromotionUrlResult> loginCheck(@RequestBody(required = false) PromotionUrlRequest request) {
        PromotionUrlResult result = taskQueueService.submitAndWait("loginCheck", request, PromotionUrlResult.class);
        return wrap(result);
    }

    @PostMapping("/jd-union/login/keepAlive")
    public Result<LoginKeepAliveResult> jdLoginKeepAlive() {
        LoginKeepAliveResult result = loginKeepAliveService.checkJd();
        return wrap(result);
    }

    @PostMapping({"/tb/login/keepAlive", "/tb-union/login/keepAlive"})
    public Result<LoginKeepAliveResult> tbLoginKeepAlive() {
        LoginKeepAliveResult result = loginKeepAliveService.checkTb();
        return wrap(result);
    }

    @PostMapping("/profile/close")
    public Result<Boolean> closeProfile(@RequestParam(required = false) String profileName) {
        boolean closed = taskQueueService.closeProfile(Strings.isEmpty(profileName) ? "common" : profileName);
        return Result.success(closed);
    }

    private Result<PromotionUrlResult> wrap(PromotionUrlResult result) {
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }

    private Result<JdUnionProductSearchResult> wrap(JdUnionProductSearchResult result) {
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }

    private Result<JdUnionPromotionOrdersResult> wrap(JdUnionPromotionOrdersResult result) {
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }

    private Result<TbPromotionOrdersResult> wrap(TbPromotionOrdersResult result) {
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }

    private Result<LoginKeepAliveResult> wrap(LoginKeepAliveResult result) {
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }

    private Result<TbProductDetailResult> wrap(TbProductDetailResult result) {
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }
}
