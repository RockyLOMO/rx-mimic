package org.rx.crawler.task.jd;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 京东联盟推广管理页按关键字搜索可转链商品。
 */
@Data
public class JdUnionProductSearchRequest implements Serializable {
    private static final long serialVersionUID = 5127301958047250020L;

    @NotBlank
    private String keyword;
    /** 单次返回最大条数，默认 20。 */
    private Integer maxResults;

    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private String outputPath;
    private Boolean debugEnabled;
    private String debugOutputDir;
}
