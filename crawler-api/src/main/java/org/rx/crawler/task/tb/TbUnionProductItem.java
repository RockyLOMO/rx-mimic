package org.rx.crawler.task.tb;

import lombok.Data;

import java.io.Serializable;

@Data
public class TbUnionProductItem implements Serializable {
    private static final long serialVersionUID = 6127301958047250021L;

    /** 联盟加密商品 ID（outputMktId），可直接作为 getTbPromotionUrl 的 keyword */
    private String itemId;
    private String productName;
    /** 联盟商品详情页链接 */
    private String productLink;
    /** 到手价 */
    private String price;
    private String commissionRate;
    private String storeName;
    private String imageUrl;
    /** 优惠券面额，无券为空 */
    private String couponAmount;
    /** 月推广销量 */
    private String monthSales;
}
