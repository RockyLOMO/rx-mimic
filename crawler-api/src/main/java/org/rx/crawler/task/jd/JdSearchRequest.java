package org.rx.crawler.task.jd;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
public class JdSearchRequest implements Serializable {
    private static final long serialVersionUID = 5127301958047250010L;

    @NotBlank
    private String keyword;
    /** 期望最多抓多少条。默认 60，上限 100。超过一页时按每页条数自动翻页。 */
    private Integer maxResults;

    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private Boolean debugEnabled;
    private String debugOutputDir;
}
