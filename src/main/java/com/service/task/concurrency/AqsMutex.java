package com.service.task.concurrency;

import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * 基于 AQS（AbstractQueuedSynchronizer）的公平、不可重入互斥锁，
 * 用于将同一 JVM 内多个 Worker 的“抢任务”请求串行化：
 * 抢到该锁的 Worker 才能在任务队列中 poll 一个 QUEUED 任务并更新为 RUNNING，
 * 未抢到的线程会在 AQS 的同步队列（CLH queue）中排队等待。
 *
 * <p>说明：
 * <ul>
 *   <li>公平模式（tryAcquire 先检查 hasQueuedPredecessors），先到先得，避免饥饿；</li>
 *   <li>不可重入：领取任务的关键路径不存在重入场景，误重入应立即暴露；</li>
 *   <li>该锁只作用于单实例；多实例部署时由 SQL 条件更新
 *       （UPDATE ... WHERE status = 'QUEUED'）兜底保证任务不会被重复领取。</li>
 * </ul>
 */
@Component
public class AqsMutex {

    private final Sync sync = new Sync();

    private static final class Sync extends AbstractQueuedSynchronizer {

        @Override
        protected boolean tryAcquire(int acquires) {
            if (hasQueuedPredecessors()) {
                return false;
            }
            if (!compareAndSetState(0, 1)) {
                return false;
            }
            setExclusiveOwnerThread(Thread.currentThread());
            return true;
        }

        @Override
        protected boolean tryRelease(int releases) {
            if (Thread.currentThread() != getExclusiveOwnerThread()) {
                throw new IllegalMonitorStateException();
            }
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }
    }

    /**
     * 尝试在给定超时内获取锁；超时返回 false（表示当前没有可领取机会）。
     */
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireNanos(1, unit.toNanos(timeout));
    }

    public void unlock() {
        sync.release(1);
    }
}
