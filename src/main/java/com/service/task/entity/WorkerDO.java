package com.service.task.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作人员表实体：Worker 首次调用领取接口时自动注册。
 */
@Data
public class WorkerDO {

    private Long id;

    private String workerId;

    private LocalDateTime createdAt;

    private LocalDateTime lastSeenAt;

    private LocalDateTime lastClaimAt;

    private int claimCount;
}
