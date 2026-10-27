package org.rx.crawler.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.rx.crawler.config.TaskApiAuthenticationFilter;
import org.rx.crawler.dto.Result;
import org.rx.crawler.task.common.TaskApiException;
import org.rx.crawler.task.price.PriceWatchCoordinator;
import org.rx.crawler.task.price.PriceWatchStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/price-watches")
public class PriceWatchController {
    private final PriceWatchCoordinator coordinator;

    @GetMapping
    public Result<List<Map<String, Object>>> list(HttpServletRequest request) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (PriceWatchStore.WatchRow watch : coordinator.list(caller(request))) {
            rows.add(view(watch));
        }
        return Result.success(rows);
    }

    @GetMapping("/budget")
    public Result<Map<String, Object>> budget() {
        return Result.success(coordinator.visits(Instant.now()));
    }

    @PostMapping
    public Result<PriceWatchCoordinator.Command> follow(@RequestBody FollowRequest body, HttpServletRequest request) {
        return done(coordinator.follow(caller(request), body.getPlatform(), body.getProductId(),
                body.getTargetPrice(), body.getTier()));
    }

    @PostMapping("/{id}/pause")
    public Result<PriceWatchCoordinator.Command> pause(@PathVariable long id, HttpServletRequest request) {
        return done(coordinator.pause(caller(request), id));
    }

    @PostMapping("/{id}/resume")
    public Result<PriceWatchCoordinator.Command> resume(@PathVariable long id, HttpServletRequest request) {
        return done(coordinator.resume(caller(request), id));
    }

    @PostMapping("/{id}/refresh")
    public Result<PriceWatchCoordinator.Command> refresh(@PathVariable long id, HttpServletRequest request) {
        return done(coordinator.manualRefresh(caller(request), id));
    }

    @GetMapping("/{id}/snapshots")
    public Result<List<Map<String, Object>>> snapshots(@PathVariable long id, HttpServletRequest request,
            @RequestParam(defaultValue = "30") int days) {
        List<PriceWatchStore.SnapshotRow> rows = coordinator.snapshots(caller(request), id, days);
        if (rows == null) {
            throw new TaskApiException(404, "WATCH_NOT_FOUND", "WATCH_NOT_FOUND");
        }
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (PriceWatchStore.SnapshotRow row : rows) {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("id", row.getId());
            view.put("rawPrice", row.getRawPrice());
            view.put("priceAmount", row.getPriceAmount());
            view.put("sourceField", row.getSourceField());
            view.put("priceKind", row.getPriceKind());
            view.put("comparable", row.isComparable());
            view.put("observedAt", row.getObservedAt().toInstant().toString());
            view.put("fetchStatus", row.getFetchStatus());
            view.put("qualityReason", row.getQualityReason());
            views.add(view);
        }
        return Result.success(views);
    }

    private Result<PriceWatchCoordinator.Command> done(PriceWatchCoordinator.Command command) {
        if (!command.isOk()) {
            throw new TaskApiException(status(command.getCode()), command.getCode(), command.getMessage());
        }
        return Result.success(command);
    }

    private Map<String, Object> view(PriceWatchStore.WatchRow watch) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", watch.getId());
        row.put("platform", watch.getPlatform() == 1 ? "jd" : watch.getPlatform() == 2 ? "tb" : String.valueOf(watch.getPlatform()));
        row.put("productId", watch.getPlatformProductId());

        row.put("title", watch.getTitle());
        row.put("shopName", watch.getShopName());

        row.put("status", watch.getStatus());
        row.put("tier", watch.getTier());
        row.put("targetPrice", watch.getTargetPrice());
        row.put("baselineSnapshotId", watch.getBaselineSnapshotId());
        row.put("lastSuccessAt", watch.getLastSuccessAt());
        row.put("lastAttemptAt", watch.getLastAttemptAt());
        row.put("currentTaskId", watch.getCurrentTaskId());
        row.put("monitoringEnabled", coordinator.monitoringEnabled());
        row.put("nextCheckAt", watch.getNextCheckAt());
        row.put("pauseReason", watch.getPauseReason());
        row.put("productUrl", watch.getResolvedUrl());
        return row;
    }

    private int status(String code) {
        if ("WATCH_NOT_FOUND".equals(code) || "PRODUCT_NOT_FOUND".equals(code)) {
            return 404;
        }
        if ("DAILY_VISIT_LIMIT".equals(code) || "RISK_STOP".equals(code)) {
            return 429;
        }
        if ("INVALID_REQUEST".equals(code)) {
            return 400;
        }
        return 409;
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

    @Data
    public static class FollowRequest {
        private String platform;
        private String productId;
        private BigDecimal targetPrice;
        private Integer tier;
    }
}
