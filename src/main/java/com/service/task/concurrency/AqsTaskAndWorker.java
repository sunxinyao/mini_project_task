package com.service.task.concurrency;

import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

@Component
public class AqsTaskAndWorker {

    private final Sync sync = new Sync();

    private static final class Sync extends AbstractQueuedSynchronizer {

        /**
         * acquires >0 : task抢占任务，state +1
         * acquires <0 : worker领取任务，state -1，最小到0，不能负数
         */
        @Override
        protected boolean tryAcquire(int acquires) {
            // 有排队前驱节点，直接排队，不抢占
            if (hasQueuedPredecessors()) {
                return false;
            }

            for (; ; ) {
                int currentState = getState();
                if (acquires > 0) {
                    // Task：任务提交，state +1
                    int newState = currentState + 1;
                    if (compareAndSetState(currentState, newState)) {
                        // 独占所有者设置当前task线程
                        setExclusiveOwnerThread(Thread.currentThread());
                        return true;
                    }
                } else if (acquires < 0) {
                    // Worker：领取任务，state-1，不能小于0
                    if (currentState <= 0) {
                        // 没有任务，获取失败
                        return false;
                    }
                    int newState = currentState - 1;
                    if (compareAndSetState(currentState, newState)) {
                        setExclusiveOwnerThread(Thread.currentThread());
                        return true;
                    }
                } else {
                    return false;
                }
            }
        }

        /**
         * release释放锁，重置独占线程，state不变！
         * 重点：state代表任务计数，释放锁不重置state，只是让出独占权限，让其他线程竞争
         */
        @Override
        protected boolean tryRelease(int releases) {
            if (Thread.currentThread() != getExclusiveOwnerThread()) {
                throw new IllegalMonitorStateException();
            }
            // 释放锁：只清空独占线程，**不修改state**，state是任务计数器
            setExclusiveOwnerThread(null);
            return true;
        }

        public int getCurrentState() {
            return getState();
        }
    }

    /**
     * Task尝试抢占(提交任务) state +1
     * @param timeout 超时
     * @param unit 时间单位
     * @return true抢占成功，false超时失败
     */
    public boolean tryLockForTask(long timeout, TimeUnit unit) throws InterruptedException {
        // acquires=1 代表task操作，state+1
        return sync.tryAcquireNanos(1, unit.toNanos(timeout));
    }

    /**
     * Worker尝试抢占(领取任务) state -1，最低到0
     * @param timeout 超时
     * @return true抢到，可以处理任务；false没有任务/超时
     */
    public boolean tryLockForWorker(long timeout, TimeUnit unit) throws InterruptedException {
        // acquires=-1代表worker操作，state-1
        return sync.tryAcquireNanos(-1, unit.toNanos(timeout));
    }

    /**
     * 释放锁，只释放独占权限，不修改任务计数state
     */
    public void unlock() {
        sync.release(1);
    }

    /**
     * 获取当前任务计数state，业务层使用
     * @return 当前state，>=0
     */
    public int getTaskCount() {
        return sync.getCurrentState();
    }
}
