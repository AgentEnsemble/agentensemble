package net.agentensemble.network.memory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory {@link LockProvider} backed by {@link ReentrantLock} instances.
 * Suitable for development, testing, and single-JVM deployments.
 */
final class InMemoryLockProvider implements LockProvider {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public AutoCloseable lock(String scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        ReentrantLock lock = locks.computeIfAbsent(scope, k -> new ReentrantLock());
        lock.lock();
        return () -> lock.unlock();
    }

    @Override
    public boolean tryLock(String scope, Duration timeout) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        ReentrantLock lock = locks.computeIfAbsent(scope, k -> new ReentrantLock());
        try {
            return lock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void unlock(String scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        ReentrantLock lock = locks.get(scope);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
