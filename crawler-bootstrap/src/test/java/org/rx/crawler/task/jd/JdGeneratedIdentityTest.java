package org.rx.crawler.task.jd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.*;
import org.rx.crawler.task.price.*;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdGeneratedIdentityTest {
    private static final String ID = "10012345";

    @Test void verifiedUnionOutputSurvivesRetailLoginWithoutOpeningRetailSite() {
        var navigator = mock(PromotionNavigator.class);
        var identities = new ProductIdentityResolver(null, navigator, null, null, null, new AppConfig());
        var task = new JdUnionPromotionTask(new AppConfig(), null, null, new ObjectMapper());
        ReflectionTestUtils.setField(task, "identities", identities);
        var request = request();
        var result = result();
        task.rememberGeneratedIdentity(request, result);
        var boundary = new UnionMaterialBoundary(new ObjectMapper(), identities);
        var output = boundary.result("getPromotionUrl", new ObjectMapper().valueToTree(result), 1);
        assertEquals("SUCCESS", output.path("status").asText());
        assertTrue(output.path("identityResolved").asBoolean());
        assertEquals(ID, output.path("productId").asText());
        assertEquals("product", output.path("productInfo").path("productName").asText());
        assertEquals(ID, identities.cached(PriceCodes.JD, "common", result.getShortUrl()));
        assertNull(identities.cached(PriceCodes.JD, "other", result.getShortUrl()));
        verifyNoInteractions(navigator);
    }

    @Test void mismatchedIncompleteOrUnverifiedSelectionCannotBindGeneratedMaterial() {
        var identities = mock(ProductIdentityResolver.class);
        var task = new JdUnionPromotionTask(new AppConfig(), null, null, new ObjectMapper());
        ReflectionTestUtils.setField(task, "identities", identities);
        var result = result();
        result.getProductInfo().setProductLink("https://item.jd.com/100123456.html");
        task.rememberGeneratedIdentity(request(), result); // contains ID but is a different SKU
        result.getProductInfo().setProductLink("https://other.test/" + ID);
        task.rememberGeneratedIdentity(request(), result);
        result = result(); result.setPromotionToken(null);
        task.rememberGeneratedIdentity(request(), result);
        result = result(); result.getProductInfo().setPrice(null);
        task.rememberGeneratedIdentity(request(), result);
        result = result(); result.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
        task.rememberGeneratedIdentity(request(), result);
        var text = request(); text.setKeyword("product");
        task.rememberGeneratedIdentity(text, result());
        verifyNoInteractions(identities);
    }

    private static PromotionUrlRequest request() {
        var request = new PromotionUrlRequest(); request.setKeyword(ID); request.setProfileName("common"); return request;
    }
    private static PromotionUrlResult result() {
        var info = new ProductInfoDto(); info.setProductName("product");
        info.setProductLink("https://item.jd.com/" + ID + ".html"); info.setPrice("10");
        info.setCommissionRate("3%"); info.setStoreName("shop"); info.setImageUrl("https://img.test/a.jpg");
        var result = new PromotionUrlResult(); result.setKeyword(ID); result.setProfileName("common");
        result.setStatus(CustomCrawlStatus.SUCCESS); result.setProductInfo(info);
        result.setPromotionUrl("https://u.jd.com/coupon"); result.setShortUrl("https://u.jd.com/coupon");
        result.setPromotionToken("!command! code");
        result.getDiagnostics().put("identityPromotionUrl", "https://u.jd.com/product"); return result;
    }
}
