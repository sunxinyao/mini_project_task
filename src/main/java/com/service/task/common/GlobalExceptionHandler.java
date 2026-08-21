package com.service.task.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：所有异常统一转换为 ApiResponse 包装结构（HTTP 状态码保持 200，业务码在 body 中）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException e) {
        log.warn("business error: code={}, msg={}", e.getErrorCode().getCode(), e.getMessage());
        return ApiResponse.of(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ApiResponse<Void> handleBadRequest(Exception e) {
        String msg;
        if (e instanceof MethodArgumentNotValidException valid) {
            msg = valid.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                    .orElse("invalid request body");
        } else if (e instanceof MissingRequestHeaderException header) {
            msg = "Missing required header: " + header.getHeaderName();
        } else {
            msg = "Malformed request body";
        }
        log.warn("bad request: {}", msg);
        return ApiResponse.of(ErrorCode.BAD_REQUEST, msg);
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return ApiResponse.of(ErrorCode.INTERNAL_ERROR);
    }
}
