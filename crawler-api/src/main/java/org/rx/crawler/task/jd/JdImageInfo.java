package org.rx.crawler.task.jd;

import lombok.Data;

import java.io.Serializable;

@Data
public class JdImageInfo implements Serializable {
    private static final long serialVersionUID = 5127301958047250014L;

    private String url;
    private String localPath;
    private String fileName;
}
