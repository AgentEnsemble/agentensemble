package net.agentensemble.ensemble;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Task;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EnsembleScheduler}.
 */
class EnsembleSchedulerTest {

    private final Task sampleTask = Task.of("Do something");

    @Test
    void schedule_intervalTask_firesOnSchedule() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger fireCount = new AtomicInteger();

        ScheduledTask st = ScheduledTask.builder()
                .name("fast-task")
                .task(sampleTask)
                .schedule(Schedule.every(Duration.ofMillis(50)))
                .build();

        try (EnsembleScheduler scheduler = new EnsembleScheduler(() -> EnsembleLifecycleState.READY)) {
            scheduler.schedule(st, () -> {
                fireCount.incrementAndGet();
                latch.countDown();
            });

            boolean completed = latch.await(500, TimeUnit.MILLISECONDS);
            assertThat(completed).isTrue();
            assertThat(fireCount.get()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void schedule_drainingState_skipsExecution() throws InterruptedException {
        AtomicBoolean fired = new AtomicBoolean(false);
        CountDownLatch attemptLatch = new CountDownLatch(1);

        ScheduledTask st = ScheduledTask.builder()
                .name("skipped-task")
                .task(sampleTask)
                .schedule(Schedule.every(Duration.ofMillis(50)))
                .build();

        // State provider always returns DRAINING -- the action should never fire.
        try (EnsembleScheduler scheduler = new EnsembleScheduler(() -> EnsembleLifecycleState.DRAINING)) {
            scheduler.schedule(st, () -> {
                fired.set(true);
                attemptLatch.countDown();
            });

            // Wait enough time for at least one interval to pass
            boolean completed = attemptLatch.await(200, TimeUnit.MILLISECONDS);
            assertThat(completed).isFalse();
            assertThat(fired.get()).isFalse();
        }
    }

    @Test
    void stop_cancelsScheduledTasks() {
        ScheduledTask st = ScheduledTask.builder()
                .name("cancel-test")
                .task(sampleTask)
                .schedule(Schedule.every(Duration.ofMillis(50)))
                .build();

        EnsembleScheduler scheduler = new EnsembleScheduler(() -> EnsembleLifecycleState.READY);
        scheduler.schedule(st, () -> {});
        scheduler.stop();

        assertThat(scheduler.isShutdown()).isTrue();
    }

    @Test
    void stop_doesNotInterruptRunning() throws InterruptedException {
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskDone = new CountDownLatch(1);

        ScheduledTask st = ScheduledTask.builder()
                .name("long-running")
                .task(sampleTask)
                .schedule(Schedule.every(Duration.ofMillis(10)))
                .build();

        EnsembleScheduler scheduler = new EnsembleScheduler(() -> EnsembleLifecycleState.READY);
        scheduler.schedule(st, () -> {
            taskStarted.countDown();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            completed.set(true);
            taskDone.countDown();
        });

        // Wait for the task to start
        assertThat(taskStarted.await(500, TimeUnit.MILLISECONDS)).isTrue();

        // Stop immediately -- should not interrupt the running task
        scheduler.stop();

        // The running task should complete
        assertThat(taskDone.await(500, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(completed.get()).isTrue();
    }

    @Test
    void close_delegatesToStop() {
        EnsembleScheduler scheduler = new EnsembleScheduler(() -> EnsembleLifecycleState.READY);
        scheduler.close();

        assertThat(scheduler.isShutdown()).isTrue();
    }

    @Test
    void isShutdown_afterStop_returnsTrue() {
        EnsembleScheduler scheduler = new EnsembleScheduler(() -> EnsembleLifecycleState.READY);
        assertThat(scheduler.isShutdown()).isFalse();

        scheduler.stop();
        assertThat(scheduler.isShutdown()).isTrue();
    }
}
