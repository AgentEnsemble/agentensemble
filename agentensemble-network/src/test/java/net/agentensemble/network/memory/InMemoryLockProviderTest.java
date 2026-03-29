package net.agentensemble.network.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryLockProvider}.
 */
class InMemoryLockProviderTest {

    private InMemoryLockProvider provider;

    @BeforeEach
    void setUp() {
        provider = new InMemoryLockProvider();
    }

    @Test
    void lockAndUnlock() throws Exception {
        AutoCloseable handle = provider.lock("scope1");
        // Should be able to close (unlock) without error
        handle.close();
    }

    @Test
    void tryLock_succeeds_whenAvailable() {
        boolean acquired = provider.tryLock("scope1", Duration.ofSeconds(1));
        assertThat(acquired).isTrue();
        provider.unlock("scope1");
    }

    @Test
    void tryLock_fails_whenHeldByAnotherThread() throws InterruptedException {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            provider.tryLock("scope1", Duration.ofSeconds(5));
            locked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            provider.unlock("scope1");
        });
        holder.start();

        locked.await(5, TimeUnit.SECONDS);

        // Try to acquire from the main thread with a short timeout
        boolean acquired = provider.tryLock("scope1", Duration.ofMillis(50));
        assertThat(acquired).isFalse();

        release.countDown();
        holder.join(5000);
    }

    @Test
    void scopeIsolation_lockingScopeA_doesNotBlockScopeB() throws InterruptedException {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean scopeBLocked = new AtomicBoolean(false);

        Thread holder = new Thread(() -> {
            provider.tryLock("scopeA", Duration.ofSeconds(5));
            locked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            provider.unlock("scopeA");
        });
        holder.start();

        locked.await(5, TimeUnit.SECONDS);

        // Locking scope B should succeed immediately
        boolean acquired = provider.tryLock("scopeB", Duration.ofMillis(100));
        scopeBLocked.set(acquired);
        if (acquired) {
            provider.unlock("scopeB");
        }

        release.countDown();
        holder.join(5000);

        assertThat(scopeBLocked.get()).isTrue();
    }

    @Test
    void unlock_noOpIfNotHeld() {
        // Should not throw even if not locked
        provider.unlock("nonexistent");
    }

    @Test
    void lock_viaStaticFactory() throws Exception {
        LockProvider lp = LockProvider.inMemory();
        AutoCloseable handle = lp.lock("test");
        handle.close();
    }
}
