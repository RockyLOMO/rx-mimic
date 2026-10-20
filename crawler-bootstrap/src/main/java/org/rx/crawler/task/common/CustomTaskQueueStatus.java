package org.rx.crawler.task.common;
public enum CustomTaskQueueStatus {
    PENDING, RUNNING, WAITING_USER, CANCEL_REQUESTED, SUCCESS, FAILED, CANCELLED, TIMED_OUT;
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }
    public String externalName() { return this == SUCCESS ? "SUCCEEDED" : name(); }
}
