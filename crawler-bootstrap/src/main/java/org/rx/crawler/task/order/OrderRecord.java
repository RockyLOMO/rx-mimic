package org.rx.crawler.task.order;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;
import java.util.LinkedHashMap;
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
    private String paidTime;
    private Map<String, Object> extensions = new LinkedHashMap<>();
    private String normalizedStatus;
    private String orderTime;
    private String finishTime;
    private String settleTime;
    private int itemCount;
    /** Legacy effective amount; use the four explicit financial fields. */
    private BigDecimal totalAmount;
    private BigDecimal estimatedBillingAmount;
    private BigDecimal estimatedCommission;
    private BigDecimal actualBillingAmount;
    private BigDecimal actualCommission;
    private BigDecimal totalCommission;
    private Timestamp firstDiscoveredAt;
    private Timestamp lastSyncedAt;
    private Long latestTaskId;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    private List<OrderItemRecord> items = new ArrayList<>();
}
