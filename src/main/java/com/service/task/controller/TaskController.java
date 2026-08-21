package com.service.task.controller;

import com.service.task.common.ApiResponse;
import com.service.task.dto.CompleteTaskRequest;
import com.service.task.dto.CreateTaskRequest;
import com.service.task.dto.FailTaskRequest;
import com.service.task.dto.TaskResponse;
import com.service.task.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    /**
     * 3.1 发布任务（支持幂等提交）。
     */
    @PostMapping
    public ApiResponse<TaskResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                            @Valid @RequestBody CreateTaskRequest request) {
        return ApiResponse.ok(taskService.createTask(request, idempotencyKey));
    }

    /**
     * 3.5 查询任务。
     */
    @GetMapping("/{taskId}")
    public ApiResponse<TaskResponse> get(@PathVariable("taskId") String taskId) {
        return ApiResponse.ok(taskService.getTask(taskId));
    }

    /**
     * 3.3 报告任务成功：携带 claim_token 条件更新为 SUCCEEDED。
     */
    @PostMapping("/{taskId}/complete")
    public ApiResponse<TaskResponse> complete(@PathVariable("taskId") String taskId,
                                              @Valid @RequestBody CompleteTaskRequest request) {
        return ApiResponse.ok(taskService.completeTask(taskId, request));
    }

    /**
     * 3.4 报告任务失败：未达上限重回 QUEUED 等待重试，达上限转为 FAILED。
     */
    @PostMapping("/{taskId}/fail")
    public ApiResponse<TaskResponse> fail(@PathVariable("taskId") String taskId,
                                          @Valid @RequestBody FailTaskRequest request) {
        return ApiResponse.ok(taskService.failTask(taskId, request));
    }
}
