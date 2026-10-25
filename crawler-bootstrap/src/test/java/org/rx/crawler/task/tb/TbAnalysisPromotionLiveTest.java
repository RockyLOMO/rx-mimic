package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: reuses the human-authenticated local common Chrome profile. */
@EnabledIfSystemProperty(named = "tb.analysis.promotion.integration", matches = "true")
class TbAnalysisPromotionLiveTest {
    @Test void promotesTheRequestedAnalysisProductInTheLoggedInLocalProfile() {
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(true);
        ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        config.getCustom().getTbPromotion().setLoginWaitSeconds(30);
        config.getCustom().getTbPromotion().setKeepBrowserOpenOnLoginRequired(false);
        config.getCustom().getChrome().setCloseBrowserAfterTask(false);
        BrowserProfileManager profiles = new BrowserProfileManager(config);
        var task = new TbPromotionUrlTask(config, profiles,
                new CrawlEntryService(new BrowserPreflightService()), new ObjectMapper());
        var request = new PromotionUrlRequest();
        request.setProfileName("common");
        request.setKeyword(System.getProperty("tb.analysis.input",
                "AjO5N33hgf4XJWA4MOU4BHvtn-qkedodO9UnW7DBy8OcB"));
        request.setAdSiteName(System.getProperty("tb.analysis.adSiteName", "0"));
        request.setDebugEnabled(true);
        try {
            PromotionUrlResult result = task.getPromotionUrl(request);
            var info = result.getProductInfo();
            System.out.println("TB_ANALYSIS_LIVE status=" + result.getStatus() + " error=" + result.getErrorCode()
                    + " message=" + result.getMessage() + " productId=" + result.getItemId()
                    + " title=" + (info == null ? "" : info.getProductName())
                    + " price=" + (info == null ? "" : info.getPrice())
                    + " commission=" + (info == null ? "" : info.getCommissionRate())
                    + " adSite=" + result.getAdSiteName() + " slotChanged=" + result.getDiagnostics().get("slotMaterialRefreshed")
                    + " verifiedSlot=" + result.getDiagnostics().get("verifiedAdSiteId")
                    + " hasShortUrl=" + (result.getShortUrl() != null && !result.getShortUrl().isBlank())
                    + " hasToken=" + (result.getPromotionToken() != null && !result.getPromotionToken().isBlank()));
            assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus(), result.getMessage());
            assertEquals(PromotionSlots.idOf(config.getCustom().getTbPromotion().getAdSites(), request.getAdSiteName()),
                    result.getDiagnostics().get("verifiedAdSiteId"));
            assertNotNull(info);
            assertTrue(info.getProductName().contains("手环"), "Must be the requested band strap, not a previous search result");
            assertTrue(result.getItemId().matches("[0-9]+"));
            assertNotNull(result.getShortUrl());
            assertFalse(result.getShortUrl().isBlank());
            assertNotNull(result.getPromotionToken());
            assertFalse(result.getPromotionToken().isBlank());
        } finally {
            profiles.destroy();
        }
    }
}
