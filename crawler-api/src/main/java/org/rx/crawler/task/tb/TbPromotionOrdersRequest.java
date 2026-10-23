package org.rx.crawler.task.tb;

import lombok.Data;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Data
public class TbPromotionOrdersRequest implements Serializable {
    private static final long serialVersionUID = 7219276468255370237L;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @NotBlank
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startTime must be yyyy-MM-dd")
    private String startTime;
    @NotBlank
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endTime must be yyyy-MM-dd")
    private String endTime;

    /**
     * Chrome profile 名称；不传时使用 common，方便复用人工登录态。
     */
    private String profileName;
    private String sourceId;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private Boolean debugEnabled;
    private String debugOutputDir;

    @AssertTrue(message = "startTime must be less than or equal to endTime")
    public boolean isTimeRangeValid() {
        if (startTime == null || endTime == null) {
            return true;
        }
        try {
            return !LocalDate.parse(startTime, DATE_FORMATTER).isAfter(LocalDate.parse(endTime, DATE_FORMATTER));
        } catch (Exception e) {
            return true;
        }
    }
}
