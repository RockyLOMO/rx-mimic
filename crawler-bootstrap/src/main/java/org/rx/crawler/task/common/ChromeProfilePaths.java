package org.rx.crawler.task.common;

import org.rx.core.Extends;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chrome 登录态只放用户目录，一个 profileName 对应一个用户数据目录。
 * 默认是 %LOCALAPPDATA%/rx-mimic/chrome/common。
 * 单元测试、login.ps1、服务都不传 profileName 时用这一份。
 * 只有显式配置 profileBasePath，或请求里带了别的 profileName，才会分开。
 */
public final class ChromeProfilePaths {
    public static final String DEFAULT_PROFILE_NAME = "common";
    private static final String SAFE_PROFILE_NAME = "[^a-zA-Z0-9_\\-]";
    private static final String[] COOKIE_PAIR = {
            "Local State",
            "Default/Network/Cookies"
    };
    private static final String[] COOKIE_SIDECARS = {
            "Default/Network/Cookies-journal",
            "Default/Network/Cookies-wal",
            "Default/Network/Cookies-shm"
    };
    private static final String[] OPTIONAL_PROFILE_FILES = {
            "Default/Preferences",
            "Default/Secure Preferences"
    };

    private ChromeProfilePaths() {
    }

    public static Path userBaseDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.trim().isEmpty()) {
            return Paths.get(localAppData.trim(), "rx-mimic", "chrome").toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".rx-mimic", "chrome").toAbsolutePath().normalize();
    }

    public static Path resolveBase(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            return userBaseDir();
        }
        return Paths.get(configured.trim()).toAbsolutePath().normalize();
    }

    public static String normalize(String profileName) {
        String value = profileName == null ? "" : profileName.trim();
        if (value.isEmpty()) {
            return DEFAULT_PROFILE_NAME;
        }
        value = value.replaceAll(SAFE_PROFILE_NAME, "-");
        return value.isEmpty() ? DEFAULT_PROFILE_NAME : value;
    }

    public static Path profileDir(String configuredBase, String profileName) {
        return resolveBase(configuredBase).resolve(normalize(profileName));
    }

    public static void applyBaseOverride(ChromeProfileConfig chrome) {
        if (chrome == null) {
            return;
        }
        String override = System.getProperty("app.custom.chrome.profileBasePath");
        if (override != null && !override.trim().isEmpty()) {
            chrome.setProfileBasePath(override.trim());
        }
    }

    /**
     * 用户目录里的默认 profile 还没有 Cookie 时，从项目里的旧目录迁一次。
     * 优先 common_pool_0，那里是最近一次登录写下的库。
     */
    public static boolean importLegacyDefaultProfile(Path target) throws IOException {
        if (!isDefaultUserProfile(target)) {
            return false;
        }
        List<Path> sources = legacyCandidates();
        return importFirstAvailable(target, sources.toArray(new Path[0]));
    }

    public static boolean importFirstAvailable(Path target, Path... sources) throws IOException {
        if (target == null || hasCookieDb(target)) {
            return false;
        }
        if (sources == null) {
            return false;
        }
        for (Path source : sources) {
            if (source == null || samePath(source, target) || !hasCookieDb(source)) {
                continue;
            }
            copyCookiePair(source, target);
            return true;
        }
        return false;
    }

    /**
     * 加密密钥（Local State）和 Cookie 库必须成对复制。
     * 目标上残留的 journal/wal 会让新库读到旧事务，复制前删掉。
     */
    public static void copyCookiePair(Path source, Path target) throws IOException {
        for (String relative : COOKIE_PAIR) {
            Path src = source.resolve(relative);
            if (!Files.isRegularFile(src) || Files.size(src) == 0) {
                throw new IOException("缺少登录凭证文件: " + src.toAbsolutePath());
            }
        }
        IOException last = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                for (String relative : COOKIE_SIDECARS) {
                    Files.deleteIfExists(target.resolve(relative));
                }
                for (String relative : COOKIE_PAIR) {
                    copyFile(source.resolve(relative), target.resolve(relative));
                }
                for (String relative : OPTIONAL_PROFILE_FILES) {
                    Path src = source.resolve(relative);
                    if (Files.isRegularFile(src) && Files.size(src) > 0) {
                        copyFile(src, target.resolve(relative));
                    }
                }
                for (String relative : COOKIE_PAIR) {
                    if (Files.size(source.resolve(relative)) != Files.size(target.resolve(relative))) {
                        throw new IOException("复制后大小不一致: " + relative);
                    }
                }
                return;
            } catch (IOException e) {
                last = e;
                Extends.sleep(500);
            }
        }
        throw new IOException("复制 Cookie 库失败: " + source + " -> " + target, last);
    }

    private static void copyFile(Path src, Path dest) throws IOException {
        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    static boolean hasCookieDb(Path profileDir) {
        if (profileDir == null || !Files.isDirectory(profileDir)) {
            return false;
        }
        Path cookies = profileDir.resolve("Default/Network/Cookies");
        Path localState = profileDir.resolve("Local State");
        try {
            return Files.isRegularFile(cookies) && Files.size(cookies) > 0
                    && Files.isRegularFile(localState) && Files.size(localState) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isDefaultUserProfile(Path target) {
        if (target == null || target.getParent() == null || target.getFileName() == null) {
            return false;
        }
        return DEFAULT_PROFILE_NAME.equals(target.getFileName().toString())
                && samePath(target.getParent(), userBaseDir());
    }

    static List<Path> legacyCandidates() {
        List<Path> found = new ArrayList<Path>();
        Path cursor = Paths.get("").toAbsolutePath().normalize();
        for (int i = 0; i < 4 && cursor != null; i++) {
            if (Files.exists(cursor.resolve("pom.xml")) || Files.exists(cursor.resolve("crawler-bootstrap"))) {
                found.add(cursor.resolve("data").resolve("chrome").resolve("common_pool_0"));
                found.add(cursor.resolve("data").resolve("chrome").resolve("common"));
            }
            cursor = cursor.getParent();
        }
        return found;
    }

    private static boolean samePath(Path left, Path right) {
        if (left == null || right == null) {
            return false;
        }
        String a = left.toAbsolutePath().normalize().toString();
        String b = right.toAbsolutePath().normalize().toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return a.equalsIgnoreCase(b);
        }
        return a.equals(b);
    }
}
