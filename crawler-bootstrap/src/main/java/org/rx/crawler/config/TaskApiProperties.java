package org.rx.crawler.config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;
@Data
@org.springframework.validation.annotation.Validated
@Component
@ConfigurationProperties(prefix = "crawler.task-api")
public class TaskApiProperties {
    /** Caller id -> secret; external config only. Empty map fails closed. */
    private Map<String, String> apiKeys = new LinkedHashMap<>();
    private String publicBasePath = "/api/v1";
    /** Legacy routes have no per-caller ownership contract and must stay private. */
    private boolean legacyEnabled = false;
    @jakarta.validation.constraints.Min(1)
    private int capacity = 100;
    @jakarta.validation.constraints.Min(1)
    private int perCallerCapacity = 50;
    @jakarta.validation.constraints.Min(1)
    private int submissionsPerMinute = 60;
    @jakarta.validation.constraints.Min(1)
    private int maxBatchSize = 20;
    @jakarta.validation.constraints.Min(1)
    private long queueWaitSeconds = 1800;
    @jakarta.validation.constraints.Min(1)
    private long executionSeconds = 600;
    @jakarta.validation.constraints.Min(1)
    private long ordersExecutionSeconds = 1800;
    @jakarta.validation.constraints.Min(1)
    private long userWaitSeconds = 600;
    @jakarta.validation.constraints.Min(1)
    private long retentionSeconds = 604800;
}
