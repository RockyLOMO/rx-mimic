package org.rx.crawler.task.jd;

import lombok.Data;

import java.io.Serializable;

/**
 * 联盟搜索卡片上的基础信息。详情图、规格不在这张卡片上。
 */
@Data
public class JdUnionProductItem implements Serializable {
    private static final long serialVersionUID = 5127301958047250021L;

    private String skuId;
    private String productName;
    private String productLink;
    private String price;
    private String commissionRate;
    private String storeName;
    private String imageUrl;
}
