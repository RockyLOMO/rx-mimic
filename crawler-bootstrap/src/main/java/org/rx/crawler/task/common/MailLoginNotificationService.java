package org.rx.crawler.task.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.util.Helper;
import org.springframework.stereotype.Service;
import org.springframework.service.MiddlewareConfig;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailLoginNotificationService implements LoginNotificationService {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppConfig appConfig;
    private final MiddlewareConfig middlewareConfig;
    private final ConcurrentHashMap<String, Long> sentAtMillisMap = new ConcurrentHashMap<String, Long>();
    private boolean credentialsPreset;

    @Override
    public void notifyLoginRequired(LoginNotificationContext context) {
        deliver(context);
    }

    @Override
    public void notifySliderFailed(LoginNotificationContext context) {
        if (context != null) {
            context.setNoticeKind("slider");
        }
        deliver(context);
    }

    public boolean sendPlain(String body) {
        AppConfig.LoginNotificationConfig config = appConfig.getCustom().getLoginNotification();
        if (body == null || body.isBlank() || config == null || !config.isEnabled()
                || config.getMail() == null || !config.getMail().isEnabled()) {
            return false;
        }
        if (!ensureSmtp(config.getMail())) {
            return false;
        }
        try {
            send(body, config.getMail());
            return true;
        } catch (RuntimeException e) {
            log.warn("Send price alert mail fail, error={}", e.getMessage());
            return false;
        }
    }

    public boolean deliver(LoginNotificationContext context) {
        AppConfig.LoginNotificationConfig config = appConfig.getCustom().getLoginNotification();
        if (context == null || config == null || !config.isEnabled()
                || config.getMail() == null || !config.getMail().isEnabled()) {
            return false;
        }
        redactContext(context);
        AppConfig.MailNotificationConfig mailConfig = config.getMail();
        if (!ensureSmtp(mailConfig)) {
            log.warn("Login notification skipped, gmail keys file is missing or smtp is not configured");
            return false;
        }

        String key = dedupeKey(context);
        long now = System.currentTimeMillis();
        long minIntervalMillis = Math.max(0L, config.getMinIntervalSeconds()) * 1000L;
        Long lastSentAt = sentAtMillisMap.get(key);
        if (lastSentAt != null && now - lastSentAt < minIntervalMillis) {
            log.info("Login notification skipped by interval, key={}", key);
            return false;
        }
        sentAtMillisMap.put(key, now);

        try {
            send(body(mailConfig, context), mailConfig);
            log.info("Login notification mail sent, taskType={}, profile={}, to={}",
                    context.getTaskType(), context.getProfileName(), middlewareConfig.getSmtp().getTo());
            return true;
        } catch (Exception e) {
            sentAtMillisMap.remove(key);
            log.warn("Send login notification mail fail, taskType={}, profile={}, error={}",
                    context.getTaskType(), context.getProfileName(), e.getMessage(), e);
            return false;
        }
    }

    private boolean ensureSmtp(AppConfig.MailNotificationConfig mailConfig) {
        if (middlewareConfig == null) {
            return false;
        }
        MiddlewareConfig.SmtpConfig smtp = middlewareConfig.getSmtp();
        if (smtp == null) {
            smtp = new MiddlewareConfig.SmtpConfig();
            middlewareConfig.setSmtp(smtp);
        }
        if (Strings.isEmpty(smtp.getHost())) {
            smtp.setHost("smtp.gmail.com");
        }
        if (smtp.getPort() == null) {
            smtp.setPort(587);
        }
        if (smtp.getSsl() == null) {
            smtp.setSsl(Boolean.FALSE);
        }
        if (smtp.getStartTls() == null) {
            smtp.setStartTls(Boolean.TRUE);
        }
        if (smtp.getTimeoutMillis() == null) {
            smtp.setTimeoutMillis(20000);
        }
        if (Strings.isEmpty(smtp.getTo()) && mailConfig != null) {
            smtp.setTo(mailConfig.getTo());
        }
        if (Strings.isEmpty(smtp.getProxy()) && canConnect("127.0.0.1", 1080)) {
            smtp.setProxy("http://127.0.0.1:1080");
        }
        credentialsPreset = !Strings.isEmpty(smtp.getUsername()) && !Strings.isEmpty(smtp.getPassword())
                && !Strings.isEmpty(smtp.getTo());
        if (credentialsPreset) {
            if (Strings.isEmpty(smtp.getFrom())) {
                smtp.setFrom(smtp.getUsername());
            }
            export(smtp);
            return true;
        }
        List<GmailKeysFile.Account> accounts = readAccounts(mailConfig);
        if (accounts.isEmpty() || Strings.isEmpty(smtp.getTo())) {
            return false;
        }
        apply(smtp, accounts.get(0));
        export(smtp);
        log.info("SMTP account loaded from keys file, key={}, to={}", accounts.get(0).keyName, smtp.getTo());
        return true;
    }

    private void send(String body, AppConfig.MailNotificationConfig mailConfig) {
        if (credentialsPreset) {
            Helper.sendEmail(body);
            return;
        }
        MiddlewareConfig.SmtpConfig smtp = middlewareConfig.getSmtp();
        List<GmailKeysFile.Account> accounts = readAccounts(mailConfig);
        if (accounts.isEmpty()) {
            Helper.sendEmail(body);
            return;
        }
        RuntimeException last = null;
        for (GmailKeysFile.Account account : accounts) {
            apply(smtp, account);
            export(smtp);
            try {
                Helper.sendEmail(body);
                log.info("Login mail accepted by smtp, key={}", account.keyName);
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("Login mail rejected, key={}, error={}", account.keyName, e.getMessage());
            }
        }
        if (last != null) {
            throw last;
        }
    }

    private List<GmailKeysFile.Account> readAccounts(AppConfig.MailNotificationConfig mailConfig) {
        String keysFile = mailConfig == null || Strings.isEmpty(mailConfig.getKeysFile())
                ? "D:\\home\\.envKeys\\keys.json" : mailConfig.getKeysFile();
        Path path = Paths.get(keysFile);
        try {
            return GmailKeysFile.load(path);
        } catch (Exception e) {
            log.warn("Read gmail keys file failed, path={}, error={}", path, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    private void apply(MiddlewareConfig.SmtpConfig smtp, GmailKeysFile.Account account) {
        smtp.setUsername(account.username);
        smtp.setPassword(account.password);
        smtp.setFrom(account.username);
    }

    private void export(MiddlewareConfig.SmtpConfig smtp) {
        setProp("app.smtp.host", smtp.getHost());
        setProp("app.smtp.port", smtp.getPort() == null ? null : String.valueOf(smtp.getPort()));
        setProp("app.smtp.ssl", smtp.getSsl() == null ? null : String.valueOf(smtp.getSsl()));
        setProp("app.smtp.startTls", smtp.getStartTls() == null ? null : String.valueOf(smtp.getStartTls()));
        setProp("app.smtp.timeoutMillis", smtp.getTimeoutMillis() == null ? null : String.valueOf(smtp.getTimeoutMillis()));
        setProp("app.smtp.username", smtp.getUsername());
        setProp("app.smtp.password", smtp.getPassword());
        setProp("app.smtp.from", smtp.getFrom());
        setProp("app.smtp.to", smtp.getTo());
        setProp("app.smtp.proxy", smtp.getProxy());
    }

    private void setProp(String key, String value) {
        if (!Strings.isEmpty(value)) {
            System.setProperty(key, value);
        }
    }

    private boolean canConnect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String resolvePlatform(LoginNotificationContext context) {
        if (context == null) {
            return "电商平台";
        }
        String taskType = context.getTaskType() != null ? context.getTaskType().toLowerCase() : "";
        String profile = context.getProfileName() != null ? context.getProfileName().toLowerCase() : "";
        String url = (context.getCurrentUrl() != null ? context.getCurrentUrl() : "") + " "
                + (context.getInitialUrl() != null ? context.getInitialUrl() : "");
        url = url.toLowerCase();

        if (taskType.contains("tb") || taskType.contains("taobao") || taskType.contains("alimama")
                || profile.contains("tb") || profile.contains("taobao")
                || url.contains("taobao.com") || url.contains("alimama.com") || url.contains("tmall.com")) {
            return "淘宝联盟";
        }
        if (taskType.contains("jd") || taskType.contains("promotion") || profile.contains("jd")
                || url.contains("jd.com") || url.contains("joybuy.com")) {
            return "京东联盟";
        }
        return "电商平台";
    }

    private String dedupeKey(LoginNotificationContext context) {
        String platform = resolvePlatform(context);
        return platform + ":" + value(context.getProfileName()) + ":" + value(context.getNoticeKind());
    }

    private boolean sliderNotice(LoginNotificationContext context) {
        return context != null && "slider".equals(context.getNoticeKind());
    }

    private void redactContext(LoginNotificationContext context) {
        context.setInitialUrl(SensitiveUrls.redact(context.getInitialUrl()));
        context.setCurrentUrl(SensitiveUrls.redact(context.getCurrentUrl()));
        context.setMessage(SensitiveUrls.redactText(context.getMessage()));
    }

    private String subject(AppConfig.MailNotificationConfig mailConfig, LoginNotificationContext context) {
        String prefix = Strings.isEmpty(mailConfig.getSubjectPrefix()) ? "[rx-mimic]" : mailConfig.getSubjectPrefix();
        String platform = resolvePlatform(context);
        if (sliderNotice(context)) {
            return prefix + " [" + platform + "] 滑动验证需要人工处理: " + value(context.getTaskType());
        }
        return prefix + " [" + platform + "] 待登录提醒: " + value(context.getTaskType());
    }

    private String body(AppConfig.MailNotificationConfig mailConfig, LoginNotificationContext context) {
        String platform = resolvePlatform(context);
        StringBuilder builder = new StringBuilder();
        String lead = sliderNotice(context)
                ? "【" + platform + "】自动滑动验证失败，请在已打开的 Chrome 窗口中手动把滑块拖完。浏览器会继续等待人工接管。"
                : "【" + platform + "】任务需要人工登录接管（遇到待登录状态或会话失效），请在已打开的 Chrome 窗口中完成登录。";
        builder.append(subject(mailConfig, context)).append(System.lineSeparator())
                .append(lead).append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("所属平台: ").append(platform).append(System.lineSeparator())
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
        builder.append(System.lineSeparator())
                .append("【操作指引】").append(System.lineSeparator())
                .append("1. 请连接到服务器/打开本地桌面对应 Chrome 窗口（Profile: ").append(value(context.getProfileName())).append("）。").append(System.lineSeparator())
                .append("2. 完成扫码或账号登录，确保进入").append(platform).append("后台首页。").append(System.lineSeparator())
                .append("3. 登录成功后，rx-mimic 队列将自动恢复执行或可通过 API 重试任务。").append(System.lineSeparator());
        return builder.toString();
    }

    private String value(String value) {
        return Strings.isEmpty(value) ? "-" : value;
    }
}
