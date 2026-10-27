package org.rx.crawler.task.jd;

import java.util.regex.Pattern;

/** 页面回退价格必须保留语义，不能把任意货币数字（例如佣金）当到手价。 */
final class JdPagePrice {
    private static final String AMOUNT = "\\s*[:：]?\\s*[￥¥]?\\s*([0-9][0-9,.]*(?:\\s*[-~～至]\\s*[￥¥]?\\s*[0-9,.]+)?起?)";
    private static final Pattern FINAL = Pattern.compile("(?:预估到手价|到手价|券后价|折后价)" + AMOUNT);
    private static final Pattern LIST = Pattern.compile("(?:京东价|售价)" + AMOUNT);
    record Price(String amount, String field) {}

    static Price read(String text, String unlabelled) {
        var match = FINAL.matcher(text == null ? "" : text);
        if (match.find()) return new Price(match.group(1).replaceAll("\\s+", ""), "finalPrice");
        match = LIST.matcher(text == null ? "" : text);
        if (match.find()) return new Price(match.group(1).replaceAll("\\s+", ""), "wlPrice");
        return new Price(unlabelled, "jdUnionDisplayedPrice"); // 可展示，未知语义仍不可参与降价判断。
    }
}
