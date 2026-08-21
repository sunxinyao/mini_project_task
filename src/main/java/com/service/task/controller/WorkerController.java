package com.service.task.controller;

import com.service.task.common.ApiResponse;
import com.service.task.common.ErrorCode;
import com.service.task.dto.TaskResponse;
import com.service.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/workers/{workerId}/tasks")
@RequiredArgsConstructor
public class WorkerController {

    private final TaskService taskService;

    /**
     * 3.2 Worker 抢任务：先竞争 AQS 锁，抢到锁的 Worker 才能从队列 poll 一个任务并置为 RUNNING。
     * 没有可领取任务时返回 code=204, msg="No task"。
     */
    @PostMapping("/claim")
    public ApiResponse<TaskResponse> claim(@PathVariable("workerId") String workerId) {
        TaskResponse claimed = taskService.claimTask(workerId);
        if (claimed == null) {
            return ApiResponse.of(ErrorCode.NO_TASK);
        }
        return ApiResponse.ok(claimed);
    }
}
