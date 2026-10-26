package org.rx.crawler.task.tb;

import org.junit.jupiter.api.Test;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.TaskApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native navigation only; clock and browser are mocked, no platform requests. */
class TbAnalysisLinkFallbackTest {
    @Test void knownTaobaoTrackingAndProductHostsRemainAccepted() {
        for (String host : List.of("e.tb.cn", "m.tb.cn", "tb.cn", "s.click.taobao.com", "click.taobao.com",
                "uland.taobao.com", "item.taobao.com", "item.tmall.com", "detail.tmall.com")) {
            assertTrue(TbUnionAnalysisPage.isAllowedProductLink("https://" + host + "/item.htm?id=123"), host);
        }
    }
    private static final String BASE = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
    private static final String LINK = "https://e.tb.cn/h.example?tk=Abcd1234567";
    private static final String NUMERIC = "123456789";
    private static final String PRODUCT = "https://detail.tmall.com/item.htm?id=" + NUMERIC;
    private static final String MATERIAL = "DynamicMaterialAlpha_123456789";

    @Test void timeoutFollowsOriginalLinkThenAnalyzesOnlyFinalNumericId() throws Exception {
        var f = new Fixture(PRODUCT, true);
        var hit = f.page.resolveMaterialWithLinkFallback(f.browser, BASE, LINK, 60, f::gate);
        assertEquals(MATERIAL, hit.materialId());
        assertEquals(NUMERIC, hit.numericId());
        assertEquals(NUMERIC, hit.analysisInput());
        assertEquals(List.of(TbUnionAnalysisPage.entryUrl(BASE, LINK), LINK,
                TbUnionAnalysisPage.entryUrl(BASE, NUMERIC)), f.navigations);
        assertEquals(List.of(15, 20, 60), f.navigationTimeouts);
        assertTrue(f.gateTargets.contains(LINK), "Original-link challenge recovery must use the original link");
        assertTrue(f.gateTargets.contains(TbUnionAnalysisPage.entryUrl(BASE, NUMERIC)));
        verify(f.browser, never()).readLinkDestination(anyString(), any(), anyInt());
    }

    @Test void settledEmptyAnalysisMayFollowOriginalLinkInsteadOfDeclaringNoRebate() throws Exception {
        var f = new Fixture(PRODUCT, true);
        when(f.browser.executeScript(anyString(), eq(TbUnionAnalysisPage.INPUT),
                eq(TbUnionAnalysisPage.ANALYZE), eq(LINK))).thenReturn("EMPTY");
        assertEquals(MATERIAL, f.page.resolveMaterialWithLinkFallback(f.browser, BASE, LINK, 4, f::gate).materialId());
        assertEquals(1, f.navigations.stream().filter(LINK::equals).count());
        verify(f.browser, times(1)).elementClick(TbUnionAnalysisPage.ANALYZE);
    }

    @Test void unsuccessfulNumericReanalysisNeverRetriesTheLinkAgain() throws Exception {
        var f = new Fixture(PRODUCT, false);
        assertThrows(TimeoutException.class,
                () -> f.page.resolveMaterialWithLinkFallback(f.browser, BASE, LINK, 4, f::gate));
        assertEquals(1, f.navigations.stream().filter(LINK::equals).count());
        assertEquals(1, f.navigations.stream().filter(TbUnionAnalysisPage.entryUrl(BASE, NUMERIC)::equals).count());
        assertEquals(3, f.navigations.size());
    }

    @Test void loginRedirectFailsBeforeNumericReanalysis() throws Exception {
        var f = new Fixture("https://login.taobao.com/member/login.jhtml", true);
        var error = assertThrows(TaskApiException.class,
                () -> f.page.resolveMaterialWithLinkFallback(f.browser, BASE, LINK, 4, f::gate));
        assertTrue(error.getCode().contains("LOGIN"));
        assertEquals(2, f.navigations.size());
    }

    @Test void intermediaryEncryptedIdAndForeignProductCannotBecomeNumericIdentity() throws Exception {
        for (String finalUrl : List.of(
                "https://uland.taobao.com/item/edetail?id=SomeEncryptedMaterial123456789",
                "https://example.com/item.htm?id=" + NUMERIC,
                "https://detail.tmall.com/item.htm?id=SomeEncryptedMaterial123456789")) {
            var f = new Fixture(finalUrl, true);
            var error = assertThrows(TaskApiException.class,
                    () -> f.page.resolveMaterialWithLinkFallback(f.browser, BASE, LINK, 4, f::gate), finalUrl);
            assertEquals("IDENTITY_UNRESOLVED", error.getCode());
            assertEquals(2, f.navigations.size(), "No numeric analysis without a trusted final product URL");
        }
    }

    @Test void foreignInputIsNeverOpenedAsFallback() throws Exception {
        var f = new Fixture(PRODUCT, true);
        String foreign = "https://example.com/item.htm?id=" + NUMERIC;
        var error = assertThrows(TaskApiException.class,
                () -> f.page.resolveMaterialWithLinkFallback(f.browser, BASE, foreign, 4, f::gate));
        assertEquals("INVALID_PRODUCT_LINK", error.getCode());
        assertFalse(f.navigations.contains(foreign));
    }

    @Test void invalidProtocolCredentialsPortAndDeceptiveHostsAreRejectedBeforeNavigation() throws Exception {
        for (String link : List.of("https://user:secret@e.tb.cn/h.example", "https://e.tb.cn:8443/h.example",
                "https://e.tb.cn.attacker.example/h.example", "https://attacker.example/e.tb.cn/h.example")) {
            var f = new Fixture(PRODUCT, true);
            var error = assertThrows(TaskApiException.class,
                    () -> f.page.resolveMaterialWithLinkFallback(f.browser, BASE, link, 4, f::gate));
            assertEquals("INVALID_PRODUCT_LINK", error.getCode());
            assertTrue(f.navigations.isEmpty());
            assertTrue(f.gateTargets.isEmpty());
        }
        assertFalse(TbUnionAnalysisPage.isAllowedProductLink("ftp://e.tb.cn/h.example"));
        assertFalse(TbUnionAnalysisPage.isAllowedProductLink("javascript:location='https://e.tb.cn/h.example'"));
    }

    @Test void linkThatAlreadyAnalyzesSuccessfullyNeverOpensOriginalProduct() throws Exception {
        var f = new Fixture(PRODUCT, true);
        when(f.browser.getCurrentUrl()).thenReturn(TbUnionAnalysisPage.entryUrl(BASE, MATERIAL));
        var hit = f.page.resolveMaterialWithLinkFallback(f.browser, BASE, LINK, 4, f::gate);
        assertEquals(MATERIAL, hit.materialId());
        assertEquals(LINK, hit.analysisInput());
        assertNull(hit.numericId());
        assertEquals(List.of(TbUnionAnalysisPage.entryUrl(BASE, LINK)), f.navigations);
    }

    @Test void numericInputUsesNormalAnalysisWithoutOriginalLinkFallback() throws Exception {
        var f = new Fixture(PRODUCT, true);
        var hit = f.page.resolveMaterialWithLinkFallback(f.browser, BASE, NUMERIC, 4, f::gate);
        assertEquals(MATERIAL, hit.materialId());
        assertEquals(NUMERIC, hit.analysisInput());
        assertEquals(List.of(TbUnionAnalysisPage.entryUrl(BASE, NUMERIC)), f.navigations);
    }

    @Test void otherBusinessFailuresDoNotTriggerOriginalLinkNavigation() throws Exception {
        var f = new Fixture(PRODUCT, true);
        var error = assertThrows(TaskApiException.class, () ->
                f.page.resolveMaterialWithLinkFallback(f.browser, BASE, LINK, 4,
                        target -> () -> { throw new TaskApiException(409, "TB_SLIDER_VERIFICATION_REQUIRED", "challenge unresolved"); }));
        assertEquals("TB_SLIDER_VERIFICATION_REQUIRED", error.getCode());
        assertEquals(List.of(TbUnionAnalysisPage.entryUrl(BASE, LINK)), f.navigations);
    }

    @Test void numericAnalysisTimeoutNeverTriggersAnyLinkFallback() throws Exception {
        var f = new Fixture(PRODUCT, false);
        assertThrows(TimeoutException.class,
                () -> f.page.resolveMaterialWithLinkFallback(f.browser, BASE, NUMERIC, 4, f::gate));
        assertEquals(List.of(TbUnionAnalysisPage.entryUrl(BASE, NUMERIC)), f.navigations);
    }

    private static final class Fixture {
        final Browser browser = mock(Browser.class);
        final AtomicLong now = new AtomicLong();
        final AtomicReference<String> current = new AtomicReference<>("");
        final List<String> navigations = new ArrayList<>();
        final List<Integer> navigationTimeouts = new ArrayList<>();
        final List<String> gateTargets = new ArrayList<>();
        final TbUnionAnalysisPage page = new TbUnionAnalysisPage(now::get);

        Fixture(String finalUrl, boolean numericSucceeds) throws TimeoutException {
            doAnswer(call -> {
                String url = call.getArgument(0);
                navigations.add(url);
                navigationTimeouts.add(call.getArgument(2));
                current.set(LINK.equals(url) ? finalUrl
                        : numericSucceeds && TbUnionAnalysisPage.entryUrl(BASE, NUMERIC).equals(url)
                        ? TbUnionAnalysisPage.entryUrl(BASE, MATERIAL) : url);
                return null;
            }).when(browser).navigateUrl(anyString(), eq(Browser.BODY_SELECTOR), anyInt());
            when(browser.getCurrentUrl()).thenAnswer(call -> current.get());
            doAnswer(call -> {
                long millis = call.getArgument(0);
                now.addAndGet(Math.max(1, millis) * 1_000_000L);
                return null;
            }).when(browser).waitForEvents(anyLong());
        }

        TbUnionAnalysisPage.VerificationGate gate(String target) {
            gateTargets.add(target);
            return () -> true;
        }
    }
}
