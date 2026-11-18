package org.rx.crawler.task.aqc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 爱企查公司搜索请求：按公司名称或统一社会信用代码搜索候选公司列表。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AqcCompanySearchRequest implements Serializable {
    private static final long serialVersionUID = 9107300000000000002L;

    /** 搜索关键字：公司名称（全称或片段）或统一社会信用代码。 */
    @NotBlank
    private String keyword;
    /** 期望返回条数。默认 20，上限 50（任务内钳制）。 */
    private Integer maxResults;

    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private Boolean debugEnabled;
    private String debugOutputDir;

    @JsonIgnore
    @AssertTrue(message = "keyword is required")
    public boolean isKeywordPresent() {
        return keyword != null && !keyword.trim().isEmpty();
    }
}
