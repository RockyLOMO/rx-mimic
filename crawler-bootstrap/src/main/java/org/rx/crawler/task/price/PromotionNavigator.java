package org.rx.crawler.task.price;

import lombok.Data;
import org.rx.crawler.task.common.CustomCrawlStatus;

/** 打开转链后的推广链接，等待最终页面。测试可以替换实现。 */
public interface PromotionNavigator {
    Nav open(int platform, String profileName, String promotionUrl);

    @Data
    class Nav {
        private CustomCrawlStatus status = CustomCrawlStatus.FAILED;
        private String message = "";
        private String finalUrl = "";
        private String productId = "";
        private ProductIdentityParser.PageKind pageKind = ProductIdentityParser.PageKind.OTHER;
        private String bodyText = "";
    }
}
