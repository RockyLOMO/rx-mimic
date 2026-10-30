package org.rx.crawler.task.jd;

import lombok.Data;
import org.rx.crawler.task.common.CustomCrawlStatus;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class JdProductDetailResult implements Serializable {
    private static final long serialVersionUID = 5127301958047250016L;

    private CustomCrawlStatus status;
    private String taskType;
    private String skuId;
    private String productUrl;
    private String profileName;
    private String title;
    private String price;
    private String shopName;
    /** 当前商品的好评度。评价多时选中「当前商品」后读取；评价很少时用弹层左上角的「全部 100%好评」。 */
    private String goodRate;
    /** 买家评价数，例如 3。 */
    private String commentCount;
    private List<JdImageInfo> mainImages = new ArrayList<JdImageInfo>();
    /** 主图相册里的视频，只保留原链接。 */
    private List<JdImageInfo> mainVideos = new ArrayList<JdImageInfo>();
    private List<JdImageInfo> detailImages = new ArrayList<JdImageInfo>();
    private List<JdProductSpecItem> specs = new ArrayList<JdProductSpecItem>();
    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private String message;
    private Date createdAt = new Date();
    private Map<String, Object> diagnostics = new HashMap<String, Object>();
    /** 本次失败时带回的上一次成功结果。成功响应里为空。 */
    private JdProductDetailResult fallback;

    /** 命中有效缓存；createdAt 仍是原始采集时间。 */
    private boolean cacheHit;
    private Date cachedAt;
    private Date cacheExpiresAt;
    private String errorCode;
}
