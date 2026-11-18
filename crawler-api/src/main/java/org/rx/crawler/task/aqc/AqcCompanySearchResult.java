package org.rx.crawler.task.aqc;

import lombok.Data;
import org.rx.crawler.task.common.CustomCrawlStatus;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 爱企查公司搜索结果：候选公司卡片列表。
 */
@Data
public class AqcCompanySearchResult implements Serializable {
    private static final long serialVersionUID = 9107300000000000004L;

    private CustomCrawlStatus status;
    private String taskType;
    private String keyword;
    private String profileName;
    private List<AqcSearchItem> items = new ArrayList<AqcSearchItem>();
    private int resultCount;
    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private String message;
    private Date createdAt = new Date();
    private Map<String, Object> diagnostics = new HashMap<String, Object>();
    private String errorCode;
}
