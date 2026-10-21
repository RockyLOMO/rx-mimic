package org.rx.crawler.task.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.TabPrefetch;
import org.rx.crawler.config.TaskApiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;
import org.rx.crawler.task.price.PriceTaskResult;
import org.rx.crawler.task.price.PriceWatchCoordinator;
import org.rx.crawler.task.jd.JdMallTask;
import org.rx.crawler.task.jd.JdProductDetailRequest;
import org.rx.crawler.task.jd.JdProductDetailResult;
import org.rx.crawler.task.jd.JdSearchRequest;
import org.rx.crawler.task.jd.JdSearchResult;
import org.rx.crawler.task.jd.JdTaskQueryResult;
import org.rx.crawler.task.jd.JdUnionProductSearchRequest;
import org.rx.crawler.task.jd.JdUnionProductSearchResult;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersResult;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.rx.crawler.task.tb.TbMallTask;
import org.rx.crawler.task.tb.TbProductDetailRequest;
import org.rx.crawler.task.tb.TbProductDetailResult;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;
import org.rx.crawler.task.tb.TbPromotionOrdersTask;
import org.rx.crawler.task.tb.TbPromotionUrlTask;
import org.rx.crawler.task.tb.TbSearchRequest;
import org.rx.crawler.task.tb.TbSearchResult;
import org.rx.crawler.task.tb.TbUnionProductSearchRequest;
import org.rx.crawler.task.tb.TbUnionProductSearchResult;
import org.rx.io.EntityDatabase;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomCrawlQueueService {
    private static final String TABLE_NAME = "custom_crawl_task_queue";
    public static final int FOREGROUND_PRIORITY = 100;
    public static final int BACKGROUND_PRIORITY = 10;
    public static final int BACKGROUND_AGING_PRIORITY = 40;

    private final EntityDatabase entityDatabase;
    private final ObjectMapper objectMapper;
    private final AppConfig appConfig;
    private final JdUnionPromotionTask jdUnionPromotionTask;
    private final JdMallTask jdMallTask;
    private final TbPromotionOrdersTask tbPromotionOrdersTask;
    private final TbPromotionUrlTask tbPromotionUrlTask;
    private final TbMallTask tbMallTask;
    private final AtomicInteger runningCount = new AtomicInteger();
    private volatile boolean initialized;
    private PriceWatchCoordinator priceWatch;
    @Autowired private TaskApiProperties taskApiProperties;
    @Autowired private BrowserOwnerExecutor ownerExecutor;
    private volatile BooleanSupplier executionGate = () -> true;
    private volatile boolean draining;
    private boolean ownsOwnerExecutor;
    @Autowired(required = false)
    public void setPriceWatch(@org.springframework.context.annotation.Lazy PriceWatchCoordinator priceWatch) {
        this.priceWatch = priceWatch;
    }

    public boolean hasPendingTasks() { return count("select count(*) from " + TABLE_NAME + " where status='PENDING' and batch_parent=false") > 0; }
    public boolean hasForegroundWork() {
        return count("select count(*) from " + TABLE_NAME + " where batch_parent=false and status in ('PENDING','RUNNING','WAITING_USER','CANCEL_REQUESTED') and priority>=" + FOREGROUND_PRIORITY) > 0;
    }
    public int countPendingPriceTasks() {
        return (int) count("select count(*) from " + TABLE_NAME + " where batch_parent=false and status in ('PENDING','RUNNING') and task_action in ('priceRefresh','resolveProductIdentity')");
    }
    public String taskStatus(long id) {
        TaskSnapshot snapshot = loadSnapshot(id);
        return snapshot == null ? null : snapshot.status.name();
    }
    public void deferTask(long id, String reason) {
        update("update " + TABLE_NAME + " set status='CANCELLED', error_code=?, business_status='DEFERRED', finished_at=current_timestamp, expires_at=?, updated_at=current_timestamp where id=? and status='PENDING'",
                reason, new Timestamp(System.currentTimeMillis() + limits().getRetentionSeconds() * 1000), id);
    }
    public boolean hasWaitingUser() { return count("select count(*) from " + TABLE_NAME + " where status='WAITING_USER' and batch_parent=false") > 0; }
    public void setExecutionGate(BooleanSupplier gate) { executionGate = gate; }
    public boolean isDraining() { return draining; }
    public void setDraining(boolean value) { draining = value; }
    public boolean isIdle() { return runningCount.get() == 0; }
    public int getRunningCount() { return runningCount.get(); }
    public boolean isDatabaseAvailable() {
        try { count("select count(*) from " + TABLE_NAME); return true; }
        catch (Exception e) { return false; }
    }
    private TaskApiProperties limits() {
        return taskApiProperties == null ? new TaskApiProperties() : taskApiProperties;
    }

    @PostConstruct
    public void init() {
        if (ownerExecutor == null) { ownerExecutor = new BrowserOwnerExecutor(); ownsOwnerExecutor = true; }
        entityDatabase.executeUpdate("create table if not exists " + TABLE_NAME + " (" +
                "id bigint auto_increment primary key," +
                "task_action varchar(64) not null," +
                "request_json clob not null," +
                "result_json clob," +
                "status varchar(16) not null," +
                "priority int not null," +
                "error_message varchar(2000)," +
                "created_at timestamp not null," +
                "updated_at timestamp not null," +
                "started_at timestamp," +
                "finished_at timestamp" +
                ")");
        entityDatabase.executeUpdate("create index if not exists idx_" + TABLE_NAME + "_status_priority on " + TABLE_NAME + "(status, priority, id)");
        // Additive migration retains records. Older binaries still need compatible state enums to read this database.
        for (String column : new String[]{"owner_id varchar(128)", "idempotency_key varchar(128)",
                "request_digest varchar(64)", "business_status varchar(64)", "error_code varchar(64)",
                "parent_task_id bigint", "batch_parent boolean default false", "queue_deadline timestamp", "execution_deadline timestamp", "user_deadline timestamp", "expires_at timestamp"}) {
            entityDatabase.executeUpdate("alter table " + TABLE_NAME + " add column if not exists " + column);
        }
        entityDatabase.executeUpdate("create unique index if not exists idx_crawl_idempotency on " + TABLE_NAME + "(owner_id, task_action, idempotency_key)");
        entityDatabase.executeUpdate("update " + TABLE_NAME + " set priority=" + FOREGROUND_PRIORITY
                + " where priority=0 and status in ('PENDING','RUNNING','WAITING_USER','CANCEL_REQUESTED')"
                + " and task_action not in ('priceRefresh','resolveProductIdentity')");
        // A lost browser page cannot be resumed after restart; never replay an uncertain side effect.
        entityDatabase.executeUpdate("update " + TABLE_NAME + " set status='FAILED', result_json=null, business_status=null, error_code='PROCESS_RESTARTED', error_message='Execution interrupted by process restart; explicit retry required', finished_at=current_timestamp, expires_at=dateadd('SECOND', " + limits().getRetentionSeconds() + ", current_timestamp), updated_at=current_timestamp where batch_parent=false and status in ('RUNNING','CANCEL_REQUESTED','WAITING_USER')");
        initialized = true;
    }

    @PreDestroy
    public void destroy() {
        draining = true;
        if (ownsOwnerExecutor) ownerExecutor.shutdown();
        initialized = false;
    }

    @Scheduled(fixedDelay = 1000)
    public void refreshQueue() {
        expireTasks();
        dispatch();
    }

    public PromotionUrlResult submitAndWait(String action, PromotionUrlRequest request, Class<PromotionUrlResult> resultType) {
        long taskId = enqueue(action, request, 0);
        return waitResult(taskId, resultType);
    }

    public JdUnionPromotionOrdersResult submitAndWaitOrders(String action, JdUnionPromotionOrdersRequest request) {
        long taskId = enqueue(action, request, 0);
        return waitResult(taskId, JdUnionPromotionOrdersResult.class);
    }

    public TbPromotionOrdersResult submitAndWaitTbOrders(String action, TbPromotionOrdersRequest request) {
        long taskId = enqueue(action, request, 0);
        return waitResult(taskId, TbPromotionOrdersResult.class);
    }

    public PromotionUrlResult submitAndWaitTbPromotionUrl(String action, PromotionUrlRequest request) {
        long taskId = enqueue(action, request, 0);
        return waitResult(taskId, PromotionUrlResult.class);
    }

    public TbProductDetailResult submitAndWaitTbProductDetail(String action, TbProductDetailRequest request) {
        long taskId = enqueue(action, request, 0);
        return waitResult(taskId, TbProductDetailResult.class);
    }

    public JdUnionProductSearchResult submitAndWaitJdUnionSearch(JdUnionProductSearchRequest request) {
        long taskId = enqueue("jdUnionSearchProducts", request, 0);
        return waitResult(taskId, JdUnionProductSearchResult.class);
    }

    public TbUnionProductSearchResult submitAndWaitTbUnionSearch(TbUnionProductSearchRequest request) {
        long taskId = enqueue("tbUnionSearchProducts", request, 0);
        return waitResult(taskId, TbUnionProductSearchResult.class);
    }

    public List<PromotionUrlResult> submitAndWaitJdPromotionUrls(List<String> keywords) {
        long taskId = enqueue("getPromotionUrls", keywords, 0);
        return waitPromotionUrlResults(taskId);
    }

    public List<PromotionUrlResult> submitAndWaitTbPromotionUrls(List<String> keywords) {
        long taskId = enqueue("getTbPromotionUrls", keywords, 0);
        return waitPromotionUrlResults(taskId);
    }

    /**
     * 异步提交：入队即返回 taskId，不等待执行结果；用 {@link #query(long)} 轮询。
     */
    public long submit(String action, Object request) {
        return enqueue(action, request, 0);
    }

    /**
     * 异步任务查询：返回队列状态；SUCCESS 时 result 反序列化为 action 对应的结果对象，
     * FAILED 时构造对应类型的失败对象。任务不存在返回 null。
     */
    public JdTaskQueryResult query(long taskId) {
        TaskSnapshot snapshot = loadSnapshot(taskId);
        if (snapshot == null) {
            return null;
        }
        JdTaskQueryResult queryResult = new JdTaskQueryResult();
        queryResult.setTaskId(snapshot.id);
        queryResult.setAction(snapshot.action);
        queryResult.setStatus(snapshot.status == CustomTaskQueueStatus.PENDING ? "PENDING" : snapshot.status.isTerminal()
                ? (snapshot.status == CustomTaskQueueStatus.SUCCESS ? "SUCCESS" : "FAILED") : "RUNNING");
        queryResult.setErrorMessage(snapshot.errorMessage);
        queryResult.setCreatedAt(snapshot.createdAt);
        queryResult.setStartedAt(snapshot.startedAt);
        queryResult.setFinishedAt(snapshot.finishedAt);
        try {
            if (snapshot.status == CustomTaskQueueStatus.SUCCESS) {
                Class<?> resultType = resultTypeOf(snapshot.action);
                queryResult.setResult(resultType != null
                        ? objectMapper.readValue(snapshot.resultJson, resultType)
                        : objectMapper.readValue(snapshot.resultJson, Object.class));
            } else if (snapshot.status.isTerminal() && snapshot.status != CustomTaskQueueStatus.SUCCESS) {
                queryResult.setResult(failedQueryResult(snapshot));
            }
        } catch (Exception e) {
            log.warn("Query queued task result parse fail, taskId={}, action={}, error={}",
                    snapshot.id, snapshot.action, e.getMessage());
        }
        return queryResult;
    }

    private Class<?> resultTypeOf(String action) {
        if (action == null) {
            return null;
        }
        switch (action) {
            case "jdSearchProduct":
                return JdSearchResult.class;
            case "jdUnionSearchProducts":
                return JdUnionProductSearchResult.class;
            case "jdProductDetail":
                return JdProductDetailResult.class;
            case "getPromotionUrl":
            case "loginCheck":
            case "getTbPromotionUrl":
                return PromotionUrlResult.class;
            case "getPromotionOrders":
                return JdUnionPromotionOrdersResult.class;
            case "getTbPromotionOrders":
                return TbPromotionOrdersResult.class;
            case "tbProductDetail":
                return TbProductDetailResult.class;
            case "tbUnionSearchProducts":
                return TbUnionProductSearchResult.class;
            case "tbSearchProduct":
                return TbSearchResult.class;
            case "priceRefresh":
            case "resolveProductIdentity":
                return PriceTaskResult.class;
            default:
                return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object failedQueryResult(TaskSnapshot snapshot) {
        Class<?> resultType = resultTypeOf(snapshot.action);
        Class resultClass = resultType != null ? resultType : PromotionUrlResult.class;
        return failedResult(snapshot, resultClass);
    }

    public boolean closeProfile(String profileName) {
        return ownerExecutor.call(() -> jdUnionPromotionTask.closeProfile(profileName)
                | jdMallTask.closeProfile(profileName) | tbPromotionOrdersTask.closeProfile(profileName)
                | tbPromotionUrlTask.closeProfile(profileName) | tbMallTask.closeProfile(profileName));
    }

    /**
     * 取消排队任务：PENDING → FAILED（error_message=cancelled）。
     * 已开跑（RUNNING）或已终结（SUCCESS/FAILED）的任务不动，返回 false。
     */
    public boolean cancelPending(long taskId) {
        return update("update " + TABLE_NAME + " set status=?, error_code='CANCELLED', error_message='cancelled', finished_at=current_timestamp, expires_at=?, updated_at=current_timestamp where id=? and status='PENDING'",
                CustomTaskQueueStatus.CANCELLED.name(), new Timestamp(System.currentTimeMillis() + limits().getRetentionSeconds() * 1000), taskId) > 0;
    }

    private long enqueue(String action, Object request, int priority) {
        return enqueueOwned("legacy", action, request, null);
    }

    /** Single-instance admission lock also covers the legacy entry points. */
    public synchronized long enqueueOwned(String owner, String action, Object request, String idempotencyKey) {
        if (!ACTIONS.contains(action)) throw apiError(400, "UNKNOWN_ACTION");
        return enqueueAt(owner, action, request, idempotencyKey, FOREGROUND_PRIORITY);
    }

    public synchronized long enqueueBackground(String owner, String action, Object request, String idempotencyKey, int priority) {
        if (!"priceRefresh".equals(action) && !"resolveProductIdentity".equals(action)) throw apiError(400, "UNKNOWN_ACTION");
        int stored = Math.max(BACKGROUND_PRIORITY, Math.min(priority, FOREGROUND_PRIORITY));
        return enqueueAt(owner, action, request, idempotencyKey, stored);
    }

    private synchronized long enqueueAt(String owner, String action, Object request, String idempotencyKey, int priority) {
        if (owner == null || owner.isBlank() || owner.length() > 128) throw apiError(400, "INVALID_CALLER");
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 128)) throw apiError(400, "INVALID_IDEMPOTENCY_KEY");
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            String digest = digest(objectMapper.readTree(requestJson));
            if (idempotencyKey != null) {
                List<TaskSnapshot> matches = querySnapshots("select * from " + TABLE_NAME + " where owner_id=? and task_action=? and idempotency_key=?", owner, action, idempotencyKey);
                if (!matches.isEmpty()) {
                    TaskSnapshot old = matches.get(0);
                    if (!digest.equals(old.requestDigest)) throw apiError(409, "IDEMPOTENCY_CONFLICT");
                    return old.id;
                }
            }
            if (draining) throw apiError(503, "DRAINING");
            expireTasks();
            String active = "status in ('PENDING','RUNNING','WAITING_USER','CANCEL_REQUESTED')";
            boolean batch = !"legacy".equals(owner) && ("getPromotionUrls".equals(action) || "getTbPromotionUrls".equals(action));
            int required = batch ? objectMapper.readTree(requestJson).size() + 1 : 1;
            if (count("select count(*) from " + TABLE_NAME + " where " + active) + required > limits().getCapacity()
                    || count("select count(*) from " + TABLE_NAME + " where " + active + " and owner_id=?", owner) + required > limits().getPerCallerCapacity())
                throw apiError(429, "QUEUE_FULL");
            Timestamp now = new Timestamp(System.currentTimeMillis());
            Timestamp deadline = new Timestamp(now.getTime() + limits().getQueueWaitSeconds() * 1000);
            long id = entityDatabase.transInvoke(java.sql.Connection.TRANSACTION_SERIALIZABLE, () -> {
                long parentId = insertTask("insert into " + TABLE_NAME +
                    "(task_action,request_json,status,priority,created_at,updated_at,owner_id,idempotency_key,request_digest,queue_deadline,batch_parent) values (?,?,?,?,?,?,?,?,?,?,?)",
                    action, requestJson, batch ? "RUNNING" : "PENDING", priority, now, now, owner, idempotencyKey, digest, batch ? null : deadline, batch);
                if (batch) {
                    for (JsonNode keyword : objectMapper.readTree(requestJson)) {
                        insertTask("insert into " + TABLE_NAME +
                            "(task_action,request_json,status,priority,created_at,updated_at,owner_id,queue_deadline,parent_task_id,batch_parent) values (?,?,?,?,?,?,?,?,?,false)",
                            action, objectMapper.writeValueAsString(List.of(keyword.asText())), "PENDING", priority, now, now, owner, deadline, parentId);
                    }
                }
                return parentId;
            });
            dispatch();
            return id;
        } catch (TaskApiException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Enqueue custom crawl task failed", e); }
    }

    static final Set<String> ACTIONS = Set.of("loginCheck", "getPromotionUrl", "getPromotionUrls", "getPromotionOrders",
            "getTbPromotionOrders", "getTbPromotionUrls", "getTbPromotionUrl", "jdUnionSearchProducts", "jdSearchProduct",
            "jdProductDetail", "tbProductDetail", "tbUnionSearchProducts", "tbSearchProduct");

    private String digest(JsonNode node) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(node).getBytes(StandardCharsets.UTF_8)));
    }
    private String canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String,String> fields = new TreeMap<>();
            node.fields().forEachRemaining(e -> fields.put(e.getKey(), canonical(e.getValue())));
            return fields.entrySet().stream().map(e -> {
                try { return objectMapper.writeValueAsString(e.getKey()) + ":" + e.getValue(); }
                catch (Exception ex) { throw new IllegalStateException(ex); }
            }).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (node.isArray()) {
            List<String> items = new ArrayList<>(); node.forEach(item -> items.add(canonical(item)));
            return String.join(",", items).transform(value -> "[" + value + "]");
        }
        return node.toString();
    }

    private TaskApiException apiError(int status, String code) { return new TaskApiException(status, code, code); }

    private TaskSnapshot owned(long id, String owner) {
        expireTasks();
        aggregateBatches();
        TaskSnapshot snapshot = loadSnapshot(id);
        if (snapshot == null || !owner.equals(snapshot.ownerId)) throw apiError(404, "TASK_NOT_FOUND");
        return snapshot;
    }

    public AsyncTaskView queryOwned(long id, String owner) { return view(owned(id, owner)); }

    private AsyncTaskView view(TaskSnapshot snapshot) {
        AsyncTaskView view = new AsyncTaskView();
        view.setTaskId(Long.toString(snapshot.id)); view.setAction(snapshot.action);
        view.setStatus(snapshot.status.externalName()); view.setStage(snapshot.status.externalName());
        view.setBusinessStatus(snapshot.businessStatus); view.setErrorCode(snapshot.errorCode);
        view.setStatusUrl(limits().getPublicBasePath().replaceAll("/+$", "") + "/tasks/" + snapshot.id);
        if (snapshot.batchParent) view.setChildTaskIds(children(snapshot.id).stream().map(child -> Long.toString(child.id)).toList());
        view.setCreatedAt(snapshot.createdAt); view.setStartedAt(snapshot.startedAt);
        view.setFinishedAt(snapshot.finishedAt); view.setExpiresAt(snapshot.expiresAt);
        return view;
    }

    public Object resultOwned(long id, String owner) {
        TaskSnapshot snapshot = owned(id, owner);
        if (!snapshot.isFinished()) throw apiError(409, "TASK_NOT_FINISHED");
        if (snapshot.expiresAt != null && snapshot.expiresAt.getTime() <= System.currentTimeMillis()) throw apiError(410, "RESULT_EXPIRED");
        try { return snapshot.resultJson == null ? Map.of("status", snapshot.status.externalName(), "errorCode", snapshot.errorCode == null ? "TASK_FAILED" : snapshot.errorCode)
                : publicResult(objectMapper.readTree(snapshot.resultJson)); }
        catch (TaskApiException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Read queued task result failed", e); }
    }

    /** Platform DTO fields are retained, local artifacts and cookies stay on the worker. */
    private JsonNode publicResult(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode)node;
            List<String> remove = new ArrayList<>();
            obj.fields().forEachRemaining(e -> {
                String key = e.getKey().toLowerCase(java.util.Locale.ROOT);
                if (key.contains("cookie") || key.contains("path") || key.contains("directory") || key.endsWith("dir")
                        || key.equals("message") || key.equals("diagnostics") || key.equals("profilename") || key.equals("currenturl") || key.contains("screenshot") || key.contains("debug")) remove.add(e.getKey());
                else publicResult(e.getValue());
            });
            remove.forEach(obj::remove);
        } else if (node.isArray()) node.forEach(this::publicResult);
        return node;
    }

    public synchronized AsyncTaskView cancelOwned(long id, String owner) {
        TaskSnapshot snapshot = owned(id, owner);
        if (snapshot.batchParent) {
            for (TaskSnapshot child:children(id)) cancelOwned(child.id,owner);
            aggregateBatches(); return view(loadSnapshot(id));
        }
        if (snapshot.status == CustomTaskQueueStatus.PENDING || snapshot.status == CustomTaskQueueStatus.WAITING_USER) {
            if (snapshot.status == CustomTaskQueueStatus.WAITING_USER) releaseWaitingProfile();
            finish(id, "CANCELLED", null, "CANCELLED", null);
        } else if (snapshot.status == CustomTaskQueueStatus.RUNNING) {
            update("update " + TABLE_NAME + " set status='CANCEL_REQUESTED', updated_at=current_timestamp where id=? and status='RUNNING'", id);
        }
        return view(loadSnapshot(id));
    }

    public synchronized AsyncTaskView resumeOwned(long id, String owner) {
        TaskSnapshot snapshot = owned(id, owner);
        if (snapshot.status != CustomTaskQueueStatus.WAITING_USER) throw apiError(409, "TASK_NOT_WAITING_USER");
        if (snapshot.batchParent) {
            for (TaskSnapshot child:children(id)) if(child.status == CustomTaskQueueStatus.WAITING_USER) resumeOwned(child.id,owner);
            aggregateBatches(); return view(loadSnapshot(id));
        }
        // Re-run the original action through its real preflight/login checks on the owner thread.
        update("update " + TABLE_NAME + " set status='PENDING', result_json=null, business_status=null, started_at=null, execution_deadline=null, queue_deadline=?, updated_at=current_timestamp where id=? and status='WAITING_USER'",
                new Timestamp(System.currentTimeMillis() + limits().getQueueWaitSeconds() * 1000), id);
        dispatch();
        return view(loadSnapshot(id));
    }

    private synchronized void expireTasks() {
        if (count("select count(*) from " + TABLE_NAME + " where status='WAITING_USER' and batch_parent=false and user_deadline < current_timestamp") > 0)
            releaseWaitingProfile();
        update("update " + TABLE_NAME + " set status='TIMED_OUT', error_code='QUEUE_TIMEOUT', finished_at=current_timestamp, expires_at=?, updated_at=current_timestamp where batch_parent=false and status='PENDING' and queue_deadline < current_timestamp",
                new Timestamp(System.currentTimeMillis() + limits().getRetentionSeconds() * 1000));
        update("update " + TABLE_NAME + " set status='TIMED_OUT', error_code='USER_TIMEOUT', finished_at=current_timestamp, expires_at=?, updated_at=current_timestamp where batch_parent=false and status='WAITING_USER' and user_deadline < current_timestamp",
                new Timestamp(System.currentTimeMillis() + limits().getRetentionSeconds() * 1000));
        aggregateBatches();
        update("update " + TABLE_NAME + " set result_json=null where expires_at < current_timestamp and result_json is not null");
    }

    private void releaseWaitingProfile() {
        if (ownerExecutor != null) ownerExecutor.execute(() -> {
            try { closeProfile("common"); } catch (Exception e) { log.warn("Release waiting profile failed: {}",e.getClass().getSimpleName()); }
        });
    }

    private List<TaskSnapshot> children(long parentId) {
        return querySnapshots("select * from " + TABLE_NAME + " where parent_task_id=? order by id",parentId);
    }

    private void aggregateBatches() {
        for (TaskSnapshot parent : querySnapshots("select * from " + TABLE_NAME + " where batch_parent=true and status in ('RUNNING','WAITING_USER','CANCEL_REQUESTED')")) {
            List<TaskSnapshot> children = children(parent.id);
            if (children.isEmpty()) continue;
            if (children.stream().allMatch(TaskSnapshot::isFinished)) {
                List<Object> results = new ArrayList<>();
                for (TaskSnapshot child:children) {
                    Map<String,Object> item=new java.util.LinkedHashMap<>();
                    item.put("taskId",Long.toString(child.id));item.put("status",child.status.externalName());
                    item.put("businessStatus",child.businessStatus);
                    try { if(child.resultJson != null)item.put("result",objectMapper.readTree(child.resultJson)); }
                    catch(Exception e) { item.put("errorCode","RESULT_PARSE_FAILED"); }
                    if(child.errorCode != null)item.put("errorCode",child.errorCode);
                    results.add(item);
                }
                String status = children.stream().allMatch(c -> c.status == CustomTaskQueueStatus.CANCELLED) ? "CANCELLED" :
                    children.stream().allMatch(c -> c.status == CustomTaskQueueStatus.SUCCESS) ? "SUCCESS" : "FAILED";
                try { finish(parent.id,status,objectMapper.writeValueAsString(results),"SUCCESS".equals(status) ? null : "BATCH_PARTIAL_OR_FAILED",null); }
                catch(Exception e) { throw new IllegalStateException("Aggregate batch failed",e); }
            } else {
                String status = children.stream().anyMatch(c -> c.status == CustomTaskQueueStatus.WAITING_USER) ? "WAITING_USER" :
                    children.stream().anyMatch(c -> c.status == CustomTaskQueueStatus.CANCEL_REQUESTED) ? "CANCEL_REQUESTED" : "RUNNING";
                update("update " + TABLE_NAME + " set status=?, updated_at=current_timestamp where id=?",status,parent.id);
            }
        }
    }

    private void finish(long id, String status, String resultJson, String errorCode, String businessStatus) {
        update("update " + TABLE_NAME + " set status=case when status='CANCEL_REQUESTED' then 'CANCELLED' else ? end, result_json=?, error_code=case when status='CANCEL_REQUESTED' then 'CANCELLED' else ? end, business_status=?, finished_at=current_timestamp, expires_at=?, updated_at=current_timestamp where id=?",
                status, resultJson, errorCode, businessStatus,
                new Timestamp(System.currentTimeMillis() + limits().getRetentionSeconds() * 1000), id);
    }

    private long count(String sql, Object... args) {
        return entityDatabase.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                try (ResultSet rs = statement.executeQuery()) { rs.next(); return rs.getLong(1); }
            }
        });
    }

    private <T> T waitResult(long taskId, Class<T> resultType) {
        long deadline = System.currentTimeMillis() + java.util.concurrent.TimeUnit.SECONDS.toMillis(appConfig.getCustom().getQueueTimeoutSeconds());
        while (System.currentTimeMillis() <= deadline) {
            TaskSnapshot snapshot = loadSnapshot(taskId);
            if (snapshot != null && snapshot.isFinished()) {
                if (snapshot.status.isTerminal() && snapshot.status != CustomTaskQueueStatus.SUCCESS) {
                    return failedResult(snapshot, resultType);
                }
                try {
                    return objectMapper.readValue(snapshot.resultJson, resultType);
                } catch (Exception e) {
                    throw new IllegalStateException("Read queued task result failed", e);
                }
            }
            Extends.sleep(300);
            dispatch();
        }
        throw new IllegalStateException("Queued custom crawl task timeout, taskId=" + taskId);
    }

    private List<PromotionUrlResult> waitPromotionUrlResults(long taskId) {
        long deadline = System.currentTimeMillis() + java.util.concurrent.TimeUnit.SECONDS.toMillis(appConfig.getCustom().getQueueTimeoutSeconds());
        while (System.currentTimeMillis() <= deadline) {
            TaskSnapshot snapshot = loadSnapshot(taskId);
            if (snapshot != null && snapshot.isFinished()) {
                if (snapshot.status.isTerminal() && snapshot.status != CustomTaskQueueStatus.SUCCESS) {
                    List<PromotionUrlResult> results = new ArrayList<PromotionUrlResult>();
                    results.add(failedResult(snapshot, PromotionUrlResult.class));
                    return results;
                }
                try {
                    return objectMapper.readValue(snapshot.resultJson,
                            new TypeReference<List<PromotionUrlResult>>() {
                            });
                } catch (Exception e) {
                    throw new IllegalStateException("Read queued batch task result failed", e);
                }
            }
            Extends.sleep(300);
            dispatch();
        }
        throw new IllegalStateException("Queued custom crawl batch task timeout, taskId=" + taskId);
    }

    private <T> T failedResult(TaskSnapshot snapshot, Class<T> resultType) {
        if (JdUnionPromotionOrdersResult.class.equals(resultType)) {
            JdUnionPromotionOrdersResult result = new JdUnionPromotionOrdersResult();
            result.setTaskType(snapshot.action);
            result.setStatus(CustomCrawlStatus.FAILED);
            result.setMessage(snapshot.errorMessage);
            return resultType.cast(result);
        }
        if (TbPromotionOrdersResult.class.equals(resultType)) {
            TbPromotionOrdersResult result = new TbPromotionOrdersResult();
            result.setTaskType(snapshot.action);
            result.setStatus(CustomCrawlStatus.FAILED);
            result.setMessage(snapshot.errorMessage);
            return resultType.cast(result);
        }
        if (JdSearchResult.class.equals(resultType)) {
            JdSearchResult result = new JdSearchResult();
            result.setTaskType(snapshot.action);
            result.setStatus(CustomCrawlStatus.FAILED);
            result.setMessage(snapshot.errorMessage);
            return resultType.cast(result);
        }
        if (JdUnionProductSearchResult.class.equals(resultType)) {
            JdUnionProductSearchResult result = new JdUnionProductSearchResult();
            result.setTaskType(snapshot.action);
            result.setStatus(CustomCrawlStatus.FAILED);
            result.setMessage(snapshot.errorMessage);
            return resultType.cast(result);
        }
        if (JdProductDetailResult.class.equals(resultType)) {
            JdProductDetailResult result = new JdProductDetailResult();
            result.setTaskType(snapshot.action);
            result.setStatus(CustomCrawlStatus.FAILED);
            result.setMessage(snapshot.errorMessage);
            return resultType.cast(result);
        }
        if (TbProductDetailResult.class.equals(resultType)) {
            TbProductDetailResult result = new TbProductDetailResult();
            result.setTaskType(snapshot.action);
            result.setStatus(CustomCrawlStatus.FAILED);
            result.setMessage(snapshot.errorMessage);
            return resultType.cast(result);
        }
        if (TbUnionProductSearchResult.class.equals(resultType)) {
            TbUnionProductSearchResult result = new TbUnionProductSearchResult();
            result.setTaskType(snapshot.action);
            result.setStatus(CustomCrawlStatus.FAILED);
            result.setMessage(snapshot.errorMessage);
            return resultType.cast(result);
        }
        if (TbSearchResult.class.equals(resultType)) {
            TbSearchResult result = new TbSearchResult();
            result.setTaskType(snapshot.action);
            result.setStatus(CustomCrawlStatus.FAILED);
            result.setMessage(snapshot.errorMessage);
            return resultType.cast(result);
        }
        if (PriceTaskResult.class.equals(resultType)) {
            PriceTaskResult result = new PriceTaskResult();
            result.setStatus(CustomCrawlStatus.FAILED);
            result.setMessage(snapshot.errorMessage);
            return resultType.cast(result);
        }
        PromotionUrlResult result = new PromotionUrlResult();
        result.setTaskType(snapshot.action);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setMessage(snapshot.errorMessage);
        return resultType.cast(result);
    }

    private synchronized void dispatch() {
        if (!initialized || draining || !executionGate.getAsBoolean()) {
            return;
        }
        while (runningCount.get() < 1) {
            TaskSnapshot snapshot = claimNext();
            if (snapshot == null) {
                return;
            }
            runningCount.incrementAndGet();
            ownerExecutor.execute(() -> {
                try {
                    TaskSnapshot current = loadSnapshot(snapshot.id);
                    if (current.status == CustomTaskQueueStatus.CANCEL_REQUESTED) {
                        finish(snapshot.id,"CANCELLED",null,"CANCELLED",null); return;
                    }
                    if(current.status.isTerminal()) return;
                    if (draining || !executionGate.getAsBoolean()) {
                        update("update " + TABLE_NAME + " set status='PENDING', started_at=null, execution_deadline=null, updated_at=current_timestamp where id=? and status='RUNNING'", snapshot.id);
                        return;
                    }
                    execute(snapshot);
                } finally {
                    runningCount.decrementAndGet();
                }
            });
        }
    }

    private synchronized TaskSnapshot claimNext() {
        if (count("select count(*) from " + TABLE_NAME + " where batch_parent=false and status='WAITING_USER'") > 0) return null;
        for (int guard = 0; guard < 8; guard++) {
            List<TaskSnapshot> items = querySnapshots("select * from " + TABLE_NAME +
                    " where batch_parent=false and status='PENDING' order by priority desc, id asc limit 1");
            if (items.isEmpty()) {
                return null;
            }
            TaskSnapshot snapshot = items.get(0);
            if (priceAction(snapshot.action) && priceWatch != null && !priceWatch.allowBackground(snapshot.requestJson)) {
                String reason = priceWatch.deferReason(snapshot.requestJson);
                deferTask(snapshot.id, reason == null ? "DEFERRED" : reason);
                try {
                    priceWatch.onDeferred(snapshot.requestJson);
                } catch (RuntimeException e) {
                    log.warn("Defer price task failed, id={}, error={}", snapshot.id, e.getMessage());
                }
                continue;
            }
            long seconds = snapshot.action.contains("Orders") ? limits().getOrdersExecutionSeconds() : limits().getExecutionSeconds();
            int updated = update("update " + TABLE_NAME + " set status=?, started_at=current_timestamp, execution_deadline=?, updated_at=current_timestamp where id=? and status='PENDING'",
                    CustomTaskQueueStatus.RUNNING.name(), new Timestamp(System.currentTimeMillis() + seconds * 1000), snapshot.id);
            if (updated > 0) {
                return snapshot;
            }
        }
        return null;
    }

    private static boolean priceAction(String action) {
        return "priceRefresh".equals(action) || "resolveProductIdentity".equals(action);
    }

    private static PriceTaskResult priceUnavailable() {
        PriceTaskResult result = new PriceTaskResult();
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setMessage("PRICE_WATCH_UNAVAILABLE");
        return result;
    }

    private TaskSnapshot loadSnapshot(long taskId) {
        List<TaskSnapshot> items = querySnapshots("select * from " + TABLE_NAME + " where id=?", taskId);
        return items.isEmpty() ? null : items.get(0);
    }

    private void execute(TaskSnapshot snapshot) {
        armTabPrefetch(snapshot);
        try {
            Object result;
            if ("loginCheck".equals(snapshot.action)) {
                PromotionUrlRequest request = objectMapper.readValue(snapshot.requestJson, PromotionUrlRequest.class);
                result = jdUnionPromotionTask.loginCheck(request);
            } else if ("getPromotionUrls".equals(snapshot.action)) {
                List<String> keywords = objectMapper.readValue(snapshot.requestJson,
                        new TypeReference<List<String>>() {
                        });
                result = jdUnionPromotionTask.getPromotionUrls(keywords);
            } else if ("getPromotionOrders".equals(snapshot.action)) {
                JdUnionPromotionOrdersRequest request = objectMapper.readValue(snapshot.requestJson, JdUnionPromotionOrdersRequest.class);
                result = jdUnionPromotionTask.getPromotionOrders(request);
            } else if ("getTbPromotionOrders".equals(snapshot.action)) {
                TbPromotionOrdersRequest request = objectMapper.readValue(snapshot.requestJson, TbPromotionOrdersRequest.class);
                result = tbPromotionOrdersTask.getPromotionOrders(request);
            } else if ("getTbPromotionUrls".equals(snapshot.action)) {
                List<String> keywords = objectMapper.readValue(snapshot.requestJson,
                        new TypeReference<List<String>>() {
                        });
                result = tbPromotionUrlTask.getPromotionUrls(keywords);
            } else if ("getTbPromotionUrl".equals(snapshot.action)) {
                PromotionUrlRequest request = objectMapper.readValue(snapshot.requestJson, PromotionUrlRequest.class);
                result = tbPromotionUrlTask.getPromotionUrl(request);
            } else if ("jdUnionSearchProducts".equals(snapshot.action)) {
                JdUnionProductSearchRequest request = objectMapper.readValue(snapshot.requestJson, JdUnionProductSearchRequest.class);
                result = jdUnionPromotionTask.searchProducts(request);
            } else if ("jdSearchProduct".equals(snapshot.action)) {
                JdSearchRequest request = objectMapper.readValue(snapshot.requestJson, JdSearchRequest.class);
                result = jdMallTask.execute(request);
            } else if ("jdProductDetail".equals(snapshot.action)) {
                JdProductDetailRequest request = objectMapper.readValue(snapshot.requestJson, JdProductDetailRequest.class);
                result = jdMallTask.productDetail(request);
            } else if ("tbProductDetail".equals(snapshot.action)) {
                TbProductDetailRequest request = objectMapper.readValue(snapshot.requestJson, TbProductDetailRequest.class);
                result = tbMallTask.productDetail(request);
            } else if ("tbUnionSearchProducts".equals(snapshot.action)) {
                TbUnionProductSearchRequest request = objectMapper.readValue(snapshot.requestJson, TbUnionProductSearchRequest.class);
                result = tbPromotionUrlTask.searchProducts(request);
            } else if ("tbSearchProduct".equals(snapshot.action)) {
                TbSearchRequest request = objectMapper.readValue(snapshot.requestJson, TbSearchRequest.class);
                result = tbMallTask.search(request);
            } else if ("getPromotionUrl".equals(snapshot.action)) {
                PromotionUrlRequest request = objectMapper.readValue(snapshot.requestJson, PromotionUrlRequest.class);
                result = jdUnionPromotionTask.getPromotionUrl(request);
            } else if ("priceRefresh".equals(snapshot.action)) {
                result = priceWatch == null ? priceUnavailable() : priceWatch.refresh(snapshot.requestJson, snapshot.id);
            } else if ("resolveProductIdentity".equals(snapshot.action)) {
                result = priceWatch == null ? priceUnavailable() : priceWatch.resolve(snapshot.requestJson, snapshot.id);
            } else { throw new IllegalArgumentException("Unsupported action"); }
            TaskSnapshot current = loadSnapshot(snapshot.id);
            String json = objectMapper.writeValueAsString(result);
            String business = businessStatus(objectMapper.readTree(json));
            if (current.status == CustomTaskQueueStatus.CANCEL_REQUESTED) finish(snapshot.id, "CANCELLED", null, "CANCELLED", business);
            else if (current.executionDeadline != null && current.executionDeadline.getTime() < System.currentTimeMillis())
                finish(snapshot.id, "TIMED_OUT", json, "EXECUTION_TIMEOUT", business);
            else if ("LOGIN_REQUIRED".equals(business)) {
                int updated = update("update " + TABLE_NAME + " set status='WAITING_USER', result_json=?, business_status=?, user_deadline=coalesce(user_deadline, ?), updated_at=current_timestamp where id=? and status='RUNNING'",
                        json, business, new Timestamp(System.currentTimeMillis() + limits().getUserWaitSeconds() * 1000), snapshot.id);
                if(updated == 0) finish(snapshot.id,"CANCELLED",null,"CANCELLED",business);
            } else finish(snapshot.id, "TIMEOUT".equals(business) ? "TIMED_OUT" :
                    ("SUCCESS".equals(business) || "NOT_FOUND".equals(business) ? "SUCCESS" : "FAILED"),
                    json, "SUCCESS".equals(business) || "NOT_FOUND".equals(business) ? null : "BUSINESS_FAILED", business);
            if (priceWatch != null && "SUCCESS".equals(business)) {
                try {
                    priceWatch.onFinished(snapshot.action, snapshot.ownerId, snapshot.id, snapshot.requestJson, json);
                } catch (Exception ingestError) {
                    log.warn("Price watch ingest failed, taskId={}, action={}, error={}",
                            snapshot.id, snapshot.action, ingestError.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Execute queued custom crawl task failed, id={}, action={}, error={}", snapshot.id, snapshot.action, e.getMessage(), e);
            TaskSnapshot current = loadSnapshot(snapshot.id);
            finish(snapshot.id, current.status == CustomTaskQueueStatus.CANCEL_REQUESTED ? "CANCELLED" : "FAILED", null,
                    current.status == CustomTaskQueueStatus.CANCEL_REQUESTED ? "CANCELLED" : "EXECUTION_FAILED", null);
            update("update " + TABLE_NAME + " set error_message=? where id=?", truncate(e.getMessage(), 2000), snapshot.id);
        } finally {
            TabPrefetch.clear();
        }
    }

    /**
     * 当前任务开始前登记起始页，并看一眼还在排队的下一条。不领取下一条。
     * 浏览器仍是一个，标签默认两个：后台只打开下一条的起始页。
     */
    private void armTabPrefetch(TaskSnapshot snapshot) {
        try {
            int slots = 1;
            if (appConfig.getCustom() != null && appConfig.getCustom().getChrome() != null) {
                slots = appConfig.getCustom().getChrome().getTabSlots();
            }
            if (slots < 2) {
                TabPrefetch.clear();
                return;
            }
            String current = TaskEntryUrls.resolve(snapshot.action, snapshot.requestJson, appConfig, objectMapper);
            String next = null;
            List<TaskSnapshot> queued = querySnapshots("select * from " + TABLE_NAME
                    + " where batch_parent=false and status='PENDING' and id<>? order by priority desc, id asc limit 1",
                    snapshot.id);
            if (!queued.isEmpty()) {
                TaskSnapshot follower = queued.get(0);
                next = TaskEntryUrls.resolve(follower.action, follower.requestJson, appConfig, objectMapper);
            }
            TabPrefetch.arm(current, next);
            log.info("Tab prefetch armed, slots={}, current={}, next={}",
                    slots, TabPrefetch.brief(current), TabPrefetch.brief(next));
        } catch (Exception e) {
            log.info("Tab prefetch arm skipped, error={}", e.getMessage());
            TabPrefetch.clear();
        }
    }

    static String businessStatus(JsonNode result) {
        if (!result.isArray()) return result.path("status").asText("FAILED");
        String status = "SUCCESS";
        for (JsonNode item : result) {
            String current = item.path("status").asText("FAILED");
            if ("LOGIN_REQUIRED".equals(current)) return current;
            if (!"SUCCESS".equals(current) && !"NOT_FOUND".equals(current)) status = current;
        }
        return status;
    }

    private static class TaskSnapshot {
        private final long id;
        private final String action;
        private final String requestJson;
        private final CustomTaskQueueStatus status;
        private final int priority;
        private final String errorMessage;
        private final String resultJson;
        private final Timestamp createdAt;
        private final Timestamp startedAt;
        private final Timestamp finishedAt;
        private String ownerId;
        private String requestDigest;
        private String businessStatus;
        private String errorCode;
        private Timestamp expiresAt;
        private Timestamp executionDeadline;
        private boolean batchParent;

        TaskSnapshot(long id, String action, String requestJson, CustomTaskQueueStatus status, int priority,
                String errorMessage, String resultJson, Timestamp createdAt, Timestamp startedAt, Timestamp finishedAt) {
            this.id = id;
            this.action = action;
            this.requestJson = requestJson;
            this.status = status;
            this.priority = priority;
            this.errorMessage = errorMessage;
            this.resultJson = resultJson;
            this.createdAt = createdAt;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
        }

        boolean isFinished() {
            return status.isTerminal();
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private long insertTask(String sql, Object... args) {
        return entityDatabase.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, args);
                int updated = statement.executeUpdate();
                if (updated <= 0) {
                    throw new IllegalStateException("Insert task queue row failed");
                }
                try (ResultSet rs = statement.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
                throw new IllegalStateException("Insert task queue row generated id missing");
            }
        });
    }

    private int update(String sql, Object... args) {
        return entityDatabase.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                return statement.executeUpdate();
            }
        });
    }

    private List<TaskSnapshot> querySnapshots(String sql, Object... args) {
        return entityDatabase.withConnection(connection -> {
            List<TaskSnapshot> items = new ArrayList<TaskSnapshot>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        TaskSnapshot item = new TaskSnapshot(
                                rs.getLong("id"),
                                rs.getString("task_action"),
                                rs.getString("request_json"),
                                CustomTaskQueueStatus.valueOf(rs.getString("status")),
                                rs.getInt("priority"),
                                rs.getString("error_message"),
                                rs.getString("result_json"),
                                rs.getTimestamp("created_at"),
                                rs.getTimestamp("started_at"),
                                rs.getTimestamp("finished_at"));
                        item.batchParent = rs.getBoolean("batch_parent");
                        item.ownerId = rs.getString("owner_id");
                        item.requestDigest = rs.getString("request_digest");
                        item.businessStatus = rs.getString("business_status");
                        item.errorCode = rs.getString("error_code");
                        item.expiresAt = rs.getTimestamp("expires_at");
                        item.executionDeadline = rs.getTimestamp("execution_deadline");
                        items.add(item);
                    }
                }
            }
            return items;
        });
    }

    private void bind(PreparedStatement statement, Object... args) throws java.sql.SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }
}
