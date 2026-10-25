package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise the public task exception boundary without a browser or live account. */
class TbPromotionBusinessErrorTest {
    @Test void singleAndBatchRetainNonRebateCodeAndMessage() {
        var config = new AppConfig();
        var profiles = mock(BrowserProfileManager.class);
        when(profiles.normalizeProfileName(anyString())).thenReturn("common");
        when(profiles.defaultProfileName()).thenReturn("common");
        when(profiles.acquire(anyString(), any())).thenThrow(
                new TaskApiException(409, "NO_REBATE_PRODUCT", "非返利商品，暂不支持转链"));
        var task = new TbPromotionUrlTask(config, profiles, mock(CrawlEntryService.class), new ObjectMapper());
        var request = new PromotionUrlRequest();
        request.setKeyword("123456789");
        PromotionUrlResult single = task.getPromotionUrl(request);
        assertBusinessError(single);
        var batch = task.getPromotionUrls(List.of("123456789", "223456789"));
        assertEquals(2, batch.size());
        batch.forEach(TbPromotionBusinessErrorTest::assertBusinessError);
    }

    private static void assertBusinessError(PromotionUrlResult result) {
        assertEquals(CustomCrawlStatus.FAILED, result.getStatus());
        assertEquals("NO_REBATE_PRODUCT", result.getErrorCode());
        assertEquals("非返利商品，暂不支持转链", result.getMessage());
        assertFalse(result.isLoginRequired());
    }
}
