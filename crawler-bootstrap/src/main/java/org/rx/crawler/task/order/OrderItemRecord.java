package org.rx.crawler.task.order;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;

@Data
public class OrderItemRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private long orderId;
    private String sourceId;
    private String sourceLineId;
    private String skuId;
    private String productName;
    private String productLink;
    private BigDecimal productPrice;
    private String storeName;
    private String orderStatus;
    private String promotionPosition;
    private int quantity = 1;
    private int refundQuantity = 0;
    private BigDecimal estimatedBillingAmount;
    private BigDecimal estimatedCommission;
    private BigDecimal actualBillingAmount;
    private BigDecimal actualCommission;
    private String commissionRate;
    private String rawJson;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
