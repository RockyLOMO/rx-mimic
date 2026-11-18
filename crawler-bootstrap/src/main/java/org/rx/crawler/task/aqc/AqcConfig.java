package org.rx.crawler.task.aqc;

import lombok.Data;
import org.rx.crawler.task.common.StepDelayConfig;

/**
 * 爱企查（aiqicha.baidu.com）公司信息抓取配置。
 */
@Data
public class AqcConfig implements StepDelayConfig {
    private String profileName = "common";
    private boolean fingerprintEnabled = true;
    private boolean headless = false;

    private boolean preflightEnabled = true;
    private String preflightUrl = "https://bot.sannysoft.com/";
    private boolean preflightStrict = true;
    private int preflightCacheMinutes = 30;
    private boolean forcePreflight = false;

    private String homeUrl = "https://aiqicha.baidu.com";
    private String searchUrlTemplate = "https://aiqicha.baidu.com/s?q={keyword}&t=0";
    private String detailUrlTemplate = "https://aiqicha.baidu.com/company_detail_{pid}";
    /** 百度账号登录页前缀（爱企查基础信息免登录，仅深层数据可能要求登录）。 */
    private String loginUrlPrefix = "https://passport.baidu.com";

    private int pageTimeoutSeconds = 60;
    private int initialPageTimeoutSeconds = 60;
    private int stepDelayMillis = 900;
    private int stepDelayRandomMillis = 600;
    /** 页面导航前的随机间歇基础值：模拟人浏览节奏。 */
    private int navigationPauseMillis = 1500;
    /** 页面导航前随机间歇的随机加成上限。 */
    private int navigationPauseRandomMillis = 2500;
    private boolean keepBrowserOpenOnLoginRequired = true;
    private int keepBrowserOpenSecondsOnLoginRequired = 180;
    private int loginWaitSeconds = 180;

    /** 百度安全验证（cbae 滑块）无自动流程，等待人工处理的秒数。 */
    private int securityHumanWaitSeconds = 120;

    /** 搜索默认返回条数。 */
    private int defaultMaxResults = 20;
    /** 搜索最多返回条数。 */
    private int maxResultsLimit = 50;

    /** 详情页懒加载栏目滚动步数（分段滚动触发各栏目表格挂载）。 */
    private int detailScrollSteps = 14;
    /** 单个栏目表格最多保留行数。 */
    private int maxSectionRows = 60;

    private String debugOutputDir = "./data/aqc/debug";
}
