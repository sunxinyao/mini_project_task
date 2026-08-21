package com.service.task;

import com.service.task.concurrency.AqsMutex;
import com.service.task.concurrency.AqsTaskAndWorker;
import com.service.task.support.IntegrationTestBase;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.TimeUnit;

public class AqsTest extends IntegrationTestBase {

    @Autowired
    private AqsTaskAndWorker mutex;

    @SneakyThrows
    @Test
    void test_aqs_try_again() {
        boolean firstTsk = mutex.tryLockForTask(1, TimeUnit.SECONDS);
        System.out.println("firstTsk获取锁，任务计数=" + mutex.getTaskCount());
        mutex.unlock();
        // worker拿到锁
        boolean workerOk = mutex.tryLockForWorker(1, TimeUnit.SECONDS);
        if (workerOk) {
            try {
                System.out.println("worker获取锁，任务计数=" + mutex.getTaskCount());
                // 在已经持有锁的worker线程内部，再新增任务 state +1，重入成功
                boolean innerTaskOk = mutex.tryLockForTask(1, TimeUnit.SECONDS);
                System.out.println("innerTaskOk:" + innerTaskOk + "，任务计数=" + mutex.getTaskCount());
            } finally {
                // ⚠️重点！！！lock两次，必须unlock两次！！
                mutex.unlock();
                mutex.unlock();
            }
        }

    }
}
