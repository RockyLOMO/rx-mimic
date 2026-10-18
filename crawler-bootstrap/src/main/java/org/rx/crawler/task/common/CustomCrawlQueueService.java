package org.rx.crawler.task.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.crawler.config.AppConfig;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomCrawlQueueService {
    private static final String TABLE_NAME = "custom_crawl_task_queue";

    private final EntityDatabase entityDatabase;
    private final ObjectMapper objectMapper;
    private final AppConfig appConfig;
    private final JdUnionPromotionTask jdUnionPromotionTask;
    private final JdMallTask jdMallTask;
    private final TbPromotionOrdersTask tbPromotionOrdersTask;
    private final TbPromotionUrlTask tbPromotionUrlTask;
    private final TbMallTask tbMallTask;
    private final AtomicInteger runningCount = new AtomicInteger();
    private ExecutorService executor;

    @PostConstruct
    public void init() {
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
        entityDatabase.executeUpdate("update " + TABLE_NAME + " set status='PENDING', updated_at=current_timestamp where status='RUNNING'");
        executor = Executors.newFixedThreadPool(Math.max(1, appConfig.getCustom().getQueueMaxConcurrency()));
    }

    @PreDestroy
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void refreshQueue() {
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
        queryResult.setStatus(snapshot.status.name());
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
            } else if (snapshot.status == CustomTaskQueueStatus.FAILED) {
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
        return jdUnionPromotionTask.closeProfile(profileName)
                || jdMallTask.closeProfile(profileName)
                || tbPromotionOrdersTask.closeProfile(profileName)
                || tbPromotionUrlTask.closeProfile(profileName)
                || tbMallTask.closeProfile(profileName);
    }

    /**
     * 取消排队任务：PENDING → FAILED（error_message=cancelled）。
     * 已开跑（RUNNING）或已终结（SUCCESS/FAILED）的任务不动，返回 false。
     */
    public boolean cancelPending(long taskId) {
        return update("update " + TABLE_NAME + " set status=?, error_message='cancelled', finished_at=current_timestamp, updated_at=current_timestamp where id=? and status='PENDING'",
                CustomTaskQueueStatus.FAILED.name(), taskId) > 0;
    }

    private long enqueue(String action, Object request, int priority) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            Timestamp now = new Timestamp(System.currentTimeMillis());
            long id = insertTask("insert into " + TABLE_NAME +
                            "(task_action, request_json, status, priority, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
                    action, requestJson, CustomTaskQueueStatus.PENDING.name(), priority, now, now);
            dispatch();
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("Enqueue custom crawl task failed", e);
        }
    }

    private <T> T waitResult(long taskId, Class<T> resultType) {
        long deadline = System.currentTimeMillis() + java.util.concurrent.TimeUnit.SECONDS.toMillis(appConfig.getCustom().getQueueTimeoutSeconds());
        while (System.currentTimeMillis() <= deadline) {
            TaskSnapshot snapshot = loadSnapshot(taskId);
            if (snapshot != null && snapshot.isFinished()) {
                if (snapshot.status == CustomTaskQueueStatus.FAILED) {
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
                if (snapshot.status == CustomTaskQueueStatus.FAILED) {
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
        PromotionUrlResult result = new PromotionUrlResult();
        result.setTaskType(snapshot.action);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setMessage(snapshot.errorMessage);
        return resultType.cast(result);
    }

    private void dispatch() {
        if (executor == null) {
            return;
        }
        while (runningCount.get() < Math.max(1, appConfig.getCustom().getQueueMaxConcurrency())) {
            TaskSnapshot snapshot = claimNext();
            if (snapshot == null) {
                return;
            }
            runningCount.incrementAndGet();
            executor.submit(() -> {
                try {
                    execute(snapshot);
                } finally {
                    runningCount.decrementAndGet();
                }
            });
        }
    }

    private synchronized TaskSnapshot claimNext() {
        List<TaskSnapshot> items = querySnapshots("select id, task_action, request_json, status, priority, error_message, result_json, created_at, started_at, finished_at from " + TABLE_NAME +
                " where status='PENDING' order by priority desc, id asc limit 1");
        if (items.isEmpty()) {
            return null;
        }
        TaskSnapshot snapshot = items.get(0);
        int updated = update("update " + TABLE_NAME + " set status=?, started_at=current_timestamp, updated_at=current_timestamp where id=? and status='PENDING'",
                CustomTaskQueueStatus.RUNNING.name(), snapshot.id);
        return updated > 0 ? snapshot : null;
    }

    private TaskSnapshot loadSnapshot(long taskId) {
        List<TaskSnapshot> items = querySnapshots("select id, task_action, request_json, status, priority, error_message, result_json, created_at, started_at, finished_at from " + TABLE_NAME + " where id=?", taskId);
        return items.isEmpty() ? null : items.get(0);
    }

    private void execute(TaskSnapshot snapshot) {
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
            } else {
                PromotionUrlRequest request = objectMapper.readValue(snapshot.requestJson, PromotionUrlRequest.class);
                result = jdUnionPromotionTask.getPromotionUrl(request);
            }
            update("update " + TABLE_NAME + " set status=?, result_json=?, error_message=null, finished_at=current_timestamp, updated_at=current_timestamp where id=?",
                    CustomTaskQueueStatus.SUCCESS.name(), objectMapper.writeValueAsString(result), snapshot.id);
        } catch (Exception e) {
            log.warn("Execute queued custom crawl task failed, id={}, action={}, error={}", snapshot.id, snapshot.action, e.getMessage(), e);
            update("update " + TABLE_NAME + " set status=?, error_message=?, finished_at=current_timestamp, updated_at=current_timestamp where id=?",
                    CustomTaskQueueStatus.FAILED.name(), truncate(e.getMessage(), 2000), snapshot.id);
        }
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
            return status == CustomTaskQueueStatus.SUCCESS || status == CustomTaskQueueStatus.FAILED;
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
                        items.add(new TaskSnapshot(
                                rs.getLong("id"),
                                rs.getString("task_action"),
                                rs.getString("request_json"),
                                CustomTaskQueueStatus.valueOf(rs.getString("status")),
                                rs.getInt("priority"),
                                rs.getString("error_message"),
                                rs.getString("result_json"),
                                rs.getTimestamp("created_at"),
                                rs.getTimestamp("started_at"),
                                rs.getTimestamp("finished_at")));
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
