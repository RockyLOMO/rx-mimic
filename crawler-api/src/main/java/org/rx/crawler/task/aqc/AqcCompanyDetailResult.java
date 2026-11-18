package org.rx.crawler.task.aqc;

import lombok.Data;
import org.rx.crawler.task.common.CustomCrawlStatus;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 爱企查公司详情结果：营业执照面工商信息 + 股东/主要人员/对外投资/分支机构/变更记录 + 风险栏目，
 * 尽取页面可见数据；未登录态可见性以实际页面为准，未抓到的栏目在 diagnostics.sectionsMissing 说明。
 */
@Data
public class AqcCompanyDetailResult implements Serializable {
    private static final long serialVersionUID = 9107300000000000009L;

    private CustomCrawlStatus status;
    private String taskType;
    private String pid;
    private String keyword;
    private String pageUrl;
    private String profileName;

    // ==================== 营业执照面（工商信息） ====================
    private String companyName;
    /** 统一社会信用代码。 */
    private String creditCode;
    private String legalPerson;
    private String registeredCapital;
    private String paidInCapital;
    /** 成立日期，如 2019-01-31。 */
    private String foundDate;
    /** 企业类型，如 有限责任公司（自然人投资或控股的法人独资）。 */
    private String companyType;
    /** 登记状态：存续/在业/注销/吊销等。 */
    private String businessStatus;
    private String registrationAuthority;
    /** 核准日期。 */
    private String approvedDate;
    /** 营业期限，如 2019-01-31 至无固定期限。 */
    private String businessTerm;
    /**人员规模，如 100-499人。 */
    private String staffScale;
    /** 参保人数。 */
    private String insuredCount;
    /** 曾用名。 */
    private String formerNames;
    /** 注册地址。 */
    private String address;
    /** 经营范围（长文本）。 */
    private String businessScope;

    // ==================== 扩展栏目 ====================
    private List<AqcShareholderItem> shareholders = new ArrayList<AqcShareholderItem>();
    private List<AqcStaffItem> staff = new ArrayList<AqcStaffItem>();
    private List<String> branches = new ArrayList<String>();
    private List<AqcChangeItem> changes = new ArrayList<AqcChangeItem>();
    private List<AqcRiskItem> risks = new ArrayList<AqcRiskItem>();
    /** 页面其余表格原样保留（key=栏目标题，value=行单元格文本），保证「能抓到的都抓到」。 */
    private Map<String, List<List<String>>> sections = new LinkedHashMap<String, List<List<String>>>();

    // ==================== 匹配辅助（keyword 自动匹配时） ====================
    /** keyword 匹配到多家且无法唯一确定时的候选列表（status=MULTIPLE_MATCHED）。 */
    private List<AqcSearchItem> candidates = new ArrayList<AqcSearchItem>();

    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private String message;
    private Date createdAt = new Date();
    private Map<String, Object> diagnostics = new HashMap<String, Object>();
    private String errorCode;
}
