package com.service.task.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 错误详情：fail 接口的入参 error，也是任务查询返回的 last_error。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorDetail {

    @NotBlank(message = "must not be blank")
    private String code;

    private String message;

    public static ErrorDetail of(String code, String message) {
        return new ErrorDetail(code, message);
    }
}
