package com.service.task.service;

public interface WorkerService {

    /** Worker 首次出现时注册，之后每次调用领取接口刷新 last_seen_at */
    void registerAndTouch(String workerId);

    /** 成功领取任务后记录领取次数与最后领取时间 */
    void recordClaimSuccess(String workerId);
}
