package org.rx.crawler.task.aqc;

import lombok.Data;

import java.io.Serializable;

/**
 * 风险类条目：经营异常/行政处罚/严重违法/动产抵押/股权出质/抽查检查等，按栏目分类。
 */
@Data
public class AqcRiskItem implements Serializable {
    private static final long serialVersionUID = 9107300000000000008L;

    /** 栏目名：经营异常/行政处罚/严重违法/动产抵押/股权出质/抽查检查等。 */
    private String category;
    private String date;
    /** 决定机关/列入机关等。 */
    private String authority;
    /** 其余关键列拼接（原因/文号/结果等）。 */
    private String detail;
}
