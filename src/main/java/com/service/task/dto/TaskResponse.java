package com.service.task.dto;

import com.service.task.entity.TaskDO;
import com.service.task.entity.TaskStatus;
import com.service.task.util.JsonCodec;
import com.service.task.util.UtcTimes;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * 任务视图：创建 / 查询 / 领取 / 成功 / 失败 接口共用的返回结构（snake_case 输出）。
 */
@Data
@Builder
public class TaskResponse {

    private String taskId;

    private String type;

    private Map<String, Object> payload;

    private TaskStatus status;

    private int attemptCount;

    private int maxAttempts;

    private String claimedBy;

    private String claimToken;

    private Instant leaseExpiresAt;

    private ErrorDetail lastError;

    private Map<String, Object> result;

    private Instant createdAt;

    private Instant updatedAt;

    public static TaskResponse from(TaskDO task, JsonCodec jsonCodec) {
        return TaskResponse.builder()
                .taskId(task.getTaskId())
                .type(task.getType())
                .payload(jsonCodec.toMap(task.getPayload()))
                .status(task.getStatus())
                .attemptCount(task.getAttemptCount())
                .maxAttempts(task.getMaxAttempts())
                .claimedBy(task.getClaimedBy())
                .claimToken(task.getClaimToken())
                .leaseExpiresAt(UtcTimes.toInstant(task.getLeaseExpiresAt()))
                .lastError(jsonCodec.fromJson(task.getLastError(), ErrorDetail.class))
                .result(jsonCodec.toMap(task.getResult()))
                .createdAt(UtcTimes.toInstant(task.getCreatedAt()))
                .updatedAt(UtcTimes.toInstant(task.getUpdatedAt()))
                .build();
    }
}
