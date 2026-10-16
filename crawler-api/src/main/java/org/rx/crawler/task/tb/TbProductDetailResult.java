package org.rx.crawler.task.tb;

import lombok.Data;
import org.rx.crawler.task.common.CustomCrawlStatus;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 淘宝/天猫商品详情抓取结果。
 */
@Data
public class TbProductDetailResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private CustomCrawlStatus status;
    private String taskType;
    private String itemId;
    private String productUrl;
    private String profileName;

    /** 商品标题。 */
    private String title;
    /** 到手价 / 展示价，例如 ¥19.90。 */
    private String price;
    /** 店铺名称。 */
    private String shopName;

    /** 主图相册列表。 */
    private List<TbImageInfo> mainImages = new ArrayList<TbImageInfo>();
    /** 详情长图列表。 */
    private List<TbImageInfo> detailImages = new ArrayList<TbImageInfo>();
    /** 规格参数键值对列表。 */
    private List<TbProductSpecItem> specs = new ArrayList<TbProductSpecItem>();

    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private String message;
    private Date createdAt = new Date();
    private Map<String, Object> diagnostics = new HashMap<String, Object>();
}
