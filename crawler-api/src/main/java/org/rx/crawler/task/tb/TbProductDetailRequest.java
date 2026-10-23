package org.rx.crawler.task.tb;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import jakarta.validation.constraints.AssertTrue;
import java.io.Serializable;

/**
 * 淘宝/天猫商品详情抓取请求。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TbProductDetailRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 商品 itemId（纯数字）。 */
    private String itemId;
    /** 商品详情链接（如 item.taobao.com/item.htm?id=xxx 或 detail.tmall.com/item.htm?id=xxx）。 */
    private String productUrl;

    /** 是否把主图/详情图下载到本地，默认 false。 */
    private Boolean downloadImages;
    /** 是否抓取规格参数，默认 true。 */
    private Boolean includeSpecs;
    /** 是否使用缓存结果，默认 true。若为 false 则跳过已有缓存直接抓取。 */
    private Boolean useCache = true;

    /** Chrome 配置文件名，默认 common。 */
    private String profileName;
    /** 是否强制执行 Sannysoft 指纹预检。 */
    private Boolean forcePreflight;
    /** 遇到登录时是否保留浏览器供人工扫码接管。 */
    private Boolean keepBrowserOpenOnLoginRequired;
    /** 遇到登录时等待人工完成的秒数。 */
    private Integer loginWaitSeconds;

    /** 结果追加写入的 JSONL 文件路径。 */
    /** 是否保存关键步骤 HTML 快照。 */
    private Boolean debugEnabled;
    /** 调试快照根目录。 */
    private String debugOutputDir;

    @JsonIgnore
    @AssertTrue(message = "itemId or productUrl is required")
    public boolean isItemIdOrUrlPresent() {
        return (itemId != null && !itemId.trim().isEmpty())
                || (productUrl != null && !productUrl.trim().isEmpty());
    }
}
