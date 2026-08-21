package com.service.task.service;

import com.service.task.common.BusinessException;
import com.service.task.common.ErrorCode;
import com.service.task.dto.ErrorDetail;
import com.service.task.entity.TaskDO;
import com.service.task.entity.TaskStatus;
import com.service.task.mapper.TaskMapper;
import com.service.task.util.JsonCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 任务状态变更的事务脚本：
 * 领取（claim）、失败上报（fail）、租约回收（reclaim）都需要
 * “SELECT ... FOR UPDATE + 条件 UPDATE” 的组合，统一放在事务内执行。
 *
 * <p>claimNext 在 AQS 锁的保护下被调用（单 JVM 内串行）；
 * 条件更新（WHERE status = 'QUEUED'）在多实例部署时兜底防重复领取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskClaimer {

    /**
     * 默认过期失败的错误日志
     */
    private static final String LEASE_EXPIRED_ERROR_JSON =
            "{\"code\":\"LEASE_EXPIRED\",\"message\":\"lease expired before result was reported\"}";

    private final TaskMapper taskMapper;
    private final JsonCodec jsonCodec;

    /**
     * 从队列 poll 一个任务并置为 RUNNING；没有可领取任务返回 null。
     * 顺带惰性回收租约已过期的任务，使其“立即”重新可被领取。
     */
    @Transactional
    public TaskDO claimNext(String workerId, String claimToken, LocalDateTime leaseExpiresAt, LocalDateTime now) {
        reclaimExpired(now);

        TaskDO head = taskMapper.selectFirstQueuedForUpdate();
        if (head == null) {
            return null;
        }
        int updated = taskMapper.updateToRunning(head.getId(), head.getAttemptCount() + 1,
                workerId, claimToken, leaseExpiresAt, now);
        if (updated != 1) {
            // AQS 锁 + 行锁 + 条件更新三重保护下仍失败，视为不可恢复的并发异常
            throw new IllegalStateException("claim lost race on task id=" + head.getId());
        }
        return taskMapper.selectByTaskId(head.getTaskId());
    }

    /**
     * 失败上报：校验 claim_token 后，未达上限重回 QUEUED（等待重试），达上限转 FAILED。
     */
    @Transactional
    public TaskDO fail(String taskId, String workerId, String claimToken, ErrorDetail error, LocalDateTime now) {
        TaskDO task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Task not found: " + taskId);
        }
        assertValidClaim(task, workerId, claimToken);

        TaskStatus next = task.getAttemptCount() >= task.getMaxAttempts() ? TaskStatus.FAILED : TaskStatus.QUEUED;
        String errorJson = jsonCodec.toJson(error);
        int updated = taskMapper.updateOnFailure(task.getId(), next, claimToken, errorJson, now);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CLAIM_CONFLICT);
        }
        return taskMapper.selectByTaskId(taskId);
    }

    /**
     * 回收租约过期的任务：还有次数则重回 QUEUED（清空领取凭证），已达上限则 FAILED。
     */
    @Transactional
    public int reclaimExpired(LocalDateTime now) {
        int requeued = taskMapper.requeueLeaseExpired(now, LEASE_EXPIRED_ERROR_JSON);
        int failed = taskMapper.failLeaseExpired(now, LEASE_EXPIRED_ERROR_JSON);
        int total = requeued + failed;
        if (total > 0) {
            log.info("lease reclaim: {} task(s) re-queued, {} task(s) marked FAILED", requeued, failed);
        }
        return total;
    }

    private void assertValidClaim(TaskDO task, String workerId, String claimToken) {
        boolean tokenMatch = task.getClaimToken() != null && task.getClaimToken().equals(claimToken);
        boolean workerMatch = task.getClaimedBy() != null && task.getClaimedBy().equals(workerId);
        if (task.getStatus() != TaskStatus.RUNNING || !tokenMatch || !workerMatch) {
            throw new BusinessException(ErrorCode.CLAIM_CONFLICT,
                    "Claim token does not match or task is not RUNNING (current status=" + task.getStatus() + ")");
        }
    }
}
