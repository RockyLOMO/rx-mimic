package org.rx.crawler.task.jd;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.rx.core.Extends;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.BrowserType;
import org.rx.crawler.task.common.ChromeProfilePaths;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.net.http.HttpClientCookieJar;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.core.Extends.tryClose;

/**
 * 本机真实 Chrome 打开京东联盟登录墙，用密钥文件登录并拖滑动拼图。
 * 只在显式打开 browser.jd.login.live 时运行。
 * 每次运行都保存证据（相当于 debugEnabled=true）：每一步的页面截图、拼图原图与缺口匹配图、验证码 DOM，
 * 写到 %TEMP%\ai\日期\jd-login-live\时间\，失败后可以离线复盘。
 */
@EnabledIf("liveEnabled")
class JdUnionPasswordLoginLiveTest {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final String ENTRY_URL = "https://union.jd.com/index?returnUrl=https%3A%2F%2Funion.jd.com%2Fentire";
    private static final String VERIFY_URL = "/slide/s.html";
    private static final String CHALLENGE_URL = "/slide/g.html";
    /**
     * 复用生产上的 common profile：它已经在京东侧建立了设备信誉，账号栏也会自动预填。
     * 之前用 java.io.tmpdir 下新建的持久化目录，虽然跨运行保留，但对京东仍是一台新设备。
     * 同一目录只允许一个 Chrome 实例，跑之前要确认 81 端口的服务没在占用。
     */
    private static final Path DEFAULT_PROFILE =
            ChromeProfilePaths.profileDir(null, ChromeProfilePaths.DEFAULT_PROFILE_NAME);
    private static final String PROFILE_ENV = "JD_LOGIN_PROFILE";
    private static final String COOLDOWN_ENV = "JD_LOGIN_COOLDOWN_HOURS";
    private static final String LAST_FAILURE_FILE = "last-failed-login.txt";
    /** 冷却标记不放进 Chrome 用户目录，避免污染 profile。 */
    private static final Path PACE_FILE = Path.of(System.getProperty("java.io.tmpdir"), "ai",
            "jd-login-pace", LAST_FAILURE_FILE);
    private static final long DEFAULT_COOLDOWN_HOURS = 6;
    private static final String PORTAL_URL = "https://www.jd.com/";
    private static final String UNION_HOME_URL = "https://union.jd.com/";

    static boolean liveEnabled() {
        return "true".equalsIgnoreCase(System.getenv("JD_LOGIN_LIVE"))
                || "true".equalsIgnoreCase(System.getProperty("browser.jd.login.live"));
    }

    private Path profile;
    private Path dumpDir;
    private int stepIndex;

    @Test
    void signsInThroughTheUnionLoginWall() throws Exception {
        dumpDir = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "ai",
                LocalDate.now().format(DATE), "jd-login-live", LocalTime.now().format(TIME)));
        System.out.println("JD_LOGIN_DUMP=" + dumpDir);
        profile = resolveProfile();
        long cooldownMillis = cooldownMillis();
        long lastFailure = readLastFailure(PACE_FILE);
        long now = System.currentTimeMillis();
        if (withinCooldown(lastFailure, now, cooldownMillis)) {
            long minutesLeft = Math.max(1, (cooldownMillis - (now - lastFailure)) / 60000);
            Assumptions.assumeTrue(false, "京东账号仍在失败冷却期，约 " + minutesLeft + " 分钟后重试；"
                    + "确需立即跑用 " + COOLDOWN_ENV + "=0。profile=" + profile);
        }
        System.out.println("JD_LOGIN_PROFILE=" + profile);

        WebBrowserConfig settings = new WebBrowserConfig();
        settings.setHeadless(false);
        // 和生产配置（application.yml 的 fingerprintEnabled: true）一致。
        settings.setFingerprintEnabled(true);
        settings.setProfileDataPath(profile.toString());
        settings.setDownloadPath(profile.resolve("download").toString());
        settings.setCookieJar(HttpClientCookieJar.memory());
        settings.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        settings.setPlaywrightChannel("chrome");
        WebBrowser browser = new WebBrowser(settings, BrowserType.CHROME);
        try {
            warmUp(browser);
            browser.navigateUrl(ENTRY_URL, "body", 40);
            // 滑块校验的请求和响应留作证据：请求里有轨迹和 webdriver 标志，响应是服务端的判定。
            browser.startResponseCapture(VERIFY_URL);
            browser.startResponseCapture(CHALLENGE_URL);
            List<String> verifyRequests = Collections.synchronizedList(new ArrayList<String>());
            Page page = (Page) ReflectionTestUtils.getField(browser, "page");
            page.onRequest(request -> {
                if (request.url().contains(VERIFY_URL)) {
                    verifyRequests.add(request.url());
                }
            });
            JdUnionConfig config = new JdUnionConfig();
            // 每次运行只打一轮：当晚累计十次以上拼图失败本身就是风控信号。
            // JD_LOGIN_ATTEMPTS 可显式调大，但默认不要连拖三次。
            int attempts = Integer.parseInt(System.getenv().getOrDefault("JD_LOGIN_ATTEMPTS", "1"));
            config.setCaptchaMaxAutoAttempts(Math.max(1, attempts));
            System.out.println("JD_LOGIN_ATTEMPTS=" + config.getCaptchaMaxAutoAttempts());
            boolean wall = stillLogin(browser, config);
            if (!wall) {
                System.out.println("JD_LOGIN_NOTE=persistent profile already signed in, url=" + browser.getCurrentUrl());
            }
            assertTrue(wall, "did not land on the login wall, url=" + browser.getCurrentUrl());
            JdCaptchaHandler captchaHandler = new JdCaptchaHandler();
            captchaHandler.setPuzzleDumpDir(dumpDir);
            boolean cleared = new JdUnionPasswordLogin(captchaHandler)
                    .attempt(browser, config, this::saveStep, candidate -> stillLogin(candidate, config));
            saveStep(browser, "final");
            dumpVerifyEvidence(browser, verifyRequests);
            System.out.println("JD_LOGIN_CLEARED=" + cleared + " URL=" + browser.getCurrentUrl());
            if (!cleared) {
                recordFailure(PACE_FILE, System.currentTimeMillis());
                System.out.println("JD_LOGIN_COOLDOWN=recorded failure, next run in "
                        + (cooldownMillis / 3600000) + "h (override with " + COOLDOWN_ENV + "=0)");
            }
            assertTrue(cleared, "password login did not clear the wall, url=" + browser.getCurrentUrl()
                    + ", dump=" + dumpDir);
            assertFalse(stillLogin(browser, config), "login wall still visible, url=" + browser.getCurrentUrl());
        } finally {
            tryClose(browser);
        }
    }

    private static Path resolveProfile() throws IOException {
        String override = System.getenv(PROFILE_ENV);
        if (override == null || override.isBlank()) {
            // 不自动新建：新建出来的空 profile 又是一台新设备，等价于回到 @TempDir 的问题。
            assertTrue(Files.isDirectory(DEFAULT_PROFILE),
                    "common profile 不存在，先用 JD_COMMON_PROFILE_LIVE=true 跑一次登录态验证，"
                            + "或用 " + PROFILE_ENV + " 显式指定： " + DEFAULT_PROFILE);
            return DEFAULT_PROFILE;
        }
        return Files.createDirectories(Path.of(override));
    }

    private static long cooldownMillis() {
        long hours = DEFAULT_COOLDOWN_HOURS;
        String raw = System.getenv(COOLDOWN_ENV);
        if (raw != null && !raw.isBlank()) {
            try {
                hours = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                System.out.println("JD_LOGIN_NOTE=bad " + COOLDOWN_ENV + ", using " + DEFAULT_COOLDOWN_HOURS);
                hours = DEFAULT_COOLDOWN_HOURS;
            }
        }
        return Math.max(0, Math.min(hours, 168)) * 3600000L;
    }

    /** 距上次失败还在冷却期内就不该再消耗真实账号的验证码次数。 */
    static boolean withinCooldown(long lastFailure, long now, long cooldownMillis) {
        return cooldownMillis > 0 && lastFailure > 0 && now > lastFailure && now - lastFailure < cooldownMillis;
    }

    static long readLastFailure(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    static void recordFailure(Path file, long now) {
        try {
            Files.writeString(file, Long.toString(now), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("JD_LOGIN_NOTE=could not record cooldown: " + e);
        }
    }

    /**
     * 先按真人的顺序走一遍门户和联盟首页，让 cookie / eid / jsToken 在正常会话里生成，
     * 再进入登录墙。冷启动直接打开 passport 登录页本身就是风险信号。
     */
    private static void warmUp(WebBrowser browser) {
        visit(browser, PORTAL_URL);
        visit(browser, UNION_HOME_URL);
    }

    private static void visit(WebBrowser browser, String url) {
        try {
            browser.navigateUrl(url, "body", 40);
            browser.mouseMove(280 + java.util.concurrent.ThreadLocalRandom.current().nextInt(420),
                    180 + java.util.concurrent.ThreadLocalRandom.current().nextInt(260));
            Extends.sleep(500 + java.util.concurrent.ThreadLocalRandom.current().nextInt(700));
        } catch (Exception e) {
            // 预热不是前置条件，访问失败就继续走原来的登录墙入口。
            System.out.println("JD_LOGIN_WARMUP_SKIPPED=" + url + " error=" + e.getMessage());
        }
    }

    /**
     * 滑块校验的请求参数和响应落盘，并打一行摘要。
     * d 是整段轨迹（编码过），o1 是 webdriver 标志，w 是图片显示宽度，c 是这次验证的 ID。
     * o 是登录时填的账号，不打印，落盘时打码。
     */
    private void dumpVerifyEvidence(Browser browser, List<String> verifyRequests) {
        try {
            StringBuilder out = new StringBuilder();
            int index = 0;
            for (String url : verifyRequests) {
                Map<String, String> params = new LinkedHashMap<String, String>();
                for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
                    int equal = pair.indexOf('=');
                    params.put(pair.substring(0, equal), pair.substring(equal + 1));
                }
                // o 是登录时填的账号，落盘时打码。
                out.append("VERIFY REQUEST ").append(++index).append('\n')
                        .append(url.replaceAll("([?&]o=)[^&]*", "$1***")).append('\n');
                System.out.println("JD_VERIFY_REQUEST=" + index + " o1=" + params.get("o1") + " w=" + params.get("w")
                        + " product=" + params.get("product") + " d.length=" + String.valueOf(params.get("d")).length()
                        + " e.length=" + String.valueOf(params.get("e")).length()
                        + " j.length=" + String.valueOf(params.get("j")).length()
                        + " s.length=" + String.valueOf(params.get("s")).length());
            }
            for (String body : browser.drainResponseCapture(VERIFY_URL)) {
                out.append("VERIFY RESPONSE\n").append(body).append('\n');
                System.out.println("JD_VERIFY_RESPONSE=" + body);
            }
            for (String body : browser.drainResponseCapture(CHALLENGE_URL)) {
                // 取图响应里是两张内联图片，图另有落盘，这里只留长度。
                out.append("CHALLENGE RESPONSE length=").append(body.length()).append('\n');
                System.out.println("JD_CHALLENGE_RESPONSE_LENGTH=" + body.length()
                        + " head=" + body.substring(0, Math.min(160, body.length())));
            }
            Files.writeString(dumpDir.resolve("slide-verify.txt"), out.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("JD_VERIFY_EVIDENCE_FAILED=" + e);
        }
    }

    /**
     * 每一步存一张视口截图；拼图第一次出现时再存一份验证码的 DOM，用来看滑钮和拼图块的结构。
     */
    private void saveStep(Browser browser, String step) {
        try {
            String name = String.format("%02d-%s", ++stepIndex, step.replaceAll("[^a-zA-Z0-9._-]+", "_"));
            byte[] png = browser.screenshotAsBytes(Browser.BODY_SELECTOR);
            if (png != null && png.length > 0) {
                Files.write(dumpDir.resolve(name + ".png"), png);
            }
            System.out.println("JD_LOGIN_STEP=" + name + " URL=" + browser.getCurrentUrl());
            if (step.endsWith("-captcha-before-slide-1")) {
                Files.write(dumpDir.resolve("captcha-dom.txt"), captchaDom(browser).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            System.out.println("JD_LOGIN_STEP_FAILED=" + step + " error=" + e);
        }
    }

    /**
     * 只读：验证码所在 frame 里，拼图区域往上四层容器的 outerHTML，以及各 frame 的地址。
     * 图片是内联的 base64，只留开头，不然整段 DOM 都被它占满。
     */
    private static String captchaDom(Browser browser) {
        Page page = (Page) ReflectionTestUtils.getField(browser, "page");
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (Frame frame : page.frames()) {
            out.append("FRAME ").append(index++).append(' ').append(frame.url()).append('\n');
            if (!frame.url().contains("passport.jd.com")) {
                continue;
            }
            Object html = frame.evaluate("() => {"
                    + "  var img = document.querySelector('.JDJRV-bigimg, [class*=JDJRV-bigimg]');"
                    + "  if (!img) { return ''; }"
                    + "  var node = img;"
                    + "  for (var i = 0; i < 5 && node.parentElement; i++) { node = node.parentElement; }"
                    + "  var text = node.outerHTML;"
                    + "  text = text.replace(/data:image\\/[a-z]+;base64,[A-Za-z0-9+\\/=]+/g,"
                    + "    function (m) { return m.slice(0, 30) + '...(' + m.length + ' chars)'; });"
                    + "  return text.length > 60000 ? text.slice(0, 60000) : text;"
                    + "}");
            out.append(html == null ? "" : html.toString()).append('\n');
        }
        return out.toString();
    }

    private static boolean stillLogin(Browser browser, JdUnionConfig config) {
        String url = browser.getCurrentUrl();
        String body = "";
        try {
            body = browser.executeScript("return document.body ? document.body.innerText : '';");
        } catch (RuntimeException ignored) {
            body = "";
        }
        return JdLoginState.unionLoginWall(url, body, config.getLoginUrlPrefix())
                || (url != null && url.contains("iv.jd.com"))
                || new JdCaptchaHandler().isCaptchaPage(browser);
    }
}
