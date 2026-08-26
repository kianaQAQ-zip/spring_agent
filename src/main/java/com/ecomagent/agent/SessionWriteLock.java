package com.ecomagent.agent;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 同 session 串行写锁（§8.8.3 并发写回）。
 *
 * <p>同一 {@code sessionId} 的「读-改-写」临界区串行化，避免并发轮次互相覆盖状态；
 * 跨 session 无竞争。生产多实例场景需配合 DB 乐观锁（见 {@link SessionStateRepository}）。
 */
@Component
public class SessionWriteLock {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLock(String sessionId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(sessionId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                locks.remove(sessionId, lock);
            }
        }
    }
}
