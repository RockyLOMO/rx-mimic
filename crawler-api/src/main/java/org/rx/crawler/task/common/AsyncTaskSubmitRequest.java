package org.rx.crawler.task.common;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.io.Serializable;
@Data
public class AsyncTaskSubmitRequest implements Serializable {
    @NotBlank private String action;
    @NotNull private Object request;
}
