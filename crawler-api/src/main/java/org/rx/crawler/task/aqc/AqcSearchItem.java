package org.rx.crawler.task.aqc;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 爱企查公司搜索结果条目：一家候选公司的卡片信息。
 */
@Data
public class AqcSearchItem implements Serializable {
    private static final long serialVersionUID = 9107300000000000001L;

    /** 爱企查公司 id，用于详情页 company_detail_{pid}。 */
    private String pid;
    private String companyName;
    /** 搜索卡片上的法定代表人（可能为空或脱敏）。 */
    private String legalPerson;
    /** 统一社会信用代码（部分卡片脱敏，以详情页为准）。 */
    private String creditCode;
    /** 登记状态：存续/在业/注销/吊销等。 */
    private String businessStatus;
    private String pageUrl;
    /** 是否与关键词精确同名（详情任务自动匹配用）。 */
    private boolean exactNameMatch;
}
