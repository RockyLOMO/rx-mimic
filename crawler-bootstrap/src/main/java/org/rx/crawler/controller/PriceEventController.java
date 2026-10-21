package org.rx.crawler.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.rx.crawler.config.TaskApiAuthenticationFilter;
import org.rx.crawler.dto.Result;
import org.rx.crawler.task.common.TaskApiException;
import org.rx.crawler.task.price.PriceWatchStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/price-events")
public class PriceEventController {
    private final PriceWatchStore store;
    private final ObjectMapper mapper;

    @GetMapping
    public Result<PriceEventsResponse> listEvents(
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            HttpServletRequest request,
            HttpServletResponse response) {
        String caller = caller(request);

        Long afterId = null;
        if (after != null && !after.isBlank()) {
            try {
                afterId = Long.parseLong(after.trim());
            } catch (NumberFormatException e) {
                throw new TaskApiException(400, "INVALID_CURSOR", "Cursor must be a valid integer: " + after);
            }
        }

        if (afterId != null && afterId > 0) {
            Long minId = store.getMinEventId();
            if (minId != null && afterId < minId) {
                response.setStatus(410);
                throw new TaskApiException(410, "CURSOR_EXPIRED",
                        "Requested cursor has expired; earliest available cursor is " + minId);
            }
        }

        int actualLimit = Math.min(200, Math.max(1, limit));
        List<PriceWatchStore.EventRow> rows = store.listEvents(caller, afterId, actualLimit + 1);

        boolean hasMore = rows.size() > actualLimit;
        List<PriceWatchStore.EventRow> page = hasMore ? rows.subList(0, actualLimit) : rows;

        List<Object> eventList = new ArrayList<Object>();
        for (PriceWatchStore.EventRow row : page) {
            try {
                JsonNode node = mapper.readTree(row.getPayload());
                eventList.add(node);
            } catch (Exception e) {
                eventList.add(row.getPayload());
            }
        }

        String nextCursor = page.isEmpty()
                ? (after != null && !after.isBlank() ? after.trim() : null)
                : String.valueOf(page.get(page.size() - 1).getId());

        return Result.success(new PriceEventsResponse(eventList, nextCursor, hasMore));
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
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceEventsResponse {
        private List<Object> events;
        private String nextCursor;
        private boolean hasMore;
    }
}
