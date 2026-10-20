package org.rx.crawler.task.common;
import lombok.Getter;
@Getter
public class TaskApiException extends RuntimeException {
    private final int status;
    private final String code;
    public TaskApiException(int status, String code, String message) {
        super(message); this.status=status; this.code=code;
    }
}
