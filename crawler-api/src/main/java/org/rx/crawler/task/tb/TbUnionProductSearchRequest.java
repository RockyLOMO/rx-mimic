package org.rx.crawler.task.tb;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 淘宝联盟选品广场按关键字搜索可转链商品。
 */
@Data
public class TbUnionProductSearchRequest implements Serializable {
    private static final long serialVersionUID = 6127301958047250020L;

    @NotBlank
    private String keyword;
    /** 单次返回最大条数，默认 20，最多 30。 */
    private Integer maxResults;

    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private Boolean debugEnabled;
    private String debugOutputDir;
}
