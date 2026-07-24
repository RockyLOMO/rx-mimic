package org.rx.crawler.task.common;

import lombok.Data;

import java.util.function.Predicate;

@Data
public class CrawlEntryOptions implements StepDelayConfig {
    private String taskType;
    private String profileName = "common";
    private boolean preflightEnabled = true;
    private String preflightUrl = "https://bot.sannysoft.com/";
    private boolean preflightStrict = true;
    private int preflightCacheMinutes = 30;
    private boolean forcePreflight = true;
    private String initialUrl;
    private String loginUrlPrefix;
    private int initialPageTimeoutSeconds = 60;
    private int loginWaitSeconds = 180;
    private int stepDelayMillis = 1200;
    private int stepDelayRandomMillis = 600;
    private boolean keepBrowserOpenOnLoginRequired = true;
    private int keepBrowserOpenSecondsOnLoginRequired = 180;
    private Predicate<String> loginRequiredUrlMatcher;
    /** 页面正文判定需要登录（URL 仍是 overview/promanager 但内容是登录框时用）。 */
    private Predicate<String> loginRequiredBodyMatcher;
    private Predicate<String> loggedInUrlMatcher;
    private Predicate<String> loggedInBodyMatcher;
}
