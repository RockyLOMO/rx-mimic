package org.rx.crawler.task.aqc;

import lombok.Data;

import java.io.Serializable;

/**
 * 股东信息条目：股东名称、持股比例、出资额等（按页面表格列原样保留）。
 */
@Data
public class AqcShareholderItem implements Serializable {
    private static final long serialVersionUID = 9107300000000000005L;

    private String name;
    /** 持股比例，如 60%。 */
    private String ratio;
    /** 认缴出资额。 */
    private String subscribedAmount;
    /** 实缴出资额。 */
    private String paidAmount;
    /** 其余列原样拼接（股东类型/最终受益股份等）。 */
    private String extra;
}
