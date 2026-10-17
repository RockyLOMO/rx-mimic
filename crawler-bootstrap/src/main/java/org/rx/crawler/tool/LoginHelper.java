package org.rx.crawler.tool;

import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.crawler.service.BrowserType;
import org.rx.crawler.task.common.BrowserPreflightResult;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.ChromeProfilePaths;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.SannysoftFingerprintEvaluator;
import org.rx.crawler.task.jd.JdLoginState;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.net.http.HttpClientCookieJar;
import org.rx.util.BeanMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 京东与淘宝/阿里妈妈登录。
 * Cookie 写进用户目录里的同一个 Chrome profile，服务和测试直接打开这份目录。
 */
public class LoginHelper {

    private static final String JD_LOGIN_URL = "https://passport.jd.com/new/login.aspx";
    private static final String JD_UNION_CHECK_URL = "https://union.jd.com/proManager/index?pageNo=1";
    private static final String JD_UNION_LOGIN_PREFIX = "https://union.jd.com/index?returnUrl=";
    private static final String TB_LOGIN_URL = "https://pub.alimama.com/portal/v2/home/plus/index.htm";

    public static void main(String[] args) {
        int code = 1;
        try {
            code = run(args);
        } catch (Exception e) {
            System.err.println("[ERROR] 登录运行异常: " + e.getMessage());
            e.printStackTrace();
        }
        System.exit(code);
    }

    public static int run(String[] args) {
        String mode = "all";
        if (args != null && args.length > 0 && !Strings.isEmpty(args[0])) {
            mode = args[0].trim().toLowerCase(Locale.ROOT);
        }

        boolean needJd = "all".equals(mode) || "jd".equals(mode) || "jingdong".equals(mode);
        boolean needTb = "all".equals(mode) || "tb".equals(mode) || "taobao".equals(mode) || "alimama".equals(mode);
        if (!needJd && !needTb) {
            System.err.println("[ERROR] 不认识的登录模式: " + mode + "（可选 all / jd / tb）");
            return 1;
        }
        String profileName = args != null && args.length > 1 ? args[1] : null;

        Path profileDir;
        try {
            profileDir = ChromeProfilePaths.profileDir(
                    System.getProperty("app.custom.chrome.profileBasePath"), profileName);
            Files.createDirectories(profileDir);
            if (ChromeProfilePaths.importLegacyDefaultProfile(profileDir)) {
                System.out.println("[INFO] 已把项目 data/chrome 里的旧 Cookie 迁到 " + profileDir.toAbsolutePath());
            }
        } catch (IOException e) {
            System.err.println("[ERROR] 准备 Chrome Profile 失败: " + e.getMessage());
            return 1;
        }
        printBanner(mode, profileDir);

        if (chromeUsing(profileDir)) {
            System.err.println("[ERROR] Chrome 正在占用这个 Profile，Cookie 无法落盘。");
            System.err.println("        请先停掉 rx-mimic 服务，或调用 POST /custom/profile/close 后再登录。");
            System.err.println("        占用目录: " + profileDir.toAbsolutePath());
            return 1;
        }

        WebBrowser browser = null;
        try {
            AppConfig appConfig = new AppConfig();
            WebBrowserConfig config = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
            config.setHeadless(false);
            config.setFingerprintEnabled(true);
            config.setFingerprintHeadless(false);
            config.setFingerprintDiagnostics(false);
            config.setCookieJar(HttpClientCookieJar.memory());
            config.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
            config.setProfileDataPath(profileDir.toAbsolutePath().toString());
            config.setDownloadPath(profileDir.resolve("downloads").toAbsolutePath().toString());
            config.setWindowRectangle(new BrowserWindowRect(0, 0, -1, -1));
            config.setPlaywrightChannel("chrome");
            config.setHumanInputEnabled(true);

            System.out.println("[INFO] 正在启动真实 Chrome 浏览器 (Profile=" + profileDir.toAbsolutePath() + ")...");
            browser = new WebBrowser(config, BrowserType.CHROME);
            browser.maximize();
            browser.focus();
            printWindowSize(browser, "启动后");
            if (!passBotCheck(browser)) {
                return 1;
            }
            browser.maximize();
            browser.focus();
            printWindowSize(browser, "检测页");

            String jdTabHandle = null;
            String tbTabHandle = null;
            String jdUrl = needJd ? jdEntryUrl(jdRawCookie(browser)) : null;
            boolean jdAlreadyLoggedIn = JD_UNION_CHECK_URL.equals(jdUrl);
            if (needJd && needTb) {
                System.out.println("[INFO] 打开标签页 1: " + jdPageLabel(jdAlreadyLoggedIn) + " -> " + jdUrl);
                browser.navigateUrl(jdUrl, "body", 30);
                jdTabHandle = browser.getCurrentHandle();
                System.out.println("[INFO] 打开标签页 2: 淘宝/阿里妈妈入口 -> " + TB_LOGIN_URL);
                tbTabHandle = browser.openTab();
                browser.navigateUrl(TB_LOGIN_URL, "body", 30);
            } else if (needJd) {
                System.out.println("[INFO] 打开" + jdPageLabel(jdAlreadyLoggedIn) + " -> " + jdUrl);
                browser.navigateUrl(jdUrl, "body", 30);
                jdTabHandle = browser.getCurrentHandle();
            } else {
                System.out.println("[INFO] 打开淘宝/阿里妈妈登录入口 -> " + TB_LOGIN_URL);
                browser.navigateUrl(TB_LOGIN_URL, "body", 30);
                tbTabHandle = browser.getCurrentHandle();
            }
            System.out.println();
            System.out.println("================================================================================");
            System.out.println(">>> 浏览器已就绪。");
            if (needJd) {
                if (jdAlreadyLoggedIn) {
                    System.out.println("    - [京东]：会话仍在，已打开联盟推广页，不再打开登录页。");
                } else {
                    System.out.println("    - [京东]：没有可用会话，请在登录页扫码。扫完后会打开联盟推广页确认。");
                }
            }
            if (needTb) {
                System.out.println("    - [淘宝/阿里妈妈]：手机淘宝扫码。停在登录页或「快速进入」时不算已登录。");
            }
            System.out.println(">>> 两个页面都离开登录墙后才保存。也可在此控制台按 Enter 提前结束。");
            System.out.println("================================================================================");
            System.out.println();

            AtomicBoolean userConfirmed = new AtomicBoolean(false);
            Thread consoleThread = new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    String line = reader.readLine();
                    if (line != null) {
                        userConfirmed.set(true);
                    }
                } catch (Exception ignored) {
                }
            }, "login-console-listener");
            consoleThread.setDaemon(true);
            consoleThread.start();

            int stableHits = 0;
            int count = 0;
            boolean[] jdUnionProbed = new boolean[1];
            while (!browser.isClosed()) {
                Extends.sleep(1500);
                count++;
                if (userConfirmed.get()) {
                    System.out.println("[INFO] 收到控制台回车，按当前页面保存 Cookie 并退出...");
                    break;
                }
                // 只在后台读页面。switchTab 默认会 bringToFront，两个标签页会每秒互抢焦点。
                boolean jdNow = !needJd || confirmJdLogin(browser, jdTabHandle, jdUnionProbed);
                boolean tbNow = !needTb || isTbLoggedIn(browser, tbTabHandle);
                if (jdNow && tbNow) {
                    stableHits++;
                    if (stableHits == 1) {
                        System.out.println("[SUCCESS] 检测到登录 Cookie，再确认一次后落盘。");
                    }
                    if (stableHits >= 2) {
                        System.out.println("[SUCCESS] 登录 Cookie 已连续确认，开始落盘。");
                        break;
                    }
                } else {
                    stableHits = 0;
                }
                if (count % 10 == 0) {
                    System.out.println(String.format("[STATUS] 等待登录中... (JD: %s, TB: %s) [按 Enter 可提前保存]",
                            !needJd ? "跳过" : (jdNow ? "已登录" : "等待中"),
                            !needTb ? "跳过" : (tbNow ? "已登录" : "等待中")));
                }
            }

            reportLiveCookies(browser, jdTabHandle, tbTabHandle, needJd, needTb);
        } catch (Exception e) {
            System.err.println("[ERROR] 登录运行异常: " + e.getMessage());
            e.printStackTrace();
            return 1;
        } finally {
            if (browser != null) {
                System.out.println("[INFO] 正在关闭浏览器，等待 Chrome 把 Cookie 写入磁盘...");
                Extends.tryClose(browser);
            }
        }

        try {
            waitProfileReleased(profileDir);
            verifySavedCookies(profileDir, needJd, needTb);
        } catch (Exception e) {
            System.err.println("[ERROR] Cookie 未保存成功: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }

        System.out.println();
        System.out.println("================================================================================");
        System.out.println("                    [SUCCESS] 登录 Cookie 已写入统一 Profile");
        System.out.println("================================================================================");
        System.out.println("  " + profileDir.toAbsolutePath());
        System.out.println("服务、单元测试、登录脚本不传 profileName 时都用这一份。");
        System.out.println("可以启动服务跑任务。启动前不要再开第二个 Chrome 占用该 Profile。");
        System.out.println("================================================================================");
        return 0;
    }

    static boolean hasCookieName(String rawCookie, String name) {
        if (rawCookie == null || name == null || name.isEmpty()) {
            return false;
        }
        String token = name + "=";
        int from = 0;
        while (from <= rawCookie.length() - token.length()) {
            int index = rawCookie.indexOf(token, from);
            if (index < 0) {
                return false;
            }
            if (index == 0) {
                return true;
            }
            char previous = rawCookie.charAt(index - 1);
            if (previous == ' ' || previous == ';') {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    /**
     * 已有京东会话时打开联盟推广页。没有会话才打开登录页。
     */
    static String jdEntryUrl(String rawCookie) {
        if (JdLoginState.hasSessionCookie(rawCookie)) {
            return JD_UNION_CHECK_URL;
        }
        return JD_LOGIN_URL;
    }

    private static String jdPageLabel(boolean alreadyLoggedIn) {
        return alreadyLoggedIn ? "京东联盟推广页" : "京东登录入口";
    }

    /**
     * 登录脚本只确认联盟这一套。Cookie 出现后打开一次推广页，
     * {@link JdLoginState#unionReady} 通过才算京东登录完成。商品详情由详情任务自己判断。
     */
    private static boolean confirmJdLogin(WebBrowser browser, String tabHandle, boolean[] unionProbed) {
        try {
            switchTab(browser, tabHandle, false);
            String url = browser.getCurrentUrl();
            String body = pageText(browser);
            String rawCookie = jdRawCookie(browser);
            if (JdLoginState.unionReady(url, body, rawCookie)) {
                return true;
            }
            if (!JdLoginState.hasSessionCookie(rawCookie)
                    || JdLoginState.unionLoginWall(url, body, JD_UNION_LOGIN_PREFIX)) {
                return false;
            }
            if (unionProbed != null && unionProbed.length > 0 && !unionProbed[0]) {
                System.out.println("[INFO] 京东会话 Cookie 已出现，打开联盟推广页校验: " + JD_UNION_CHECK_URL);
                browser.navigateUrl(JD_UNION_CHECK_URL, "body", 30);
                unionProbed[0] = true;
                return false;
            }
            url = browser.getCurrentUrl();
            body = pageText(browser);
            rawCookie = jdRawCookie(browser);
            return JdLoginState.unionReady(url, body, rawCookie);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String jdRawCookie(WebBrowser browser) {
        String mall = browser.getRawCookie("https://www.jd.com");
        String union = browser.getRawCookie("https://union.jd.com");
        return (mall == null ? "" : mall) + "; " + (union == null ? "" : union);
    }

    private static boolean isTbLoggedIn(WebBrowser browser, String tabHandle) {
        try {
            switchTab(browser, tabHandle, false);
            clickQuickEnter(browser);
            String tbCookie = browser.getRawCookie("https://www.taobao.com");
            String alimamaCookie = browser.getRawCookie("https://pub.alimama.com");
            return tbSessionReady(browser.getCurrentUrl(), pageText(browser), tbCookie, alimamaCookie);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 旧 Cookie 不能代替当前这个淘宝标签页。停在登录页、login_jump 或「快速进入」都继续等。
     */
    static boolean tbSessionReady(String url, String body, String tbCookie, String alimamaCookie) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        String text = body == null ? "" : body;
        if (lowerUrl.contains("login.taobao.com")
                || lowerUrl.contains("passport.taobao.com")
                || lowerUrl.contains("havanaone/login")
                || lowerUrl.contains("havanalogin")
                || lowerUrl.contains("login_jump")) {
            return false;
        }
        if (text.contains("扫码登录") || text.contains("密码登录") || text.contains("快速进入")
                || text.contains("快速登录") || text.contains("短信登录")) {
            return false;
        }
        if (text.trim().length() < 20) {
            return false;
        }
        boolean alimamaSession = hasCookieName(alimamaCookie, "cookie2_alimama")
                || hasCookieName(alimamaCookie, "t_alimama")
                || hasCookieName(alimamaCookie, "login_aliyunid_ticket")
                || hasCookieName(alimamaCookie, "pub_login_id");
        boolean taobaoUser = hasCookieName(tbCookie, "unb")
                || hasCookieName(tbCookie, "tracknick")
                || hasCookieName(tbCookie, "_nk_")
                || hasCookieName(tbCookie, "cookie2");
        return taobaoUser || alimamaSession;
    }

    private static void printWindowSize(WebBrowser browser, String stage) {
        try {
            Object value = browser.executeScript(
                    "return (window.outerWidth||0)+'x'+(window.outerHeight||0)"
                            + "+' screen='+(screen.availWidth||0)+'x'+(screen.availHeight||0);");
            System.out.println("[INFO] 浏览器窗口(" + stage + "): " + value);
        } catch (Exception e) {
            System.out.println("[WARN] 读取窗口大小失败: " + e.getMessage());
        }
    }

    private static boolean passBotCheck(WebBrowser browser) {
        System.out.println("[INFO] 先打开 https://bot.sannysoft.com/ ，检测全部通过后才打开京东和淘宝。");
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setPreflightEnabled(true);
        options.setPreflightStrict(true);
        options.setForcePreflight(true);
        options.setPreflightUrl(SannysoftFingerprintEvaluator.DEFAULT_URL);
        options.setProfileName(ChromeProfilePaths.DEFAULT_PROFILE_NAME);
        options.setInitialPageTimeoutSeconds(60);
        BrowserPreflightResult result = new BrowserPreflightService().check(browser, options, true);
        if (result.isPassed()) {
            System.out.println("[INFO] bot 检测通过。");
            return true;
        }
        System.err.println("[ERROR] bot 检测未通过，不打开京东/淘宝。 " + result.getMessage());
        Object failed = result.getDiagnostics().get("sannysoftFailedCritical");
        if (failed != null) {
            System.err.println("[ERROR] 未通过项: " + failed);
        }
        System.err.println("[ERROR] webdriverOk=" + result.getDiagnostics().get("webdriverOk")
                + " basicOk=" + result.getDiagnostics().get("basicOk")
                + " reportOk=" + result.getDiagnostics().get("reportOk"));
        return false;
    }

    private static void reportLiveCookies(WebBrowser browser, String jdTabHandle, String tbTabHandle,
                                          boolean needJd, boolean needTb) {
        if (browser == null || browser.isClosed()) {
            return;
        }
        try {
            if (needJd) {
                String raw = browser.getRawCookie("https://www.jd.com");
                System.out.println("[INFO] 京东 Cookie 标记: "
                        + marker(raw, "pt_key") + " " + marker(raw, "pt_pin") + " "
                        + marker(raw, "thor") + " " + marker(raw, "pin") + " " + marker(raw, "unick"));
            }
            if (needTb) {
                String tb = browser.getRawCookie("https://www.taobao.com");
                String ali = browser.getRawCookie("https://pub.alimama.com");
                System.out.println("[INFO] 淘宝 Cookie 标记: "
                        + marker(tb, "tracknick") + " " + marker(tb, "unb") + " " + marker(tb, "cookie2"));
                System.out.println("[INFO] 阿里妈妈 Cookie 标记: "
                        + marker(ali, "cookie2_alimama") + " " + marker(ali, "t_alimama"));
            }
        } catch (Exception e) {
            System.out.println("[WARN] 读取浏览器 Cookie 标记失败: " + e.getMessage());
        }
    }

    private static String marker(String raw, String name) {
        return name + "=" + (hasCookieName(raw, name) ? "有" : "无");
    }

    private static void switchTab(WebBrowser browser, String tabHandle, boolean bringToFront) {
        if (!Strings.isEmpty(tabHandle)) {
            browser.switchTab(tabHandle, bringToFront);
        }
    }

    private static void clickQuickEnter(WebBrowser browser) {
        try {
            Object clicked = browser.executeScript(
                    "var nodes=document.querySelectorAll('button,a,span,div');"
                            + "for(var i=0;i<nodes.length;i++){var t=(nodes[i].innerText||'').trim();"
                            + "if(t==='快速进入'||t==='快速登录'){nodes[i].click();return true;}}"
                            + "return false;");
            if (Boolean.TRUE.equals(clicked)) {
                System.out.println("[INFO] 已点击「快速进入」，等待会话 Cookie 写入...");
                Extends.sleep(1200);
            }
        } catch (Exception ignored) {
        }
    }

    private static String pageText(WebBrowser browser) {
        try {
            Object text = browser.executeScript(
                    "var body=document.body;return body?(body.innerText||'').slice(0,2000):'';");
            return text == null ? "" : String.valueOf(text);
        } catch (Exception e) {
            return "";
        }
    }

    private static void verifySavedCookies(Path profileDir, boolean needJd, boolean needTb) throws IOException {
        Path cookies = profileDir.resolve("Default/Network/Cookies");
        Path localState = profileDir.resolve("Local State");
        if (!Files.isRegularFile(cookies) || Files.size(cookies) == 0) {
            throw new IOException("Cookie 库不存在: " + cookies.toAbsolutePath());
        }
        if (!Files.isRegularFile(localState) || Files.size(localState) == 0) {
            throw new IOException("Local State 不存在，Cookie 无法解密: " + localState.toAbsolutePath());
        }
        byte[] data = Files.readAllBytes(cookies);
        if (needJd && !containsAscii(data, "thor") && !containsAscii(data, "pt_key")) {
            throw new IOException("京东登录 Cookie 未写入 " + cookies.toAbsolutePath() + "（缺少 thor/pt_key）");
        }
        if (needTb && !containsAscii(data, "tracknick") && !containsAscii(data, "cookie2")
                && !containsAscii(data, "_nk_") && !containsAscii(data, "wk_unb")) {
            throw new IOException("淘宝/阿里妈妈登录 Cookie 未写入 " + cookies.toAbsolutePath());
        }
        System.out.println("[INFO] 已核对 Cookie 库 " + cookies.toAbsolutePath() + " size=" + data.length);
    }

    static boolean containsAscii(byte[] data, String text) {
        byte[] needle = text.getBytes(StandardCharsets.US_ASCII);
        if (needle.length == 0 || data == null || data.length < needle.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void printBanner(String mode, Path profileDir) {
        System.out.println("================================================================================");
        System.out.println("                 rx-mimic 统一登录助手 (JD & Taobao / Alimama)");
        System.out.println("================================================================================");
        System.out.println("当前登录模式: " + mode.toUpperCase(Locale.ROOT));
        System.out.println("Profile: " + profileDir.toAbsolutePath());
        System.out.println("================================================================================");
    }

    private static boolean chromeUsing(Path profileDir) {
        String marker = profileDir.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return ProcessHandle.allProcesses().anyMatch(process -> {
            String command = process.info().commandLine().orElse("");
            if (command.isEmpty()) {
                return false;
            }
            String normalized = command.replace('\\', '/').toLowerCase(Locale.ROOT);
            return normalized.contains("chrome") && normalized.contains(marker);
        });
    }

    private static void waitProfileReleased(Path poolDir) {
        long deadline = System.currentTimeMillis() + 20000L;
        while (System.currentTimeMillis() < deadline) {
            Path wal = poolDir.resolve("Default/Network/Cookies-wal");
            boolean walBusy = false;
            try {
                walBusy = Files.isRegularFile(wal) && Files.size(wal) > 0;
            } catch (Exception ignored) {
            }
            if (!chromeUsing(poolDir) && !walBusy && canRead(poolDir.resolve("Default/Network/Cookies"))) {
                return;
            }
            Extends.sleep(500);
        }
        System.out.println("[WARN] 等待 Chrome 释放 Cookie 库超时，仍继续核对。");
    }

    private static boolean canRead(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return in.read() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

}
