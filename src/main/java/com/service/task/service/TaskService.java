package com.service.task.service;

import com.service.task.dto.CompleteTaskRequest;
import com.service.task.dto.CreateTaskRequest;
import com.service.task.dto.FailTaskRequest;
import com.service.task.dto.TaskResponse;

public interface TaskService {

    /** 幂等创建任务，返回 QUEUED 状态的任务视图 */
    TaskResponse createTask(CreateTaskRequest request, String idempotencyKey);

    /** 查询任务，不存在抛 NOT_FOUND */
    TaskResponse getTask(String taskId);

    /** 成功上报：claim_token 条件更新为 SUCCEEDED */
    TaskResponse completeTask(String taskId, CompleteTaskRequest request);

    /** 失败上报：未达上限重回 QUEUED，达上限 FAILED */
    TaskResponse failTask(String taskId, FailTaskRequest request);

    /** Worker 抢任务：没有可领取任务时返回 null */
    TaskResponse claimTask(String workerId);

    /** 回收租约过期的 RUNNING 任务，返回回收条数 */
    int reclaimExpiredLeases();
}
