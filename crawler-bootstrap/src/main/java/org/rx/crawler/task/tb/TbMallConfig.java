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
    private int stepDelayMillis = 700;
    private int stepDelayRandomMillis = 400;
    private int navigationPauseMillis = 1500;
    private int navigationPauseRandomMillis = 2500;
    private boolean keepBrowserOpenOnLoginRequired = true;
    private int keepBrowserOpenSecondsOnLoginRequired = 180;
    private int loginWaitSeconds = 180;

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

    private String defaultOutputPath = "./data/tb-mall/output.jsonl";
    private String debugOutputDir = "./data/tb-mall/debug";
}
