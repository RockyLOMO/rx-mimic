package org.rx.test;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.crawler.service.BrowserType;
import org.rx.crawler.task.common.ChromeProfilePaths;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.net.http.HttpClientCookieJar;
import org.rx.util.BeanMapper;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.rx.core.Extends.tryClose;

@Slf4j
public class DevTest {

    public WebBrowser init(BrowserType type) {
        AppConfig config = new AppConfig();
        WebBrowserConfig conf = BeanMapper.DEFAULT.map(config.getBrowser(), WebBrowserConfig.class);
        conf.setProfileDataPath(System.getProperty("browser.dev.profileDataPath",
                ChromeProfilePaths.profileDir(null, "dev").toString()));
        conf.setDownloadPath(conf.getDownloadPath());
        conf.setCookieJar(HttpClientCookieJar.memory());
        conf.setConfigureScriptExecutorType(conf.getConfigureScriptExecutorType());
        log.info("loadConf {}", conf);
        return new WebBrowser(conf, type);
    }

    @SneakyThrows
    @Test
    public void chrome() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("browser.dev.integration", "false")));
        WebBrowser browser = init(BrowserType.CHROME);
        try {

//        while (true) {
//            byte[] bytes = System.in.readAllBytes();
//            String s = new String(bytes);
//            switch (s) {
//                case "1":
                    browser.navigateUrl("https://union.jd.com/proManager/index?keywords=100075085022");
//                    break;
//                case "0":
//                    return;
//            }
//        }
            System.in.read();
        } finally {
            tryClose(browser);
        }
    }

    @SneakyThrows
    @Test
    public void firefox() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("browser.dev.integration", "false")));
        WebBrowser browser = init(BrowserType.FIRE_FOX);
        try {
            browser.navigateUrl("https://union.jd.com");
            System.in.read();
        } finally {
            tryClose(browser);
        }
    }

    @SneakyThrows
    @Test
    public void changeTab() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("browser.dev.integration", "false")));
        WebBrowser caller = init(BrowserType.CHROME);
        try {
            String currentHandle = caller.getCurrentHandle();
            System.out.println(currentHandle);

            String handle = caller.openTab();
            System.out.println(handle);
            Thread.sleep(2000);

            caller.openTab();
            System.out.println(handle);
            Thread.sleep(2000);

            caller.switchTab(handle);
            System.out.println("switch");
            Thread.sleep(2000);

            caller.closeTab(handle);
            System.out.println("close");
        } finally {
            tryClose(caller);
        }
    }
}
