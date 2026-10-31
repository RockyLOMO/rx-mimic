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
    private static final Set<String> JD_LINK_HOSTS = Set.of("u.jd.com", "union-click.jd.com", "jingfen.jd.com", "3.cn", "3.jd.hk", "u.jd.hk", "union-click.jd.hk");
    private static final Set<String> LINK_HOSTS = Set.of("u.jd.com", "union-click.jd.com", "jingfen.jd.com", "3.cn", "3.jd.hk", "u.jd.hk", "union-click.jd.hk",
            "s.click.taobao.com", "click.taobao.com", "uland.taobao.com", "m.tb.cn", "tb.cn", "e.tb.cn");
    private final BoundedMemoryCache<String> cache = new BoundedMemoryCache<>(500, 2 * 1024 * 1024);
    private final ConcurrentHashMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final BrowserOwnerExecutor executor;
    private final PromotionNavigator navigator;
    private final TbPromotionUrlTask tb;
    private final PriceRiskGate risk;
    private final PriceWatchStore store;
    private final AppConfig config;
    private final ThreadLocal<Boolean> recovering = new ThreadLocal<>();
    private java.util.function.LongSupplier nanoTime = System::nanoTime;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private ProductVisitBudget budget;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private org.rx.crawler.task.tb.TbMallTask tbMall;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private org.rx.crawler.task.jd.JdUnionPromotionTask jd;

    public ProductIdentityResolver(BrowserOwnerExecutor executor, PromotionNavigator navigator, TbPromotionUrlTask tb,
            PriceRiskGate risk, PriceWatchStore store, AppConfig config) {
        this.executor = executor; this.navigator = navigator; this.tb = tb;
        this.risk = risk; this.store = store; this.config = config;
    }

    public String cached(int platform, String profile, String input) {
        input = ProductIdentityParser.normalizeInput(input);
        if (ProductIdentityParser.isNumeric(input)) return input;
        ProductIdentityParser.Hit direct = ProductIdentityParser.inspect(platform, input);
        if (direct.kind == ProductIdentityParser.PageKind.PRODUCT) return direct.productId;
        return input == null ? null : cache.get(key(platform, profile, input));
    }

    public void remember(int platform, String profile, String input, String numericId) {
        input = ProductIdentityParser.normalizeInput(input);
        if (input == null || input.isBlank() || input.length() > 4096 || !ProductIdentityParser.isNumeric(numericId)) return;
        if (ProductIdentityParser.isNumeric(input)) return;
        cache.put(key(platform, profile, input), numericId, Duration.ofHours(6), numericId.length() * 2);
        if (platform == PriceCodes.TB && isEncrypted(input)) {
            // Adapter-only reverse hint, in the same bounded/expiring cache, never in the database.
            cache.put("material:" + key(platform, profile, numericId), input, Duration.ofHours(6), input.length() * 2);
        }
    }

    public String cachedMaterial(int platform, String profile, String numericId) {
        if (platform != PriceCodes.TB || !ProductIdentityParser.isNumeric(numericId)) return null;
        return cache.get("material:" + key(platform, profile, numericId));
    }

    /** 历史方案保留（商品名 + 店铺 + 有界候选核验），生产主流程已停用，改走商品分析页。
     * Do not call from numeric promotion/search; retained only for reference and regression tests. */
    @Deprecated
    public String recoverMaterial(String profile, String numericId) {
        String cached = cachedMaterial(PriceCodes.TB, profile, numericId);
        if (cached != null) return cached;
        if (!executor.isOwnerThread()) return executor.call(() -> recoverMaterial(profile, numericId));
        if (Boolean.TRUE.equals(recovering.get())) return null;
        var settings = config.getCustom().getPriceWatch();
        var limit = new RecoveryLimit(Math.max(1, Math.min(5, settings.getTbIdentityMaxCandidates())),
                nanoTime.getAsLong() + TimeUnit.SECONDS.toNanos(Math.max(10, Math.min(180, settings.getTbIdentityTimeoutSeconds()))));
        recovering.set(true);
        try { return recoverOnOwner(profile, numericId, limit); }
        finally { recovering.remove(); }
    }

    private String recoverOnOwner(String profile, String numericId, RecoveryLimit limit) {
        PriceWatchStore.ProductHit product = store.findProduct(PriceCodes.TB, numericId);
        String title = product == null ? "" : product.getTitle();
        String shop = product == null ? "" : product.getShopName();
        if (title != null && !title.isBlank()) {
            String recovered = recoverByTitle(profile, numericId, title, shop, limit);
            if (recovered != null) return recovered;
            limit.check();
        }
        if (tbMall == null) return null;
        limit.check();
        if (budget != null) budget.reserve(PriceCodes.TB, ProductVisitBudget.isBackground());
        var detailRequest = new org.rx.crawler.task.tb.TbProductDetailRequest();
        detailRequest.setItemId(numericId); detailRequest.setProfileName(profile);
        detailRequest.setDownloadImages(false); detailRequest.setIncludeSpecs(false);
        detailRequest.setKeepBrowserOpenOnLoginRequired(false); detailRequest.setLoginWaitSeconds(1);
        detailRequest.setDebugEnabled(false);
        var detail = tbMall.productIdentity(detailRequest);
        if (detail.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED) throw failure("LOGIN_REQUIRED");
        if (detail.getStatus() == CustomCrawlStatus.FAILED && detail.getMessage() != null
                && detail.getMessage().contains("slider")) throw failure("TB_DETAIL_VERIFICATION_REQUIRED");
        limit.check();
        if (detail.getStatus() != CustomCrawlStatus.SUCCESS || !numericId.equals(detail.getItemId())
                || detail.getTitle() == null || detail.getTitle().isBlank()) return null;
        store.upsertProduct(PriceCodes.TB, numericId, detail.getTitle(), detail.getShopName(), "",
                java.sql.Timestamp.from(Instant.now()));
        if (detail.getTitle().equals(title) && normalizedShop(detail.getShopName()).equals(normalizedShop(shop))) return null;
        return recoverByTitle(profile, numericId, detail.getTitle(), detail.getShopName(), limit);
    }

    private String recoverByTitle(String profile, String numericId, String title, String shop, RecoveryLimit limit) {
        limit.check();
        var request = new org.rx.crawler.task.tb.TbUnionProductSearchRequest();
        request.setKeyword(title); request.setProfileName(profile); request.setMaxResults(5);
        var result = tb.searchProducts(request);
        limit.check();
        if (result.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED) throw failure("LOGIN_REQUIRED");
        if (result.getStatus() != CustomCrawlStatus.SUCCESS || result.getItems() == null) return null;
        var candidates = new java.util.ArrayList<>(result.getItems());
        candidates.removeIf(java.util.Objects::isNull);
        if (!normalizedShop(shop).isEmpty()) candidates.sort(java.util.Comparator.comparing(
                item -> !normalizedShop(shop).equals(normalizedShop(item.getStoreName()))));
        for (var item : candidates) {
            if (!isEncrypted(item.getItemId())) continue;
            limit.takeCandidate();
            try {
                String resolved = resolve(PriceCodes.TB, profile, item.getItemId());
                limit.checkTime();
                if (numericId.equals(resolved))
                    return cachedMaterial(PriceCodes.TB, profile, numericId);
            } catch (TaskApiException e) {
                if (Set.of("LOGIN_REQUIRED", "RISK_STOP", "DAILY_VISIT_LIMIT", "IDENTITY_WINDOW_CLOSED",
                        "TB_IDENTITY_CANDIDATE_LIMIT", "TB_IDENTITY_TIME_LIMIT").contains(e.getCode())) throw e;
            }
        }
        return null;
    }

    private final class RecoveryLimit {
        private int remaining;
        private final long deadline;
        private RecoveryLimit(int remaining, long deadline) { this.remaining = remaining; this.deadline = deadline; }
        private void checkTime() { if (nanoTime.getAsLong() - deadline >= 0) throw failure("TB_IDENTITY_TIME_LIMIT"); }
        private void check() {
            checkTime();
            if (remaining <= 0) throw failure("TB_IDENTITY_CANDIDATE_LIMIT");
        }
        private void takeCandidate() { check(); remaining--; }
    }

    private static String normalizedShop(String shop) { return shop == null ? "" : shop.replaceAll("[\\s\\u3000]+", ""); }

    public String resolve(int platform, String profile, String input) {
        if (input == null || input.isBlank() || input.length() > 4096) throw failure("INVALID_PRODUCT_INPUT");
        String value = ProductIdentityParser.normalizeInput(input);
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
                    } catch (org.rx.crawler.task.common.TaskCancellation.Cancelled cancelled) {
                        result.completeExceptionally(failure("IDENTITY_CANCELLED"));
                        throw cancelled;
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
        String materialNumericId = "";
        boolean link = value.regionMatches(true, 0, "https://", 0, 8) || value.regionMatches(true, 0, "http://", 0, 7);
        boolean token = org.rx.crawler.task.tb.TbUnionAnalysisPage.isToken(value);
        if (link) {
            URI uri;
            try { uri = URI.create(value); } catch (Exception e) { throw failure("INVALID_PRODUCT_LINK"); }
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1
                    || !LINK_HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))) throw failure("INVALID_PRODUCT_LINK");
            boolean jdHost = JD_LINK_HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
            if ((platform == PriceCodes.JD) != jdHost) throw failure("INVALID_PLATFORM_LINK");
        } else if (!isEncrypted(value) && !token) throw failure("INVALID_ENCRYPTED_PRODUCT_ID");
        if (platform == PriceCodes.JD && !token) {
            // Dynamic JD IDs must use Jingfen instead of unionSearchGoods.
            url = link ? value : "https://jingfen.jd.com/detail/" + value + ".html";
        } else {
            PromotionUrlRequest request = new PromotionUrlRequest();
            request.setKeyword(value); request.setAdSiteName("0"); request.setProfileName(profile);
            request.setDebugEnabled(false);
            if (platform == PriceCodes.JD && jd == null) throw failure("IDENTITY_WORKER_UNAVAILABLE");
            PromotionUrlResult promoted = platform == PriceCodes.TB ? tb.getPromotionUrl(request) : jd.getPromotionUrl(request);
            if (promoted.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED) throw failure("LOGIN_REQUIRED");
            if (promoted.getStatus() != CustomCrawlStatus.SUCCESS || promoted.getPromotionUrl() == null) {
                if ("NO_REBATE_PRODUCT".equals(promoted.getErrorCode()))
                    throw new TaskApiException(409, "NO_REBATE_PRODUCT", "非返利商品，暂不支持转链");
                throw failure("MATERIAL_PROMOTION_FAILED");
            }
            if (ProductIdentityParser.isNumeric(promoted.getItemId())) materialNumericId = promoted.getItemId();
            url = promoted.getPromotionUrl();
        }
        // Analysis numeric-input -> marketing-ID mapping and exact selection are bound in the task.
        // Reuse the profile-scoped temporary mapping, without opening coupon/retail pages.
        String verified = cached(platform, profile, url);
        if (ProductIdentityParser.isNumeric(verified)) {
            if (!materialNumericId.isEmpty() && !materialNumericId.equals(verified)) throw failure("PRODUCT_ID_MISMATCH");
            return verified;
        }
        // 未命中数字身份缓存时，两平台都必须等真实商品详情 URL；营销字段不能冒充数字身份。
        PromotionNavigator.Nav nav = navigator.open(platform, profile, url);
        if (nav.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED) throw failure("LOGIN_REQUIRED");
        ProductIdentityParser.Hit identity = ProductIdentityParser.inspect(platform, nav.getFinalUrl());
        if (nav.getStatus() != CustomCrawlStatus.SUCCESS || nav.getPageKind() != ProductIdentityParser.PageKind.PRODUCT
                || identity.kind != ProductIdentityParser.PageKind.PRODUCT) throw failure("IDENTITY_UNRESOLVED");
        if (!materialNumericId.isEmpty() && !materialNumericId.equals(identity.productId)) throw failure("PRODUCT_ID_MISMATCH");
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
