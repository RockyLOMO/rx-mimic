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
    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private String outputPath;
    private Boolean debugEnabled;
    private String debugOutputDir;
}
