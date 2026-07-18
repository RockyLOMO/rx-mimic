package org.rx.crawler.service.impl;

import lombok.Data;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.net.http.HttpClientCookieJar;

@Data
public class WebBrowserConfig {
    private long waitMillis = 500;
    private int pageLoadTimeoutSeconds = 30;
    private int findElementTimeoutSeconds = 6;
    private boolean headless = true;
    private String profileDataPath;
    private String downloadPath;
    private BrowserWindowRect windowRectangle = new BrowserWindowRect(0, 0, -1, -1);
    private String configureScriptExecutorType;
    private boolean fingerprintEnabled = false;
    private String fingerprintScriptPath = "/bot/chrome-fingerprint.js";
    private String fingerprintStealthScriptPath = "/static/js/stealth.min.js";
    private String fingerprintCheckUrl = "https://bot.sannysoft.com/";
    private boolean fingerprintHeadless = false;
    private boolean fingerprintDiagnostics = false;
    private String playwrightChannel = "chrome";
    private String playwrightExecutablePath;
    /**
     * 浏览器语言。空=自动跟随运行机器 {@link java.util.Locale#getDefault()}，禁止写死开发机语言。
     */
    private String locale;
    /**
     * 时区。空=自动跟随运行机器 {@link java.time.ZoneId#systemDefault()}。
     */
    private String timezoneId;
    private String userAgent;
    private boolean blockResourceEnabled = false;
    private boolean humanInputEnabled = true;
    private int humanActionMinDelayMillis = 180;
    private int humanActionMaxDelayMillis = 650;
    private int operationRandomMinDelayMillis = 80;
    private int operationRandomMaxDelayMillis = 320;
    private int mouseMoveMinSteps = 24;
    private int mouseMoveMaxSteps = 56;
    private int typingMinDelayMillis = 90;
    private int typingMaxDelayMillis = 260;
    private HttpClientCookieJar cookieJar;
}
