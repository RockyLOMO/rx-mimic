package org.rx.crawler.tool;

import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.LoginNotificationContext;
import org.rx.crawler.task.common.MailLoginNotificationService;
import org.springframework.service.MiddlewareConfig;

/**
 * 按当前登录窗口的京东、淘宝状态各走一次提醒。healthy 不发信，expired 才发。
 * 用法：LoginExpireMailCheck send -Dlogin.check.jd=expired -Dlogin.check.tb=healthy
 */
public final class LoginExpireMailCheck {
    private LoginExpireMailCheck() {
    }

    public static void main(String[] args) {
        if (args == null || args.length == 0 || !"send".equals(args[0])) {
            System.out.println("usage: LoginExpireMailCheck send");
            return;
        }
        AppConfig appConfig = new AppConfig();
        appConfig.getCustom().getLoginNotification().setEnabled(true);
        appConfig.getCustom().getLoginNotification().getMail().setTo("ilovehaley.kid@gmail.com");
        MiddlewareConfig middlewareConfig = new MiddlewareConfig();
        MiddlewareConfig.SmtpConfig smtp = middlewareConfig.getSmtp();
        smtp.setHost("smtp.gmail.com");
        smtp.setPort(587);
        smtp.setSsl(Boolean.FALSE);
        smtp.setStartTls(Boolean.TRUE);
        smtp.setTimeoutMillis(20000);
        smtp.setTo("ilovehaley.kid@gmail.com");
        MailLoginNotificationService service = new MailLoginNotificationService(appConfig, middlewareConfig);

        boolean jdSent = sendIfExpired(service, "jd", System.getProperty("login.check.jd", "skip"));
        boolean tbSent = sendIfExpired(service, "tb", System.getProperty("login.check.tb", "skip"));
        System.out.println("[RESULT] jdSent=" + jdSent + " tbSent=" + tbSent);
        if ("expired".equals(state("jd")) && !jdSent) {
            System.exit(1);
        }
        if ("expired".equals(state("tb")) && !tbSent) {
            System.exit(1);
        }
    }

    private static boolean sendIfExpired(MailLoginNotificationService service, String platform, String state) {
        if (!"expired".equals(state)) {
            System.out.println("[SKIP] " + platform + " state=" + state + "，不发提醒邮件");
            return false;
        }
        LoginNotificationContext context = new LoginNotificationContext();
        context.setProfileName("common");
        context.setLoginWaitSeconds(0);
        context.setKeepBrowserOpenSeconds(0);
        if ("jd".equals(platform)) {
            context.setTaskType("jdLoginKeepAlive");
            context.setInitialUrl("https://www.jd.com");
            context.setCurrentUrl("https://passport.jd.com/new/login.aspx");
            context.setMessage("当前登录窗口里京东仍停在登录页，登录已失效或尚未完成。");
        } else {
            context.setTaskType("tbLoginKeepAlive");
            context.setInitialUrl("https://pub.alimama.com/portal/v2/home/plus/index.htm");
            context.setCurrentUrl("https://login.taobao.com/havanaone/login/login.htm");
            context.setMessage("当前登录窗口里淘宝/阿里妈妈需要重新登录。");
        }
        boolean sent = service.deliver(context);
        System.out.println(sent ? "[SENT] " + platform + " 登录失效提醒已提交" : "[FAIL] " + platform + " 邮件未发出");
        return sent;
    }

    private static String state(String platform) {
        return System.getProperty("login.check." + platform, "skip");
    }
}
