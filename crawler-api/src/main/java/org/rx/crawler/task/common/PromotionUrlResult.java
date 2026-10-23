package org.rx.crawler.task.common;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Data
public class PromotionUrlResult implements Serializable {
    private static final long serialVersionUID = 6813060541670472936L;

    private CustomCrawlStatus status;
    private String taskType;
    private String keyword;
    /** Platform numeric product ID; encrypted materials never enter this field. */
    private String productId;
    /** 链接可已生成但数字身份尚未验证；此时不会创建价格跟踪。 */
    private Boolean identityResolved;
    /** 淘宝数字商品 ID。京东转链不使用。 */
    private String itemId;
    /** 联盟加密商品 ID，只在平台适配器内临时使用，不序列化。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String encryptedProductId;
    private String adSiteName;
    private String mediaName;
    private String profileName;
    private ProductInfoDto productInfo;
    private String promotionUrl;
    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private String message;
    private Date createdAt = new Date();
    private Map<String, Object> diagnostics = new HashMap<String, Object>();
    /** 本次失败时带回的上一次成功转链。成功响应里为空。支持淘宝和京东。 */
    private PromotionUrlResult fallback;

    /** 命中有效缓存；createdAt 仍是原始采集时间。 */
    private boolean cacheHit;
    private Date cachedAt;
    private Date cacheExpiresAt;
    private String errorCode;
    private String promotionToken;
    private String shortUrl;
    private String longUrl;
}
