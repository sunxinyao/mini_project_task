package com.service.task.concurrency;

import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * AqsTaskAndWorker 支持可重入
 */
@Component
public class AqsTaskAndWorker {

    private final Sync sync = new Sync();

    private static final class Sync extends AbstractQueuedSynchronizer {
        // 独立：独占锁重入计数，不和业务任务state混用！
        private int holdCount = 0;

        /**
         * acquires >0 : task，state +1
         * acquires <0 : worker，state -1，最小0
         */
        @Override
        protected boolean tryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            // ==========支持重入：当前线程已经持有锁==========
            if (getExclusiveOwnerThread() == current) {
                holdCount++;
                // 同一个线程持有锁，直接修改业务state，不需要CAS竞争
                int curState = getState();
                if (acquires > 0) {
                    setState(curState + 1);
                } else if (acquires < 0) {
                    if (curState > 0) {
                        setState(curState - 1);
                    }
                }
                return true;
            }

            // 不是持有线程：正常走排队+CAS竞争
            if (hasQueuedPredecessors()) {
                return false;
            }

            for (; ; ) {
                int currentState = getState();
                if (acquires > 0) {
                    // Task +1
                    int newState = currentState + 1;
                    if (compareAndSetState(currentState, newState)) {
                        setExclusiveOwnerThread(current);
                        holdCount = 1;
                        return true;
                    }
                } else if (acquires < 0) {
                    // Worker -1，不能小于0
                    if (currentState <= 0) {
                        return false;
                    }
                    int newState = currentState - 1;
                    if (compareAndSetState(currentState, newState)) {
                        setExclusiveOwnerThread(current);
                        holdCount = 1;
                        return true;
                    }
                } else {
                    return false;
                }
            }
        }

        @Override
        protected boolean tryRelease(int releases) {
            if (Thread.currentThread() != getExclusiveOwnerThread()) {
                throw new IllegalMonitorStateException();
            }
            // 处理重入：holdCount递减，只有降到0才真正释放独占所有权
            holdCount--;
            if (holdCount > 0) {
                // 重入未完全释放，不清除ownerThread，不真正释放锁
                return true;
            }
            holdCount = 0;
            setExclusiveOwnerThread(null);
            return true;
        }

        public int getTaskCount() {
            return getState();
        }
    }

    /**
     * Task 提交任务 state+1
     */
    public boolean tryLockForTask(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireNanos(1, unit.toNanos(timeout));
    }

    /**
     * Worker 领取任务 state-1
     */
    public boolean tryLockForWorker(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireNanos(-1, unit.toNanos(timeout));
    }

    /**
     * unlock：注意重入场景，调用多少次lock就要多少次unlock
     */
    public void unlock() {
        sync.release(1);
    }

    /**
     * 获取当前待处理任务计数
     */
    public int getTaskCount() {
        return sync.getTaskCount();
    }
}
