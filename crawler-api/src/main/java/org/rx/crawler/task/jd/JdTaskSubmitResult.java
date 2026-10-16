package org.rx.crawler.task.jd;

import lombok.Data;

import java.io.Serializable;

/**
 * 异步任务提交回执：任务已入队，轮询 /custom/jd/task/{taskId} 获取结果。
 */
@Data
public class JdTaskSubmitResult implements Serializable {
    private static final long serialVersionUID = 5127301958047250017L;

    private long taskId;
    private String action;
    private String status;
}
