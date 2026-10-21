package org.rx.crawler.task.price;

import lombok.Data;
import org.rx.crawler.task.common.CustomCrawlStatus;

/** 按联盟 ID 精确查价。调用方必须丢掉非精确命中。 */
public interface UnionPriceProbe {
    Probe probe(int platform, String unionProductId, String profileName);

    @Data
    class Probe {
        private CustomCrawlStatus status = CustomCrawlStatus.FAILED;
        private String message = "";
        private String title = "";
        private String shop = "";
        private String image = "";
        private String rawPrice = "";
        private String priceField = "";
        private boolean exactMatch;
    }
}
