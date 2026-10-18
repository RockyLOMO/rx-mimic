package org.rx.crawler.task.tb;

import lombok.Data;

import java.io.Serializable;

@Data
public class TbSearchItem implements Serializable {
    private static final long serialVersionUID = 6127301958047250011L;

    private String itemId;
    private String title;
    private String price;
    private String imageUrl;
    private String shopName;
    /** item.taobao.com / detail.tmall.com 商品链接 */
    private String productUrl;
    /** 销量原始文本，如 "1万+人付款" */
    private String soldCount;
    /** 发货地 */
    private String location;
}
