package com.service.task.scheduler;

import com.service.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 3.6 租约过期巡检：Worker 领取任务后超过 lease_expires_at 仍未上报结果，
 * 任务立即重回 QUEUED（清空 claim_token），等待新 Worker 用新 token 领取。
 * 领取接口内也会惰性回收一次，保证过期任务“立即”可见。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaseExpireScheduler {

    private final TaskService taskService;

    @Scheduled(fixedDelayString = "${task.lease.check-interval-ms:2000}", initialDelayString = "1000")
    public void reclaimExpiredLeases() {
        try {
            taskService.reclaimExpiredLeases();
        } catch (Exception e) {
            log.error("lease reclaim job failed", e);
        }
    }
}
