package org.rx.crawler.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.rx.crawler.config.TaskApiAuthenticationFilter;
import org.rx.crawler.dto.Result;
import org.rx.crawler.task.common.ClientCashbackQueueStore;
import org.rx.crawler.task.common.TaskApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cashback/queue")
public class CashbackQueueController {
    private final ClientCashbackQueueStore store;

    @GetMapping
    public Result<JsonNode> getQueue(
            @RequestHeader(value = "X-Device-Id", required = false) String headerDeviceId,
            @RequestParam(value = "deviceId", required = false) String queryDeviceId,
            HttpServletRequest request) {
        String caller = caller(request);
        String deviceId = resolveDeviceId(headerDeviceId, queryDeviceId);
        return Result.success(store.loadQueue(caller, deviceId));
    }

    @PutMapping
    public Result<JsonNode> saveQueue(
            @RequestHeader(value = "X-Device-Id", required = false) String headerDeviceId,
            @RequestParam(value = "deviceId", required = false) String queryDeviceId,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        String caller = caller(request);
        String deviceId = resolveDeviceId(headerDeviceId, queryDeviceId);
        return Result.success(store.saveQueue(caller, deviceId, body));
    }

    private static String resolveDeviceId(String headerDeviceId, String queryDeviceId) {
        if (headerDeviceId != null && !headerDeviceId.isBlank()) {
            return headerDeviceId;
        }
        return queryDeviceId;
    }

    private static String caller(HttpServletRequest request) {
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

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Object>> invalidInput(Exception exception) {
        return ResponseEntity.badRequest().body(Result.fail("INVALID_REQUEST", "Invalid request", null));
    }
}
