package org.rx.crawler.task.price;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.*;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** A foreground conversion may verify login while login failures block product visits. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomaticLoginRiskRecovery {
    private static final long RETRY_MILLIS = 30_000;
    private final PriceRiskGate risk;
    private final JdUnionPromotionTask jd;
    private final AppConfig config;
    private final BrowserProfileManager profiles;
    private final BrowserOwnerExecutor owner;
    private long retryAfter;

    public void beforeJdPromotion(String requestedProfile) {
        if (!owner.isOwnerThread()) throw new IllegalStateException("Login recovery requires browser owner");
        TaskCancellation.check();
        Instant now = Instant.now();
        int limit = risk.limit();
        if (limit <= 0 || !risk.stopped(PriceCodes.JD, now) || risk.count(0, now) >= limit) return;
        var snapshot = risk.snapshot(PriceCodes.JD, now);
        if (!snapshot.autoRecoverable() || snapshot.totalCount() - snapshot.loginCount() >= limit) return;
        String configured = config.getCustom().getJdUnion().getProfileName();
        if (configured == null || configured.isBlank()) configured = profiles.defaultProfileName();
        String profile = profiles.normalizeProfileName(configured);
        if (requestedProfile != null && !requestedProfile.isBlank()
                && !profile.equals(profiles.normalizeProfileName(requestedProfile))) return;
        if (now.toEpochMilli() < retryAfter) return;
        retryAfter = now.toEpochMilli() + RETRY_MILLIS;
        PromotionUrlRequest request = new PromotionUrlRequest();
        request.setProfileName(profile);
        request.setUseCache(false);
        request.setDebugEnabled(true);
        request.setKeepBrowserOpenOnLoginRequired(false);
        PromotionUrlResult verified = jd.loginCheck(request);
        TaskCancellation.check();
        if (verified == null || verified.getStatus() != CustomCrawlStatus.SUCCESS || verified.isLoginRequired()
                || !Boolean.TRUE.equals(verified.getDiagnostics().get("loginVerified"))) return;
        try {
            int recovered = risk.recoverLoginFailuresAfterVerifiedLogin(snapshot);
            retryAfter = 0;
            log.info("Foreground login risk recovered, platform=jd, loginFailures={}", recovered);
        } catch (IllegalStateException changed) {
            log.info("Foreground login recovery deferred because risk or business date changed");
        }
    }
}
