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
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final AppConfig appConfig;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BrowserOwnerExecutor ownerExecutor;
    private final java.util.concurrent.atomic.AtomicBoolean warming = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean browserReady;
    private volatile long nextWarmupMillis;

    public boolean isBrowserReady() { return browserReady; }
    public boolean isPreWarmEnabled() { return appConfig.getCustom().getChrome().isPreWarm(); }
    public void requestWarmup() {
        if (!browserReady && System.currentTimeMillis() >= nextWarmupMillis) { warmup(true); }
    }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DesktopAvailability desktopAvailability;

    private void requireOwner() {
        if (ownerExecutor != null && !ownerExecutor.isOwnerThread()) {
            throw new IllegalStateException("Browser operations must run on chrome-owner");
        }
    }
    private final Map<String, ProfilePool> profilePools = new ConcurrentHashMap<>();
    private java.util.function.BiFunction<WebBrowserConfig, BrowserType, WebBrowser> browserFactory = WebBrowser::new;

    public void setBrowserFactory(java.util.function.BiFunction<WebBrowserConfig, BrowserType, WebBrowser> browserFactory) {
        this.browserFactory = browserFactory;
    }

    @PostConstruct
    public void init() { warmup(false); }

    private void warmup(boolean required) {
        if ((required || appConfig.getCustom().getChrome().isPreWarm()) && warming.compareAndSet(false, true)) {
            int minIdle = required ? 1 : appConfig.getCustom().getChrome().getMinIdle();
            if (minIdle > 0) {
                String defaultProfile = defaultProfileName();
                Runnable prewarm = () -> {
                    if (desktopAvailability != null && !desktopAvailability.isAvailable()) { warming.set(false); return; }
                    try {
                        WebBrowserConfig config = org.rx.util.BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
                        try (ProfileLease ignored = acquire(defaultProfile, config)) {
                            browserReady = true;
                            log.info("Pre-warmed chrome profile {}", defaultProfile);
                        }
                    } catch (Exception e) {
                        browserReady = false;
                        nextWarmupMillis = System.currentTimeMillis() + 60000;
                        log.warn("Pre-warming chrome profile failed: {}", e.getMessage(), e);
                    } finally { warming.set(false); }
                };
                if (ownerExecutor != null) {
                    ownerExecutor.execute(prewarm);
                } else {
                    prewarm.run();
                }
            } else { warming.set(false); }
        }
    }

    public String defaultProfileName() {
        String profileName = appConfig.getCustom().getChrome().getDefaultProfileName();
        return Strings.isEmpty(profileName) ? "common" : normalizeProfileName(profileName);
    }

    public String normalizeProfileName(String profileName) {
        if (Strings.isEmpty(profileName)) {
            return defaultProfileName();
        }
        return ChromeProfilePaths.normalize(profileName);
    }

    public String resolveBaseProfileDir() {
        return ChromeProfilePaths.resolveBase(appConfig.getCustom().getChrome().getProfileBasePath()).toString();
    }

    public String resolveProfileDataPath(String profileName) {
        Path path = ChromeProfilePaths.profileDir(resolveBaseProfileDir(), normalizeProfileName(profileName));
        try {
            Files.createDirectories(path);
            if (ChromeProfilePaths.importLegacyDefaultProfile(path)) {
                log.info("Imported legacy chrome cookies into {}", path);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Create chrome profile path fail: " + path, e);
        }
        return path.toAbsolutePath().normalize().toString();
    }

    /**
     * 同一个 profileName 不再拆 common_pool_N。index 只保留给旧调用，目录始终是这一份。
     */
    public String resolveInstanceProfilePath(String profileName, int index) {
        return resolveProfileDataPath(profileName);
    }

    public ProfileLease acquire(String profileName, WebBrowserConfig config) {
        requireOwner();
        String normalized = normalizeProfileName(profileName);
        ProfilePool pool = profilePools.computeIfAbsent(normalized, k -> new ProfilePool(normalized));
        try (TaskTiming.Span ignored = TaskTiming.span("browser.acquire", normalized)) {
            return pool.acquire(config);
        }
    }

    private void focus(WebBrowser browser) {
        try {
            browser.focus();
        } catch (Exception e) {
            log.debug("Focus chrome profile ignored, error={}", e.getMessage());
        }
    }

    public boolean closeSession(String profileName) {
        if (ownerExecutor != null && !ownerExecutor.isOwnerThread()) {
            return ownerExecutor.call(() -> closeSession(profileName));
        }
        String normalized = normalizeProfileName(profileName);
        ProfilePool pool = profilePools.get(normalized);
        if (pool == null) {
            return false;
        }
        pool.closeAll();
        browserReady = activeSessionCount() > 0;
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
        if (ownerExecutor != null && !ownerExecutor.isOwnerThread()) {
            ownerExecutor.call(() -> { destroy(); return null; });
            return;
        }
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
            this.maxActive = 1;
            this.minIdle = appConfig.getCustom().getChrome().getMinIdle();
        }

        public ProfileLease acquire(WebBrowserConfig config) {
            lock.lock();
            try {
                // Clean up expired or closed idle sessions
                idleBrowsers.removeIf(session -> {
                    if (session.isExpired() || session.getBrowser().isSessionUnavailable()) {
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
                        if (session.isExpired() || session.getBrowser().isSessionUnavailable()) {
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

                    String instanceProfilePath = resolveProfileDataPath(profileName);

                    WebBrowserConfig instanceConfig = org.rx.util.BeanMapper.DEFAULT.map(config, WebBrowserConfig.class);
                    instanceConfig.setProfileDataPath(instanceProfilePath);
                    browser = browserFactory.apply(instanceConfig, BrowserType.CHROME);
                    browser.setSessionInvalidationListener(() -> browserReady = false);

                    activeBrowsers.add(browser);
                    browserPoolIndices.put(browser, poolIndex);
                    fromSession = false;
                    log.info("Open chrome profile {} instance {} at {}", profileName, poolIndex, instanceProfilePath);
                }

                browserReady = true;
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

                boolean closeBrowser = appConfig.getCustom().getChrome().isCloseBrowserAfterTask() || browser.isSessionUnavailable();
                if (closeBrowser) {
                    closeBrowser(browser);
                    browserReady = false;
                    log.info("Close chrome profile {} after task", profileName);
                } else if (keepOpen) {
                    long expireAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(keepOpenSeconds);
                    idleBrowsers.add(new ProfileSession(browser, expireAt));
                    log.info("Keep chrome profile {} instance open {} seconds", profileName, keepOpenSeconds);
                } else {
                    if (idleBrowsers.size() < minIdle && !browser.isSessionUnavailable()) {
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
            requireOwner();
            try (TaskCancellation.Scope ignored = TaskCancellation.suspend()) {
                pool.release(browser, keepOpen, keepOpenSeconds);
            }
        }
    }
}
