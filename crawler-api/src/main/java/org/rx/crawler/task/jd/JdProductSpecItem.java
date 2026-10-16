package org.rx.crawler.task.jd;

import lombok.Data;

import java.io.Serializable;

@Data
public class JdProductSpecItem implements Serializable {
    private static final long serialVersionUID = 5127301958047250015L;

    private String name;
    private String value;
}
