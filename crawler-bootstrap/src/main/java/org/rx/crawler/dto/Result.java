package org.rx.crawler.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class Result<T> implements Serializable {
    private static final long serialVersionUID = 8046825036431634477L;

    private boolean success;
    private String code;
    private String message;
    /** Increments by one each time a GitHub Action publish assigns a new release. */
    private int release;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<T>();
        result.setSuccess(true);
        result.setCode("SUCCESS");
        result.setMessage("");
        result.setRelease(RuntimeRelease.current());
        result.setData(data);
        return result;
    }

    public static <T> Result<T> fail(String code, String message, T data) {
        Result<T> result = new Result<T>();
        result.setSuccess(false);
        result.setCode(code);
        result.setMessage(message);
        result.setRelease(RuntimeRelease.current());
        result.setData(data);
        return result;
    }
}
