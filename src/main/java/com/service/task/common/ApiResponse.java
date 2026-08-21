package com.service.task.common;

import lombok.Getter;

/**
 * 统一响应包装类：{ code, msg, data }。
 * code = 0 表示成功，其余为业务错误码（见 {@link ErrorCode}）。
 */
@Getter
public class ApiResponse<T> {

    private final int code;
    private final String msg;
    private final T data;

    private ApiResponse(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getDefaultMessage(), data);
    }

    public static <T> ApiResponse<T> of(ErrorCode errorCode) {
        return of(errorCode, errorCode.getDefaultMessage());
    }

    public static <T> ApiResponse<T> of(ErrorCode errorCode, String msg) {
        return new ApiResponse<>(errorCode.getCode(), msg, null);
    }
}
