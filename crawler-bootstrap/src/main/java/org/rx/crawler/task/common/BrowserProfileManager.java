package org.rx.crawler.task.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.crawler.service.BrowserType;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.rx.core.Extends.tryClose;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserProfileManager {
    private static final String SAFE_PROFILE_NAME = "[^a-zA-Z0-9_\\-]";

    private final AppConfig appConfig;
    private final Map<String, ProfilePool> profilePools = new ConcurrentHashMap<>();
    private java.util.function.BiFunction<WebBrowserConfig, BrowserType, WebBrowser> browserFactory = WebBrowser::new;

    public void setBrowserFactory(java.util.function.BiFunction<WebBrowserConfig, BrowserType, WebBrowser> browserFactory) {
        this.browserFactory = browserFactory;
    }

    @PostConstruct
    public void init() {
        if (appConfig.getCustom().getChrome().isPreWarm()) {
            int minIdle = appConfig.getCustom().getChrome().getMinIdle();
            if (minIdle > 0) {
                String defaultProfile = defaultProfileName();
                new Thread(() -> {
                    try {
                        log.info("Pre-warming chrome profile {} with {} instances...", defaultProfile, minIdle);
                        List<ProfileLease> leases = new ArrayList<>();
                        for (int i = 0; i < minIdle; i++) {
                            WebBrowserConfig config = org.rx.util.BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
                            leases.add(acquire(defaultProfile, config));
                        }
                        for (ProfileLease lease : leases) {
                            lease.close();
                        }
                        log.info("Pre-warming chrome profile {} completed.", defaultProfile);
                    } catch (Exception e) {
                        log.warn("Pre-warming chrome profile failed: {}", e.getMessage(), e);
                    }
                }, "chrome-prewarm").start();
            }
        }
    }

    public String defaultProfileName() {
        String profileName = appConfig.getCustom().getChrome().getDefaultProfileName();
        return Strings.isEmpty(profileName) ? "common" : normalizeProfileName(profileName);
    }

    public String normalizeProfileName(String profileName) {
        String value = Strings.isEmpty(profileName) ? defaultProfileName() : profileName.trim();
        value = value.replaceAll(SAFE_PROFILE_NAME, "-");
        return Strings.isEmpty(value) ? "common" : value;
    }

    public String resolveBaseProfileDir() {
        String basePath = appConfig.getCustom().getChrome().getProfileBasePath();
        if (Strings.isEmpty(basePath)) {
            basePath = new ChromeProfileConfig().getProfileBasePath();
        }
        Path path = Paths.get(basePath);
        if (path.isAbsolute()) {
            return path.normalize().toString();
        }
        Path parentCheck = Paths.get("..", basePath).normalize();
        if (Files.exists(parentCheck.resolve("common")) && Files.exists(Paths.get("..", "pom.xml"))) {
            return parentCheck.toAbsolutePath().normalize().toString();
        }
        if (Files.exists(path.resolve("common"))) {
            return path.toAbsolutePath().normalize().toString();
        }
        if (Files.exists(parentCheck) && Files.exists(Paths.get("..", "pom.xml"))) {
            return parentCheck.toAbsolutePath().normalize().toString();
        }
        return path.toAbsolutePath().normalize().toString();
    }

    public String resolveProfileDataPath(String profileName) {
        String basePath = resolveBaseProfileDir();
        String path = Paths.get(basePath, normalizeProfileName(profileName)).toString();
        try {
            java.nio.file.Files.createDirectories(Paths.get(path));
        } catch (Exception e) {
            throw new IllegalStateException("Create chrome profile path fail: " + path, e);
        }
        return path;
    }

    public String resolveInstanceProfilePath(String profileName, int index) {
        String basePath = resolveBaseProfileDir();
        String path = Paths.get(basePath, normalizeProfileName(profileName) + "_pool_" + index).toString();
        try {
            java.nio.file.Files.createDirectories(Paths.get(path));
        } catch (Exception e) {
            throw new IllegalStateException("Create chrome pool profile path fail: " + path, e);
        }
        return path;
    }

    private void cloneProfileDirectory(String sourcePath, String targetPath) {
        try {
            Path source = Paths.get(sourcePath);
            Path target = Paths.get(targetPath);
            if (!Files.exists(source)) {
                return;
            }
            // 池实例目录若已有 Chrome 会话数据，禁止再用 common 源目录覆盖。
            // 否则每次新开进程都会把 common_pool_0 里已登录的 Cookie 冲掉，表现为「每次测试都要登录」。
            if (hasUsableChromeProfile(target)) {
                log.info("Reuse existing pool profile session at {}, skip reseed from {}", targetPath, sourcePath);
                return;
            }
            if (!Files.exists(target)) {
                Files.createDirectories(target);
            }
            log.info("Seed pool profile {} from {}", targetPath, sourcePath);
            syncCopy(source, target);
        } catch (Exception e) {
            log.warn("Clone profile directory failed from {} to {}, error={}", sourcePath, targetPath, e.getMessage(), e);
        }
    }

    /**
     * 判断目录是否已是可用的持久化 Chrome profile（有 Local State / Cookies 等），
     * 有则说明上次登录态应保留在池实例中。
     */
    private boolean hasUsableChromeProfile(Path profileDir) {
        if (profileDir == null || !Files.isDirectory(profileDir)) {
            return false;
        }
        String[] markers = {
                "Local State",
                "Default/Cookies",
                "Default/Network/Cookies",
                "Default/Login Data",
                "Default/Preferences"
        };
        for (String marker : markers) {
            Path p = profileDir.resolve(marker);
            try {
                if (Files.isRegularFile(p) && Files.size(p) > 0) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void syncCopy(java.nio.file.Path source, java.nio.file.Path target) throws java.io.IOException {
        java.nio.file.Files.walk(source).forEach(sourcePath -> {
            try {
                java.nio.file.Path targetPath = target.resolve(source.relativize(sourcePath));
                String relPath = source.relativize(sourcePath).toString().replace("\\", "/");
                if (isExcludePath(relPath)) {
                    return;
                }
                
                if (java.nio.file.Files.isDirectory(sourcePath)) {
                    if (!java.nio.file.Files.exists(targetPath)) {
                        java.nio.file.Files.createDirectories(targetPath);
                    }
                } else {
                    if (!java.nio.file.Files.exists(targetPath) || 
                        java.nio.file.Files.getLastModifiedTime(sourcePath).toMillis() > java.nio.file.Files.getLastModifiedTime(targetPath).toMillis() ||
                        java.nio.file.Files.size(sourcePath) != java.nio.file.Files.size(targetPath)) {
                        try {
                            java.nio.file.Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (java.io.IOException e) {
                            log.debug("Ignore copy file error: {} -> {}, error={}", sourcePath, targetPath, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Ignore copy error for path {}: {}", sourcePath, e.getMessage());
            }
        });
    }
    
    private boolean isExcludePath(String relPath) {
        String lower = relPath.toLowerCase();
        return lower.contains("/cache/") 
            || lower.contains("/code cache/") 
            || lower.contains("/gpucache/") 
            || lower.contains("/service worker/")
            || lower.contains("/crashpad/")
            || lower.contains("/lock")
            || lower.endsWith("/lock")
            || lower.contains("/singletoncookie")
            || lower.contains("/singletonsocket")
            || lower.contains("/singletonlock");
    }

    public ProfileLease acquire(String profileName, WebBrowserConfig config) {
        String normalized = normalizeProfileName(profileName);
        ProfilePool pool = profilePools.computeIfAbsent(normalized, k -> new ProfilePool(normalized));
        return pool.acquire(config);
    }

    private void focus(WebBrowser browser) {
        try {
            browser.focus();
        } catch (Exception e) {
            log.debug("Focus chrome profile ignored, error={}", e.getMessage());
        }
    }

    public boolean closeSession(String profileName) {
        String normalized = normalizeProfileName(profileName);
        ProfilePool pool = profilePools.get(normalized);
        if (pool == null) {
            return false;
        }
        pool.closeAll();
        return true;
    }

    public int activeSessionCount() {
        int count = 0;
        for (ProfilePool pool : profilePools.values()) {
            count += pool.activeCount();
        }
        return count;
    }

    @PreDestroy
    public void destroy() {
        for (ProfilePool pool : profilePools.values()) {
            pool.closeAll();
        }
        profilePools.clear();
    }

    @Getter
    private static class ProfileSession {
        private final WebBrowser browser;
        private final long expireAtMillis;

        ProfileSession(WebBrowser browser, long expireAtMillis) {
            this.browser = browser;
            this.expireAtMillis = expireAtMillis;
        }

        boolean isExpired() {
            return expireAtMillis > 0 && System.currentTimeMillis() > expireAtMillis;
        }
    }

    private class ProfilePool {
        private final String profileName;
        private final int maxActive;
        private final int minIdle;
        private final List<ProfileSession> idleBrowsers = new ArrayList<>();
        private final Set<WebBrowser> activeBrowsers = new HashSet<>();
        private final Map<WebBrowser, Integer> browserPoolIndices = new ConcurrentHashMap<>();
        private final Set<Integer> allocatedIndices = ConcurrentHashMap.newKeySet();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition hasIdleOrCanCreate = lock.newCondition();

        public ProfilePool(String profileName) {
            this.profileName = profileName;
            this.maxActive = appConfig.getCustom().getChrome().getMaxActive();
            this.minIdle = appConfig.getCustom().getChrome().getMinIdle();
        }

        public ProfileLease acquire(WebBrowserConfig config) {
            lock.lock();
            try {
                // Clean up expired or closed idle sessions
                idleBrowsers.removeIf(session -> {
                    if (session.isExpired() || session.getBrowser().isClosed()) {
                        closeBrowser(session.getBrowser());
                        return true;
                    }
                    return false;
                });

                // Wait until there is an idle browser, or we can create a new one
                while (idleBrowsers.isEmpty() && activeBrowsers.size() >= maxActive) {
                    log.info("Chrome profile pool {} is full (active={}), waiting for release...", profileName, activeBrowsers.size());
                    hasIdleOrCanCreate.await();
                    
                    idleBrowsers.removeIf(session -> {
                        if (session.isExpired() || session.getBrowser().isClosed()) {
                            closeBrowser(session.getBrowser());
                            return true;
                        }
                        return false;
                    });
                }

                WebBrowser browser;
                boolean fromSession;
                int poolIndex;

                if (!idleBrowsers.isEmpty()) {
                    ProfileSession session = idleBrowsers.remove(idleBrowsers.size() - 1);
                    browser = session.getBrowser();
                    // 预热实例按全局配置创建（可能未开指纹注入），任务方开启去自动化时补注册，对后续导航生效
                    try {
                        browser.ensureFingerprintScripts(config);
                    } catch (Exception e) {
                        log.debug("Ensure fingerprint scripts on reuse ignored, error={}", e.getMessage());
                    }
                    activeBrowsers.add(browser);
                    fromSession = true;
                    poolIndex = browserPoolIndices.getOrDefault(browser, 0);
                    log.info("Reuse chrome profile session {} index {}", profileName, poolIndex);
                } else {
                    poolIndex = -1;
                    for (int i = 0; i < maxActive; i++) {
                        if (!allocatedIndices.contains(i)) {
                            poolIndex = i;
                            break;
                        }
                    }
                    if (poolIndex == -1) {
                        poolIndex = activeBrowsers.size();
                    }
                    allocatedIndices.add(poolIndex);

                    String instanceProfilePath = resolveInstanceProfilePath(profileName, poolIndex);
                    cloneProfileDirectory(resolveProfileDataPath(profileName), instanceProfilePath);

                    WebBrowserConfig instanceConfig = org.rx.util.BeanMapper.DEFAULT.map(config, WebBrowserConfig.class);
                    instanceConfig.setProfileDataPath(instanceProfilePath);
                    browser = browserFactory.apply(instanceConfig, BrowserType.CHROME);

                    activeBrowsers.add(browser);
                    browserPoolIndices.put(browser, poolIndex);
                    fromSession = false;
                    log.info("Open chrome profile {} instance {} at {}", profileName, poolIndex, instanceProfilePath);
                }

                focus(browser);
                return new ProfileLease(profileName, this, browser, fromSession);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while acquiring chrome profile: " + profileName, e);
            } finally {
                lock.unlock();
            }
        }

        public void release(WebBrowser browser, boolean keepOpen, long keepOpenSeconds) {
            lock.lock();
            try {
                activeBrowsers.remove(browser);

                try {
                    browser.saveCookies(false);
                } catch (Exception e) {
                    log.warn("Save chrome profile cookies fail, profile={}, error={}", profileName, e.getMessage());
                }

                boolean closeBrowser = appConfig.getCustom().getChrome().isCloseBrowserAfterTask();
                if (closeBrowser) {
                    closeBrowser(browser);
                    log.info("Close chrome profile {} after task", profileName);
                } else if (keepOpen) {
                    long expireAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(keepOpenSeconds);
                    idleBrowsers.add(new ProfileSession(browser, expireAt));
                    log.info("Keep chrome profile {} instance open {} seconds", profileName, keepOpenSeconds);
                } else {
                    if (idleBrowsers.size() < minIdle && !browser.isClosed()) {
                        idleBrowsers.add(new ProfileSession(browser, 0));
                        log.info("Recycle chrome profile {} instance to pool, current idle size={}", profileName, idleBrowsers.size());
                    } else {
                        closeBrowser(browser);
                        log.info("Close excess chrome profile {} instance, current idle size={}", profileName, idleBrowsers.size());
                    }
                }
                hasIdleOrCanCreate.signal();
            } finally {
                lock.unlock();
            }
        }

        public int activeCount() {
            lock.lock();
            try {
                return activeBrowsers.size() + idleBrowsers.size();
            } finally {
                lock.unlock();
            }
        }

        public void closeAll() {
            lock.lock();
            try {
                for (ProfileSession session : idleBrowsers) {
                    tryClose(session.getBrowser());
                }
                idleBrowsers.clear();
                for (WebBrowser browser : activeBrowsers) {
                    tryClose(browser);
                }
                activeBrowsers.clear();
                allocatedIndices.clear();
                browserPoolIndices.clear();
            } finally {
                lock.unlock();
            }
        }

        private void closeBrowser(WebBrowser browser) {
            Integer poolIndex = browserPoolIndices.remove(browser);
            if (poolIndex != null) {
                allocatedIndices.remove(poolIndex);
            }
            tryClose(browser);
        }
    }

    public class ProfileLease implements AutoCloseable {
        @Getter
        private final String profileName;
        private final ProfilePool pool;
        @Getter
        private final WebBrowser browser;
        @Getter
        private final boolean fromSession;
        private boolean keepOpen;
        private long keepOpenSeconds;

        ProfileLease(String profileName, ProfilePool pool, WebBrowser browser, boolean fromSession) {
            this.profileName = profileName;
            this.pool = pool;
            this.browser = browser;
            this.fromSession = fromSession;
        }

        public void keepOpen(long seconds) {
            keepOpen = true;
            keepOpenSeconds = seconds <= 0 ? TimeUnit.MINUTES.toSeconds(5) : seconds;
        }

        @Override
        public void close() {
            pool.release(browser, keepOpen, keepOpenSeconds);
        }
    }
}
