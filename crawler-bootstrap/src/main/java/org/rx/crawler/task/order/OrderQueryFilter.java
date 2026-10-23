package org.rx.crawler.task.order;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class OrderQueryFilter implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<String> authorizedSources;
    private String sourceId;
    private String platform;
    private String startDate;
    private String endDate;
    private String orderNo;
    private String productId;
    private String skuId;
    private String productName;
    private String orderStatus;
    private int page = 1;
    private int pageSize = 50;
}
