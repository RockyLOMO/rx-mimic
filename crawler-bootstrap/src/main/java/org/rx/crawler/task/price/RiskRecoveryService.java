package org.rx.crawler.task.price;

import lombok.RequiredArgsConstructor;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.CustomCrawlQueueService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.common.TaskApiException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/** Explicit administrator recovery; ordinary login checks never reset risk protection. */
@Service
@RequiredArgsConstructor
public class RiskRecoveryService {
    private final CustomCrawlQueueService queue;
    private final PriceRiskGate risk;
    private final AppConfig config;

    public synchronized Map<String, Object> recoverJd() {
        Instant checkedAt = Instant.now();
        int expected = risk.count(PriceCodes.JD, checkedAt);
        int limit = risk.limit();
        if (limit > 0 && risk.count(0, checkedAt) >= limit) {
            throw new TaskApiException(409, "GLOBAL_RISK_STOP", "公共风险保护仍生效，需先排查来源");
        }
        PromotionUrlRequest request = new PromotionUrlRequest();
        request.setProfileName(config.getCustom().getJdUnion().getProfileName());
        request.setDebugEnabled(true);
        request.setUseCache(false);
        request.setKeepBrowserOpenOnLoginRequired(false);
        PromotionUrlResult validation = queue.submitAndWait("loginCheck", request, PromotionUrlResult.class);
        if (validation == null || validation.getStatus() != CustomCrawlStatus.SUCCESS
                || validation.isLoginRequired()
                || !Boolean.TRUE.equals(validation.getDiagnostics().get("loginVerified"))) {
            throw new TaskApiException(409, "LOGIN_NOT_VERIFIED", "京东登录验证未通过，保护未解除");
        }
        final int previous;
        try {
            previous = risk.recoverAfterVerifiedLogin(PriceCodes.JD, checkedAt, expected);
        } catch (IllegalStateException changed) {
            throw new TaskApiException(409, "RISK_CHANGED", "验证期间风险记录发生变化，保护未解除，请重新检查");
        }
        return Map.of("platform", "jd", "previousRiskCount", previous, "riskCount", 0,
                "loginVerified", true);
    }
}
