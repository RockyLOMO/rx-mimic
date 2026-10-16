package org.rx.crawler.task.jd;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
public class JdSearchRequest implements Serializable {
    private static final long serialVersionUID = 5127301958047250010L;

    @NotBlank
    private String keyword;
    /** 单次返回最大条数，默认 60，上限约 100（搜索页单页懒加载上限）。 */
    private Integer maxResults;

    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private String outputPath;
    private Boolean debugEnabled;
    private String debugOutputDir;
}
