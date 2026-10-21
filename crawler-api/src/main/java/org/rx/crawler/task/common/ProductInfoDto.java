package org.rx.crawler.task.common;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class ProductInfoDto implements Serializable {
    private static final long serialVersionUID = -8128369459494691492L;

    @NotBlank
    private String productName;
    @NotBlank
    private String productLink;
    @NotBlank
    private String commissionRate;
    /** 部分商品卡片不展示店铺名，不强制非空。 */
    private String storeName;
    private String price;
    /** 实际命中的价格字段。券后价不再额外减一次券额。 */
    private String priceField;
    private String imageUrl;
}
