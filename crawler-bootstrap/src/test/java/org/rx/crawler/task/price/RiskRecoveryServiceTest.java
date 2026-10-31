package org.rx.crawler.task.price;

import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RiskRecoveryServiceTest {
    final CustomCrawlQueueService queue = mock(CustomCrawlQueueService.class);
    final PriceRiskGate risk = mock(PriceRiskGate.class);
    final AppConfig config = new AppConfig();
    final RiskRecoveryService service = new RiskRecoveryService(queue, risk, config);

    @Test void recoveryRequiresSuccessfulFreshLoginAndUsesCountFromBeforeVerification() {
        config.getCustom().getJdUnion().setProfileName("common");
        when(risk.count(eq(PriceCodes.JD), any())).thenReturn(8);
        var verified = new PromotionUrlResult();
        verified.setStatus(CustomCrawlStatus.SUCCESS);
        verified.getDiagnostics().put("loginVerified", true);
        when(queue.submitAndWait(eq("loginCheck"), any(), eq(PromotionUrlResult.class))).thenReturn(verified);
        when(risk.recoverAfterVerifiedLogin(eq(PriceCodes.JD), any(), eq(8))).thenReturn(8);
        var result = service.recoverJd();
        assertEquals(8, result.get("previousRiskCount"));
        assertEquals(true, result.get("loginVerified"));
        var order = inOrder(risk, queue);
        order.verify(risk).count(eq(PriceCodes.JD), any());
        order.verify(queue).submitAndWait(eq("loginCheck"), argThat(request ->
                "common".equals(request.getProfileName()) && Boolean.FALSE.equals(request.getUseCache())
                        && Boolean.TRUE.equals(request.getDebugEnabled())), eq(PromotionUrlResult.class));
        order.verify(risk).recoverAfterVerifiedLogin(eq(PriceCodes.JD), any(), eq(8));
    }

    @Test void failedMissingOrContradictoryLoginNeverClearsProtection() {
        for (CustomCrawlStatus status : new CustomCrawlStatus[]{null, CustomCrawlStatus.LOGIN_REQUIRED, CustomCrawlStatus.FAILED, CustomCrawlStatus.SUCCESS}) {
            var failed = status == null ? null : new PromotionUrlResult();
            if (failed != null) { failed.setStatus(status); failed.setLoginRequired(true); }
            when(queue.submitAndWait(eq("loginCheck"), any(), eq(PromotionUrlResult.class))).thenReturn(failed);
            assertEquals("LOGIN_NOT_VERIFIED", assertThrows(TaskApiException.class, service::recoverJd).getCode());
        }
        verify(risk, never()).recoverAfterVerifiedLogin(anyInt(), any(), anyInt());
    }

    @Test void globalRiskAndConcurrentChangesAreNotCleared() {
        when(risk.limit()).thenReturn(3);
        when(risk.count(eq(0), any())).thenReturn(3);
        assertEquals("GLOBAL_RISK_STOP", assertThrows(TaskApiException.class, service::recoverJd).getCode());
        verifyNoInteractions(queue);
        when(risk.count(eq(0), any())).thenReturn(0);
        var verified = new PromotionUrlResult();
        verified.setStatus(CustomCrawlStatus.SUCCESS);
        verified.getDiagnostics().put("loginVerified", true);
        when(queue.submitAndWait(eq("loginCheck"), any(), eq(PromotionUrlResult.class))).thenReturn(verified);
        when(risk.recoverAfterVerifiedLogin(anyInt(), any(), anyInt())).thenThrow(new IllegalStateException());
        assertEquals("RISK_CHANGED", assertThrows(TaskApiException.class, service::recoverJd).getCode());
    }

    @Test void genericSuccessWithoutPositiveSessionProofCannotClearRisk() {
        var result = new PromotionUrlResult();
        result.setStatus(CustomCrawlStatus.SUCCESS);
        when(queue.submitAndWait(eq("loginCheck"), any(), eq(PromotionUrlResult.class))).thenReturn(result);
        assertEquals("LOGIN_NOT_VERIFIED", assertThrows(TaskApiException.class, service::recoverJd).getCode());
        verify(risk, never()).recoverAfterVerifiedLogin(anyInt(), any(), anyInt());
    }
}
