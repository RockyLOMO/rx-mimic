package org.rx.crawler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 过期只决定是否重新抓取，历史成功结果仍可供失败兜底。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.product-cache")
public class ProductCacheProperties {
    private long searchTtlSeconds = 1800;
    private long detailTtlSeconds = 21600;
    private long promotionTtlSeconds = 3600;
}
