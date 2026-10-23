package org.rx.crawler.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.config.TaskApiAuthenticationFilter;
import org.rx.crawler.dto.Result;
import org.rx.crawler.task.common.TaskApiException;
import org.rx.crawler.task.order.OrderPage;
import org.rx.crawler.task.order.OrderQueryFilter;
import org.rx.crawler.task.order.OrderRecord;
import org.rx.crawler.task.order.OrderStore;
import org.rx.crawler.task.order.OrderSyncCoordinator;
import org.rx.crawler.task.order.OrderSyncState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class OrderController {
    private final OrderStore orderStore;
    private final OrderSyncCoordinator coordinator;
    private final AppConfig appConfig;

    @GetMapping("/orders")
    public Result<OrderPage> listOrders(
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "sourceId", required = false) String sourceId,
            @RequestParam(name = "startDate", required = false) String startDate,
            @RequestParam(name = "endDate", required = false) String endDate,
            @RequestParam(name = "orderNo", required = false) String orderNo,
            @RequestParam(name = "orderStatus", required = false) String orderStatus,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "50") int pageSize,
            HttpServletRequest request) {
        String caller = caller(request);
        List<String> authorizedSources = resolveAuthorizedSources(caller);

        if (sourceId != null && !sourceId.isBlank()) {
            if (!authorizedSources.contains(sourceId.trim())) {
                throw new TaskApiException(403, "FORBIDDEN", "Not authorized for data source: " + sourceId);
            }
        }

        OrderQueryFilter filter = new OrderQueryFilter();
        filter.setAuthorizedSources(authorizedSources);
        filter.setPlatform(platform);
        filter.setSourceId(sourceId);
        filter.setStartDate(startDate);
        filter.setEndDate(endDate);
        filter.setOrderNo(orderNo);
        filter.setOrderStatus(orderStatus);
        filter.setPage(page);
        filter.setPageSize(pageSize);

        OrderPage result = orderStore.queryOrders(filter);
        return Result.success(result);
    }

    @GetMapping("/orders/{id}")
    public Result<OrderRecord> getOrder(@PathVariable("id") long id, HttpServletRequest request) {
        String caller = caller(request);
        OrderRecord order = orderStore.getOrderWithItems(id);
        if (order == null) {
            throw new TaskApiException(404, "NOT_FOUND", "Order not found with id: " + id);
        }

        List<String> authorizedSources = resolveAuthorizedSources(caller);
        if (!authorizedSources.contains(order.getSourceId())) {
            throw new TaskApiException(403, "FORBIDDEN", "Not authorized for data source: " + order.getSourceId());
        }

        return Result.success(order);
    }

    @GetMapping("/order-sync/status")
    public Result<List<OrderSyncState>> getSyncStatus(
            @RequestParam(name = "sourceId", required = false) String sourceId,
            HttpServletRequest request) {
        String caller = caller(request);
        List<String> authorizedSources = resolveAuthorizedSources(caller);

        if (sourceId != null && !sourceId.isBlank()) {
            if (!authorizedSources.contains(sourceId.trim())) {
                throw new TaskApiException(403, "FORBIDDEN", "Not authorized for data source: " + sourceId);
            }
            OrderSyncState state = orderStore.getSyncState(sourceId.trim());
            return Result.success(state != null ? List.of(state) : Collections.emptyList());
        }

        List<OrderSyncState> states = orderStore.listSyncStates();
        List<OrderSyncState> filtered = new ArrayList<>();
        for (OrderSyncState s : states) {
            if (authorizedSources.contains(s.getSourceId())) {
                filtered.add(s);
            }
        }
        return Result.success(filtered);
    }

    private List<String> resolveAuthorizedSources(String caller) {
        List<AppConfig.OrderSourceConfig> configured = coordinator.resolveSources();
        List<String> allowed = new ArrayList<>();
        for (AppConfig.OrderSourceConfig src : configured) {
            if (src.allowsCaller(caller)) {
                allowed.add(src.getId());
            }
        }
        return allowed;
    }

    private String caller(HttpServletRequest request) {
        Object caller = request.getAttribute(TaskApiAuthenticationFilter.CALLER_ATTRIBUTE);
        if (caller == null) {
            throw new TaskApiException(401, "UNAUTHORIZED", "UNAUTHORIZED");
        }
        return caller.toString();
    }

    @ExceptionHandler(TaskApiException.class)
    public ResponseEntity<Result<Object>> error(TaskApiException exception) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(exception.getStatus());
        if (exception.getStatus() == 429 || exception.getStatus() == 503) {
            builder.header("Retry-After", "5");
        }
        return builder.body(Result.fail(exception.getCode(), exception.getMessage(), null));
    }
}
