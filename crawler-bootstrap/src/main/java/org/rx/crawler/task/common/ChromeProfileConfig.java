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
    /**
     * 同一个 profileName 只有一个 Chrome 用户目录，服务里写死只开一个 Chrome。
     * 多账号要多个 profile，以后再做。
     * 多标签不是多任务并行：Playwright 的 Page 只能在创建它的线程上调用，
     * 系统鼠标拖拽也只有一个光标，操作必须串行。不要为了并行再开一个调试端口。
     */
    private int maxActive = 1;
    private int minIdle = 1;
    private boolean preWarm = true;
    /**
     * 同一个 Chrome 里最多保留几个标签。默认 2：当前任务一个，后台一个。
     * 后台标签只提前打开下一个排队任务的起始页，不点击、不拖滑块。
     * 1 表示不预开，行为和单标签一样。
     */
    private int tabSlots = 2;
}
