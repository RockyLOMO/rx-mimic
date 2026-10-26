package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Opt-in diagnostic: no raw page, cookies, input text or promotion material is printed. */
@EnabledIfSystemProperty(named = "tb.analysis.diagnostic", matches = "true")
class TbAnalysisMaterialLiveDiagnosticTest {
    @Test void analyzeGivenLinkAndReportReadinessOnFailure() throws Exception {
        AppConfig config = new AppConfig();
        config.getCustom().setDebugEnabled(true);
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().getTbPromotion().setKeepBrowserOpenOnLoginRequired(false);
        config.getCustom().getTbPromotion().setLoginWaitSeconds(10);
        config.getCustom().getTbPromotion().setPageTimeoutSeconds(45);
        ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        BrowserProfileManager profiles = new BrowserProfileManager(config);
        ObjectMapper mapper = new ObjectMapper();
        TbPromotionUrlTask task = new TbPromotionUrlTask(config, profiles,
                new CrawlEntryService(new BrowserPreflightService()), mapper);
        PromotionUrlRequest request = new PromotionUrlRequest();
        request.setKeyword(System.getProperty("tb.analysis.input")); request.setAdSiteName("0");
        request.setProfileName("common"); request.setDebugEnabled(true);
        request.setDebugOutputDir(System.getProperty("tb.analysis.debugDir"));
        try {
            var result = task.getPromotionUrl(request);
            System.out.println("TB_ANALYSIS_LIVE status=" + result.getStatus() + " error=" + result.getErrorCode());
            if (result.getStatus() != CustomCrawlStatus.SUCCESS) {
                WebBrowserConfig browserConfig = new WebBrowserConfig();
                browserConfig.setProfileDataPath(profiles.resolveProfileDataPath("common"));
                try (var lease = profiles.acquire("common", browserConfig)) {
                    var state = lease.getBrowser().executeScript("""
                            var field=document.querySelector(arguments[0]),button=document.querySelector(arguments[1]);
                            var url=new URL(location.href),id=url.searchParams.get('itemId')||'',body=document.body.innerText||'';
                            return {host:url.hostname,path:url.pathname,pageKey:url.searchParams.get('pageKey'),
                              urlMaterial:/^[A-Za-z0-9_-]{24,}$/.test(id)&&/[A-Za-z]/.test(id),
                              hasInput:!!field,inputEmpty:!field||!field.value.trim(),
                              inputMatches:!!field&&field.value.trim()===arguments[2].trim(),
                              inputLength:field?field.value.length:0,hasAnalyze:!!button,disabled:button?button.disabled:null,
                              hasDetail:!!document.querySelector('#detail'),
                              hasProductTitle:!!document.querySelector('#detail a[href*="uland.taobao.com/item/edetail"]'),
                              hasPromote:!!document.querySelector('#detail button[data-spm-click*="ai_good_promo"]'),
                              hasLoginText:/登录后|请登录|扫码登录/.test(body),hasNetworkText:/网络异常|网络错误|加载失败|请求失败|系统繁忙|服务异常/.test(body),
                              hasEmptyText:/输入商品链接\\/ID[，,、\\s]*智能分析/.test(body),
                              analysisLines:body.split('\\n').filter(s=>/输入|智能|分析|暂无/.test(s)).slice(0,12)};
                            """, TbUnionAnalysisPage.INPUT, TbUnionAnalysisPage.ANALYZE, request.getKeyword());
                    System.out.println("TB_ANALYSIS_READINESS=" + mapper.writeValueAsString(state));
                }
            }
            assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus(), "Given promotable product must convert");
            org.junit.jupiter.api.Assertions.assertNotNull(result.getProductInfo());
            org.junit.jupiter.api.Assertions.assertFalse(result.getShortUrl().isBlank());
            org.junit.jupiter.api.Assertions.assertFalse(result.getPromotionToken().isBlank());
            System.out.println("TB_ANALYSIS_LIVE_OK fallback=" + result.getDiagnostics().get("analysisLinkIdFallback")
                    + " name=" + result.getProductInfo().getProductName() + " productId=" + result.getItemId()
                    + " shortUrl=true token=true");
        } finally { task.closeProfile("common"); }
    }
}
