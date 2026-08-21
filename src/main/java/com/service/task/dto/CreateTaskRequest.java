package com.service.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /tasks 请求体。
 * JSON 字段为 snake_case：{ "type": ..., "payload": {...}, "max_attempts": n }
 */
@Data
public class CreateTaskRequest {

    @NotBlank(message = "must not be blank")
    private String type;

    private Map<String, Object> payload = new LinkedHashMap<>();

    @Min(value = 1, message = "must be >= 1")
    @Max(value = 100, message = "must be <= 100")
    private Integer maxAttempts = 3;
}
