package org.rx.crawler.task.aqc;

import lombok.Data;

import java.io.Serializable;

/**
 * 变更记录条目：变更时间/项目/前后值。
 */
@Data
public class AqcChangeItem implements Serializable {
    private static final long serialVersionUID = 9107300000000000007L;

    private String date;
    /** 变更项目，如 注册资本、经营范围、高级管理人员备案。 */
    private String item;
    private String beforeValue;
    private String afterValue;
}
