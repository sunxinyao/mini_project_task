# Task Queue — 分布式任务队列服务

v1.1版本
最新增加[AqsTaskAndWorker.java] AQS锁类
用于新建task任务或者task执行失败，需要变更到QUEUED状态时 竞争锁
锁具体逻辑 task拿到锁时AQS的state +1，worker拿到锁时 AQS的state -1
理论可行（task任务在非终态，即QUEUED和RUNNING之间变换时均需要加锁）
备注：时间有限，业务逻辑未改，[AqsTest.java]已测通过！
未优化到位的点：目前时worker拿锁，再去判断task租约到期，调换下顺序，避免worker拿锁时state=0，返回false

v1.0版本
基于 Spring Boot 4.1.0 + MyBatis + MySQL 的任务队列系统，支持多 Worker 并发竞争领取任务、
幂等提交、失败自动重试、租约过期回收。

使用AI工具：智谱的ZCode
根据任务的pdf，个人总结的提示词见末尾
git地址：https://github.com/sunxinyao/mini_project_task.git


---

## 一、整体设计思路

### 1.1 场景概述

系统核心围绕两个实体运转：

- **Task（任务）**：由客户端通过 HTTP POST 创建，进入 FIFO 队列等待 Worker 领取执行。
- **Worker（工作人员）**：通过 HTTP POST 不断用AQS锁竞争领取队列中的任务，执行完毕后上报结果。

整个流程形成一个**生产者-消费者**模型：多个客户端生产任务，多个 Worker 消费任务。

### 1.2 任务状态机

```
                    ┌─────────── 创建 ───────────┐
                    │                           ▼
                    │                       QUEUED ◄──────── 失败重试
                    │                      (等待领取)          (attempt < max)
                    │                           │
                    │                      Worker 领取
                    │                           │
                    │                           ▼
                    │                       RUNNING
                    │                      (执行中)    ── 租约过期 ──► QUEUED（清空 claim_token）
                    │                      /        \
                    │                   成功         失败
                    │                    /             \
                    │                   ▼               ▼
                    │              SUCCEEDED          FAILED
                    │             (终止态)        (达到 max_attempts，终止态)
                    │
                    └── 租约过期且已达上限 ──► FAILED
```

| 状态 | 含义 | 是否终止态 |
|------|------|-----------|
| `QUEUED` | 等待领取或等待重试 | 否 |
| `RUNNING` | 已被某个 Worker 领取，等待结果上报 | 否 |
| `SUCCEEDED` | 执行成功 | 是 |
| `FAILED` | 达到最大尝试次数，不再重试 | 是 |

### 1.3 核心业务流程

1. **客户端发布任务**（`POST /tasks`）：任务入队，状态 `QUEUED`，支持幂等提交。
2. **Worker 竞争领取**（`POST /workers/{workerId}/tasks/claim`）：通过 AQS 锁串行化竞争，抢到锁的 Worker 从队头 poll 一个任务，状态变为 `RUNNING`，获得 `claim_token` 和 3 分钟租约。
3. **Worker 上报成功**（`POST /tasks/{taskId}/complete`）：携带 `claim_token` 校验后，状态变为 `SUCCEEDED`。
4. **Worker 上报失败**（`POST /tasks/{taskId}/fail`）：携带 `claim_token` 校验后，尝试次数 +1；未达上限重回 `QUEUED` 等待重试，达上限转为 `FAILED`。
5. **租约过期回收**：Worker 领取后超时未上报，定时任务（或下次领取时惰性检测）将任务重置为 `QUEUED`，清空领取凭证，允许新 Worker 用新 `claim_token` 重新领取。

---

## 二、关键技术点

### 2.1 AQS 互斥锁 —— Worker 竞争领取

> 对应代码：`concurrency/AqsMutex.java`

这是本项目的核心并发控制机制。当多个 Worker 同时调用领取接口时，自定义锁继承AQS竞争，需要保证同一时刻只有一个 Worker 能从队列中取任务。
AQS 锁 + 行锁 + 条件更新 保证并发竞争

**个人还有个想法待尝试: 新建task任务或者task执行失败，需要变更到QUEUED状态时，AQS的state +1，worker负责—1，类似将任务也加入锁竞争，形成类似可重入**


### 2.2 异常租约过期

> 对应代码：`service/TaskClaimer.java/reclaimExpired()`

因为每次worker抢到任务就开启一个定时器去管理租约的话，复杂难以管理，所以我只处理两个场景
1、单机定时器对任务中租约过期的任务进行回收
2、每次worker拿到锁之后，一个事务中完成以下步骤：先对任务中过期的租约进行回收，再poll新任务去执行

## 三、测试类说明

### 3.1 关键主要场景说明

成功场景：
    [TaskControllerTest.java] createTask_thenQuery_returnsQueuedTask() 
--->[TaskClaimControllerTest.java] claimTask_success_marksRunningWithTokenAndLease()
--->[TaskReportControllerTest.java] completeTask_marksSucceededAndStoresResult()

1个task任务 2个worker竞争场景：
[TaskClaimControllerTest.java] concurrentClaim_twoWorkers_exactlyOneSucceeds()

3次重试失败终止
[TaskReportControllerTest.java] failTask_afterMaxAttempts_marksFailedAndNeverReclaimed()

租约过期场景：
[TaskReportControllerTest.java] leaseExpiry_requeuesTask_andNewWorkerReclaimsWithNewToken()


## 四、AI coding 提示词

<div style="border:1px solid #999;padding:10px;border‑radius:4px;">

我需要你帮我完成java代码
代码约束：项目是springboot，持久化使用mysql，mybatis，其他中间件没有说明不要使用；
所有http请求返回结果使用包装类包装错误码和错误msg；
所有controller接口需要写测试类。

项目要满足以下条件：
1、场景实体：worker 工作人员、task 任务
2、task任务状态：
QUEUED ：等待领取或等待重试；
RUNNING ：已被某个 Worker 领取；
SUCCEEDED ：执行成功，终止状态；
FAILED ：达到最大尝试次数，终止状态。
3、场景主要流程：
3.1、多个客户端http请求发布新建task任务队列中（FIFO），任务状态 QUEUED；
POST /tasks
Content-Type: application/json
Idempotency-Key: request-001
请求：
{
"type": "generate-report",
"payload": {
"report_id": "report-123"
},
"max_attempts": 3
}
返回：
{
"task_id": "task-001",
"status": "QUEUED",
"attempt_count": 0,
"max_attempts": 3,
"created_at": "2026-07-27T10:00:00Z"
}
支持幂等提交：使用相同 Idempotency-Key 和相同请求内容重复提交时，返回原任务，不创建新任务；使用相同 Idempotency-Key 但不同请求内容时，应返回明确的冲突错误。

    3.2、同时多个worker 工作人员不断的http请求，一直在竞争抢任务 我想使用 AQS 队列，谁抢到 AQS 的锁，谁就可以去task任务队列中 poll 一个任务出来，并携带claim_token(uuid)和租约时间lease_expires_at 去更新任务状态为 RUNNING；
        POST /workers/worker-001/tasks/claim
        Content-Type: application/json
        请求：{}
        返回：
        {
            "task_id": "task-001",
            "type": "generate-report",
            "payload": {
            "report_id": "report-123"
            },
            "status": "RUNNING",
            "attempt_count": 1,
            "claimed_by": "worker-001",
            "claim_token": "claim-token-001",
            "lease_expires_at": "2026-07-27T10:00:30Z"
        }
        没有可领取任务时，可以返回 错误码：204  错误msg：No task 
        成功领取后，任务状态变为 RUNNING ，并记录 Worker、领取凭证和租约到期时间。

    3.3、worker后台执行不用关注代码，如果成功，http报告xx任务成功，携带claim_token作为条件更新任务状态 SUCCEEDED,否则异常错误msg提示；
    报告任务成功
    POST /tasks/task-001/complete
    Content-Type: application/json
    请求：
    {
        "worker_id": "worker-001",
        "claim_token": "claim-token-001",
        "result": {
            "file_url": "https://example.test/report-123"
        }
    }

    3.4、报告任务失败与重试，同理携带claim_token作为条件更新任务状态 task任务重新进入可领取状态 QUEUED，重试次数+1（初始0），持久化当前task任务的尝试次数和最后一次错误error，重试次数满3次依然失败，task任务状态变为FAILED，不再重试执行。
    POST /tasks/task-001/fail
    Content-Type: application/json
    请求：
    {
        "worker_id": "worker-001",
        "claim_token": "claim-token-001",
        "error": {
            "code": "TEMPORARY_ERROR",
            "message": "Temporary dependency failure"
        }
    }   

    3.5、查询任务
    GET /tasks/task-001
    返回：
    {
        "task_id": "task-001",
        "type": "generate-report",
        "payload": {
        "report_id": "report-123"
        },
        "status": "RUNNING",
        "attempt_count": 1,
        "max_attempts": 3,
        "claimed_by": "worker-001",
        "claim_token": "claim-token-001",
        "lease_expires_at": "2026-07-27T10:00:30Z",
        "last_error": null,
        "created_at": "2026-07-27T10:00:00Z",
        "updated_at": "2026-07-27T10:00:05Z"
    }

    3.6、worker没有http请求 成功或者失败的报告，超过租约时间lease_expires_at，立即更新任务状态为QUEUED，更新claim_token为空，重新被新worker领取，使用新的claim_token。
    ease_expires_at默认领取时间3min后
    采用定时任务将租约到期的任务变为QUEUED，以及每次worker拿到锁后进行租约到期变更任务QUEUED，再poll任务

4、自动化测试要求
至少覆盖：
1. 创建并查询任务。
2. 相同幂等键的重复请求不会创建两个任务。
3. 任务成功和失败重试的状态转换。
4. 并发领取任务接口测试：预先只有一个可领取任务，两个 Worker 同时领取，断言最多一个 Worker 成功。
   测试应尽量稳定、可重复，不应依赖随机竞争或长时间等待才能通过。

</div>

