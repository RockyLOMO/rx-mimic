package org.rx.crawler.task.jd;

import lombok.Data;
import org.rx.crawler.task.common.StepDelayConfig;

/**
 * 京东商城（主站搜索 / 商品详情）抓取配置。
 */
@Data
public class JdMallConfig implements StepDelayConfig {
    private String profileName = "common";
    private boolean fingerprintEnabled = true;
    private boolean headless = false;

    private boolean preflightEnabled = true;
    private String preflightUrl = "https://bot.sannysoft.com/";
    private boolean preflightStrict = true;
    private int preflightCacheMinutes = 30;
    private boolean forcePreflight = false;

    private String homeUrl = "https://www.jd.com";
    private String searchUrlTemplate = "https://search.jd.com/Search?keyword={keyword}&enc=utf-8";
    private String itemUrlTemplate = "https://item.jd.com/{skuId}.html";
    private String loginUrlPrefix = "https://passport.jd.com";

    private int pageTimeoutSeconds = 60;
    private int initialPageTimeoutSeconds = 60;
    private int stepDelayMillis = 700;
    private int stepDelayRandomMillis = 400;
    /** 页面导航前的随机间歇基础值：模拟人浏览节奏（如搜索完成→点开商品详情页之间的停顿）。 */
    private int navigationPauseMillis = 1500;
    /** 页面导航前随机间歇的随机加成上限。 */
    private int navigationPauseRandomMillis = 2500;
    private boolean keepBrowserOpenOnLoginRequired = true;
    private int keepBrowserOpenSecondsOnLoginRequired = 180;
    private int loginWaitSeconds = 180;

    /** JDJRV 滑块自动拖拽次数；仍失败转人工等待。 */
    private int captchaMaxAutoAttempts = 3;
    /** 自动拖拽全部失败后等待人工处理验证码的秒数。 */
    private int captchaHumanWaitSeconds = 120;

    /** 搜索默认返回条数。超过单页时自动翻页。 */
    private int defaultMaxResults = 60;
    /** 搜索最多返回条数。 */
    private int maxResultsLimit = 100;
    /** 自动翻页最多打开几页，防止单页条数识别失败时一直点下一页。 */
    private int maxSearchPages = 8;
    /** 搜索滚动加载最大轮数。 */
    private int maxScrollRounds = 60;

    /** 详情长图收集上限。 */
    private int maxDetailImages = 120;
    /** 详情页拟人滚动步数（触发懒加载）。 */
    private int detailScrollSteps = 28;

    /** 详情图下载目录；相对路径基于工作目录。 */
    private String imageSaveDir = "./data/jd-mall/images";
    /** 相邻两张图下载之间的基础停顿（另有随机加成），模拟真人浏览节奏。 */
    private int downloadDelayMillis = 150;

    private String debugOutputDir = "./data/jd-mall/debug";
}
