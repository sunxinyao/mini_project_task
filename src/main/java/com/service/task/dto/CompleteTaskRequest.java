package com.service.task.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * POST /tasks/{taskId}/complete 请求体：
 * { "worker_id": ..., "claim_token": ..., "result": {...} }
 */
@Data
public class CompleteTaskRequest {

    @NotBlank(message = "must not be blank")
    private String workerId;

    @NotBlank(message = "must not be blank")
    private String claimToken;

    private Map<String, Object> result;
}
