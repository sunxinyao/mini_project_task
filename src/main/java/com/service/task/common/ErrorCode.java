package com.service.task.common;

import lombok.Getter;

/**
 * 业务错误码：所有 HTTP 接口统一返回 HTTP 200，业务结果由包装类 ApiResponse 中的 code/msg 表达。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "success"),
    /** 领取任务时队列中没有可领取任务 */
    NO_TASK(204, "No task"),
    BAD_REQUEST(400, "Bad request"),
    NOT_FOUND(404, "Task not found"),
    /** 相同幂等键 + 不同请求内容 */
    IDEMPOTENCY_CONFLICT(409, "Idempotency-Key has already been used with a different request body"),
    /** claim_token 不匹配、worker 不匹配或任务不在 RUNNING 状态 */
    CLAIM_CONFLICT(409, "Claim token does not match or task is not RUNNING"),
    INTERNAL_ERROR(500, "Internal server error");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
