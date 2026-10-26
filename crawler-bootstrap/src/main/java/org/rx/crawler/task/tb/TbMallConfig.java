package org.rx.crawler.task.tb;

import lombok.Data;
import org.rx.crawler.task.common.StepDelayConfig;

/**
 * 淘宝/天猫商城商品详情抓取配置。
 */
@Data
public class TbMallConfig implements StepDelayConfig {
    private String profileName = "common";
    private boolean fingerprintEnabled = true;
    private boolean headless = false;

    private boolean preflightEnabled = true;
    private String preflightUrl = "https://bot.sannysoft.com/";
    private boolean preflightStrict = true;
    private int preflightCacheMinutes = 30;
    private boolean forcePreflight = false;

    private String homeUrl = "https://www.taobao.com";
    private String itemUrlTemplate = "https://item.taobao.com/item.htm?id={itemId}";
    private String searchUrlTemplate = "https://s.taobao.com/search?q={keyword}";
    private String loginUrlPrefix = "https://login.taobao.com";

    private int pageTimeoutSeconds = 60;
    private int initialPageTimeoutSeconds = 60;
    private int stepDelayMillis = 1500;
    private int stepDelayRandomMillis = 1000;
    private int navigationPauseMillis = 3000;
    private int navigationPauseRandomMillis = 3000;
    /** 读完当前页后的额外翻页间隔。 */
    private int searchPagePauseMillis = 6000;
    private int searchPagePauseRandomMillis = 4000;
    private boolean keepBrowserOpenOnLoginRequired = true;
    private int keepBrowserOpenSecondsOnLoginRequired = 180;
    private int loginWaitSeconds = 180;

    /** 搜索默认返回条数。超过单页时自动翻页。 */
    private int defaultMaxResults = 60;
    /** 搜索最多返回条数。 */
    private int maxResultsLimit = 100;
    /** 自动翻页最多打开几页。 */
    private int maxSearchPages = 8;
    /** 每页逐屏滚动的上限，避免懒加载未完成便返回半页。 */
    private int maxSearchScrollRounds = 60;

    /** 滑块验证最大自动重试次数。 */
    private int sliderMaxAutoAttempts = 3;
    /** 详情长图收集上限。 */
    private int maxDetailImages = 120;
    /** 详情页拟人滚动步数（触发懒加载）。 */
    private int detailScrollSteps = 24;

    /** 详情图下载目录；相对路径基于工作目录。 */
    private String imageSaveDir = "./data/tb-mall/images";
    /** 相邻两张图下载之间的停顿（毫秒）。 */
    private int downloadDelayMillis = 150;

    private String debugOutputDir = "./data/tb-mall/debug";
}
