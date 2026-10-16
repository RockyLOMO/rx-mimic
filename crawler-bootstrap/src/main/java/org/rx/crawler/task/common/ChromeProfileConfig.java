package org.rx.crawler.task.common;

import lombok.Data;

@Data
public class ChromeProfileConfig {
    /**
     * 空 = 用户目录 %LOCALAPPDATA%/rx-mimic/chrome。
     * 只有显式写成绝对路径时才改存放位置。
     */
    private String profileBasePath = "";
    private String defaultProfileName = "common";
    private boolean closeBrowserAfterTask = false;
    /** 同一个 profileName 只有一个 Chrome 用户目录，默认只开一个实例。 */
    private int maxActive = 1;
    private int minIdle = 1;
    private boolean preWarm = true;
}
