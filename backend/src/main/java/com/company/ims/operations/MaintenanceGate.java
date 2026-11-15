package com.company.ims.operations;

import com.company.ims.shared.BusinessException;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** 单实例维护门禁：备份前停止接受新业务请求，等在途请求完整结束后取得独占锁。 */
@Component
public class MaintenanceGate {
    /** 公平读写锁，防止连续业务请求使维护永久饥饿。 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    /** 包含等待在途请求阶段，避免新请求继续进入。 */
    private final AtomicBoolean maintenance = new AtomicBoolean(false);

    /** 普通请求进入时取得共享租约；维护开始后立即返回503而非挂起请求。 */
    public Lease enterRequest() {
        if (maintenance.get() || !lock.readLock().tryLock()) throw new BusinessException(503,"系统正在执行备份或完整性校验，请稍后重试");
        if (maintenance.get()) { lock.readLock().unlock(); throw new BusinessException(503,"系统正在进入维护，请稍后重试"); }
        return () -> lock.readLock().unlock();
    }

    /** 等待至多30秒排空现有请求，失败即释放维护状态，不终止任何其他进程。 */
    public Lease exclusive() {
        if (!maintenance.compareAndSet(false,true)) throw new BusinessException(409,"已有维护任务执行中，请勿重复提交");
        try {
            if (!lock.writeLock().tryLock(30,TimeUnit.SECONDS)) throw new BusinessException(409,"在途请求尚未结束，请稍后重新执行维护");
            return () -> { lock.writeLock().unlock(); maintenance.set(false); };
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); maintenance.set(false);
            throw new BusinessException(503,"维护任务已中断，未执行备份");
        } catch (RuntimeException failure) {
            maintenance.set(false); throw failure;
        }
    }

    /** 可自动释放的门禁租约，调用方必须用try-with-resources覆盖完整操作。 */
    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        /** 释放当前线程持有的共享或独占门禁。 */
        @Override void close();
    }
}
