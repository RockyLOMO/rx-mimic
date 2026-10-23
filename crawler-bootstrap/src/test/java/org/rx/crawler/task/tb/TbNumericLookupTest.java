package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.*;
import org.rx.crawler.task.price.*;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TbNumericLookupTest {
    @Test void numericLookupExposesPriceOnlyAfterRedirectIdentityMatches() {
        Fixture f = new Fixture();
        when(f.identities.resolve(PriceCodes.TB, "common", "https://s.click.taobao.com/private")).thenReturn("123456789");
        var result = f.task.searchProducts(f.request());
        assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
        assertEquals("123456789", result.getItems().get(0).getProductId());
        assertEquals("19.90", result.getItems().get(0).getPrice());
        verify(f.profiles, never()).acquire(anyString(), any());
    }

    @Test void sameTitleAndShopCannotOverrideAContradictingFinalProductId() {
        Fixture f = new Fixture();
        when(f.identities.resolve(PriceCodes.TB, "common", "https://s.click.taobao.com/private")).thenReturn("999999999");
        assertEquals("PRODUCT_ID_MISMATCH", assertThrows(TaskApiException.class,
                () -> f.task.searchProducts(f.request())).getCode());
    }

    private static class Fixture {
        final ProductIdentityResolver identities = mock(ProductIdentityResolver.class);
        final BrowserProfileManager profiles = mock(BrowserProfileManager.class);
        final TbPromotionUrlTask task = spy(new TbPromotionUrlTask(new AppConfig(), profiles,
                mock(CrawlEntryService.class), new ObjectMapper()));
        Fixture() {
            when(profiles.normalizeProfileName(anyString())).thenReturn("common");
            ReflectionTestUtils.setField(task, "identities", identities);
            var promoted = new PromotionUrlResult();
            promoted.setStatus(CustomCrawlStatus.SUCCESS);
            promoted.setPromotionUrl("https://s.click.taobao.com/private");
            var info = new ProductInfoDto(); info.setProductName("full title"); info.setStoreName("shop");
            info.setPrice("19.90"); info.setPriceField("unionPromotionPrice"); promoted.setProductInfo(info);
            doReturn(promoted).when(task).getPromotionUrl(any());
        }
        TbUnionProductSearchRequest request() {
            var request = new TbUnionProductSearchRequest(); request.setKeyword("123456789"); return request;
        }
    }
}
