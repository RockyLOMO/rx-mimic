package org.rx.crawler.task.common;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.io.Serializable;
@Data
public class AsyncTaskSubmitRequest implements Serializable {
    @NotBlank private String action;
    @NotNull private Object request;
    /** 是否使用缓存结果，默认 true。若传入且内部 request 未显式指定，则透传生效。 */
    private Boolean useCache;
}
