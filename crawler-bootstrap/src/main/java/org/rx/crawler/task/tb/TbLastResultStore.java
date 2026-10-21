package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.rx.crawler.task.common.CrawlFallbackService;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.price.PriceCodes;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;

import java.nio.file.Path;

/**
 * 淘宝商品详情和转链结果兜底存储适配器。
 * 内部委托至统一基于 H2 数据库的 {@link CrawlFallbackService}，不再向磁盘写入海量碎 JSON 文件。
 */
public final class TbLastResultStore {
    private final CrawlFallbackService fallbackService;

    public TbLastResultStore(CrawlFallbackService fallbackService) {
        this.fallbackService = fallbackService;
    }

    public TbLastResultStore(ObjectMapper objectMapper, Path root) {
        // 兼容遗留测试调用：使用内存 H2 数据库支持单测
        EntityDatabase database = new EntityDatabaseImpl("jdbc:h2:mem:tb-fallback-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", null, 4, true);
        this.fallbackService = new CrawlFallbackService(database, objectMapper);
        this.fallbackService.init();
    }

    public void finishDetail(TbProductDetailResult result) {
        if (fallbackService != null) {
            fallbackService.finishTbDetail(result);
        }
    }

    public void finishPromotion(PromotionUrlResult result) {
        if (fallbackService != null) {
            fallbackService.finishPromotion(PriceCodes.TB, result);
        }
    }
}
