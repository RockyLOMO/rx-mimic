package org.rx.crawler.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.rx.crawler.config.TaskApiAuthenticationFilter;
import org.rx.crawler.config.TaskApiProperties;
import org.rx.crawler.dto.Result;
import org.rx.crawler.task.common.*;
import org.rx.crawler.task.jd.*;
import org.rx.crawler.task.tb.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.Map;
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tasks")
public class AsyncTaskController {
    private final CustomCrawlQueueService queue;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final TaskApiProperties properties;
    private static final Map<String,Class<?>> REQUESTS = Map.ofEntries(
        Map.entry("jdSearchProduct",JdSearchRequest.class), Map.entry("jdProductDetail",JdProductDetailRequest.class),
        Map.entry("tbSearchProduct",TbSearchRequest.class),Map.entry("tbProductDetail",TbProductDetailRequest.class),
        Map.entry("jdUnionSearchProducts",JdUnionProductSearchRequest.class),Map.entry("tbUnionSearchProducts",TbUnionProductSearchRequest.class),
        Map.entry("getPromotionUrl",PromotionUrlRequest.class),Map.entry("getTbPromotionUrl",PromotionUrlRequest.class),
        Map.entry("getPromotionOrders",JdUnionPromotionOrdersRequest.class),Map.entry("getTbPromotionOrders",TbPromotionOrdersRequest.class),
        Map.entry("loginCheck",PromotionUrlRequest.class));
    @PostMapping
    public ResponseEntity<Result<AsyncTaskView>> submit(@Valid @RequestBody AsyncTaskSubmitRequest input,
            @RequestHeader(value="Idempotency-Key", required=false) String key,HttpServletRequest request) {
        Object params = validate(input);
        long id = queue.enqueueOwned(caller(request),input.getAction(),params,key);
        AsyncTaskView view = decorate(queue.queryOwned(id,caller(request)));
        return ResponseEntity.accepted().location(URI.create(view.getStatusUrl())).header("Retry-After","2").body(Result.success(view));
    }
    @GetMapping("/{taskId}")
    public ResponseEntity<Result<AsyncTaskView>> status(@PathVariable long taskId,HttpServletRequest request) {
        return ResponseEntity.ok().header("Retry-After","2").body(Result.success(decorate(queue.queryOwned(taskId,caller(request)))));
    }
    @GetMapping("/{taskId}/result")
    public Result<Object> result(@PathVariable long taskId,HttpServletRequest request) { return Result.success(queue.resultOwned(taskId,caller(request))); }
    @PostMapping("/{taskId}/cancel")
    public Result<AsyncTaskView> cancel(@PathVariable long taskId,HttpServletRequest request) { return Result.success(decorate(queue.cancelOwned(taskId,caller(request)))); }
    @PostMapping("/{taskId}/resume")
    public Result<AsyncTaskView> resume(@PathVariable long taskId,HttpServletRequest request) { return Result.success(decorate(queue.resumeOwned(taskId,caller(request)))); }
    private AsyncTaskView decorate(AsyncTaskView view) {
        view.setStatusUrl(properties.getPublicBasePath().replaceAll("/+$", "") + "/tasks/" + view.getTaskId()); return view;
    }
    private String caller(HttpServletRequest request) {
        Object caller = request.getAttribute(TaskApiAuthenticationFilter.CALLER_ATTRIBUTE);
        if (caller == null) throw new TaskApiException(401,"UNAUTHORIZED","UNAUTHORIZED");
        return caller.toString();
    }
    Object validate(AsyncTaskSubmitRequest input) {
        JsonNode node = mapper.valueToTree(input.getRequest());
        if ("getPromotionUrls".equals(input.getAction()) || "getTbPromotionUrls".equals(input.getAction())) {
            if (!node.isArray() || node.isEmpty() || node.size()>properties.getMaxBatchSize()) invalid("Invalid batch size");
            for (JsonNode item:node) if (!item.isTextual() || item.asText().isBlank() || item.asText().length()>500) invalid("Invalid keyword");
            return mapper.convertValue(node,java.util.List.class);
        }
        Class<?> type = REQUESTS.get(input.getAction());
        if (type == null || !node.isObject()) invalid("Unknown action or invalid request");
        // External callers cannot select profiles or local output/debug paths.
        for (String field:new String[]{"profileName","debugOutputDir","debugEnabled","forcePreflight","keepBrowserOpenOnLoginRequired"})
            if (node.has(field)) invalid("Worker managed field: " + field);
        if (input.getUseCache() != null && node.isObject() && !node.has("useCache")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("useCache", input.getUseCache());
        }
        Object value;
        try { value=mapper.treeToValue(node,type); }
        catch(Exception e) { throw new TaskApiException(400,"INVALID_REQUEST","Invalid action request"); }
        if (!"loginCheck".equals(input.getAction()) && !validator.validate(value).isEmpty()) invalid("Action request validation failed");
        return value;
    }
    private void invalid(String message) { throw new TaskApiException(400,"INVALID_REQUEST",message); }
    @ExceptionHandler(TaskApiException.class)
    public ResponseEntity<Result<Object>> error(TaskApiException exception) {
        ResponseEntity.BodyBuilder builder=ResponseEntity.status(exception.getStatus());
        if(exception.getStatus()==429 || exception.getStatus()==503) builder.header("Retry-After","5");
        return builder.body(Result.fail(exception.getCode(),exception.getMessage(),null));
    }
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Object>> invalidInput(Exception exception) {
        return ResponseEntity.badRequest().body(Result.fail("INVALID_REQUEST","Invalid request",null));
    }
}
