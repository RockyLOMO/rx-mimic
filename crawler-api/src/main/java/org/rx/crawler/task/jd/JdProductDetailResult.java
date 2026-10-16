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
    private List<JdImageInfo> mainImages = new ArrayList<JdImageInfo>();
    private List<JdImageInfo> detailImages = new ArrayList<JdImageInfo>();
    private List<JdProductSpecItem> specs = new ArrayList<JdProductSpecItem>();
    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private String message;
    private Date createdAt = new Date();
    private Map<String, Object> diagnostics = new HashMap<String, Object>();
}
