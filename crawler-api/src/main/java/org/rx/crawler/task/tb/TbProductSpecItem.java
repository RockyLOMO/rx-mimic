package org.rx.crawler.task.tb;

import lombok.Data;

import java.io.Serializable;

/**
 * 淘宝/天猫商品规格参数项。
 */
@Data
public class TbProductSpecItem implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private String value;
}
