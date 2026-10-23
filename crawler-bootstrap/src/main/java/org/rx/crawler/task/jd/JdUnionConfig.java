package org.rx.crawler.task.jd;

import lombok.Data;
import org.rx.crawler.task.common.PromotionSlot;
import org.rx.crawler.task.common.PromotionSlots;
import org.rx.crawler.task.common.StepDelayConfig;

import java.util.List;

@Data
public class JdUnionConfig implements StepDelayConfig {
    private String profileName = "common";
    private boolean fingerprintEnabled = true;
    private boolean headless = false;

    private boolean preflightEnabled = true;
    private String preflightUrl = "https://bot.sannysoft.com/";
    private boolean preflightStrict = true;
    private int preflightCacheMinutes = 30;
    private boolean forcePreflight = false;

    private String overviewUrl = "https://union.jd.com/overview";
    private String entireUrl = "https://union.jd.com/entire";
    private String orderUrl = "https://union.jd.com/order";
    private String loginCheckUrl = "https://union.jd.com/overview";
    private String workbenchUrl = "https://union.jd.com/proManager/index?pageNo=1";
    private String loginUrlPrefix = "https://union.jd.com/index?returnUrl=";
    private String defaultMediaType = "导购媒体推广";
    private String defaultMediaName = "wx";
    private String defaultMediaId = "4101055439";
    private String defaultAdSiteName = "0";
    private List<PromotionSlot> adSites = PromotionSlots.jdDefaults();

    private int pageTimeoutSeconds = 60;
    private int initialPageTimeoutSeconds = 60;
    private int stepDelayMillis = 550;
    private int stepDelayRandomMillis = 300;
    private boolean keepBrowserOpenOnLoginRequired = true;
    private int keepBrowserOpenSecondsOnLoginRequired = 180;
    private int loginWaitSeconds = 180;
    private String debugOutputDir = "./data/jd-union/debug";
}
