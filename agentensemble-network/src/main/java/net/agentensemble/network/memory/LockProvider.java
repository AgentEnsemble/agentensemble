package net.agentensemble.network.memory;

import java.time.Duration;

/**
 * SPI for distributed lock providers used by {@link SharedMemory} with
 * {@link Consistency#LOCKED} mode.
 *
 * <p>Implementations must be thread-safe.
 */
public interface LockProvider {

    /**
     * Acquire an exclusive lock on the named scope. Blocks until acquired.
     *
     * @param scope the scope name
     * @return an {@link AutoCloseable} handle; closing releases the lock
     */
    AutoCloseable lock(String scope);

    /**
     * Try to acquire a lock within the given timeout.
     *
     * @param scope   the scope name
     * @param timeout maximum time to wait
     * @return true if the lock was acquired
     */
    boolean tryLock(String scope, Duration timeout);

    /**
     * Release the lock on the named scope.
     *
     * @param scope the scope name
     */
    void unlock(String scope);

    /**
     * Create an in-memory lock provider backed by {@link java.util.concurrent.locks.ReentrantLock}.
     *
     * @return a new in-memory lock provider
     */
    static LockProvider inMemory() {
        return new InMemoryLockProvider();
    }
}
