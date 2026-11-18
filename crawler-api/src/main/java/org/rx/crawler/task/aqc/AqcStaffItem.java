package org.rx.crawler.task.aqc;

import lombok.Data;

import java.io.Serializable;

/**
 * 主要人员条目：姓名 + 职务。
 */
@Data
public class AqcStaffItem implements Serializable {
    private static final long serialVersionUID = 9107300000000000006L;

    private String name;
    private String position;
}
