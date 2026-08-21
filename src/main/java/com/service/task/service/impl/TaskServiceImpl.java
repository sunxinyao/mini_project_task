package com.service.task.service.impl;

import com.service.task.common.BusinessException;
import com.service.task.common.ErrorCode;
import com.service.task.concurrency.AqsMutex;
import com.service.task.dto.CompleteTaskRequest;
import com.service.task.dto.CreateTaskRequest;
import com.service.task.dto.FailTaskRequest;
import com.service.task.dto.TaskResponse;
import com.service.task.entity.TaskDO;
import com.service.task.entity.TaskStatus;
import com.service.task.mapper.TaskMapper;
import com.service.task.service.LeaseProperties;
import com.service.task.service.TaskClaimer;
import com.service.task.service.TaskService;
import com.service.task.service.WorkerService;
import com.service.task.util.JsonCodec;
import com.service.task.util.UtcTimes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    private static final String TASK_ID_PREFIX = "task-";
    /** AQS 锁等待上限：领取临界区极短，正常情况下必然在毫秒级获得锁 */
    private static final long CLAIM_LOCK_TIMEOUT_SECONDS = 5;

    private final TaskMapper taskMapper;
    private final TaskClaimer taskClaimer;
    private final WorkerService workerService;
    private final AqsMutex claimMutex;
    private final JsonCodec jsonCodec;
    private final LeaseProperties leaseProperties;

    @Override
    public TaskResponse createTask(CreateTaskRequest request, String idempotencyKey) {
        int maxAttempts = request.getMaxAttempts() == null ? 3 : request.getMaxAttempts();
        String requestHash = sha256Hex(request.getType() + "|" + maxAttempts + "|"
                + jsonCodec.canonicalize(request.getPayload()));

        // 幂等提交：相同 Idempotency-Key + 相同内容 => 返回原任务；不同内容 => 冲突
        TaskDO existing = taskMapper.selectByIdempotencyKey(idempotencyKey);
        log.info("idempotencyKey TaskDO={}, idempotencyKey={}", existing, idempotencyKey);
        if (existing != null) {
            log.info("idempotencyKey exist.");
            return replayOrConflict(existing, requestHash);
        }

        LocalDateTime now = UtcTimes.nowUtc();
        TaskDO task = new TaskDO();
        task.setTaskId(TASK_ID_PREFIX + UUID.randomUUID());
        task.setType(request.getType());
        task.setPayload(jsonCodec.toJson(JsonCodec.emptyMapIfNull(request.getPayload())));
        task.setStatus(TaskStatus.QUEUED);
        task.setAttemptCount(0);
        task.setMaxAttempts(maxAttempts);
        task.setIdempotencyKey(idempotencyKey);
        task.setRequestHash(requestHash);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发提交相同幂等键：唯一索引兜底，回读后按幂等/冲突处理
            TaskDO winner = taskMapper.selectByIdempotencyKey(idempotencyKey);
            if (winner == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "idempotency insert race failed");
            }
            return replayOrConflict(winner, requestHash);
        }
        return TaskResponse.from(task, jsonCodec);
    }

    private TaskResponse replayOrConflict(TaskDO existing, String requestHash) {
        if (existing.getRequestHash().equals(requestHash)) {
            return TaskResponse.from(existing, jsonCodec);
        }
        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Override
    public TaskResponse getTask(String taskId) {
        TaskDO task = taskMapper.selectByTaskId(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Task not found: " + taskId);
        }
        return TaskResponse.from(task, jsonCodec);
    }

    @Override
    public TaskResponse completeTask(String taskId, CompleteTaskRequest request) {
        LocalDateTime now = UtcTimes.nowUtc();
        int updated = taskMapper.updateToSucceeded(taskId, request.getClaimToken(),
                request.getWorkerId(), jsonCodec.toJson(request.getResult()), now);
        if (updated != 1) {
            // 条件更新未命中：区分任务不存在 / 领取凭证失效
            TaskDO task = taskMapper.selectByTaskId(taskId);
            if (task == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Task not found: " + taskId);
            }
            throw new BusinessException(ErrorCode.CLAIM_CONFLICT,
                    "Claim token does not match or task is not RUNNING (current status=" + task.getStatus() + ")");
        }
        return getTask(taskId);
    }

    @Override
    public TaskResponse failTask(String taskId, FailTaskRequest request) {
        TaskDO task = taskClaimer.fail(taskId, request.getWorkerId(), request.getClaimToken(),
                request.getError(), UtcTimes.nowUtc());
        return TaskResponse.from(task, jsonCodec);
    }

    @Override
    public TaskResponse claimTask(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "invalid workerId");
        }
        workerService.registerAndTouch(workerId);

        String claimToken = UUID.randomUUID().toString();
        LocalDateTime now = UtcTimes.nowUtc();
        LocalDateTime leaseExpiresAt = now.plusSeconds(leaseProperties.getSeconds());

        boolean locked;
        try {
            // 多个 Worker 竞争 AQS 锁：抢到锁才能去队列 poll 任务
            locked = claimMutex.tryLock(CLAIM_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "claim interrupted");
        }
        if (!locked) {
            // 极端争用下超时：按“当前无可领取任务”处理
            return null;
        }
        try {
            TaskDO claimed = taskClaimer.claimNext(workerId, claimToken, leaseExpiresAt, now);
            if (claimed == null) {
                return null;
            }
            workerService.recordClaimSuccess(workerId);
            return TaskResponse.from(claimed, jsonCodec);
        } finally {
            claimMutex.unlock();
        }
    }

    @Override
    public int reclaimExpiredLeases() {
        return taskClaimer.reclaimExpired(UtcTimes.nowUtc());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
