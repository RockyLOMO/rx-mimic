package org.rx.crawler.task.common;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.io.Serializable;

@Data
public class PromotionUrlRequest implements Serializable {
    private static final long serialVersionUID = 7661573515710373020L;

    @NotBlank
    private String keyword;
    @NotBlank
    @Pattern(regexp = "[0-3]", message = "adSiteName must be 0, 1, 2, or 3")
    private String adSiteName;

    private String mediaName;
    /** 是否使用缓存结果，默认 true。若为 false 则跳过已有缓存直接抓取。 */
    private Boolean useCache = true;
    /** 仅京东单条转链生效：优先在入口页尝试 AJAX，减少额外噪点和节奏停顿。 */
    private Boolean fast = false;
    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private Boolean debugEnabled;
    private String debugOutputDir;
}
