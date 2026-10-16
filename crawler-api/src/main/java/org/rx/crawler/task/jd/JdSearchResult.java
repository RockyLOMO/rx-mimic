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
public class JdSearchResult implements Serializable {
    private static final long serialVersionUID = 5127301958047250012L;

    private CustomCrawlStatus status;
    private String taskType;
    private String keyword;
    private String profileName;
    private List<JdSearchItem> items = new ArrayList<JdSearchItem>();
    private int resultCount;
    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private String message;
    private Date createdAt = new Date();
    private Map<String, Object> diagnostics = new HashMap<String, Object>();
}
