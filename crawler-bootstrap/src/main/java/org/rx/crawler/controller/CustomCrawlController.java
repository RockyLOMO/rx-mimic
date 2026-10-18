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
import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/custom")
public class CustomCrawlController {
    private final CustomCrawlQueueService taskQueueService;
    private final LoginKeepAliveService loginKeepAliveService;

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
        TbPromotionOrdersResult result = taskQueueService.submitAndWaitTbOrders("getTbPromotionOrders", request);
        return wrap(result);
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

    @PostMapping("/tb/product/detail")
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
