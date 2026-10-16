package org.rx.crawler.task.jd;

import lombok.Data;

import java.io.Serializable;

@Data
public class JdSearchItem implements Serializable {
    private static final long serialVersionUID = 5127301958047250011L;

    private String skuId;
    private String title;
    private String price;
    private String imageUrl;
    private String shopName;
    private String productUrl;
    /** 评论数（2026 搜索卡 span[title="xx万+条评价"]，原始文本） */
    private String commentCount;
    /** 好评度（"98%好评" 或 "400+人好评"，原始文本） */
    private String goodRate;
    /** 销量（"已售2万+"，原始文本） */
    private String soldCount;
}
