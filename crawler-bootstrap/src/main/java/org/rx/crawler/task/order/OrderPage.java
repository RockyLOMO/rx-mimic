package org.rx.crawler.task.order;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderPage implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<OrderRecord> items = new ArrayList<>();
    private long total;
    private int page;
    private int pageSize;
    private boolean initialized;
    private Timestamp lastSuccessfulSyncAt;
    private boolean stale;
}
