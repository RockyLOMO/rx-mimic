package org.rx.crawler.task.tb;

import lombok.Data;

import java.io.Serializable;

@Data
public class TbUnionProductItem implements Serializable {
    private static final long serialVersionUID = 6127301958047250021L;

    /** 对外统一的数字商品 ID；未完成解析的临时搜索卡片为空。 */
    private String productId;
    /** 仅临时搜索响应携带，选择商品时可提交给解析入口；不落库。 */
    private String encryptedProductId;
    private Boolean identityResolved;
    /** 平台适配器接收的原始 itemId；持久化边界只允许数字值。 */
    private String itemId;
    private String productName;
    /** 联盟商品详情页链接 */
    private String productLink;
    /** 到手价 */
    private String price;
    /** 实际命中的价格字段，例如 unionPromotionPrice、price。 */
    private String priceField;
    private String commissionRate;
    private String storeName;
    private String imageUrl;
    /** 优惠券面额，无券为空 */
    private String couponAmount;
    /** 月推广销量 */
    private String monthSales;
}
