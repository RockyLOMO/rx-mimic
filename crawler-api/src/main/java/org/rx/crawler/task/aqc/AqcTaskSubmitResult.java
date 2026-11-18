package org.rx.crawler.task.aqc;

import lombok.Data;

import java.io.Serializable;

/**
 * 异步任务提交回执：任务已入队，轮询 /custom/aqc/task/{taskId} 获取结果。
 */
@Data
public class AqcTaskSubmitResult implements Serializable {
    private static final long serialVersionUID = 9107300000000000010L;

    private long taskId;
    private String action;
    private String status;
}
