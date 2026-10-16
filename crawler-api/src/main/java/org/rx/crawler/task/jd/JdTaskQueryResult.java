package org.rx.crawler.task.jd;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 异步任务查询结果：status 为队列状态（PENDING/RUNNING/SUCCESS/FAILED），
 * SUCCESS 时 result 为对应任务的结果对象（JdSearchResult / JdProductDetailResult 等）。
 */
@Data
public class JdTaskQueryResult implements Serializable {
    private static final long serialVersionUID = 5127301958047250018L;

    private long taskId;
    private String action;
    private String status;
    private String errorMessage;
    private Date createdAt;
    private Date startedAt;
    private Date finishedAt;
    private Object result;
}
