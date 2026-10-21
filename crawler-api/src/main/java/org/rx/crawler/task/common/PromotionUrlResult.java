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
    /** 淘宝数字商品 ID。京东转链不使用。 */
    private String itemId;
    /** 淘宝联盟加密商品 ID。京东转链不使用。 */
    private String mktItemId;
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
    /** 本次失败时带回的上一次成功转链。成功响应里为空。京东不使用。 */
    private PromotionUrlResult fallback;
}
