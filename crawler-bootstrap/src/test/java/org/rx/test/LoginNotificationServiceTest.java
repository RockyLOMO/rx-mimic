package org.rx.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.LoginNotificationContext;
import org.rx.crawler.task.common.MailLoginNotificationService;
import org.rx.util.Helper;
import org.springframework.service.MiddlewareConfig;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SuppressWarnings("unchecked")
public class LoginNotificationServiceTest {
    @Test
    public void notifyLoginRequiredShouldSendMailAndThrottleDuplicate() {
        AppConfig appConfig = new AppConfig();
        appConfig.getCustom().getLoginNotification().setEnabled(true);
        appConfig.getCustom().getLoginNotification().setMinIntervalSeconds(300);
        MiddlewareConfig middlewareConfig = new MiddlewareConfig();
        MiddlewareConfig.SmtpConfig smtp = new MiddlewareConfig.SmtpConfig();
        smtp.setUsername("robot@example.com");
        smtp.setPassword("pwd");
        smtp.setTo("ops@example.com");
        middlewareConfig.setSmtp(smtp);

        MailLoginNotificationService service = new MailLoginNotificationService(appConfig, middlewareConfig);
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType("getPromotionUrl");
        context.setProfileName("common");
        context.setInitialUrl("https://union.jd.com/overview");
        context.setCurrentUrl("https://union.jd.com/index?returnUrl=abc");
        context.setMessage("Login required");
        context.setLoginWaitSeconds(180);
        context.setKeepBrowserOpenSeconds(180);

        try (MockedStatic<Helper> helper = org.mockito.Mockito.mockStatic(Helper.class)) {
            service.notifyLoginRequired(context);
            service.notifyLoginRequired(context);

            helper.verify(() -> Helper.sendEmail(anyString()));
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            helper.verify(() -> Helper.sendEmail(body.capture()));
            assertTrue(body.getValue().contains("getPromotionUrl"));
            assertTrue(body.getValue().contains("任务需要人工登录接管"));
            assertTrue(body.getValue().contains("common"));
        }
    }

    @Test
    public void notifySliderFailedShouldSendDistinctMailAndThrottle() {
        AppConfig appConfig = new AppConfig();
        appConfig.getCustom().getLoginNotification().setEnabled(true);
        appConfig.getCustom().getLoginNotification().setMinIntervalSeconds(300);
        MiddlewareConfig middlewareConfig = new MiddlewareConfig();
        MiddlewareConfig.SmtpConfig smtp = new MiddlewareConfig.SmtpConfig();
        smtp.setUsername("robot@example.com");
        smtp.setPassword("pwd");
        smtp.setTo("ops@example.com");
        middlewareConfig.setSmtp(smtp);

        MailLoginNotificationService service = new MailLoginNotificationService(appConfig, middlewareConfig);
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType("getTbPromotionOrders");
        context.setProfileName("common");
        context.setCurrentUrl("https://pub.alimama.com/punish?x5secdata=secret-token");
        context.setMessage("自动滑动验证失败，步骤 02-order-slider");
        context.setLoginWaitSeconds(180);

        try (MockedStatic<Helper> helper = org.mockito.Mockito.mockStatic(Helper.class)) {
            service.notifySliderFailed(context);
            service.notifySliderFailed(context);

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            helper.verify(() -> Helper.sendEmail(body.capture()));
            assertTrue(body.getValue().contains("滑动验证需要人工处理"));
            assertTrue(body.getValue().contains("getTbPromotionOrders"));
            assertTrue(body.getValue().contains("自动滑动验证失败"));
            assertTrue(body.getValue().contains("?redacted"));
            assertTrue(!body.getValue().contains("secret-token"));
        }
    }

    @Test
    public void notifyLoginRequiredShouldSkipWhenDisabled() {
        AppConfig appConfig = new AppConfig();
        MiddlewareConfig middlewareConfig = mock(MiddlewareConfig.class);
        MailLoginNotificationService service = new MailLoginNotificationService(appConfig, middlewareConfig);
        try (MockedStatic<Helper> helper = org.mockito.Mockito.mockStatic(Helper.class)) {
            service.notifyLoginRequired(new LoginNotificationContext());
            helper.verify(() -> Helper.sendEmail(anyString()), never());
        }
    }

    @Test
    public void notifyLoadsGmailKeysWhenSmtpPasswordMissing(@TempDir Path tempDir) throws Exception {
        Path keys = tempDir.resolve("keys.json");
        Files.writeString(keys, "{\"smtp_gmail2\":{\"usr\":\"bot@gmail.com\",\"pwd\":\"abcd efgh ijkl mnop\"}}");
        AppConfig appConfig = new AppConfig();
        appConfig.getCustom().getLoginNotification().setEnabled(true);
        appConfig.getCustom().getLoginNotification().getMail().setKeysFile(keys.toString());
        appConfig.getCustom().getLoginNotification().getMail().setTo("ilovehaley.kid@gmail.com");
        MiddlewareConfig middlewareConfig = new MiddlewareConfig();

        MailLoginNotificationService service = new MailLoginNotificationService(appConfig, middlewareConfig);
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType("jdLoginKeepAlive");
        context.setProfileName("common");
        context.setCurrentUrl("https://passport.jd.com/new/login.aspx");

        try (MockedStatic<Helper> helper = org.mockito.Mockito.mockStatic(Helper.class)) {
            assertTrue(service.deliver(context));
            helper.verify(() -> Helper.sendEmail(anyString()));
        }
        assertEquals("bot@gmail.com", middlewareConfig.getSmtp().getUsername());
        assertEquals("abcdefghijklmnop", middlewareConfig.getSmtp().getPassword());
        assertEquals("ilovehaley.kid@gmail.com", middlewareConfig.getSmtp().getTo());
    }
}
