package org.rx.crawler.task.common;
import lombok.Data;
import java.io.Serializable;
import java.util.Date;
@Data
public class AsyncTaskView implements Serializable {
    private String taskId;
    private java.util.List<String> childTaskIds;
    private String action;
    private String status;
    private String stage;
    private String businessStatus;
    private String errorCode;
    private String statusUrl;
    private Date createdAt;
    private Date startedAt;
    private Date finishedAt;
    private Date expiresAt;
}
