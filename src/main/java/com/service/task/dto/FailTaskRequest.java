package com.service.task.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * POST /tasks/{taskId}/fail 请求体：
 * { "worker_id": ..., "claim_token": ..., "error": { "code": ..., "message": ... } }
 */
@Data
public class FailTaskRequest {

    @NotBlank(message = "must not be blank")
    private String workerId;

    @NotBlank(message = "must not be blank")
    private String claimToken;

    @NotNull(message = "must not be null")
    @Valid
    private ErrorDetail error;
}
