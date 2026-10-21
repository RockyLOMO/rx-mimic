package org.rx.crawler.task.price;

import lombok.Data;
import org.rx.crawler.task.common.CustomCrawlStatus;

import java.io.Serializable;

@Data
public class PriceTaskResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private CustomCrawlStatus status = CustomCrawlStatus.SUCCESS;
    private String message = "";
    private Long watchId;
}
