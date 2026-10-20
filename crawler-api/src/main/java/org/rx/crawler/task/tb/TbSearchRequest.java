package org.rx.crawler.task.tb;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 淘宝主站（s.taobao.com）按关键字搜索商品。
 */
@Data
public class TbSearchRequest implements Serializable {
    private static final long serialVersionUID = 6127301958047250010L;

    @NotBlank
    private String keyword;
    /** 期望最多抓多少条。默认 60，上限 100。超过一页时按每页条数自动翻页。 */
    private Integer maxResults;

    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private String outputPath;
    private Boolean debugEnabled;
    private String debugOutputDir;
}
