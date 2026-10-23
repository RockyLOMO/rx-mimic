package org.rx.crawler.task.order;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Data
public class OrderRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private String sourceId;
    private String platform;
    private String platformOrderNo;
    private String mainOrderNo;
    private String orderStatus;
    private String normalizedStatus;
    private String orderTime;
    private String finishTime;
    private String settleTime;
    private int itemCount;
    private BigDecimal totalAmount;
    private BigDecimal totalCommission;
    private Timestamp firstDiscoveredAt;
    private Timestamp lastSyncedAt;
    private Long latestTaskId;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    private List<OrderItemRecord> items = new ArrayList<>();
}
