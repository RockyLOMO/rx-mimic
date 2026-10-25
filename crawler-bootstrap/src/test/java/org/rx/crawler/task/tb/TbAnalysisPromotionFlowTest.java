package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The resolved analysis product must be promoted there, without searching its encrypted alias again. */
class TbAnalysisPromotionFlowTest {
    private static final String MATERIAL = "DynamicMaterialAlpha_123456789";

    @Test void goodsSearchRouteRemainsSelectableWhileAnalysisIsTheDefault() throws Exception {
        Fixture f = new Fixture(MATERIAL);
        assertFalse(f.config.isAnalysisViaGoodsSearch());
        f.config.setAnalysisViaGoodsSearch(true);
        var reachedGoods = new PromotionReached();
        doThrow(reachedGoods).when(f.browser).navigateUrl(eq(f.config.getPromotionGoodsUrl()), anyString(), anyInt());
        assertSame(reachedGoods, assertThrows(PromotionReached.class, f::run));
        verify(f.browser).navigateUrl(eq(f.config.getPromotionGoodsUrl()), anyString(), anyInt());
    }

    @Test void numericProductReachesPromotionOnAnalysisPageWithoutReturningToGoodsSearch() throws Exception {
        Fixture f = new Fixture("123456789");
        var reachedPromotion = new PromotionReached();
        try (MockedConstruction<TbUnionAnalysisPage> constructed = mockConstruction(TbUnionAnalysisPage.class,
                (analysis, context) -> {
                    f.stubAnalysis(analysis, "123456789");
                    doThrow(reachedPromotion).when(analysis).promote(f.browser);
                })) {
            assertSame(reachedPromotion, assertThrows(PromotionReached.class, f::run));
            TbUnionAnalysisPage analysis = constructed.constructed().getFirst();
            var order = inOrder(analysis);
            order.verify(analysis).resolveMaterial(eq(f.browser), eq(f.config.getAnalysisUrl()),
                    eq("123456789"), eq(f.config.getPageTimeoutSeconds()), any());
            order.verify(analysis).awaitReadyProduct(eq(f.browser), eq(f.config.getPageTimeoutSeconds()), any());
            order.verify(analysis).readProductId(f.browser, f.config.getPageTimeoutSeconds());
            order.verify(analysis).promote(f.browser);
            f.verifyNoGoodsSearch();
        }
    }

    @Test void contradictoryFinalProductIdStopsBeforeAnyPromotionClick() throws Exception {
        Fixture f = new Fixture("123456789");
        try (MockedConstruction<TbUnionAnalysisPage> constructed = mockConstruction(TbUnionAnalysisPage.class,
                (analysis, context) -> f.stubAnalysis(analysis, "999999999"))) {
            f.run();
            assertEquals(CustomCrawlStatus.FAILED, f.result.getStatus());
            assertEquals("PRODUCT_ID_MISMATCH", f.result.getMessage());
            verify(constructed.constructed().getFirst(), never()).promote(any());
            f.verifyNoGoodsSearch();
        }
    }

    @Test void encryptedInputStillInitializesItsOwnAnalysisBeforeReadingOrPromoting() throws Exception {
        Fixture f = new Fixture(MATERIAL);
        var reachedPromotion = new PromotionReached();
        try (MockedConstruction<TbUnionAnalysisPage> constructed = mockConstruction(TbUnionAnalysisPage.class,
                (analysis, context) -> {
                    f.stubAnalysis(analysis, "123456789");
                    doThrow(reachedPromotion).when(analysis).promote(f.browser);
                })) {
            assertSame(reachedPromotion, assertThrows(PromotionReached.class, f::run));
            verify(constructed.constructed().getFirst()).resolveMaterial(eq(f.browser),
                    eq(f.config.getAnalysisUrl()), eq(MATERIAL), eq(f.config.getPageTimeoutSeconds()), any());
            f.verifyNoGoodsSearch();
        }
    }

    private static final class PromotionReached extends RuntimeException { }

    private static final class Fixture {
        final AppConfig app = new AppConfig();
        final TbPromotionConfig config = app.getCustom().getTbPromotion();
        final Browser browser = mock(Browser.class);
        final TbPromotionUrlTask task = new TbPromotionUrlTask(app, mock(BrowserProfileManager.class),
                mock(CrawlEntryService.class), new ObjectMapper());
        final PromotionUrlRequest request = new PromotionUrlRequest();
        final PromotionUrlResult result = new PromotionUrlResult();
        final Object debug;

        Fixture(String input) throws Exception {
            request.setKeyword(input);
            request.setProfileName("common");
            request.setAdSiteName("0");
            request.setDebugEnabled(false);
            when(browser.getCurrentUrl()).thenReturn(TbUnionAnalysisPage.entryUrl(config.getAnalysisUrl(), MATERIAL));
            when(browser.executeScript("return document.body ? document.body.innerText : '';"))
                    .thenReturn("商品分析 商品信息 分析结果");
            ReflectionTestUtils.setField(task, "sliderVerifyHandler", mock(SliderVerifyHandler.class));
            Class<?> type = Class.forName(TbPromotionUrlTask.class.getName() + "$DebugRecorder");
            Constructor<?> constructor = type.getDeclaredConstructor(TbPromotionUrlTask.class,
                    PromotionUrlRequest.class, TbPromotionConfig.class);
            constructor.setAccessible(true);
            debug = constructor.newInstance(task, request, config);
        }

        void stubAnalysis(TbUnionAnalysisPage analysis, String finalId) throws Exception {
            when(analysis.resolveMaterial(eq(browser), anyString(), eq(request.getKeyword()), anyInt(), any()))
                    .thenAnswer(call -> {
                        assertTrue(((TbUnionAnalysisPage.VerificationGate) call.getArgument(4)).clear());
                        return MATERIAL;
                    });
            var info = new ProductInfoDto();
            info.setProductName("Huawei band silicone strap");
            info.setProductLink("https://uland.taobao.com/item/edetail?id=" + MATERIAL);
            info.setPrice("5.78");
            when(analysis.awaitReadyProduct(eq(browser), anyInt(), any())).thenAnswer(call -> {
                assertTrue(((TbUnionAnalysisPage.VerificationGate) call.getArgument(2)).clear());
                return new TbUnionAnalysisPage.Hit(info, MATERIAL);
            });
            when(analysis.readProductId(eq(browser), anyInt())).thenReturn(finalId);
        }

        void run() {
            ReflectionTestUtils.invokeMethod(task, "runAnalysisFlow", browser, request, config, result, debug);
        }

        void verifyNoGoodsSearch() throws Exception {
            // In this fixture the analysis helper is mocked, so every navigation is an unintended detour.
            verify(browser, never()).navigateUrl(anyString(), anyString(), anyInt());
            verify(browser, never()).elementPress(anyString(), anyString(), anyBoolean());
            assertTrue(mockingDetails(browser).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("executeScript"))
                    .noneMatch(call -> call.getArgument(0, String.class).contains("data-rx-tb-promo-search-input")));
        }
    }
}
