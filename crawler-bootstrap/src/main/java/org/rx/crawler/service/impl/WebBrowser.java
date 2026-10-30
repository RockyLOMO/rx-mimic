package org.rx.crawler.service.impl;

import com.google.gson.JsonObject;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.ViewportSize;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;
import org.rx.core.Delegate;
import org.rx.core.Disposable;
import org.rx.core.EventArgs;
import org.rx.core.EventPublisher;
import org.rx.core.Extends;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.core.cache.MemoryCache;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.TabPrefetch;
import org.rx.crawler.task.common.SensitiveUrls;
import org.rx.crawler.task.common.TaskTiming;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.crawler.service.ConfigureScriptExecutor;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.io.Files;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpClientCookieJar;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.require;
import static org.rx.core.Sys.cacheKey;

@Slf4j
public final class WebBrowser extends Disposable implements Browser, EventPublisher<WebBrowser> {
    static final String RESOURCE_JS_PATH = "/bot/root.js";
    static final AtomicInteger chromeIdCounter = new AtomicInteger();
    static final Map<String, String> jsResourceCache = new ConcurrentHashMap<String, String>();

    public final Delegate<WebBrowser, EventArgs> onNavigating = Delegate.create(), onNavigated = Delegate.create();
    final WebBrowserConfig config;
    final HttpClientCookieJar cookieJar;
    final ConfigureScriptExecutor configureScriptExecutor;
    final Set<String> injectedScript = ConcurrentHashMap.newKeySet();
    final Map<String, Page> tabs = new LinkedHashMap<String, Page>();
    /** 后台标签承诺要打开的起始页。只在 chrome-owner 上读写。 */
    private final Map<String, String> prefetchTargetByHandle = new HashMap<String, String>();
    /** Adopted prefetch tabs remain disposable when inactive, even after their target marker is cleared. */
    private final Set<Page> prefetchOwnedTabs = new HashSet<>();
    @Getter
    @Setter
    private String cookieRegion;
    @Getter
    @Setter
    private long waitMillis = 500;
    private Playwright playwright;
    private BrowserContext context;
    private volatile boolean sessionClosed;
    @Setter
    private Runnable sessionInvalidationListener = () -> { };
    private Page page;
    private Frame activeFrame;
    private volatile String currentHandle;
    private volatile String navigatedUrl, navigatedSelector;
    private volatile boolean fingerprintScriptsRegistered;
    private double mouseX = 160, mouseY = 120;
    private static final int MAX_CAPTURED_RESPONSES = 8;
    private final Map<String, java.util.Deque<String>> responseCaptures = new ConcurrentHashMap<String, java.util.Deque<String>>();

    @Override
    public org.rx.crawler.service.BrowserType getType() {
        return org.rx.crawler.service.BrowserType.CHROME;
    }

    public String getCurrentUrl() {
        org.rx.crawler.task.common.TaskCancellation.check();
        return page.url();
    }

    public String getCurrentHandle() {
        return currentHandle;
    }

    public BrowserWindowRect getWindowRectangle() {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();

        ViewportSize size = page.viewportSize();
        BrowserWindowRect rect = config.getWindowRectangle();
        int x = rect == null ? 0 : rect.getX();
        int y = rect == null ? 0 : rect.getY();
        if (size == null) {
            return rect == null ? new BrowserWindowRect(x, y, 0, 0) : rect;
        }
        return new BrowserWindowRect(x, y, size.width, size.height);
    }

    public void setWindowRectangle(@NonNull BrowserWindowRect rectangle) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();

        if (isMaximized(rectangle)) {
            maximize();
            return;
        }
        page.setViewportSize(rectangle.getWidth(), rectangle.getHeight());
        Map<String, Object> rect = new LinkedHashMap<String, Object>();
        rect.put("x", rectangle.getX());
        rect.put("y", rectangle.getY());
        rect.put("width", rectangle.getWidth());
        rect.put("height", rectangle.getHeight());
        page.evaluate("rect => { try { window.moveTo(rect.x, rect.y); window.resizeTo(rect.width, rect.height); } catch (e) {} }", rect);
    }

    @SneakyThrows
    public WebBrowser(@NonNull WebBrowserConfig config, @NonNull org.rx.crawler.service.BrowserType type) {
        if (type != org.rx.crawler.service.BrowserType.CHROME) {
            throw new InvalidException("Only CHROME is supported by Playwright implementation, type={}", type);
        }
        this.config = config;
        this.waitMillis = config.getWaitMillis();
        this.cookieJar = ifNull(config.getCookieJar(), HttpClientCookieJar.memory());
        this.configureScriptExecutor = org.rx.core.Reflects.newInstance(Class.forName(config.getConfigureScriptExecutorType()), this);
        createChromeContext();
    }

    private void createChromeContext() {
        String userDataPath = resolveUserDataPath();
        // 语言/时区：配置为空则不覆盖，交给本机 Chrome + profile + 系统设置（最像真人）。
        // 仅当配置显式指定时才 setLocale / --lang / Accept-Language。
        boolean localeForced = !Strings.isEmpty(config.getLocale());
        boolean timezoneForced = !Strings.isEmpty(config.getTimezoneId());
        String locale = localeForced
                ? BrowserRuntimeEnvironment.resolveLocale(config.getLocale())
                : null;
        String timezoneId = timezoneForced
                ? BrowserRuntimeEnvironment.resolveTimezone(config.getTimezoneId())
                : null;
        // 使用本机 Chrome channel：确认已安装并记录版本（无需 ChromeDriver）。
        BrowserRuntimeEnvironment.ChromeInstall chromeInstall = BrowserRuntimeEnvironment.ensureChromeForChannel(
                config.getPlaywrightChannel(), config.getPlaywrightExecutablePath());

        Map<String, String> env = new HashMap<String, String>(System.getenv());
        // 系统 Chrome 模式下跳过 Playwright 自带浏览器下载；驱动本体随 maven 依赖自带。
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));

        BrowserWindowRect rect = config.getWindowRectangle();
        boolean maximized = isMaximized(rect);
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(isHeadless())
                .setAcceptDownloads(true)
                // Fiddler/MITM 场景需要；正式 HTTPS 站点不受影响。
                .setIgnoreHTTPSErrors(true)
                // 跟随系统配色偏好，不写死 light。
                .setColorScheme(ColorScheme.NO_PREFERENCE)
                .setHasTouch(false)
                .setIsMobile(false)
                .setJavaScriptEnabled(true)
                // 必须开启沙箱：--no-sandbox 会黄条且易被识别为自动化环境。
                .setChromiumSandbox(true)
                // 剥离 Playwright 测试向默认参数，降低自动化指纹。
                .setIgnoreDefaultArgs(java.util.Arrays.asList(
                        "--enable-automation",
                        "--no-sandbox",
                        "--disable-setuid-sandbox",
                        "--disable-extensions",
                        "--disable-component-extensions-with-background-pages",
                        "--disable-default-apps",
                        "--disable-blink-features=AutomationControlled"
                ))
                .setArgs(buildChromeArgs(locale));
        if (localeForced) {
            options.setLocale(locale);
            // 仅显式配置语言时覆盖请求头，避免与本机 Chrome 自然语言冲突。
            options.setExtraHTTPHeaders(buildStealthHttpHeaders(locale));
        }
        if (timezoneForced) {
            options.setTimezoneId(timezoneId);
        }
        if (maximized) {
            // null viewport = 使用真实窗口尺寸，避免固定 1280x720 的自动化默认视口指纹。
            options.setViewportSize((ViewportSize) null);
        } else if (rect != null) {
            options.setViewportSize(rect.getWidth(), rect.getHeight());
            options.setScreenSize(rect.getWidth(), rect.getHeight());
        }
        if (!Strings.isEmpty(config.getDownloadPath())) {
            Files.createDirectory(config.getDownloadPath());
            options.setDownloadsPath(Paths.get(config.getDownloadPath()));
        }
        // 默认不覆盖 UA：沿用本机 Chrome 真实 UA / Client Hints。
        if (!Strings.isEmpty(config.getUserAgent())) {
            options.setUserAgent(config.getUserAgent());
        }
        if (!Strings.isEmpty(config.getPlaywrightExecutablePath())) {
            options.setExecutablePath(Paths.get(config.getPlaywrightExecutablePath()));
        } else if (!Strings.isEmpty(config.getPlaywrightChannel())) {
            options.setChannel(config.getPlaywrightChannel());
        }

        context = playwright.chromium().launchPersistentContext(Paths.get(userDataPath), options);
        watchContextLifecycle(context);
        context.setDefaultTimeout(config.getFindElementTimeoutSeconds() * 1000D);
        context.setDefaultNavigationTimeout(config.getPageLoadTimeoutSeconds() * 1000D);
        context.onDialog(dialog -> {
            try {
                dialog.accept();
            } catch (Exception e) {
                log.debug("Ignore dialog accept fail: {}", e.getMessage());
            }
        });
        context.onPage(this::registerTab);
        context.onResponse(this::captureResponse);
        // 只做去自动化指纹；不 JS 强改 language（defineProperty 本身可被检测，也不如真机 Chrome 自然）。
        registerFingerprintScripts();
        registerOptionalRoutes();

        List<Page> pages = context.pages();
        if (pages.isEmpty()) {
            setCurrentPage(context.newPage());
        } else {
            setCurrentPage(pages.get(0));
        }
        focus();
        if (maximized) {
            maximize();
        }
        log.info("Playwright chrome started, profile={}, channel={}, headless={}, humanInput={}, localeForced={}, locale={}, timezoneForced={}, timezone={}, chromeVersion={}",
                userDataPath, config.getPlaywrightChannel(), isHeadless(), config.isHumanInputEnabled(),
                localeForced, locale, timezoneForced, timezoneId,
                chromeInstall == null ? "" : chromeInstall.getVersion());
    }

    void watchContextLifecycle(BrowserContext browserContext) {
        sessionClosed = false;
        browserContext.onClose(closed -> {
            sessionClosed = true;
            sessionInvalidationListener.run();
        });
    }

    /** Called only on the owner thread; pumps no actions into an unavailable browser. */
    public boolean isSessionUnavailable() {
        if (isClosed() || sessionClosed || context == null) { return true; }
        try {
            if (page != null && !page.isClosed()) { return false; }
            for (Page candidate : context.pages()) {
                if (!candidate.isClosed()) {
                    setCurrentPage(candidate);
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) { return true; }
    }

    // package-private：供单元测试校验隐蔽性启动参数，勿随意扩大可见性。
    List<String> buildChromeArgs() {
        String locale = Strings.isEmpty(config.getLocale()) ? null
                : BrowserRuntimeEnvironment.resolveLocale(config.getLocale());
        return buildChromeArgs(locale);
    }

    List<String> buildChromeArgs(String locale) {
        List<String> args = new ArrayList<String>();
        // 去自动化特征（真人 Chrome 也不会带 enable-automation）。
        args.add("--disable-blink-features=AutomationControlled");
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        // 少加“测试向”开关；命令行特征本身可被侧信道观察。
        args.add("--disable-features=TranslateUI");
        args.add("--autoplay-policy=document-user-activation-required");
        // 复用 profile 强杀后的恢复气泡会挡操作，也不像日常已打开的浏览器。
        args.add("--hide-crash-restore-bubble");
        if (isMaximized(config.getWindowRectangle())) {
            // --window-position / --window-size 会让 Chrome 忽略 --start-maximized。
            args.add("--start-maximized");
        }
        // 仅显式配置语言时加 --lang；否则交给本机 Chrome/profile。
        if (!Strings.isEmpty(locale)) {
            args.add("--lang=" + locale);
        }
        // 防御：任何路径都不要把 sandbox 关掉。
        args.removeIf(a -> a != null && (a.equals("--no-sandbox") || a.startsWith("--no-sandbox=")
                || a.equals("--disable-setuid-sandbox")));
        return args;
    }

    /**
     * 与 locale 对齐的请求头，减少 Accept-Language 与 navigator.language 不一致的指纹冲突。
     * locale 应来自运行机器探测或显式配置，禁止写死开发机语言。
     */
    static Map<String, String> buildStealthHttpHeaders(String locale) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Accept-Language", buildAcceptLanguage(locale));
        return headers;
    }

    static String buildAcceptLanguage(String locale) {
        List<String> languages = buildNavigatorLanguages(locale);
        if (languages.isEmpty()) {
            return buildAcceptLanguage(BrowserRuntimeEnvironment.detectLocale());
        }
        if (languages.size() == 1) {
            return languages.get(0);
        }
        StringBuilder sb = new StringBuilder(languages.get(0));
        // 首项 q 省略（默认 1），后续按常见浏览器步长递减，并与 navigator.languages 顺序一致。
        double q = 0.9;
        for (int i = 1; i < languages.size(); i++) {
            sb.append(',').append(languages.get(i)).append(";q=");
            if (Math.abs(q - Math.rint(q)) < 1e-9) {
                sb.append((int) Math.rint(q));
            } else {
                sb.append(String.format(java.util.Locale.US, "%.1f", q));
            }
            q = Math.max(0.1, q - 0.1);
        }
        return sb.toString();
    }

    /**
     * navigator.languages 候选列表，顺序与 Accept-Language 一致。
     * 空 locale 时自动使用运行机器默认语言，不写死 zh-CN。
     */
    static List<String> buildNavigatorLanguages(String locale) {
        List<String> languages = new ArrayList<String>();
        String resolved = Strings.isEmpty(locale)
                ? BrowserRuntimeEnvironment.detectLocale()
                : BrowserRuntimeEnvironment.normalizeLocaleTag(locale);
        if (Strings.isEmpty(resolved)) {
            resolved = "en-US";
        }
        String primary = resolved;
        String language = primary.contains("-") ? primary.substring(0, primary.indexOf('-')) : primary;
        languages.add(primary);
        if (!primary.equalsIgnoreCase(language)) {
            languages.add(language);
        }
        if ("zh".equalsIgnoreCase(language)) {
            if (!containsIgnoreCase(languages, "en-US")) {
                languages.add("en-US");
            }
            if (!containsIgnoreCase(languages, "en")) {
                languages.add("en");
            }
            return languages;
        }
        if ("en".equalsIgnoreCase(language)) {
            if (!containsIgnoreCase(languages, "en")) {
                languages.add("en");
            }
            return languages;
        }
        // 其它语言：主语言 + en 回退（通用跨站），不绑定特定国家。
        if (!containsIgnoreCase(languages, "en-US")) {
            languages.add("en-US");
        }
        if (!containsIgnoreCase(languages, "en")) {
            languages.add("en");
        }
        return languages;
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    static boolean isMaximized(BrowserWindowRect rect) {
        return rect != null && rect.getWidth() <= 0 && rect.getHeight() <= 0;
    }

    private void registerFingerprintScripts() {
        registerFingerprintScripts(isChromeFingerprintEnabled());
    }

    private synchronized void registerFingerprintScripts(boolean enabled) {
        if (!enabled || fingerprintScriptsRegistered) {
            return;
        }

        try {
            boolean headless = isHeadless();
            String script = buildFingerprintPreloadScript(headless);
            context.addInitScript(script);
            fingerprintScriptsRegistered = true;
            log.info("Playwright fingerprint preload registered, script={}, stealth={}, headless={}",
                    config.getFingerprintScriptPath(), headless ? config.getFingerprintStealthScriptPath() : "skipped-in-headed", headless);
        } catch (Exception e) {
            if (isChromeFingerprintDiagnostics()) {
                throw new InvalidException("Playwright fingerprint preload init fail, script={}", config.getFingerprintScriptPath(), e);
            }
            log.warn("Playwright fingerprint preload init fail, script={}, error={}", config.getFingerprintScriptPath(), e.getMessage());
        }
    }

    String buildFingerprintPreloadScript(boolean headless) {
        String fingerprintScript = loadJsResource(config.getFingerprintScriptPath());
        if (!headless || Strings.isEmpty(config.getFingerprintStealthScriptPath())) {
            // 有头真实 Chrome 已原生具备 window.chrome / plugins / 视口尺寸等特征；
            // 跳过 puppeteer-extra stealth.min.js，避免其对 Function.prototype.toString、document.createElement 等挂多层 Proxy 被霸下/fireyejs 识别。
            return fingerprintScript;
        }
        return loadJsResource(config.getFingerprintStealthScriptPath()) + "\n;\n" + fingerprintScript;
    }

    /**
     * 池内复用的浏览器按调用方任务配置补注册去自动化脚本（如预热实例按全局配置创建、未开指纹注入）。
     * addInitScript 对后续所有导航生效；本实例已注册过或调用方未开启时幂等跳过。
     */
    public void ensureFingerprintScripts(WebBrowserConfig callerConfig) {
        if (fingerprintScriptsRegistered) {
            return;
        }
        boolean callerEnabled = callerConfig != null && callerConfig.isFingerprintEnabled();
        registerFingerprintScripts(callerEnabled || isChromeFingerprintEnabled());
    }

    private void registerOptionalRoutes() {
        if (!config.isBlockResourceEnabled()) {
            return;
        }
        context.route("**/*", route -> {
            String url = route.request().url();
            String resourceType = route.request().resourceType();
            if (shouldAllowResource(url, resourceType)) {
                route.resume();
                return;
            }
            route.abort();
        });
    }

    static boolean shouldAllowResource(String url, String resourceType) {
        if ("script".equalsIgnoreCase(resourceType)
                || "document".equalsIgnoreCase(resourceType)
                || "xhr".equalsIgnoreCase(resourceType)
                || "fetch".equalsIgnoreCase(resourceType)
                || isProtectedAntiScrapingUrl(url)) {
            return true;
        }
        return !"image".equalsIgnoreCase(resourceType)
                && !"media".equalsIgnoreCase(resourceType)
                && !"font".equalsIgnoreCase(resourceType);
    }

    static boolean isProtectedAntiScrapingUrl(String url) {
        if (Strings.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        // 霸下(baxia) / AWSC / fireyejs / et_f / securitySDK 等风控脚本及淘系域名资源禁止拦截，
        // 防止缺失 bx-ua/bx-et/tfstk 签名令牌或元素布局尺寸为 0 触发服务端「访问异常提示」降级。
        return lower.contains("/sd/baxia")
                || lower.contains("/awsc/")
                || lower.contains("fireyejs")
                || lower.contains("et_f.js")
                || lower.contains("securitysdk")
                || lower.contains("/nc.js")
                || lower.contains("x5sec")
                || lower.contains(".taobao.com/")
                || lower.contains(".tmall.com/")
                || lower.contains(".alimama.com/")
                || lower.contains(".alicdn.com/")
                || lower.contains(".mmstat.com/");
    }

    private String resolveUserDataPath() {
        String profileDataPath = config.getProfileDataPath();
        if (!Strings.isEmpty(profileDataPath)) {
            Files.createDirectory(profileDataPath);
            return profileDataPath;
        }

        int id = chromeIdCounter.getAndIncrement();
        String dataDir = Paths.get(System.getProperty("java.io.tmpdir"), "rx-mimic-playwright", String.valueOf(id)).toString();
        Files.createDirectory(dataDir);
        return dataDir;
    }

    private boolean isChromeFingerprintEnabled() {
        return Boolean.parseBoolean(System.getProperty("app.browser.fingerprintEnabled", String.valueOf(config.isFingerprintEnabled())));
    }

    private boolean isChromeFingerprintDiagnostics() {
        return Boolean.parseBoolean(System.getProperty("app.browser.fingerprintDiagnostics", String.valueOf(config.isFingerprintDiagnostics())));
    }

    private boolean isHeadless() {
        if (isChromeFingerprintEnabled()) {
            return Boolean.parseBoolean(System.getProperty("app.browser.fingerprintHeadless", String.valueOf(config.isFingerprintHeadless())));
        }
        return Boolean.parseBoolean(System.getProperty("app.browser.headless", String.valueOf(config.isHeadless())));
    }

    private String loadJsResource(String path) {
        return jsResourceCache.computeIfAbsent(path, p -> {
            try (InputStream in = WebBrowser.class.getResourceAsStream(p)) {
                if (in == null) {
                    throw new InvalidException("Resource {} not found", p);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                if (e instanceof InvalidException) {
                    throw (InvalidException) e;
                }
                throw new InvalidException("Resource {} read fail", p, e);
            }
        });
    }

    @Override
    protected void dispose() {
        try {
            if (context != null && !context.isClosed()) {
                context.close();
            }
        } catch (Exception e) {
            log.debug("Ignore playwright context close fail: {}", e.getMessage());
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception e) {
            log.debug("Ignore playwright close fail: {}", e.getMessage());
        }
    }

    @Override
    public void navigateUrl(String url) throws TimeoutException {
        navigateUrl(url, null);
    }

    @Override
    public void navigateUrl(String url, String locatorSelector) throws TimeoutException {
        navigateUrl(url, locatorSelector, config.getFindElementTimeoutSeconds(), null);
    }

    @Override
    public void navigateUrl(String url, String locatorSelector, int timeoutSeconds) throws TimeoutException {
        navigateUrl(url, locatorSelector, timeoutSeconds, null);
    }

    private synchronized void navigateUrl(@NonNull String url, String locatorSelector, int timeoutSeconds, Predicate<Integer> checkComplete) throws TimeoutException {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        try (TaskTiming.Span ignored = TaskTiming.span("navigate", SensitiveUrls.redact(url))) {
        humanOperationPause();

        injectedScript.clear();
        activeFrame = null;
        try {
            publishEvent(onNavigating, EventArgs.EMPTY);

            boolean adoptThis = TabPrefetch.claimAdopt(url);
            if (adoptThis && adoptPrefetchedTab(url, timeoutSeconds)) {
                // 已切到预加载标签。下一任务的起始页在切走之前交给了空出来的标签。
            } else if (adoptThis) {
                navigateOverlapped(url, timeoutSeconds);
            } else {
                navigate(url);
                prefetchNextOnIdleTab(page);
            }
            navigatedUrl = url;
            navigatedSelector = locatorSelector;

            if (locatorSelector != null) {
                waitElementLocated(locatorSelector, timeoutSeconds, checkComplete);
            }
            publishEvent(onNavigated, EventArgs.EMPTY);
        } catch (TimeoutException e) {
            TraceHandler.INSTANCE.saveExceptionTrace(Thread.currentThread(),
                    String.format("waitElementLocated fail, url=%s selector=%s|%s", url, locatorSelector, timeoutSeconds), e);
            throw e;
        } catch (Exception e) {
            throw new InvalidException("NavigateUrl {} fail", url, e);
        } finally {
            navigatedSelector = null;
        }
        }
    }

    @Override
    public synchronized String saveCookies(boolean reset) throws TimeoutException {
        String u = currentCookieUrl();
        saveCookiesToJar(u);
        String rawCookie = cookieJar.loadForRequest(java.net.URI.create(u));
        if (reset) {
            navigate(u);
            if (Strings.startsWith(u, navigatedUrl) && navigatedSelector != null) {
                waitElementLocated(navigatedSelector);
            }
        }
        return rawCookie;
    }

    @Override
    public synchronized void clearCookies(boolean onlyBrowser) {
        String u = currentCookieUrl();
        if (!Strings.startsWithIgnoreCase(u, "http")) {
            log.warn("clearCookies fail, url={}", u);
            return;
        }
        if (onlyBrowser) {
            context.clearCookies();
            return;
        }

        context.clearCookies();
        cookieJar.clear();
    }

    @Override
    public void setRawCookie(String rawCookie) {
        cookieJar.saveRawCookie(java.net.URI.create(currentCookieUrl()), rawCookie);
    }

    @Override
    public String getRawCookie() {
        return cookieJar.loadForRequest(java.net.URI.create(currentCookieUrl()));
    }

    public synchronized String getRawCookie(String url) {
        if (Strings.isEmpty(url) || !Strings.startsWithIgnoreCase(url, "http")) {
            return Strings.EMPTY;
        }
        saveCookiesToJar(url);
        String regionUrl = cookieRegion != null
                ? HttpClient.buildUrl(url, java.util.Collections.singletonMap("_Region", cookieRegion))
                : url;
        return ifNull(cookieJar.loadForRequest(java.net.URI.create(regionUrl)), Strings.EMPTY);
    }

    private String currentCookieUrl() {
        String u = getCurrentUrl();
        return cookieRegion != null ? HttpClient.buildUrl(u, java.util.Collections.singletonMap("_Region", cookieRegion)) : u;
    }

    @Override
    public boolean hasElement(String selector) {
        return findElements(selector, false).any();
    }

    @Override
    public String elementText(String selector) {
        return ifNull(elementsText(selector).firstOrDefault(), "");
    }

    @Override
    public Linq<String> elementsText(String selector) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();

        List<String> values = new ArrayList<String>();
        for (Locator locator : findElements(selector, false)) {
            values.add(locator.innerText());
        }
        return Linq.from(values);
    }

    @Override
    public String elementVal(String selector) {
        return ifNull(elementsVal(selector).firstOrDefault(), "");
    }

    @Override
    public Linq<String> elementsVal(String selector) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();

        List<String> values = new ArrayList<String>();
        for (Locator locator : findElements(selector, false)) {
            try {
                values.add(locator.inputValue());
            } catch (Exception e) {
                values.add(locator.getAttribute("value"));
            }
        }
        return Linq.from(values);
    }

    @Override
    public String elementAttr(String selector, String... attrArgs) {
        return ifNull(elementsAttr(selector, attrArgs).firstOrDefault(), "");
    }

    @Override
    public Linq<String> elementsAttr(String selector, @NonNull String... attrArgs) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        require(attrArgs, attrArgs.length > 0);

        String attrName = attrArgs[0], attrVal = attrArgs.length > 1 ? attrArgs[1] : null;
        List<String> values = new ArrayList<String>();
        for (Locator locator : findElements(selector, false)) {
            if (attrVal != null) {
                locator.evaluate("(el, args) => el.setAttribute(args[0], args[1])", java.util.Arrays.asList(attrName, attrVal));
                continue;
            }
            values.add(locator.getAttribute(attrName));
        }
        return Linq.from(values);
    }

    @Override
    public void elementClick(String selector) {
        elementClick(selector, false);
    }

    @Override
    public void elementClick(String selector, boolean waitElementLocated) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        try (TaskTiming.Span ignored = TaskTiming.span("click", selector)) {
        humanOperationPause();

        Locator locator;
        if (waitElementLocated) {
            try {
                locator = waitElementLocated(selector, config.getFindElementTimeoutSeconds(), null).first();
            } catch (TimeoutException e) {
                throw new InvalidException("Element {} not found", selector, e);
            }
        } else {
            locator = findElement(selector, false);
        }
        if (locator == null) {
            return;
        }

        if (config.isHumanInputEnabled()) {
            humanClick(locator);
        } else {
            locator.click(new Locator.ClickOptions().setTimeout(2500));
        }
        }
    }

    @Override
    public void elementPress(String selector, String keys) {
        elementPress(selector, keys, false);
    }

    @Override
    public synchronized void elementPress(String selector, String keys, boolean waitElementLocated) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        try (TaskTiming.Span ignored = TaskTiming.span("type", selector)) {
        humanOperationPause();

        Locator locator;
        if (waitElementLocated) {
            try {
                locator = waitElementLocated(selector, config.getFindElementTimeoutSeconds(), null).first();
            } catch (TimeoutException e) {
                throw new InvalidException("Element {} not found", selector, e);
            }
        } else {
            locator = findElement(selector, true);
        }
        if (config.isHumanInputEnabled()) {
            if (!locator.isEditable()) {
                throw new PlaywrightException("Input target is not editable: " + selector);
            }
            humanClick(locator);
            requireInputFocus(locator, selector);
            page.keyboard().press("Control+A");
            humanPause(40, 120);
            page.keyboard().press("Backspace");
            // 长关键词（商品标题）逐字 90~260ms 会拖到 10s+；先慢打前缀再 insertText，更像粘贴且仍走键盘通道
            requireInputFocus(locator, selector);
            humanType(keys);
            verifyInputValue(locator, keys, selector);
            return;
        }
        locator.fill(keys);
        verifyInputValue(locator, keys, selector);
        }
    }

    private void requireInputFocus(Locator locator, String selector) {
        if (!Boolean.TRUE.equals(locator.evaluate("el => el === (el.getRootNode().activeElement || el.ownerDocument.activeElement)"))) {
            throw new PlaywrightException("Input target did not receive focus: " + selector);
        }
    }

    private void verifyInputValue(Locator locator, String expected, String selector) {
        Object actual = locator.evaluate("el => 'value' in el ? el.value : el.textContent");
        if (!java.util.Objects.equals(expected == null ? "" : expected, actual)) {
            throw new PlaywrightException("Input value verification failed: " + selector);
        }
    }

    @Override
    public synchronized void waitForEvents(long millis) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        long wait = Math.max(0L, millis);
        if (wait < 400 || !TaskTiming.active()) {
            cancellationAwareWait(wait);
            return;
        }
        try (TaskTiming.Span ignored = TaskTiming.span("wait", wait + "ms")) {
            cancellationAwareWait(wait);
        }
    }

    private void cancellationAwareWait(long millis) {
        long remaining = millis;
        do {
            org.rx.crawler.task.common.TaskCancellation.check();
            long part = Math.min(remaining, 100);
            page.waitForTimeout(part);
            remaining -= part;
        } while (remaining > 0);
        org.rx.crawler.task.common.TaskCancellation.check();
    }

    @Override
    public void waitElementLocated(String selector) throws TimeoutException {
        waitElementLocated(selector, config.getFindElementTimeoutSeconds());
    }

    @Override
    public void waitElementLocated(String selector, int timeoutSeconds) throws TimeoutException {
        waitElementLocated(selector, timeoutSeconds, null);
    }

    private Linq<Locator> waitElementLocated(String selector, int timeoutSeconds, Predicate<Integer> checkComplete) throws TimeoutException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int evaluatedCount = 0;
        while (System.currentTimeMillis() <= deadline) {
            Linq<Locator> elements = findElements(selector, false);
            if (elements.any()) {
                log.debug("Wait {} located ok", selector);
                return elements;
            }
            if (checkComplete != null && checkComplete.test(++evaluatedCount)) {
                return elements;
            }
            Extends.sleep(Math.max(50, getWaitMillis()));
        }
        throw new TimeoutException(String.format("No such elements '%s'", selector));
    }

    private Locator findElement(String selector, boolean throwOnEmpty) {
        Linq<Locator> elements = findElements(selector, throwOnEmpty);
        if (!elements.any()) {
            if (throwOnEmpty) {
                throw new InvalidException("Element {} not found", selector);
            }
            return null;
        }
        return elements.first();
    }

    private Linq<Locator> findElements(@NonNull String selector, boolean throwOnEmpty) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();

        List<Locator> list = new ArrayList<Locator>();
        try {
            Locator locator = currentFrame().locator(toPlaywrightSelector(selector));
            int count = locator.count();
            for (int i = 0; i < count; i++) {
                list.add(locator.nth(i));
            }
        } catch (Exception e) {
            if (throwOnEmpty) {
                throw e instanceof RuntimeException ? (RuntimeException) e : new InvalidException("Find element {} fail", selector, e);
            }
        }
        if (throwOnEmpty && list.isEmpty()) {
            throw new InvalidException("Element {} not found", selector);
        }
        return Linq.from(list);
    }

    public byte[] screenshotAsBytes(String selector) {
        return findElement(selector, true).screenshot();
    }

    @Override
    public synchronized byte[] captureViewportPng() {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        if (page == null) {
            return new byte[0];
        }
        try {
            return page.screenshot();
        } catch (RuntimeException e) {
            log.warn("Viewport screenshot fail: {}", e.getMessage());
            return new byte[0];
        }
    }

    @Override
    public synchronized void injectScript(@NonNull String script) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        if (injectedScript.contains(script)) {
            return;
        }

        currentFrame().evaluate("() => {\n" + script + "\n}");
        injectedScript.add(script);
    }

    @Override
    public synchronized <T> T executeScript(@NonNull String script, Object... args) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();

        String rootJs = Cache.<String, String>getInstance(MemoryCache.class)
                .get(cacheKey("injectRootJs"), k -> Browser.readResourceJs(RESOURCE_JS_PATH));
        injectScript(rootJs);
        try {
            Object result = currentFrame().evaluate("(payload) => (function() {\n" + script + "\n}).apply(null, payload)",
                    java.util.Arrays.asList(args));
            return (T) result;
        } catch (PlaywrightException e) {
            throw new InvalidException("ScriptContent:\n{}\n\n{}", script, e.getMessage(), e);
        }
    }

    @Override
    public synchronized <T> T injectAndExecuteScript(String injectScript, String script, Object... args) {
        injectScript(injectScript);
        return executeScript(script, args);
    }

    @Override
    public <T> T executeConfigureScript(String scriptName, Object... args) {
        return configureScriptExecutor.execute(scriptName, args);
    }

    public synchronized void focus() {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        try {
            ensureLivePage();
            page.bringToFront();
        } catch (RuntimeException e) {
            if (!targetClosed(e)) {
                throw e;
            }
            log.warn("Chrome tab was already closed, open a replacement tab");
            openReplacementPage();
            page.bringToFront();
        }
    }

    public synchronized void maximize() {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();

        Map<String, Object> beforeMetrics = readWindowMetrics();
        boolean maximized = maximizeByCdp();
        if (!maximized) {
            try {
                maximizeByWindowsApi();
                maximized = true;
            } catch (Exception e) {
                log.debug("Playwright browser maximize fallback ignored, error={}", e.getMessage());
            }
        }
        if (maximized) {
            syncViewportAfterMaximize(beforeMetrics);
            notifyWindowResizeAfterMaximize();
        }
    }

    private boolean maximizeByCdp() {
        if (context == null || page == null) {
            return false;
        }

        CDPSession session = null;
        try {
            session = openWindowSession();
            if (session == null) {
                return false;
            }
            JsonObject window = windowForTarget(session);
            if (window == null || !window.has("windowId")) {
                return false;
            }
            int windowId = window.get("windowId").getAsInt();
            // 持久化 profile 会恢复上次的普通窗口。先回到 normal，再 maximized，否则这次调用会被忽略。
            setWindowState(session, windowId, "normal");
            setWindowState(session, windowId, "maximized");
            return true;
        } catch (Exception e) {
            log.info("Browser CDP maximize failed, error={}", e.getMessage());
            return false;
        } finally {
            if (session != null) {
                try {
                    session.detach();
                } catch (Exception e) {
                    log.debug("Ignore browser CDP session detach fail: {}", e.getMessage());
                }
            }
        }
    }

    private CDPSession openWindowSession() {
        try {
            return context.newCDPSession(page);
        } catch (Exception pageSessionError) {
            log.debug("Page CDP session ignored, error={}", pageSessionError.getMessage());
        }
        if (context.browser() == null || !context.browser().isConnected()) {
            return null;
        }
        return context.browser().newBrowserCDPSession();
    }

    private JsonObject windowForTarget(CDPSession session) {
        try {
            return session.send("Browser.getWindowForTarget");
        } catch (Exception ignored) {
        }
        JsonObject info = session.send("Target.getTargetInfo");
        if (info == null || !info.has("targetInfo")) {
            return null;
        }
        JsonObject targetInfo = info.getAsJsonObject("targetInfo");
        if (targetInfo == null || !targetInfo.has("targetId")) {
            return null;
        }
        JsonObject args = new JsonObject();
        args.addProperty("targetId", targetInfo.get("targetId").getAsString());
        return session.send("Browser.getWindowForTarget", args);
    }

    private void setWindowState(CDPSession session, int windowId, String state) {
        JsonObject bounds = new JsonObject();
        bounds.addProperty("windowState", state);
        JsonObject args = new JsonObject();
        args.addProperty("windowId", windowId);
        args.add("bounds", bounds);
        session.send("Browser.setWindowBounds", args);
    }

    private void maximizeByWindowsApi() {
        if (page == null) {
            return;
        }
        page.bringToFront();
        Extends.sleep(250);

        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try {
            HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (!isChromeWindow(hwnd)) {
                return;
            }
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_MAXIMIZE);
            User32.INSTANCE.SetForegroundWindow(hwnd);
            Extends.sleep(300);
            page.evaluate("() => { try { window.dispatchEvent(new Event('resize')); } catch (e) {} }");
        } catch (Exception e) {
            log.debug("Windows API maximize ignored, error={}", e.getMessage());
        }
    }

    private static boolean isChromeWindow(HWND hwnd) {
        if (hwnd == null) {
            return false;
        }
        char[] buffer = new char[256];
        User32.INSTANCE.GetClassName(hwnd, buffer, buffer.length);
        int end = 0;
        while (end < buffer.length && buffer[end] != '\0') {
            end++;
        }
        return "Chrome_WidgetWin_1".equals(new String(buffer, 0, end));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readWindowMetrics() {
        if (page == null) {
            return new HashMap<String, Object>();
        }
        try {
            Object value = page.evaluate("() => ({"
                    + "innerWidth: window.innerWidth || 0,"
                    + "innerHeight: window.innerHeight || 0,"
                    + "outerWidth: window.outerWidth || 0,"
                    + "outerHeight: window.outerHeight || 0,"
                    + "availWidth: window.screen && window.screen.availWidth || 0,"
                    + "availHeight: window.screen && window.screen.availHeight || 0"
                    + "})");
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
        } catch (Exception e) {
            log.debug("Read browser window metrics ignored, error={}", e.getMessage());
        }
        return new HashMap<String, Object>();
    }

    private void syncViewportAfterMaximize(Map<String, Object> beforeMetrics) {
        if (page == null || page.viewportSize() == null) {
            return;
        }
        try {
            Extends.sleep(250);
            Map<String, Object> afterMetrics = readWindowMetrics();
            int beforeInnerWidth = metricInt(beforeMetrics, "innerWidth");
            int beforeInnerHeight = metricInt(beforeMetrics, "innerHeight");
            int chromeWidth = Math.max(0, metricInt(beforeMetrics, "outerWidth") - beforeInnerWidth);
            int chromeHeight = Math.max(0, metricInt(beforeMetrics, "outerHeight") - beforeInnerHeight);
            int targetWidth = Math.max(metricInt(afterMetrics, "outerWidth"), metricInt(afterMetrics, "availWidth")) - chromeWidth;
            int targetHeight = Math.max(metricInt(afterMetrics, "outerHeight"), metricInt(afterMetrics, "availHeight")) - chromeHeight;
            if (targetWidth <= 0 || targetHeight <= 0) {
                return;
            }

            ViewportSize current = page.viewportSize();
            if (Math.abs(current.width - targetWidth) > 8 || Math.abs(current.height - targetHeight) > 8) {
                page.setViewportSize(targetWidth, targetHeight);
                log.debug("Browser viewport synced after maximize, viewport={}x{}", targetWidth, targetHeight);
            }
        } catch (Exception e) {
            log.debug("Browser viewport sync after maximize ignored, error={}", e.getMessage());
        }
    }

    private int metricInt(Map<String, Object> metrics, String key) {
        if (metrics == null || !metrics.containsKey(key)) {
            return 0;
        }
        Object value = metrics.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private void notifyWindowResizeAfterMaximize() {
        if (page == null) {
            return;
        }
        try {
            page.bringToFront();
            Extends.sleep(350);
            page.evaluate("() => { try { window.dispatchEvent(new Event('resize')); } catch (e) {} }");
            log.debug("Browser resize event dispatched after maximize");
        } catch (Exception e) {
            log.debug("Browser resize event after maximize ignored, error={}", e.getMessage());
        }
    }

    public synchronized void normalize() {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();

        BrowserWindowRect rect = config.getWindowRectangle();
        if (rect != null) {
            setWindowRectangle(rect);
        }
    }

    @Override
    public synchronized void mouseMove(double x, double y) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        moveMouseLikeHuman(x, y);
        humanPause();
    }

    @Override
    public synchronized void mouseClick(double x, double y) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        moveMouseLikeHuman(x, y);
        humanPause(80, 240);
        Mouse mouse = page.mouse();
        mouse.down();
        try {
            humanPause(65, 160);
        } finally {
            mouse.up();
        }
        humanPause();
        mouseX = x;
        mouseY = y;
    }

    @Override
    public synchronized void scrollPage(double deltaX, double deltaY) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        try (TaskTiming.Span ignored = TaskTiming.span("scroll", null)) {
        humanOperationPause();
        if (Math.abs(deltaX) < 0.01 && Math.abs(deltaY) < 0.01) {
            return;
        }

        try {
            ViewportSize viewport = page.viewportSize();
            if (viewport == null) {
                Map<String, Object> metrics = readWindowMetrics();
                int width = metricInt(metrics, "innerWidth"), height = metricInt(metrics, "innerHeight");
                if (width <= 0 || height <= 0) {
                    throw new PlaywrightException("Current browser viewport has no usable dimensions");
                }
                viewport = new ViewportSize(width, height);
            }
            double targetX = randomBetween(viewport.width * 0.35, viewport.width * 0.65);
            double targetY;
            if (deltaY > 0) {
                targetY = randomBetween(viewport.height * 0.72, viewport.height * 0.86);
            } else if (deltaY < 0) {
                targetY = randomBetween(viewport.height * 0.16, viewport.height * 0.28);
            } else {
                targetY = randomBetween(viewport.height * 0.35, viewport.height * 0.65);
            }
            moveMouseLikeHuman(targetX, targetY);
            int steps = Math.max(2, Math.min(9, (int) Math.ceil(Math.abs(deltaY) / 180D)));
            double usedX = 0, usedY = 0;
            for (int i = 1; i <= steps; i++) {
                double nextX = deltaX * i / steps;
                double nextY = deltaY * i / steps;
                double wheelX = nextX - usedX + randomBetween(-2, 2);
                double wheelY = nextY - usedY + randomBetween(-8, 8);
                page.mouse().wheel(wheelX, wheelY);
                usedX = nextX;
                usedY = nextY;
                humanPause(70, 180);
            }
            mouseX = targetX;
            mouseY = targetY;
        } catch (Exception e) {
            log.debug("Playwright wheel scroll fallback to JS, deltaX={}, deltaY={}, error={}", deltaX, deltaY, e.getMessage());
            jsScrollPage(deltaX, deltaY);
        }
        }
    }

    @Override
    public synchronized boolean scrollToElement(String selector, double viewportRatio) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        if (Strings.isEmpty(selector)) {
            return false;
        }
        double ratio = Math.max(0.1, Math.min(0.9, viewportRatio));
        try {
            Locator locator = findElement(selector, false);
            if (locator == null) {
                return false;
            }
            locator.scrollIntoViewIfNeeded();
            humanPause(120, 260);
            if (scrollToElementByWheel(selector, ratio)) {
                return true;
            }
        } catch (Exception e) {
            log.debug("Playwright scroll to element fallback to JS, selector={}, error={}", selector, e.getMessage());
        }
        return jsScrollToElement(selector, ratio);
    }

    /**
     * 拖拽。滑块走 Windows SendInput，系统光标按下并移动。
     * page.mouse 不移动系统光标，只在系统光标不可用时兜底。
     */
    @Override
    public synchronized void mouseDrag(double startX, double startY, double endX, double endY, int steps) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        try (TaskTiming.Span ignored = TaskTiming.span("drag", null)) {
        if (WindowsMouse.drag(page, startX, startY, endX, endY)) {
            mouseX = endX;
            mouseY = endY;
            return;
        }

        // 实际步数加随机偏移，避免固定步数被检测
        int actualSteps = steps + randomInt(-5, 8);
        if (actualSteps < 15) actualSteps = 15;

        // 先移动到起点附近（用贝塞尔曲线模拟人类鼠标轨迹）
        moveMouseLikeHuman(startX, startY);
        humanPause(150, 400);

        // 按下鼠标
        page.mouse().down();
        humanPause(80, 250);

        // 随机选取1-2个中途停顿点（模拟犹豫）
        int pausePoint1 = randomInt(actualSteps / 4, actualSteps / 2);
        int pausePoint2 = randomInt(actualSteps * 2 / 3, actualSteps * 4 / 5);
        boolean doPause2 = ThreadLocalRandom.current().nextBoolean();

        double totalDx = endX - startX;
        double totalDy = endY - startY;
        double prevCx = startX, prevCy = startY;
        for (int i = 1; i <= actualSteps; i++) {
            double ratio = (double) i / actualSteps;
            // 三段变速缓动：加速 → 匀速 → 减速
            double ease;
            if (ratio < 0.2) {
                // 起步阶段：慢加速（二次缓入）
                ease = ratio / 0.2;
                ease = ease * ease * 0.2;
            } else if (ratio < 0.75) {
                // 中段：匀速推进
                ease = 0.2 + (ratio - 0.2) / 0.55 * 0.65;
            } else {
                // 末段：减速（二次缓出）
                double t = (ratio - 0.75) / 0.25;
                ease = 0.85 + (1 - (1 - t) * (1 - t)) * 0.15;
            }

            // X 方向加随机抖动（越靠后抖动越小，模拟手指稳定过程）
            double jitterX = randomBetween(-2.0, 2.0) * (1.0 - ratio * 0.6);
            // Y 方向微抖动
            double jitterY = randomBetween(-1.5, 1.5) * (1.0 - ratio * 0.5);
            double cx = startX + totalDx * ease + jitterX;
            double cy = startY + totalDy * ease + jitterY;

            // 偶尔制造微回退（概率 5%，且不在起步/末尾阶段）
            if (i > 3 && i < actualSteps - 3 && ThreadLocalRandom.current().nextInt(100) < 5) {
                double backCx = prevCx - randomBetween(1, 4);
                page.mouse().move(backCx, prevCy + randomBetween(-1, 1), new Mouse.MoveOptions().setSteps(1));
                humanPause(15, 45);
            }

            page.mouse().move(cx, cy, new Mouse.MoveOptions().setSteps(1));
            prevCx = cx;
            prevCy = cy;

            // 中途停顿（模拟犹豫/手指调整）
            if (i == pausePoint1) {
                humanPause(80, 280);
            } else if (doPause2 && i == pausePoint2) {
                humanPause(50, 180);
            } else {
                // 常规延迟：前段快、中段适中、末段慢
                if (ratio < 0.2) {
                    humanPause(12, 40);
                } else if (ratio < 0.75) {
                    humanPause(8, 30);
                } else {
                    humanPause(20, 70);
                }
            }
        }

        // 过冲回调：先超过终点 3~8px，再慢慢退回到终点（模拟人手惯性）
        double overshoot = randomBetween(3, 8);
        page.mouse().move(endX + overshoot, endY + randomBetween(-1, 1), new Mouse.MoveOptions().setSteps(1));
        humanPause(40, 120);
        // 分 2-3 步回退到终点
        int backSteps = randomInt(2, 3);
        for (int b = backSteps; b >= 1; b--) {
            double bx = endX + overshoot * ((double) b / (backSteps + 1));
            page.mouse().move(bx, endY + randomBetween(-0.5, 0.5), new Mouse.MoveOptions().setSteps(1));
            humanPause(25, 60);
        }
        page.mouse().move(endX, endY, new Mouse.MoveOptions().setSteps(1));
        humanPause(60, 250);

        // 松开鼠标
        page.mouse().up();
        humanPause(100, 350);
        mouseX = endX;
        mouseY = endY;
        }
    }

    @Override
    public synchronized Map<String, Object> locateInFrames(String selector) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        for (Frame frame : page.frames()) {
            ElementHandle handle;
            try {
                handle = frame.querySelector(selector);
            } catch (PlaywrightException e) {
                // frame 可能正在导航/销毁
                continue;
            }
            if (handle == null) {
                continue;
            }
            BoundingBox box = handle.boundingBox();
            if (box == null || box.width <= 0 || box.height <= 0) {
                continue;
            }
            Map<String, Object> location = new LinkedHashMap<String, Object>();
            location.put("x", box.x + box.width / 2);
            location.put("y", box.y + box.height / 2);
            location.put("width", box.width);
            location.put("height", box.height);
            location.put("frameUrl", frame.url());
            return location;
        }
        return null;
    }

    @Override
    public synchronized byte[] downloadAsBytes(String url) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        try (TaskTiming.Span ignored = TaskTiming.span("http.download", SensitiveUrls.redact(url))) {
        APIResponse response = context.request().get(url);
        if (response == null || !response.ok()) {
            int status = response == null ? -1 : response.status();
            throw new InvalidException("Download %s failed, http status=%s", url, status);
        }
        return response.body();
        }
    }

    @Override
    public synchronized void pressKey(String key) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        humanOperationPause();
        page.keyboard().press(key);
    }

    @Override
    public void startResponseCapture(@NonNull String urlKeyword) {
        responseCaptures.put(urlKeyword, new java.util.concurrent.ConcurrentLinkedDeque<String>());
    }

    @Override
    public List<String> drainResponseCapture(@NonNull String urlKeyword) {
        java.util.Deque<String> queue = responseCaptures.get(urlKeyword);
        List<String> out = new ArrayList<String>();
        if (queue == null) {
            return out;
        }
        String body;
        while ((body = queue.pollFirst()) != null) {
            out.add(body);
        }
        return out;
    }

    @Override
    public void stopResponseCapture(@NonNull String urlKeyword) {
        responseCaptures.remove(urlKeyword);
    }

    private void captureResponse(com.microsoft.playwright.Response response) {
        if (TaskTiming.active()) {
            notePageHttp(response);
        }
        if (responseCaptures.isEmpty()) {
            return;
        }
        try {
            String type = response.request().resourceType();
            if (!"xhr".equals(type) && !"fetch".equals(type) && !"script".equals(type)) {
                return;
            }
            String url = response.url();
            for (Map.Entry<String, java.util.Deque<String>> entry : responseCaptures.entrySet()) {
                if (!url.contains(entry.getKey())) {
                    continue;
                }
                java.util.Deque<String> queue = entry.getValue();
                queue.addLast(response.text());
                while (queue.size() > MAX_CAPTURED_RESPONSES) {
                    queue.pollFirst();
                }
            }
        } catch (Exception e) {
            log.debug("Capture response skipped: {}", e.getMessage());
        }
    }

    /** debug 打开时记下页面自己发出的慢请求。地址去掉 query。 */
    private void notePageHttp(com.microsoft.playwright.Response response) {
        try {
            String type = response.request().resourceType();
            boolean xhr = "xhr".equals(type) || "fetch".equals(type);
            boolean script = "script".equals(type);
            if (!xhr && !script) {
                return;
            }
            String url = response.url();
            com.microsoft.playwright.options.Timing timing = response.request().timing();
            if (timing == null || timing.responseEnd < 0 || timing.requestStart < 0
                    || timing.responseEnd < timing.requestStart) {
                return;
            }
            long elapsed = Math.round(timing.responseEnd - timing.requestStart);
            boolean business = businessApi(url);
            if (business) {
                if (elapsed >= 30) {
                    TaskTiming.note("http.api", elapsed, url);
                }
                return;
            }
            if (xhr && elapsed >= 100) {
                TaskTiming.note("http." + type, elapsed, url);
            } else if (script && elapsed >= 300) {
                TaskTiming.note("http.script", elapsed, url);
            }
        } catch (RuntimeException e) {
            log.debug("HTTP timing skipped: {}", e.getMessage());
        }
    }

    private static boolean businessApi(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("mtop.")
                || lower.contains("functionid=")
                || lower.contains("api.m.jd.com")
                || lower.contains("color.jd.com")
                || lower.contains("gettbk")
                || lower.contains("alimama.com")
                || lower.contains("h5api.m.taobao.com");
    }

    @Override
    public synchronized void nativeGet(String url) {
        try (TaskTiming.Span ignored = TaskTiming.span("navigate", SensitiveUrls.redact(url))) {
            navigate(url);
        }
    }

    public synchronized void switchToDefault() {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        activeFrame = null;
    }

    public synchronized void switchToFrame(String selector) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();

        ElementHandle handle = findElement(selector, true).elementHandle();
        Frame frame = handle == null ? null : handle.contentFrame();
        if (frame == null) {
            throw new InvalidException("Frame {} not found", selector);
        }
        activeFrame = frame;
    }

    public synchronized String openTab() {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();

        Page newPage = context.newPage();
        setCurrentPage(newPage);
        return currentHandle;
    }

    @Override
    public synchronized boolean runAndFollowNewTab(Runnable action, Predicate<String> acceptedUrl, int timeoutMillis) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        java.util.Objects.requireNonNull(action, "action");
        java.util.Objects.requireNonNull(acceptedUrl, "acceptedUrl");
        Page source = page;
        String sourceUrl = source.url();
        String sourceHandle = handleOf(source);
        Set<Page> existing = new HashSet<>(context.pages());
        action.run();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        do {
            for (Page candidate : context.pages()) {
                if (!existing.contains(candidate) && !candidate.isClosed() && acceptedUrl.test(candidate.url())) {
                    setCurrentPage(candidate);
                    // Replace only this task's source; preserve unrelated and prefetched tabs.
                    if (sourceHandle != null) closeTab(sourceHandle);
                    else if (!source.isClosed()) source.close();
                    return true;
                }
            }
            if (!source.isClosed() && !sourceUrl.equals(source.url()) && acceptedUrl.test(source.url())) return true;
            if (source.isClosed() || System.nanoTime() >= deadline) return false;
            source.waitForTimeout(100);
        } while (true);
    }

    @Override
    public synchronized void runInCurrentTab(Runnable action, Predicate<String> discardPopup, int settleMillis) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        java.util.Objects.requireNonNull(action, "action");
        java.util.Objects.requireNonNull(discardPopup, "discardPopup");
        Page source = page;
        List<Page> opened = new ArrayList<>();
        java.util.function.Consumer<Page> onPopup = opened::add;
        source.onPopup(onPopup);
        Throwable actionFailure = null;
        try {
            action.run();
        } catch (RuntimeException | Error e) {
            actionFailure = e;
            throw e;
        } finally {
            RuntimeException cleanupFailure = null;
            try {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                        Math.min(1000, Math.max(0, settleMillis)));
                do {
                    // Pump source events even when the action threw, so asynchronously opened popups are observed.
                    if (!source.isClosed() && System.nanoTime() < deadline) source.waitForTimeout(100);
                    for (Page temporary : new ArrayList<>(opened)) {
                        if (temporary.isClosed() || !discardPopup.test(temporary.url())) continue;
                        String handle = handleOf(temporary);
                        temporary.close();
                        if (handle != null) {
                            tabs.remove(handle);
                            prefetchTargetByHandle.remove(handle);
                        }
                    }
                } while (!source.isClosed() && System.nanoTime() < deadline);
            } catch (RuntimeException e) {
                cleanupFailure = e;
            } finally {
                try {
                    source.offPopup(onPopup);
                } catch (RuntimeException e) {
                    if (cleanupFailure == null) cleanupFailure = e;
                    else cleanupFailure.addSuppressed(e);
                }
                try {
                    activeFrame = null;
                    if (!source.isClosed()) setCurrentPage(source, true);
                } catch (RuntimeException e) {
                    if (cleanupFailure == null) cleanupFailure = e;
                    else cleanupFailure.addSuppressed(e);
                }
            }
            if (cleanupFailure != null) {
                if (actionFailure != null) actionFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    @Override
    public synchronized String readLinkDestination(String selector, java.util.function.Predicate<String> acceptedUrl,
            int timeoutSeconds) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();
        Page source = page;
        // Give the temporary detail tab the second slot; only discard our disposable prefetch tab.
        for (String handle : new ArrayList<>(tabs.keySet())) {
            Page candidate = tabs.get(handle);
            if (candidate != source && (prefetchTargetByHandle.containsKey(handle)
                    || prefetchOwnedTabs.contains(candidate))) closeTab(handle);
        }
        long liveTabs = context.pages().stream().filter(p -> !p.isClosed()).count();
        if (liveTabs >= 2) throw new InvalidException("DETAIL_TAB_SLOT_UNAVAILABLE");
        Page detail = null;
        List<Page> opened = new ArrayList<>();
        java.util.function.Consumer<Page> onPopup = opened::add;
        source.onPopup(onPopup);
        try {
            detail = source.waitForPopup(new Page.WaitForPopupOptions().setTimeout(Math.max(1, timeoutSeconds) * 1000D),
                    () -> elementClick(selector));
            setCurrentPage(detail, true);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
            String accepted = "";
            while (!detail.isClosed() && System.nanoTime() < deadline) {
                detail.waitForTimeout(400);
                String url = detail.url();
                if (acceptedUrl.test(url)) {
                    if (url.equals(accepted)) return url;
                    accepted = url;
                } else accepted = "";
            }
            return "";
        } finally {
            source.offPopup(onPopup);
            if (detail != null && !opened.contains(detail)) opened.add(detail);
            try {
                RuntimeException cleanupFailure = null;
                for (Page temporary : opened) {
                    String handle = handleOf(temporary);
                    try {
                        if (!temporary.isClosed()) temporary.close();
                        if (handle != null) {
                            tabs.remove(handle);
                            prefetchTargetByHandle.remove(handle);
                        }
                    } catch (RuntimeException e) { cleanupFailure = e; }
                }
                if (cleanupFailure != null) throw new InvalidException("DETAIL_TAB_CLOSE_FAILED");
            } finally {
                if (!source.isClosed()) setCurrentPage(source, true);
            }
        }
    }

    public synchronized void switchTab(@NonNull String winHandle) {
        switchTab(winHandle, true);
    }

    public synchronized void switchTab(@NonNull String winHandle, boolean bringToFront) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();

        Page target = tabs.get(winHandle);
        if (target == null || target.isClosed()) {
            throw new InvalidException("Tab {} not found", winHandle);
        }
        setCurrentPage(target, bringToFront);
    }

    public synchronized void closeTab(@NonNull String winHandle) {
        org.rx.crawler.task.common.TaskCancellation.check();
        checkNotClosed();

        prefetchTargetByHandle.remove(winHandle);
        Page target = tabs.remove(winHandle);
        prefetchOwnedTabs.remove(target);
        if (target != null && !target.isClosed()) {
            target.close();
        }
        if (tabs.isEmpty()) {
            setCurrentPage(context.newPage());
            return;
        }
        if (winHandle.equals(currentHandle)) {
            Map.Entry<String, Page> first = tabs.entrySet().iterator().next();
            currentHandle = first.getKey();
            page = first.getValue();
            activeFrame = null;
        }
    }

    private void navigate(String url) {
        navigateOnce(url, WaitUntilState.DOMCONTENTLOADED);
    }

    /**
     * 当前任务起始页没有预加载标签时：先收到首包，再让空闲标签去开下一任务，然后等当前页文档就绪。
     * 这样下一页的加载和当前页剩余加载叠在一起。
     */
    private void navigateOverlapped(String url, int timeoutSeconds) {
        navigateOnce(url, WaitUntilState.COMMIT);
        prefetchNextOnIdleTab(page);
        waitDocumentReady(timeoutSeconds);
    }

    /**
     * 复用闲置 Chrome 时，当前标签可能已经被关掉，但 isClosed 要等下一次 CDP 才变。
     * 导航若报 target closed，在同一个 context 里新开一个标签再试一次，不另开 Chrome。
     */
    private void navigateOnce(String url, WaitUntilState until) {
        ensureLivePage();
        Page.NavigateOptions options = new Page.NavigateOptions()
                .setTimeout(config.getPageLoadTimeoutSeconds() * 1000D)
                .setWaitUntil(until);
        try {
            page.navigate(url, options);
        } catch (RuntimeException e) {
            if (!targetClosed(e)) {
                throw e;
            }
            log.warn("Page target closed during navigate, open a new tab and retry once, url={}",
                    TabPrefetch.brief(url));
            openReplacementPage();
            page.navigate(url, options);
        }
        activeFrame = null;
    }

    private void ensureLivePage() {
        if (page != null && !page.isClosed()) {
            return;
        }
        if (context == null || context.isClosed()) {
            throw new InvalidException("Browser context is closed");
        }
        for (Page candidate : context.pages()) {
            if (candidate != null && !candidate.isClosed()) {
                setCurrentPage(candidate, true);
                return;
            }
        }
        openReplacementPage();
    }

    private void openReplacementPage() {
        if (context == null || context.isClosed()) {
            throw new InvalidException("Browser context is closed");
        }
        setCurrentPage(context.newPage(), true);
    }

    static boolean targetClosed(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("target closed") || lower.contains("has been closed")
                        || lower.contains("targetclosed")) {
                    return true;
                }
            }
            String name = current.getClass().getSimpleName();
            if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains("targetclosed")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 后台标签已经在打开这个地址。先把下一个排队任务放到即将空出来的标签上，再切过来等它加载完。
     * 不点击、不拖后台标签。
     */
    private boolean adoptPrefetchedTab(String url, int timeoutSeconds) {
        Page warm = findPrefetchTarget(url);
        if (warm == null || warm.isClosed()) {
            return false;
        }
        // The previous task page becomes the idle task slot even when no next prefetch is queued.
        if (page != null && page != warm) prefetchOwnedTabs.add(page);
        prefetchNextOnIdleTab(warm);
        setCurrentPage(warm, true);
        String handle = handleOf(warm);
        if (handle != null) {
            prefetchTargetByHandle.remove(handle);
        }
        waitDocumentReady(timeoutSeconds);
        log.info("Tab prefetch adopted, url={}", TabPrefetch.brief(url));
        return true;
    }

    /** protect 是正在操作或即将操作的标签，预加载不能写到它上面。 */
    private void prefetchNextOnIdleTab(Page protect) {
        String next = TabPrefetch.peekNext();
        if (Strings.isEmpty(next)) {
            return;
        }
        String reserved = TabPrefetch.adoptUrl();
        Page background = null;
        for (Map.Entry<String, Page> entry : tabs.entrySet()) {
            Page candidate = entry.getValue();
            if (candidate == null || candidate.isClosed() || candidate == protect) {
                continue;
            }
            String target = prefetchTargetByHandle.get(entry.getKey());
            if (!reserved.isEmpty() && TabPrefetch.same(reserved, target)) {
                continue;
            }
            if (TabPrefetch.same(next, target)) {
                TabPrefetch.markNextStarted();
                return;
            }
            background = candidate;
            break;
        }
        Page foreground = page;
        if (background == null) {
            if (tabs.size() >= 2) {
                log.info("Tab prefetch waiting for adopt, next={}", TabPrefetch.brief(next));
                return;
            }
            background = context.newPage();
            registerTab(background);
            if (foreground != null && !foreground.isClosed()) {
                setCurrentPage(foreground, true);
            }
        }
        String handle = handleOf(background);
        prefetchOwnedTabs.add(background);
        try (TaskTiming.Span ignored = TaskTiming.span("prefetch", SensitiveUrls.redact(next))) {
            background.navigate(next, new Page.NavigateOptions()
                    .setTimeout(8000)
                    .setWaitUntil(WaitUntilState.COMMIT));
            if (handle != null) {
                prefetchTargetByHandle.put(handle, TabPrefetch.normalize(next));
            }
            TabPrefetch.markNextStarted();
            log.info("Tab prefetch started, next={}, slots=2", TabPrefetch.brief(next));
        } catch (Exception e) {
            log.info("Tab prefetch failed, next={}, error={}", TabPrefetch.brief(next), e.getMessage());
            TabPrefetch.markNextStarted();
        } finally {
            if (foreground != null && !foreground.isClosed() && page != foreground) {
                setCurrentPage(foreground, true);
            }
        }
    }

    private Page findPrefetchTarget(String url) {
        for (Map.Entry<String, Page> entry : tabs.entrySet()) {
            if (!TabPrefetch.same(url, prefetchTargetByHandle.get(entry.getKey()))) {
                continue;
            }
            Page candidate = entry.getValue();
            if (candidate != null && !candidate.isClosed()) {
                return candidate;
            }
        }
        return null;
    }

    private String handleOf(Page target) {
        if (target == null) {
            return null;
        }
        for (Map.Entry<String, Page> entry : tabs.entrySet()) {
            if (entry.getValue() == target) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void waitDocumentReady(int timeoutSeconds) {
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions()
                    .setTimeout(Math.max(1, timeoutSeconds) * 1000D));
        } catch (Exception e) {
            log.info("Document ready wait incomplete, error={}", e.getMessage());
        }
    }

    private Frame currentFrame() {
        if (activeFrame != null && !activeFrame.isDetached()) {
            return activeFrame;
        }
        return page.mainFrame();
    }

    private void jsScrollPage(double deltaX, double deltaY) {
        currentFrame().evaluate("(args) => {" +
                "var dx=args[0],dy=args[1];" +
                "window.scrollBy(dx,dy);" +
                "}", java.util.Arrays.asList(deltaX, deltaY));
    }

    private boolean jsScrollToElement(String selector, double viewportRatio) {
        Object ok = currentFrame().evaluate("(args) => {" +
                "var el=document.querySelector(args[0]),ratio=args[1];if(!el){return false;}" +
                "var root=document.scrollingElement||document.documentElement;" +
                "for(var p=el.parentElement;p&&p!==root;p=p.parentElement){" +
                "var style=getComputedStyle(p);" +
                "if(!/(auto|scroll|overlay)/.test(style.overflowY)||p.scrollHeight<=p.clientHeight){continue;}" +
                "var r=el.getBoundingClientRect(),c=p.getBoundingClientRect();" +
                "var visibleTop=Math.max(0,c.top+p.clientTop),visibleBottom=Math.min(window.innerHeight,c.top+p.clientTop+p.clientHeight);" +
                "if(visibleBottom>visibleTop){p.scrollTop+=r.top+r.height/2-(visibleTop+(visibleBottom-visibleTop)*ratio);}" +
                "}" +
                "var r=el.getBoundingClientRect(),vh=window.innerHeight||document.documentElement.clientHeight;" +
                "window.scrollBy(0,r.top+r.height/2-vh*ratio);" +
                "r=el.getBoundingClientRect();if(r.top<0||r.bottom>vh){return false;}" +
                "for(var p=el.parentElement;p&&p!==root;p=p.parentElement){" +
                "var style=getComputedStyle(p);if(!/(auto|scroll|hidden|clip|overlay)/.test(style.overflowY)){continue;}" +
                "var c=p.getBoundingClientRect();if(r.top<c.top+p.clientTop||r.bottom>c.top+p.clientTop+p.clientHeight){return false;}" +
                "}return true;}", java.util.Arrays.asList(selector, viewportRatio));
        return Boolean.TRUE.equals(ok);
    }

    private boolean scrollToElementByWheel(String selector, double viewportRatio) {
        for (int i = 0; i < 10; i++) {
            Map<String, Object> before = readScrollMetrics(selector, viewportRatio);
            if (before == null || !Boolean.TRUE.equals(before.get("exists"))) {
                return false;
            }
            if (Boolean.TRUE.equals(before.get("inView")) && Math.abs(numberValue(before.get("centerDiff"))) < 90D) {
                return true;
            }
            double delta = numberValue(before.get("delta"));
            if (Math.abs(delta) < 20D) {
                return Boolean.TRUE.equals(before.get("inView"));
            }
            double wheelDelta = Math.max(-760D, Math.min(760D, delta));
            double wheelX = numberValue(before.get("wheelX"));
            double wheelY = numberValue(before.get("wheelY"));
            moveMouseLikeHuman(wheelX, wheelY);
            humanPause(80, 180);
            page.mouse().wheel(randomBetween(-3, 3), wheelDelta + randomBetween(-10, 10));
            mouseX = wheelX;
            mouseY = wheelY;
            humanPause(130, 280);

            Map<String, Object> after = readScrollMetrics(selector, viewportRatio);
            if (after != null && Boolean.TRUE.equals(after.get("inView"))
                    && Math.abs(numberValue(after.get("centerDiff"))) < 90D) {
                return true;
            }
            if (after != null && Math.abs(numberValue(after.get("top")) - numberValue(before.get("top"))) < 2D) {
                jsScrollToElement(selector, viewportRatio);
                humanPause(120, 240);
            }
        }
        Map<String, Object> metrics = readScrollMetrics(selector, viewportRatio);
        return metrics != null && Boolean.TRUE.equals(metrics.get("inView"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readScrollMetrics(String selector, double viewportRatio) {
        Object value = currentFrame().evaluate("(args) => {" +
                "var selector=args[0],ratio=args[1],el=document.querySelector(selector);" +
                "if(!el){return {exists:false};}" +
                "function clamp(v,min,max){return Math.max(min,Math.min(max,v));}" +
                "function scrollable(n){return n&&n.scrollHeight>n.clientHeight+20;}" +
                "var vw=window.innerWidth||document.documentElement.clientWidth||1200;" +
                "var vh=window.innerHeight||document.documentElement.clientHeight||800;" +
                "var container=null;" +
                "for(var p=el.parentElement;p;p=p.parentElement){if(scrollable(p)){container=p;break;}}" +
                "var cr=container?container.getBoundingClientRect():{left:0,top:0,right:vw,bottom:vh,width:vw,height:vh};" +
                "var visibleTop=clamp(Math.max(cr.top,0),0,vh),visibleBottom=clamp(Math.min(cr.bottom,vh),0,vh);" +
                "if(visibleBottom-visibleTop<80){visibleTop=0;visibleBottom=vh;}" +
                "var visibleLeft=clamp(Math.max(cr.left,0),0,vw),visibleRight=clamp(Math.min(cr.right,vw),0,vw);" +
                "if(visibleRight-visibleLeft<80){visibleLeft=0;visibleRight=vw;}" +
                "var r=el.getBoundingClientRect(),center=r.top+r.height/2,target=visibleTop+(visibleBottom-visibleTop)*ratio;" +
                "var desiredWheelY=center>target?visibleBottom-70:visibleTop+70;" +
                "return {exists:true,top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height," +
                "inView:r.top>=40&&r.bottom<=vh-30&&r.left>=0&&r.right<=vw,centerDiff:center-target,delta:center-target," +
                "wheelX:clamp(r.left+r.width/2,visibleLeft+40,visibleRight-40),wheelY:clamp(desiredWheelY,40,vh-40)," +
                "containerTop:cr.top,containerBottom:cr.bottom,containerScrollTop:container?container.scrollTop:((document.scrollingElement||document.documentElement).scrollTop||0)};" +
                "}", java.util.Arrays.asList(selector, viewportRatio));
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private double numberValue(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0D;
    }

    private String toPlaywrightSelector(String selector) {
        return Strings.startsWith(selector, "/") ? "xpath=" + selector : selector;
    }

    private void humanClick(Locator locator) {
        // Trial checks visibility, stability, enabled state and event reception without clicking.
        locator.click(new Locator.ClickOptions().setTrial(true).setTimeout(2500));
        BoundingBox box = locator.boundingBox();
        if (box == null || box.width <= 0 || box.height <= 0) {
            throw new PlaywrightException("Click target has no visible bounds");
        }
        double relativeX = randomBetween(box.width * 0.28, box.width * 0.72);
        double relativeY = randomBetween(box.height * 0.30, box.height * 0.70);
        moveMouseLikeHuman(box.x + relativeX, box.y + relativeY);
        humanPause(120, 320);
        // Re-resolve the locator and re-run actionability after movement/hover, rather than
        // dispatching a down/up at stale absolute coordinates or forcing an obstructed target.
        locator.click(new Locator.ClickOptions().setPosition(relativeX, relativeY)
                .setDelay(randomInt(65, 160)).setTimeout(2500));
        humanPause(40, 120);
    }

    private void moveMouseLikeHuman(double targetX, double targetY) {
        int steps = randomInt(config.getMouseMoveMinSteps(), config.getMouseMoveMaxSteps());
        double c1x = mouseX + randomBetween(-120, 120);
        double c1y = mouseY + randomBetween(-80, 80);
        double c2x = targetX + randomBetween(-120, 120);
        double c2y = targetY + randomBetween(-80, 80);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double x = cubic(mouseX, c1x, c2x, targetX, t);
            double y = cubic(mouseY, c1y, c2y, targetY, t);
            page.mouse().move(x, y, new Mouse.MoveOptions().setSteps(1));
            humanPause(8, 28);
        }
        mouseX = targetX;
        mouseY = targetY;
    }

    private double cubic(double p0, double p1, double p2, double p3, double t) {
        double u = 1 - t;
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
    }

    private void humanType(String value) {
        if (Strings.isEmpty(value)) {
            return;
        }
        int codePointCount = value.codePointCount(0, value.length());
        // 短文本：全量逐字。长文本（如淘宝商品标题）：前 6 个码点拟人，剩余 insertText（键盘事件，非 fill）
        int slowCodePoints = codePointCount <= 12 ? codePointCount : 6;
        int offset = 0;
        int typed = 0;
        while (offset < value.length() && typed < slowCodePoints) {
            int codePoint = value.codePointAt(offset);
            String ch = new String(Character.toChars(codePoint));
            page.keyboard().type(ch, new Keyboard.TypeOptions().setDelay(
                    randomInt(config.getTypingMinDelayMillis(), config.getTypingMaxDelayMillis())));
            offset += Character.charCount(codePoint);
            typed++;
            if (ThreadLocalRandom.current().nextInt(100) < 6) {
                humanPause(120, 320);
            }
        }
        if (offset < value.length()) {
            humanPause(80, 180);
            page.keyboard().insertText(value.substring(offset));
            humanPause(60, 160);
        }
    }

    private void humanPause() {
        humanPause(config.getHumanActionMinDelayMillis(), config.getHumanActionMaxDelayMillis());
    }

    private void humanOperationPause() {
        if (!config.isHumanInputEnabled()) {
            return;
        }
        int min = Math.max(0, config.getOperationRandomMinDelayMillis());
        int max = Math.max(min, config.getOperationRandomMaxDelayMillis());
        if (max == 0) {
            return;
        }
        humanPause(min, max);
    }

    private void humanPause(int minMillis, int maxMillis) {
        long remaining = randomInt(minMillis, maxMillis);
        do {
            org.rx.crawler.task.common.TaskCancellation.check();
            long part = Math.min(remaining, 100);
            Extends.sleep(part);
            remaining -= part;
        } while (remaining > 0);
        org.rx.crawler.task.common.TaskCancellation.check();
    }

    private double randomBetween(double min, double max) {
        return min + ThreadLocalRandom.current().nextDouble() * (max - min);
    }

    private int randomInt(int min, int max) {
        if (max <= min) {
            return Math.max(0, min);
        }
        return ThreadLocalRandom.current().nextInt(Math.max(0, min), max + 1);
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void registerTab(Page p) {
        synchronized (tabs) {
            for (Page value : tabs.values()) {
                if (value == p) {
                    return;
                }
            }
            tabs.put(UUID.randomUUID().toString(), p);
        }
        p.setDefaultTimeout(config.getFindElementTimeoutSeconds() * 1000D);
        p.setDefaultNavigationTimeout(config.getPageLoadTimeoutSeconds() * 1000D);
    }

    private void setCurrentPage(Page target) {
        setCurrentPage(target, true);
    }

    private void setCurrentPage(Page target, boolean bringToFront) {
        registerTab(target);
        for (Map.Entry<String, Page> entry : tabs.entrySet()) {
            if (entry.getValue() == target) {
                currentHandle = entry.getKey();
                break;
            }
        }
        page = target;
        activeFrame = null;
        if (bringToFront) {
            page.bringToFront();
        }
        page.setDefaultTimeout(config.getFindElementTimeoutSeconds() * 1000D);
        page.setDefaultNavigationTimeout(config.getPageLoadTimeoutSeconds() * 1000D);
    }

    private void saveCookiesToJar(String url) {
        if (cookieJar == null || Strings.isEmpty(url) || !Strings.startsWithIgnoreCase(url, "http")) {
            return;
        }

        java.util.List<Cookie> cookies = context.cookies(url);
        if (cookies.isEmpty()) {
            return;
        }
        long nowSeconds = System.currentTimeMillis() / 1000L;
        java.util.List<String> setCookies = new java.util.ArrayList<String>(cookies.size());
        for (Cookie cookie : cookies) {
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            sb.append(cookie.name).append("=").append(cookie.value);
            if (!Strings.isEmpty(cookie.domain)) {
                String host = java.net.URI.create(url).getHost();
                if (!Strings.equals(cookie.domain, host) || Strings.startsWith(cookie.domain, ".")) {
                    sb.append("; Domain=").append(cookie.domain);
                }
            }
            if (!Strings.isEmpty(cookie.path)) {
                sb.append("; Path=").append(cookie.path);
            }
            if (cookie.expires != null && cookie.expires > 0D) {
                long maxAge = Math.max(1L, (long) Math.ceil(cookie.expires - nowSeconds));
                sb.append("; Max-Age=").append(maxAge);
            }
            if (Boolean.TRUE.equals(cookie.secure)) {
                sb.append("; Secure");
            }
            if (Boolean.TRUE.equals(cookie.httpOnly)) {
                sb.append("; HttpOnly");
            }
            if (cookie.sameSite != null) {
                sb.append("; SameSite=").append(toSameSite(cookie.sameSite));
            }
            setCookies.add(sb.toString());
        }
        String storageUrl = cookieRegion != null
                ? HttpClient.buildUrl(url, java.util.Collections.singletonMap("_Region", cookieRegion))
                : url;
        java.net.URI uri = java.net.URI.create(storageUrl);
        cookieJar.clear(uri);
        cookieJar.saveFromResponse(uri, setCookies);
    }

    private String toSameSite(SameSiteAttribute sameSite) {
        switch (sameSite) {
            case STRICT:
                return "Strict";
            case LAX:
                return "Lax";
            case NONE:
                return "None";
            default:
                return null;
        }
    }
}
