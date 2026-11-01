package org.rx.crawler.task.price;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.BrowserOwnerExecutor;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.common.TaskCancellation;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Foreground recovery authorization and login evidence; never opens Chrome. */
class AutomaticLoginRiskRecoveryTest {
    private PriceRiskGate risk;
    private JdUnionPromotionTask jd;
    private BrowserOwnerExecutor owner;
    private BrowserProfileManager profiles;
    private AppConfig config;
    private AutomaticLoginRiskRecovery recovery;
    private PriceRiskGate.RiskSnapshot snapshot;

    @BeforeEach
    void setup() {
        risk = mock(PriceRiskGate.class);
        jd = mock(JdUnionPromotionTask.class);
        owner = mock(BrowserOwnerExecutor.class);
        profiles = mock(BrowserProfileManager.class);
        config = new AppConfig();
        config.getCustom().getJdUnion().setProfileName("common");
        recovery = new AutomaticLoginRiskRecovery(risk, jd, config, profiles, owner);
        when(owner.isOwnerThread()).thenReturn(true);
        when(profiles.normalizeProfileName(anyString())).thenAnswer(call -> call.getArgument(0));
        when(profiles.defaultProfileName()).thenReturn("common");
        when(risk.limit()).thenReturn(3);
        when(risk.stopped(eq(PriceCodes.JD), any(Instant.class))).thenReturn(true);
        snapshot = new PriceRiskGate.RiskSnapshot(PriceCodes.JD, "2026-10-31", 4, 3);
        when(risk.snapshot(eq(PriceCodes.JD), any(Instant.class))).thenReturn(snapshot);
    }

    @Test
    void browserOwnerIsRequiredBeforeAnyRiskOrLoginWork() {
        when(owner.isOwnerThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> recovery.beforeJdPromotion("common"));
        verifyNoInteractions(risk, jd);
    }

    @Test
    void cancelledTaskDoesNotProbeOrClearRisk() {
        var token = new TaskCancellation.Token();
        token.cancel();
        try (var ignored = TaskCancellation.open(token)) {
            assertThrows(TaskCancellation.Cancelled.class, () -> recovery.beforeJdPromotion("common"));
        }
        verifyNoInteractions(risk, jd);
    }

    @Test
    void healthyOrDisabledRiskGateDoesNotProbe() {
        when(risk.stopped(eq(PriceCodes.JD), any(Instant.class))).thenReturn(false);
        recovery.beforeJdPromotion("common");
        when(risk.limit()).thenReturn(0);
        recovery.beforeJdPromotion("common");
        verifyNoInteractions(jd);
        verify(risk, never()).recoverLoginFailuresAfterVerifiedLogin(any());
    }

    @Test
    void globalUnknownRiskCannotBeClearedByJdLogin() {
        when(risk.count(eq(0), any(Instant.class))).thenReturn(3);
        recovery.beforeJdPromotion("common");
        verifyNoInteractions(jd);
        verify(risk, never()).snapshot(anyInt(), any());
        verify(risk, never()).recoverLoginFailuresAfterVerifiedLogin(any());
    }

    @Test
    void pureSliderAndUnclassifiedHistoricalRiskDoNotTriggerLoginProbe() {
        when(risk.snapshot(eq(PriceCodes.JD), any(Instant.class)))
                .thenReturn(new PriceRiskGate.RiskSnapshot(PriceCodes.JD, "2026-10-31", 3, 0));
        recovery.beforeJdPromotion("common");
        verifyNoInteractions(jd);
        verify(risk, never()).recoverLoginFailuresAfterVerifiedLogin(any());
    }

    @Test
    void loginProbeIsSkippedWhenRemainingNonLoginRiskWouldStillStopVisits() {
        when(risk.snapshot(eq(PriceCodes.JD), any(Instant.class)))
                .thenReturn(new PriceRiskGate.RiskSnapshot(PriceCodes.JD, "2026-10-31", 5, 2));
        recovery.beforeJdPromotion("common");
        verifyNoInteractions(jd);
        verify(risk, never()).recoverLoginFailuresAfterVerifiedLogin(any());
    }

    @Test
    void anotherProfileCannotAuthorizeClearingConfiguredProfileRisk() {
        recovery.beforeJdPromotion("another-account");
        verifyNoInteractions(jd);
        verify(risk, never()).recoverLoginFailuresAfterVerifiedLogin(any());
    }

    @Test
    void missingResultOrSuccessWithoutPositiveLoginEvidenceNeverClearsRisk() {
        var unverified = result(CustomCrawlStatus.SUCCESS, false, null);
        for (PromotionUrlResult response : new PromotionUrlResult[]{null, unverified,
                result(CustomCrawlStatus.SUCCESS, false, false), result(CustomCrawlStatus.SUCCESS, false, "true"),
                result(CustomCrawlStatus.LOGIN_REQUIRED, true, true), result(CustomCrawlStatus.SUCCESS, true, true)}) {
            var attempt = new AutomaticLoginRiskRecovery(risk, jd, config, profiles, owner);
            when(jd.loginCheck(any())).thenReturn(response);
            attempt.beforeJdPromotion("common");
        }
        verify(risk, never()).recoverLoginFailuresAfterVerifiedLogin(any());
    }

    @Test
    void verifiedLoginRecoversOnlyTheExactPreProbeSnapshotIncludingMixedRisk() {
        when(jd.loginCheck(any())).thenReturn(result(CustomCrawlStatus.SUCCESS, false, true));
        when(risk.recoverLoginFailuresAfterVerifiedLogin(snapshot)).thenReturn(3);
        recovery.beforeJdPromotion("common");
        var ordered = inOrder(risk, jd);
        ordered.verify(risk).snapshot(eq(PriceCodes.JD), any(Instant.class));
        ordered.verify(jd).loginCheck(argThat(request -> "common".equals(request.getProfileName())
                && Boolean.FALSE.equals(request.getUseCache()) && Boolean.TRUE.equals(request.getDebugEnabled())
                && Boolean.FALSE.equals(request.getKeepBrowserOpenOnLoginRequired())));
        ordered.verify(risk).recoverLoginFailuresAfterVerifiedLogin(same(snapshot));
        verify(risk, never()).recoverAfterVerifiedLogin(anyInt(), any(), anyInt());
    }

    @Test
    void changedRiskSnapshotDefersRecoveryAndDoesNotRetryImmediately() {
        when(jd.loginCheck(any())).thenReturn(result(CustomCrawlStatus.SUCCESS, false, true));
        when(risk.recoverLoginFailuresAfterVerifiedLogin(snapshot)).thenThrow(new IllegalStateException("Risk changed"));
        assertDoesNotThrow(() -> recovery.beforeJdPromotion("common"));
        recovery.beforeJdPromotion("common");
        verify(jd).loginCheck(any());
        verify(risk).recoverLoginFailuresAfterVerifiedLogin(same(snapshot));
        verify(risk, never()).recoverAfterVerifiedLogin(anyInt(), any(), anyInt());
    }

    @Test
    void failedVerificationUsesThirtySecondCooldownThenAllowsAnotherProbe() {
        when(jd.loginCheck(any())).thenReturn(result(CustomCrawlStatus.LOGIN_REQUIRED, true, false));
        long before = System.currentTimeMillis();
        recovery.beforeJdPromotion("common");
        long after = System.currentTimeMillis();
        long retryAfter = (long) ReflectionTestUtils.getField(recovery, "retryAfter");
        assertTrue(retryAfter >= before + 30_000 && retryAfter <= after + 30_000);
        recovery.beforeJdPromotion("common");
        verify(jd).loginCheck(any());
        // Move only the retry deadline; avoid a real 30-second wait or touching Chrome.
        ReflectionTestUtils.setField(recovery, "retryAfter", System.currentTimeMillis() - 1);
        recovery.beforeJdPromotion("common");
        verify(jd, times(2)).loginCheck(any());
        verify(risk, never()).recoverLoginFailuresAfterVerifiedLogin(any());
    }

    private PromotionUrlResult result(CustomCrawlStatus status, boolean loginRequired, Object evidence) {
        var result = new PromotionUrlResult();
        result.setStatus(status);
        result.setLoginRequired(loginRequired);
        if (evidence != null) result.getDiagnostics().put("loginVerified", evidence);
        return result;
    }
}
