package org.rx.crawler.task.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.util.Helper;
import org.springframework.stereotype.Service;
import org.springframework.service.MiddlewareConfig;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailLoginNotificationService implements LoginNotificationService {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppConfig appConfig;
    private final MiddlewareConfig middlewareConfig;
    private final ConcurrentHashMap<String, Long> sentAtMillisMap = new ConcurrentHashMap<String, Long>();

    @Override
    public void notifyLoginRequired(LoginNotificationContext context) {
        AppConfig.LoginNotificationConfig config = appConfig.getCustom().getLoginNotification();
        if (context == null || config == null || !config.isEnabled()
                || config.getMail() == null || !config.getMail().isEnabled()) {
            return;
        }
        AppConfig.MailNotificationConfig mailConfig = config.getMail();
        if (middlewareConfig == null || middlewareConfig.getSmtp() == null
                || Strings.isEmpty(middlewareConfig.getSmtp().getUsername())
                || Strings.isEmpty(middlewareConfig.getSmtp().getPassword())
                || Strings.isEmpty(middlewareConfig.getSmtp().getTo())) {
            log.warn("Login notification skipped, middleware smtp config is not configured");
            return;
        }

        String key = dedupeKey(context);
        long now = System.currentTimeMillis();
        long minIntervalMillis = Math.max(0L, config.getMinIntervalSeconds()) * 1000L;
        Long lastSentAt = sentAtMillisMap.get(key);
        if (lastSentAt != null && now - lastSentAt < minIntervalMillis) {
            log.info("Login notification skipped by interval, key={}", key);
            return;
        }
        sentAtMillisMap.put(key, now);

        try {
            Helper.sendEmail(body(mailConfig, context));
            log.info("Login notification mail sent, taskType={}, profile={}", context.getTaskType(),
                    context.getProfileName());
        } catch (Exception e) {
            sentAtMillisMap.remove(key);
            log.warn("Send login notification mail fail, taskType={}, profile={}, error={}",
                    context.getTaskType(), context.getProfileName(), e.getMessage(), e);
        }
    }

    private String dedupeKey(LoginNotificationContext context) {
        return value(context.getTaskType()) + "|" + value(context.getProfileName()) + "|"
                + value(context.getCurrentUrl());
    }

    private String subject(AppConfig.MailNotificationConfig mailConfig, LoginNotificationContext context) {
        String prefix = Strings.isEmpty(mailConfig.getSubjectPrefix()) ? "[rx-mimic]" : mailConfig.getSubjectPrefix();
        return prefix + " 任务需要人工登录: " + value(context.getTaskType());
    }

    private String body(AppConfig.MailNotificationConfig mailConfig, LoginNotificationContext context) {
        StringBuilder builder = new StringBuilder();
        builder.append(subject(mailConfig, context)).append(System.lineSeparator())
                .append("任务需要人工登录接管，请在已打开的 Chrome profile 中完成登录。").append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("任务类型: ").append(value(context.getTaskType())).append(System.lineSeparator())
                .append("Chrome profile: ").append(value(context.getProfileName())).append(System.lineSeparator())
                .append("当前页面: ").append(value(context.getCurrentUrl())).append(System.lineSeparator())
                .append("初始页面: ").append(value(context.getInitialUrl())).append(System.lineSeparator())
                .append("登录等待秒数: ").append(context.getLoginWaitSeconds()).append(System.lineSeparator())
                .append("浏览器保留秒数: ").append(context.getKeepBrowserOpenSeconds()).append(System.lineSeparator())
                .append("触发时间: ").append(LocalDateTime.now().format(TIME_FORMATTER)).append(System.lineSeparator());
        if (!Strings.isEmpty(context.getMessage())) {
            builder.append("说明: ").append(context.getMessage()).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String value(String value) {
        return Strings.isEmpty(value) ? "-" : value;
    }
}
