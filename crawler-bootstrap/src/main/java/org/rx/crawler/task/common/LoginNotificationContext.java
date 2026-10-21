package org.rx.crawler.task.common;

import lombok.Data;

@Data
public class LoginNotificationContext {
    private String taskType;
    private String profileName;
    private String initialUrl;
    private String currentUrl;
    private String message;
    private int loginWaitSeconds;
    private int keepBrowserOpenSeconds;
    /** login 或 slider。空值按登录失效邮件处理。 */
    private String noticeKind;
}
