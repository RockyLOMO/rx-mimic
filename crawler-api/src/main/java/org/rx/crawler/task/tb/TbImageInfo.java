package org.rx.crawler.task.tb;

import lombok.Data;

import java.io.Serializable;

/**
 * 淘宝/天猫商品详情抓取图片元数据。
 */
@Data
public class TbImageInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 原始图片 URL。 */
    private String url;
    /** 本地下载绝对路径（downloadImages=true 时填充）。 */
    private String localPath;
    /** 本地保存文件名，如 001.jpg。 */
    private String fileName;
}
