package org.rx.crawler.task.order;

import lombok.Data;

import java.io.Serializable;
import java.sql.Timestamp;

@Data
public class OrderSyncState implements Serializable {
    private static final long serialVersionUID = 1L;

    private String sourceId;
    private String platform;
    private String profileName;
    private String accountName;
    private String allowedCallers;
    private boolean initialized;
    private String syncStatus = "IDLE";
    private Timestamp lastSuccessfulSyncAt;
    private String lastSuccessfulWindowEnd;
    private Timestamp nextSyncAt;
    private Long currentTaskId;
    private Long pendingIngestTaskId;
    private String lastError;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
