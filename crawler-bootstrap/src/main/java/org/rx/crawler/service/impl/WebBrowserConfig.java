package org.rx.crawler.service.impl;

import lombok.Data;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.net.http.HttpClientCookieJar;

@Data
public class WebBrowserConfig {
    private long waitMillis = 500;
    private int pageLoadTimeoutSeconds = 30;
    private int findElementTimeoutSeconds = 6;
    // 第一原则：有头真机 Chrome。默认 headless 会在 fingerprintEnabled 关闭时悄悄退化成可被
    // UA / WebGL / 视口 / permissions 多维判定的自动化环境，因此默认值必须是 false。
    private boolean headless = false;
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
    /**
     * 红队校准档位，仅用于验证「自家站点」的风控灵敏度，默认 HARDENED。
     * <ul>
     *   <li>HARDENED：全量隐蔽（不注入主世界工具库、不派发非可信事件、拟人统计分布）。</li>
     *   <li>RAW：故意恢复旧版行为泄漏面（非可信 resize 事件 / 均匀分布延迟 /
     *       直线匀速轨迹 / 长文本一次 insertText / 固定光标起点），作为对照基线。
     *       注意：RAW 也<b>不</b>注入任何页面全局或原型补丁，主世界在两档下同样干净；
     *       因此两档的差异纯粹在行为层，正好用来测风控有没有行为检测能力。</li>
     * </ul>
     * 严禁对未授权域名使用 RAW 以外的用途；生产抓取保持 HARDENED。
     */
    private String adversaryProfile = "HARDENED";
    private HttpClientCookieJar cookieJar;
}
