package org.rx.crawler.task.tb;

import lombok.Data;
import org.rx.crawler.task.common.PromotionSlot;
import org.rx.crawler.task.common.PromotionSlots;
import org.rx.crawler.task.common.StepDelayConfig;

import java.util.List;

@Data
public class TbPromotionConfig implements StepDelayConfig {
    private String profileName = "common";
    private boolean fingerprintEnabled = true;
    private boolean headless = false;

    private boolean preflightEnabled = true;
    private String preflightUrl = "https://bot.sannysoft.com/";
    private boolean preflightStrict = true;
    private int preflightCacheMinutes = 30;
    private boolean forcePreflight = false;

    private String homeUrl = "https://pub.alimama.com/portal/v2/home/plus/index.htm";
    private String promotionGoodsUrl = "https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm";
    private String analysisUrl = "https://pub.alimama.com/portal/v2/union-lab.htm?pageKey=analysic";
    private String orderUrl = "https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm";
    private String loginUrlPrefix = "https://login.taobao.com/havanaone/login/login.htm";
    private String defaultMediaName = "RockyLOMO";
    private String defaultAdSiteName = "0";
    private List<PromotionSlot> adSites = PromotionSlots.taobaoDefaults();

    private int pageTimeoutSeconds = 60;
    private int initialPageTimeoutSeconds = 180;
    private int stepDelayMillis = 700;
    private int stepDelayRandomMillis = 400;
    private boolean keepBrowserOpenOnLoginRequired = true;
    private int keepBrowserOpenSecondsOnLoginRequired = 180;
    private int loginWaitSeconds = 180;
    private String debugOutputDir = "./data/tb-union/debug";
}
