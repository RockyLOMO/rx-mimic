package org.rx.test;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.core.Extends;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.crawler.service.BrowserType;
import org.rx.crawler.task.common.ChromeProfilePaths;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.net.http.HttpClientCookieJar;
import org.rx.util.BeanMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.rx.core.Extends.tryClose;

public class BrowserWindowTests {
    @SneakyThrows
    @Test
    public void chromeWindowShouldBeMaximized() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("browser.window.integration", "false")));

        AppConfig appConfig = new AppConfig();
        WebBrowserConfig config = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
        config.setHeadless(false);
        config.setFingerprintEnabled(false);
        config.setCookieJar(HttpClientCookieJar.memory());
        config.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        config.setProfileDataPath(System.getProperty("browser.window.profileDataPath",
                ChromeProfilePaths.profileDir(null, "window").toString()));
        config.setDownloadPath(System.getProperty("browser.window.downloadPath", "./data/temp/"));
        config.setWindowRectangle(new BrowserWindowRect(0, 0, -1, -1));

        WebBrowser browser = new WebBrowser(config, BrowserType.CHROME);
        try {
            String checkUrl = System.getProperty("browser.window.checkUrl", config.getFingerprintCheckUrl());
            browser.navigateUrl(checkUrl, "body", 60);
            Extends.sleep(Long.parseLong(System.getProperty("browser.window.inspectMillis", "8000")));

            Map<String, Object> size = browser.executeScript("return {" +
                    "title: document.title," +
                    "href: location.href," +
                    "innerWidth: window.innerWidth," +
                    "innerHeight: window.innerHeight," +
                    "outerWidth: window.outerWidth," +
                    "outerHeight: window.outerHeight," +
                    "screenWidth: window.screen && window.screen.width," +
                    "screenHeight: window.screen && window.screen.height," +
                    "availWidth: window.screen && window.screen.availWidth," +
                    "availHeight: window.screen && window.screen.availHeight" +
                    "};");
            System.out.println("WINDOW_PAGE=" + size.get("title") + " " + size.get("href"));
            System.out.println("WINDOW_RESULT=" + size);
            System.out.println("WINDOW_RECT=" + browser.getWindowRectangle());

            int outerWidth = ((Number) size.get("outerWidth")).intValue();
            int innerWidth = ((Number) size.get("innerWidth")).intValue();
            assertTrue(outerWidth >= 1400, "outerWidth should be maximized");
            assertTrue(innerWidth >= 1400, "innerWidth should be maximized");
        } finally {
            tryClose(browser);
        }
    }
}
