package com.service.task.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务表实体。payload / result / lastError 为 JSON 字符串；
 * 时间字段统一存 UTC 的 LocalDateTime（DATETIME(3)）。
 */
@Data
public class TaskDO {

    private Long id;

    private String taskId;

    private String type;

    private String payload;

    private TaskStatus status;

    private int attemptCount;

    private int maxAttempts;

    private String claimedBy;

    private String claimToken;

    private LocalDateTime leaseExpiresAt;

    private String lastError;

    private String result;

    private String idempotencyKey;

    private String requestHash;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
