package org.rx.test;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.core.Extends;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.BrowserType;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.ChromeProfilePaths;
import org.rx.net.http.HttpClientCookieJar;
import org.rx.util.BeanMapper;

import java.nio.file.Path;

@Slf4j
public class SessionVerificationTest {

    @Test
    public void verifyJdAndTbSessions() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.parseBoolean(System.getProperty("browser.session.verify", "false")));
        AppConfig config = new AppConfig();
        WebBrowserConfig conf = BeanMapper.DEFAULT.map(config.getBrowser(), WebBrowserConfig.class);
        Path profileDir = ChromeProfilePaths.profileDir(null, "common");
        conf.setProfileDataPath(profileDir.toString());
        conf.setPlaywrightChannel("chrome");
        conf.setHeadless(false);
        conf.setDownloadPath(conf.getDownloadPath());
        conf.setCookieJar(HttpClientCookieJar.memory());

        System.out.println("================================================================================");
        System.out.println("[INFO] 正在启动真实 Chrome 校验会话: Profile=" + profileDir);
        System.out.println("================================================================================");

        WebBrowser browser = null;
        try {
            browser = new WebBrowser(conf, BrowserType.CHROME);
            browser.maximize();

            // 1. 检验京东订单页
            System.out.println("[INFO] >>> 正在访问京东订单页: https://union.jd.com/order");
            browser.navigateUrl("https://union.jd.com/order", "body", 30);
            Extends.sleep(4000);
            String jdFinalUrl = browser.getCurrentUrl();
            String jdText = pageSnippet(browser);
            boolean jdLogged = !jdFinalUrl.contains("passport") && !jdFinalUrl.contains("login")
                    && !jdText.contains("扫码登录") && !jdText.contains("请使用京东商城账号登录");

            System.out.println("[RESULT] 京东最终跳转 URL: " + jdFinalUrl);
            System.out.println("[RESULT] 京东页面摘要: " + jdText.replace("\n", " ").trim());
            System.out.println("[RESULT] 京东会话状态: " + (jdLogged ? "【有效 - 已登录】" : "【无效/失效 - 已跳到登录墙】"));
            System.out.println("--------------------------------------------------------------------------------");

            // 2. 检验阿里妈妈订单概览页
            System.out.println("[INFO] >>> 正在访问阿里妈妈订单概览页: https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm");
            String tbTab = browser.openTab();
            browser.switchTab(tbTab, true);
            browser.navigateUrl("https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm", "body", 30);
            Extends.sleep(5000);
            String tbFinalUrl = browser.getCurrentUrl();
            String tbText = pageSnippet(browser);
            boolean tbLogged = !tbFinalUrl.contains("login.taobao.com")
                    && !tbFinalUrl.contains("passport.taobao.com")
                    && !tbFinalUrl.contains("havana")
                    && !tbFinalUrl.contains("login_jump")
                    && !tbText.contains("扫码登录")
                    && !tbText.contains("密码登录")
                    && !tbText.contains("快速进入");

            System.out.println("[RESULT] 阿里妈妈最终跳转 URL: " + tbFinalUrl);
            System.out.println("[RESULT] 阿里妈妈页面摘要: " + tbText.replace("\n", " ").trim());
            System.out.println("[RESULT] 阿里妈妈会话状态: " + (tbLogged ? "【有效 - 已登录】" : "【无效/失效 - 已跳到登录墙】"));
            System.out.println("================================================================================");

        } catch (Exception e) {
            System.err.println("[ERROR] 会话核验异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (browser != null) {
                System.out.println("[INFO] 正在关闭 Chrome 校验浏览器...");
                Extends.tryClose(browser);
            }
        }
    }

    private static String pageSnippet(WebBrowser browser) {
        try {
            Object obj = browser.executeScript("return document.body ? (document.body.innerText || '').slice(0, 300) : '';");
            return obj == null ? "" : String.valueOf(obj);
        } catch (Exception e) {
            return "";
        }
    }
}
