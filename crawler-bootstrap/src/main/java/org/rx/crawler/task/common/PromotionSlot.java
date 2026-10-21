package org.rx.crawler.task.common;

import lombok.Data;

/**
 * 一个允许使用的推广位。name 是页面上的推广位名称，id 是转链接口要的推广位 ID。
 * 淘宝 id 是 mm_ 开头的 pid，京东 id 是推广位数字 ID。
 */
@Data
public class PromotionSlot {
    private String name = "";
    private String id = "";

    public PromotionSlot() {
    }

    public PromotionSlot(String name, String id) {
        this.name = name;
        this.id = id;
    }
}
