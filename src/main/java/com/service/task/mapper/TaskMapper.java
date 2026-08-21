package com.service.task.mapper;

import com.service.task.entity.TaskDO;
import com.service.task.entity.TaskStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface TaskMapper {

    @Insert("""
            INSERT INTO `task` (`task_id`, `type`, `payload`, `status`, `attempt_count`, `max_attempts`,
                                `idempotency_key`, `request_hash`, `created_at`, `updated_at`)
            VALUES (#{taskId}, #{type}, #{payload}, #{status}, #{attemptCount}, #{maxAttempts},
                    #{idempotencyKey}, #{requestHash}, #{createdAt}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TaskDO task);

    @Select("SELECT * FROM `task` WHERE `task_id` = #{taskId}")
    TaskDO selectByTaskId(@Param("taskId") String taskId);

    @Select("SELECT * FROM `task` WHERE `task_id` = #{taskId} FOR UPDATE")
    TaskDO selectByTaskIdForUpdate(@Param("taskId") String taskId);

    @Select("SELECT * FROM `task` WHERE `idempotency_key` = #{idempotencyKey} LIMIT 1")
    TaskDO selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /**
     * FIFO 取队头任务：按自增 id 升序即提交顺序；行锁防止并发重复领取。
     */
    @Select("""
            SELECT * FROM `task`
            WHERE `status` = 'QUEUED' AND `attempt_count` < `max_attempts`
            ORDER BY `id` ASC
            LIMIT 1
            FOR UPDATE
            """)
    TaskDO selectFirstQueuedForUpdate();

    /**
     * 条件更新为 RUNNING：仅当仍是 QUEUED 时才允许领取成功（多实例部署下的兜底防重）。
     */
    @Update("""
            UPDATE `task`
            SET `status` = 'RUNNING', `attempt_count` = #{attemptCount}, `claimed_by` = #{claimedBy},
                `claim_token` = #{claimToken}, `lease_expires_at` = #{leaseExpiresAt}, `updated_at` = #{now}
            WHERE `id` = #{id} AND `status` = 'QUEUED'
            """)
    int updateToRunning(@Param("id") Long id,
                        @Param("attemptCount") int attemptCount,
                        @Param("claimedBy") String claimedBy,
                        @Param("claimToken") String claimToken,
                        @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                        @Param("now") LocalDateTime now);

    /**
     * 条件更新为 SUCCEEDED：claim_token、worker、RUNNING 状态全部匹配才生效。
     */
    @Update("""
            UPDATE `task`
            SET `status` = 'SUCCEEDED', `result` = #{result}, `updated_at` = #{now}
            WHERE `task_id` = #{taskId} AND `status` = 'RUNNING'
              AND `claim_token` = #{claimToken} AND `claimed_by` = #{workerId}
            """)
    int updateToSucceeded(@Param("taskId") String taskId,
                          @Param("claimToken") String claimToken,
                          @Param("workerId") String workerId,
                          @Param("result") String result,
                          @Param("now") LocalDateTime now);

    /**
     * 失败上报：未达最大次数则重回 QUEUED，已达上限则 FAILED；均清空领取凭证。
     */
    @Update("""
            UPDATE `task`
            SET `status` = #{newStatus}, `claim_token` = NULL, `lease_expires_at` = NULL,
                `last_error` = #{errorJson}, `updated_at` = #{now}
            WHERE `id` = #{id} AND `status` = 'RUNNING' AND `claim_token` = #{claimToken}
            """)
    int updateOnFailure(@Param("id") Long id,
                        @Param("newStatus") TaskStatus newStatus,
                        @Param("claimToken") String claimToken,
                        @Param("errorJson") String errorJson,
                        @Param("now") LocalDateTime now);

    /**
     * 租约过期且还有重试机会：立即重回 QUEUED，清空领取凭证，等待新 Worker 领取。
     */
    @Update("""
            UPDATE `task`
            SET `status` = 'QUEUED', `claimed_by` = NULL, `claim_token` = NULL, `lease_expires_at` = NULL,
                `last_error` = #{errorJson}, `updated_at` = #{now}
            WHERE `status` = 'RUNNING' AND `lease_expires_at` <= #{now} AND `attempt_count` < `max_attempts`
            """)
    int requeueLeaseExpired(@Param("now") LocalDateTime now, @Param("errorJson") String errorJson);

    /**
     * 租约过期且已达最大尝试次数：直接 FAILED，不再重试。
     */
    @Update("""
            UPDATE `task`
            SET `status` = 'FAILED', `claimed_by` = NULL, `claim_token` = NULL, `lease_expires_at` = NULL,
                `last_error` = #{errorJson}, `updated_at` = #{now}
            WHERE `status` = 'RUNNING' AND `lease_expires_at` <= #{now} AND `attempt_count` >= `max_attempts`
            """)
    int failLeaseExpired(@Param("now") LocalDateTime now, @Param("errorJson") String errorJson);
}
