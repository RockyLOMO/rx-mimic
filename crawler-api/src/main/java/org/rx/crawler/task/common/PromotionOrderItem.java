package org.rx.crawler.task.common;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;
import java.util.LinkedHashMap;

@Data
public class PromotionOrderItem implements Serializable {
    private static final long serialVersionUID = -5338651386297968923L;

    /**
     * 商品名称。
     */
    @NotBlank
    private String productName;
    /**
     * 商品超链接；页面 DOM 未提供时为空。
     */
    private String productLink;
    /**
     * 商品价格。
     */
    private String productPrice;
    /**
     * 店铺名。
     */
    @NotBlank
    private String storeName;
    /**
     * 订单号。
     */
    @NotBlank
    private String orderNo;
    /**
     * 主单号。
     */
    private String mainOrderNo;
    /**
     * 订单状态。
     */
    @NotBlank
    private String orderStatus;
    /** 统一状态；原平台状态保留在 orderStatus 及 extensions.rawOrderStatus。 */
    private String normalizedStatus;
    /**
     * 时间原始文本，包含下单、完成、结算等时间信息。
     */
    private String time;
    /**
     * 下单时间。
     */
    private String orderTime;
    /**
     * 完成时间。
     */
    private String finishTime;
    /** 付款时间，与下单和签收时间分别记录。 */
    private String paidTime;
    /**
     * 结算时间。
     */
    private String settleTime;
    /**
     * 预估计佣金额。
     */
    private String estimatedBillingAmount;
    /**
     * 预估佣金。
     */
    private String estimatedCommission;
    /**
     * 佣金比例。
     */
    private String commissionRate;
    /**
     * 分成比例。
     */
    private String shareRate;
    /**
     * 实际计佣金额。
     */
    private String actualBillingAmount;
    /**
     * 实际佣金。
     */
    private String actualCommission;
    /**
     * 数量原始文本，包含商品数量、售后数量、退货数量。
     */
    private String quantity;
    /**
     * 商品数量。
     */
    private String productQuantity;
    /**
     * 售后数量。
     */
    private String afterSaleQuantity;
    /**
     * 退货数量。
     */
    private String returnQuantity;
    /**
     * 推广信息原始文本。
     */
    private String promotionInfo;
    /**
     * 推广位。
     */
    @NotBlank
    private String promotionPosition;
    /**
     * SKU / 商品标识。
     */
    private String skuId;
    /**
     * 原生明细 ID / 稳定行标识。
     */
    private String sourceLineId;
    /**
     * 归属数据源。
     */
    private String sourceId;
    /**
     * 原始行 JSON。
     */
    private String rawJson;
    /** 平台扩展；旧平台专属字段仍兼容读取。 */
    private Map<String, Object> extensions = new LinkedHashMap<>();
}
