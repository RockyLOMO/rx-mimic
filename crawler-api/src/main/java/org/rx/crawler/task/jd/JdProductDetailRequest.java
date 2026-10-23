package org.rx.crawler.task.jd;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import jakarta.validation.constraints.AssertTrue;
import java.io.Serializable;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JdProductDetailRequest implements Serializable {
    private static final long serialVersionUID = 5127301958047250013L;

    private String skuId;
    private String productUrl;
    /** 是否把主图/详情图下载到本地，默认 true。 */
    private Boolean downloadImages;
    /** 是否抓取规格参数，默认 true。 */
    private Boolean includeSpecs;

    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private Boolean debugEnabled;
    private String debugOutputDir;

    @JsonIgnore
    @AssertTrue(message = "skuId or productUrl is required")
    public boolean isSkuOrUrlPresent() {
        return (skuId != null && !skuId.trim().isEmpty())
                || (productUrl != null && !productUrl.trim().isEmpty());
    }
}
