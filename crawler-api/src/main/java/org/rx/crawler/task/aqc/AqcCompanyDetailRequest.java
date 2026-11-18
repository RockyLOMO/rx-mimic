package org.rx.crawler.task.aqc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import jakarta.validation.constraints.AssertTrue;
import java.io.Serializable;

/**
 * 爱企查公司详情请求：pid 精确查，或按公司名称/统一社会信用代码自动匹配后查详情。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AqcCompanyDetailRequest implements Serializable {
    private static final long serialVersionUID = 9107300000000000003L;

    /** 爱企查公司 id（搜索结果里的 pid），详情页 company_detail_{pid}。 */
    private String pid;
    /** 公司名称或统一社会信用代码；无 pid 时先搜索自动匹配（精确同名优先）。 */
    private String keyword;

    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private Boolean debugEnabled;
    private String debugOutputDir;

    @JsonIgnore
    @AssertTrue(message = "pid or keyword is required")
    public boolean isPidOrKeywordPresent() {
        return (pid != null && !pid.trim().isEmpty())
                || (keyword != null && !keyword.trim().isEmpty());
    }
}
