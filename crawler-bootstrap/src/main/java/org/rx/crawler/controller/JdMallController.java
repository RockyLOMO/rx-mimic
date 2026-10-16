package org.rx.crawler.controller;

import lombok.RequiredArgsConstructor;
import org.rx.crawler.dto.Result;
import org.rx.crawler.task.common.CustomCrawlQueueService;
import org.rx.crawler.task.jd.JdProductDetailRequest;
import org.rx.crawler.task.jd.JdSearchRequest;
import org.rx.crawler.task.jd.JdTaskQueryResult;
import org.rx.crawler.task.jd.JdTaskSubmitResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * 京东商城（主站）搜索 / 商品详情抓取：异步 API。
 * 提交即返回 taskId，浏览器后台执行，轮询 GET /custom/jd/task/{taskId} 取结果。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/custom/jd")
public class JdMallController {
    private final CustomCrawlQueueService taskQueueService;

    @PostMapping("/search")
    public Result<JdTaskSubmitResult> search(@Valid @RequestBody JdSearchRequest request) {
        return submit("jdSearchProduct", request);
    }

    @PostMapping("/product/detail")
    public Result<JdTaskSubmitResult> productDetail(@Valid @RequestBody JdProductDetailRequest request) {
        return submit("jdProductDetail", request);
    }

    @GetMapping("/task/{taskId}")
    public Result<JdTaskQueryResult> task(@PathVariable("taskId") long taskId) {
        JdTaskQueryResult status = taskQueueService.query(taskId);
        if (status == null) {
            return Result.fail("NOT_FOUND", "Task not found: " + taskId, null);
        }
        return Result.success(status);
    }

    /**
     * 取消排队中的任务：仅 PENDING 可取消（置为 FAILED，error_message=cancelled）；
     * RUNNING / 已终结任务不受影响。
     */
    @PostMapping("/task/{taskId}/cancel")
    public Result<JdTaskQueryResult> cancelTask(@PathVariable("taskId") long taskId) {
        boolean cancelled = taskQueueService.cancelPending(taskId);
        if (!cancelled) {
            JdTaskQueryResult status = taskQueueService.query(taskId);
            String state = status == null ? "NOT_FOUND" : status.getStatus();
            return Result.fail("NOT_PENDING", "Task " + taskId + " not cancellable, status=" + state, null);
        }
        return Result.success(taskQueueService.query(taskId));
    }

    private Result<JdTaskSubmitResult> submit(String action, Object request) {
        long taskId = taskQueueService.submit(action, request);
        JdTaskSubmitResult data = new JdTaskSubmitResult();
        data.setTaskId(taskId);
        data.setAction(action);
        data.setStatus("PENDING");
        return Result.success(data);
    }
}
