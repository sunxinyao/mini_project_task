package com.service.task.entity;

/**
 * 任务状态机：
 * QUEUED ->（领取）-> RUNNING ->（成功）-> SUCCEEDED
 *                        |->（失败且未达上限）-> QUEUED
 *                        |->（失败且已达上限）-> FAILED
 *                        |->（租约过期）-> QUEUED（若已达上限则 FAILED）
 */
public enum TaskStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
