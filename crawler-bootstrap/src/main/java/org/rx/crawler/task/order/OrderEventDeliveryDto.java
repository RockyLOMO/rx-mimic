package org.rx.crawler.task.order;

import lombok.Data;

import java.io.Serializable;
import java.sql.Timestamp;

@Data
public class OrderEventDeliveryDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private long eventId;
    private String eventPublicId;
    private String subscriberId;
    private String channel;
    private String targetUrl;
    private int status;
    private int attempts;
    private Timestamp nextAttemptAt;
    private Integer lastHttpStatus;
    private String lastError;
    private Timestamp sentAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private String payload;
}
