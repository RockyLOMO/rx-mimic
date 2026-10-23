package org.rx.crawler.task.price;

import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.BrowserOwnerExecutor;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.common.TaskApiException;
import org.rx.crawler.task.tb.TbPromotionUrlTask;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Only this boundary knows encryptedProductId. The rest of the application receives numeric IDs. */
@Service
public class ProductIdentityResolver {
    private static final Set<String> LINK_HOSTS = Set.of("u.jd.com", "union-click.jd.com", "jingfen.jd.com",
            "s.click.taobao.com", "click.taobao.com", "uland.taobao.com", "m.tb.cn", "tb.cn", "e.tb.cn");
    private final BoundedMemoryCache<String> cache = new BoundedMemoryCache<>(500, 2 * 1024 * 1024);
    private final ConcurrentHashMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final BrowserOwnerExecutor executor;
    private final PromotionNavigator navigator;
    private final TbPromotionUrlTask tb;
    private final PriceRiskGate risk;
    private final PriceWatchStore store;
    private final AppConfig config;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private ProductVisitBudget budget;

    public ProductIdentityResolver(BrowserOwnerExecutor executor, PromotionNavigator navigator, TbPromotionUrlTask tb,
            PriceRiskGate risk, PriceWatchStore store, AppConfig config) {
        this.executor = executor; this.navigator = navigator; this.tb = tb;
        this.risk = risk; this.store = store; this.config = config;
    }

    public String cached(int platform, String profile, String input) {
        if (ProductIdentityParser.isNumeric(input)) return input;
        ProductIdentityParser.Hit direct = ProductIdentityParser.inspect(platform, input);
        if (direct.kind == ProductIdentityParser.PageKind.PRODUCT) return direct.productId;
        return input == null ? null : cache.get(key(platform, profile, input));
    }

    public void remember(int platform, String profile, String input, String numericId) {
        if (input == null || input.isBlank() || input.length() > 4096 || !ProductIdentityParser.isNumeric(numericId)) return;
        cache.put(key(platform, profile, input), numericId, Duration.ofHours(6), numericId.length() * 2);
    }

    public String resolve(int platform, String profile, String input) {
        if (input == null || input.isBlank() || input.length() > 4096) throw failure("INVALID_PRODUCT_INPUT");
        String value = input.trim();
        String hit = cached(platform, profile, value);
        if (hit != null && !hit.isEmpty()) return hit;
        String key = key(platform, profile, value);
        CompletableFuture<String> future;
        synchronized (pending) {
            future = pending.get(key);
            if (future == null) {
                if (pending.size() >= 20) throw new TaskApiException(429, "IDENTITY_QUEUE_FULL", "Identity queue full");
                future = new CompletableFuture<>();
                pending.put(key, future);
                CompletableFuture<String> result = future;
                Runnable run = () -> {
                    try {
                        String id = navigate(platform, profile, value);
                        remember(platform, profile, value, id);
                        result.complete(id);
                    } catch (Exception e) {
                        // Only constant error codes escape: navigation errors can contain the complete material URL.
                        result.completeExceptionally(e instanceof TaskApiException ? e : failure("IDENTITY_UNRESOLVED"));
                    } finally { pending.remove(key, result); }
                };
                try {
                    if (executor.isOwnerThread()) run.run(); else executor.execute(run);
                } catch (RuntimeException rejected) {
                    pending.remove(key, result);
                    result.completeExceptionally(failure("IDENTITY_WORKER_UNAVAILABLE"));
                }
            }
        }
        try { return future.get(120, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof TaskApiException api) throw api;
            throw failure("IDENTITY_UNRESOLVED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw failure("IDENTITY_WAIT_INTERRUPTED");
        } catch (java.util.concurrent.TimeoutException e) { throw failure("IDENTITY_PENDING_RETRY"); }
    }

    private String navigate(int platform, String profile, String value) {
        if (platform != PriceCodes.JD && platform != PriceCodes.TB) throw failure("INVALID_PLATFORM");
        if (risk.stopped(platform, Instant.now())) throw failure("RISK_STOP");
        PriceWatchClock clock = PriceWatchClock.parse(config.getCustom().getPriceWatch().getTimezone(),
                config.getCustom().getPriceWatch().getWindowStart(), config.getCustom().getPriceWatch().getWindowEnd());
        if (!clock.valid() || !clock.open(Instant.now())) throw failure("IDENTITY_WINDOW_CLOSED");
        if (budget != null) budget.reserve(platform, ProductVisitBudget.isBackground());
        else {
            Integer max = config.getCustom().getPriceWatch().getDailyMaxProductVisits().get(PriceCodes.platformKey(platform));
            if (store.reserveVisit(platform, clock.businessDate(Instant.now()).toString(), max == null ? 0 : max) < 0)
                throw failure("DAILY_VISIT_LIMIT");
        }
        String url;
        if (value.startsWith("https://") || value.startsWith("http://")) {
            URI uri;
            try { uri = URI.create(value); } catch (Exception e) { throw failure("INVALID_PRODUCT_LINK"); }
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1
                    || !LINK_HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))) throw failure("INVALID_PRODUCT_LINK");
            boolean jd = uri.getHost().endsWith("jd.com");
            if ((platform == PriceCodes.JD) != jd) throw failure("INVALID_PLATFORM_LINK");
            url = value;
        } else {
            if (!isEncrypted(value)) throw failure("INVALID_ENCRYPTED_PRODUCT_ID");
            if (platform == PriceCodes.JD) {
                // Deliberately bypass unionSearchGoods. Jingfen is the material entry point, not a search keyword.
                url = "https://jingfen.jd.com/detail/" + value + ".html";
            } else {
                PromotionUrlRequest request = new PromotionUrlRequest();
                request.setKeyword(value); request.setAdSiteName("0"); request.setProfileName(profile);
                request.setOutputPath(""); request.setDebugEnabled(false);
                PromotionUrlResult promoted = tb.getPromotionUrl(request);
                if (promoted.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED) throw failure("LOGIN_REQUIRED");
                if (promoted.getStatus() != CustomCrawlStatus.SUCCESS || promoted.getPromotionUrl() == null)
                    throw failure("MATERIAL_PROMOTION_FAILED");
                url = promoted.getPromotionUrl();
            }
        }
        PromotionNavigator.Nav nav = navigator.open(platform, profile, url);
        if (nav.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED) throw failure("LOGIN_REQUIRED");
        ProductIdentityParser.Hit identity = ProductIdentityParser.inspect(platform, nav.getFinalUrl());
        if (nav.getStatus() != CustomCrawlStatus.SUCCESS || nav.getPageKind() != ProductIdentityParser.PageKind.PRODUCT
                || identity.kind != ProductIdentityParser.PageKind.PRODUCT) throw failure("IDENTITY_UNRESOLVED");
        remember(platform, profile, url, identity.productId);
        return identity.productId;
    }

    public static boolean isEncrypted(String input) {
        return input != null && input.length() >= 16 && input.length() <= 512
                && input.matches("[A-Za-z0-9_-]+") && input.matches(".*[A-Za-z].*");
    }

    private String key(int platform, String profile, String value) {
        try { return platform + ":" + (profile == null ? "common" : profile) + ":" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static TaskApiException failure(String code) { return new TaskApiException(409, code, code); }
}
