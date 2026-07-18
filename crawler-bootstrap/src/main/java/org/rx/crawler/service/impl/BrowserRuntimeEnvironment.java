package org.rx.crawler.service.impl;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行时环境探测：指纹/语言/时区/本机 Chrome 一律跟当前机器走，禁止写死开发机参数。
 * <p>
 * 说明：本项目使用 Playwright + 本机 Chrome channel，不使用 Selenium ChromeDriver。
 * Playwright Java 驱动随 maven 依赖自带，无需按 Chrome 版本另下 chromedriver。
 */
@Slf4j
public final class BrowserRuntimeEnvironment {
    private static final Pattern CHROME_VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)");
    private static volatile ChromeInstall cachedChrome;
    private static volatile String cachedLocale;
    private static volatile String cachedTimezone;

    private BrowserRuntimeEnvironment() {
    }

    /**
     * 配置为空时自动探测本机默认语言（BCP47，如 zh-CN / en-US）。
     */
    public static String resolveLocale(String configured) {
        if (!Strings.isEmpty(configured)) {
            return normalizeLocaleTag(configured);
        }
        return detectLocale();
    }

    /**
     * 配置为空时自动探测本机默认时区（如 Asia/Shanghai / America/New_York）。
     */
    public static String resolveTimezone(String configured) {
        if (!Strings.isEmpty(configured)) {
            return configured.trim();
        }
        return detectTimezone();
    }

    public static String detectLocale() {
        String cached = cachedLocale;
        if (cached != null) {
            return cached;
        }
        Locale locale = Locale.getDefault();
        String tag = locale.toLanguageTag();
        if (Strings.isEmpty(tag) || "und".equalsIgnoreCase(tag)) {
            tag = locale.getLanguage();
            if (!Strings.isEmpty(locale.getCountry())) {
                tag = tag + "-" + locale.getCountry();
            }
        }
        tag = normalizeLocaleTag(tag);
        if (Strings.isEmpty(tag)) {
            tag = "en-US";
        }
        cachedLocale = tag;
        return tag;
    }

    public static String detectTimezone() {
        String cached = cachedTimezone;
        if (cached != null) {
            return cached;
        }
        String zone = ZoneId.systemDefault().getId();
        if (Strings.isEmpty(zone)) {
            zone = "UTC";
        }
        cachedTimezone = zone;
        return zone;
    }

    public static String normalizeLocaleTag(String locale) {
        if (Strings.isEmpty(locale)) {
            return "";
        }
        String tag = locale.trim().replace('_', '-');
        // zh-cn -> zh-CN
        String[] parts = tag.split("-");
        if (parts.length == 0) {
            return tag;
        }
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() == 2 && p.chars().allMatch(Character::isLetter)) {
                sb.append('-').append(p.toUpperCase(Locale.ROOT));
            } else if (p.length() == 4) {
                sb.append('-').append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase(Locale.ROOT));
            } else {
                sb.append('-').append(p);
            }
        }
        return sb.toString();
    }

    /**
     * 探测本机已安装的 Google Chrome（Windows 常见路径 + PATH）。
     * 结果缓存；测试可用 {@link #clearCache()} 重置。
     */
    public static ChromeInstall detectInstalledChrome() {
        ChromeInstall cached = cachedChrome;
        if (cached != null) {
            return cached;
        }
        ChromeInstall found = probeInstalledChrome();
        cachedChrome = found;
        return found;
    }

    /**
     * 启动前校验：使用 channel=chrome 或未指定可执行文件时，确认本机 Chrome 可用并记录版本。
     * 不下载 ChromeDriver（Playwright 不需要）；也不强制下载 Playwright 自带浏览器。
     */
    public static ChromeInstall ensureChromeForChannel(String channel, String executablePath) {
        if (!Strings.isEmpty(executablePath)) {
            Path path = Paths.get(executablePath);
            ChromeInstall install = new ChromeInstall();
            install.setExecutablePath(path.toAbsolutePath().toString());
            install.setFound(Files.isRegularFile(path));
            if (install.isFound()) {
                install.setVersion(readChromeVersion(path));
            }
            log.info("Chrome executable configured, path={}, found={}, version={}",
                    install.getExecutablePath(), install.isFound(), install.getVersion());
            if (!install.isFound()) {
                throw new IllegalStateException("Configured playwrightExecutablePath not found: " + executablePath);
            }
            return install;
        }

        String ch = Strings.isEmpty(channel) ? "chrome" : channel.trim().toLowerCase(Locale.ROOT);
        if (!"chrome".equals(ch) && !"chrome-beta".equals(ch) && !"chrome-dev".equals(ch) && !"msedge".equals(ch)) {
            log.info("Playwright channel={} is not a system browser channel, skip local Chrome ensure", channel);
            return null;
        }

        ChromeInstall install = detectInstalledChrome();
        if ("msedge".equals(ch)) {
            // 当前探测以 Chrome 为主；Edge 由 Playwright channel 自行解析，仅记录。
            log.info("Playwright channel=msedge, local Chrome probe optional path={}, version={}",
                    install.getExecutablePath(), install.getVersion());
            return install;
        }
        if (!install.isFound()) {
            throw new IllegalStateException(
                    "Local Google Chrome not found. Install Chrome or set app.browser.playwrightExecutablePath. "
                            + "This project uses system Chrome (no ChromeDriver download).");
        }
        log.info("Local Chrome ready for Playwright channel={}, path={}, version={}",
                ch, install.getExecutablePath(), install.getVersion());
        return install;
    }

    public static void clearCache() {
        cachedChrome = null;
        cachedLocale = null;
        cachedTimezone = null;
    }

    private static ChromeInstall probeInstalledChrome() {
        ChromeInstall install = new ChromeInstall();
        List<Path> candidates = new ArrayList<Path>();
        String localAppData = System.getenv("LOCALAPPDATA");
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        if (!Strings.isEmpty(localAppData)) {
            candidates.add(Paths.get(localAppData, "Google", "Chrome", "Application", "chrome.exe"));
        }
        if (!Strings.isEmpty(programFiles)) {
            candidates.add(Paths.get(programFiles, "Google", "Chrome", "Application", "chrome.exe"));
        }
        if (!Strings.isEmpty(programFilesX86)) {
            candidates.add(Paths.get(programFilesX86, "Google", "Chrome", "Application", "chrome.exe"));
        }
        candidates.add(Paths.get("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"));
        candidates.add(Paths.get("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"));
        // mac / linux common
        candidates.add(Paths.get("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
        candidates.add(Paths.get("/usr/bin/google-chrome"));
        candidates.add(Paths.get("/usr/bin/google-chrome-stable"));
        candidates.add(Paths.get("/usr/bin/chromium-browser"));

        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                install.setFound(true);
                install.setExecutablePath(candidate.toAbsolutePath().toString());
                install.setVersion(readChromeVersion(candidate));
                return install;
            }
        }

        String which = resolveOnPath(isWindows() ? "chrome.exe" : "google-chrome");
        if (!Strings.isEmpty(which) && Files.isRegularFile(Paths.get(which))) {
            install.setFound(true);
            install.setExecutablePath(which);
            install.setVersion(readChromeVersion(Paths.get(which)));
        }
        return install;
    }

    private static String readChromeVersion(Path chromePath) {
        if (chromePath == null || !Files.isRegularFile(chromePath)) {
            return "";
        }
        // 1) 优先读安装目录下的版本号文件夹（Chrome 已运行时 --version 常打印 "Opening in existing browser session."）
        Path parent = chromePath.getParent();
        if (parent != null && Files.isDirectory(parent)) {
            try {
                String dirVersion = Files.list(parent)
                        .filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(name -> CHROME_VERSION_PATTERN.matcher(name).matches())
                        .sorted()
                        .reduce((a, b) -> b) // 取字典序最后一个，通常是最新
                        .orElse("");
                if (!Strings.isEmpty(dirVersion)) {
                    return dirVersion;
                }
            } catch (Exception ignored) {
            }
        }
        // 2) 命令行探测（过滤无用输出）
        String fromCmd = runVersionCommand(chromePath.toAbsolutePath().toString(), "--product-version");
        if (Strings.isEmpty(fromCmd) || !looksLikeChromeVersionOutput(fromCmd)) {
            fromCmd = runVersionCommand(chromePath.toAbsolutePath().toString(), "--version");
        }
        if (!Strings.isEmpty(fromCmd) && looksLikeChromeVersionOutput(fromCmd)) {
            Matcher matcher = CHROME_VERSION_PATTERN.matcher(fromCmd);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return fromCmd.trim();
        }
        return "";
    }

    private static boolean looksLikeChromeVersionOutput(String text) {
        if (Strings.isEmpty(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("opening in existing") || lower.contains("browser session")) {
            return false;
        }
        return CHROME_VERSION_PATTERN.matcher(text).find() || lower.contains("chrome");
    }

    private static String runVersionCommand(String executable, String arg) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(executable, arg);
            pb.redirectErrorStream(true);
            // Windows 下尽量不弹窗
            pb.environment().putIfAbsent("CHROME_LOG_FILE", "NUL");
            process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(line.trim());
                }
                return sb.toString().trim();
            }
        } catch (Exception e) {
            log.debug("Read chrome version fail, exe={}, arg={}, error={}", executable, arg, e.getMessage());
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String resolveOnPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (Strings.isEmpty(pathEnv)) {
            return null;
        }
        String[] parts = pathEnv.split(Pattern.quote(java.io.File.pathSeparator));
        for (String part : parts) {
            if (Strings.isEmpty(part)) {
                continue;
            }
            Path candidate = Paths.get(part, command);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    @Data
    public static class ChromeInstall {
        private boolean found;
        private String executablePath = "";
        private String version = "";
    }
}
