package org.rx.crawler.task.tb;

import lombok.Data;
import org.rx.crawler.task.common.CustomCrawlStatus;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class TbUnionProductSearchResult implements Serializable {
    private static final long serialVersionUID = 6127301958047250022L;

    private CustomCrawlStatus status;
    private String taskType;
    private String keyword;
    private String profileName;
    private List<TbUnionProductItem> items = new ArrayList<TbUnionProductItem>();
    private int resultCount;
    /** 联盟侧总命中数 */
    private long totalCount;
    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private String message;
    private Date createdAt = new Date();
    private Map<String, Object> diagnostics = new HashMap<String, Object>();

    /** 命中有效缓存；createdAt 仍是原始采集时间。 */
    private boolean cacheHit;
    private Date cachedAt;
    private Date cacheExpiresAt;
    private String errorCode;
    /** 本次失败时的历史成功结果，与正常业务字段分开。 */
    private TbUnionProductSearchResult fallback;
}
