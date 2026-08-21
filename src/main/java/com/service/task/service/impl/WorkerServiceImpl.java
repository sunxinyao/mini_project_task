package com.service.task.service.impl;

import com.service.task.mapper.WorkerMapper;
import com.service.task.service.WorkerService;
import com.service.task.util.UtcTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class WorkerServiceImpl implements WorkerService {

    private final WorkerMapper workerMapper;

    @Override
    public void registerAndTouch(String workerId) {
        LocalDateTime now = UtcTimes.nowUtc();
        int inserted = 0;
        try {
            inserted = workerMapper.insertWorker(workerId, now);
        } catch (DuplicateKeyException e) {
            // 并发首次注册：唯一索引兜底
        }
        if (inserted == 0) {
            workerMapper.touchLastSeen(workerId, now);
        }
    }

    @Override
    public void recordClaimSuccess(String workerId) {
        workerMapper.recordClaim(workerId, UtcTimes.nowUtc());
    }
}
