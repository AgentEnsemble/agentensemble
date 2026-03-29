package net.agentensemble.network.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link ProfileScheduler}.
 */
class ProfileSchedulerTest {

    @Test
    void schedule_firesCallback() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        ProfileApplier applier = mock(ProfileApplier.class, (Answer<?>) invocation -> {
            latch.countDown();
            return null;
        });

        NetworkProfile profile = NetworkProfile.builder()
                .name("scheduled-profile")
                .ensemble("kitchen", Capacity.replicas(2).maxConcurrent(20))
                .build();

        try (ProfileScheduler scheduler = new ProfileScheduler(applier)) {
            scheduler.schedule(profile, Duration.ofMillis(0), Duration.ofMillis(50));

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }

        verify(applier, atLeast(2)).apply(profile);
    }

    @Test
    void scheduleOnce_firesOnce() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        ProfileApplier applier = mock(ProfileApplier.class, (Answer<?>) invocation -> {
            latch.countDown();
            return null;
        });

        NetworkProfile profile = NetworkProfile.builder().name("one-shot").build();

        try (ProfileScheduler scheduler = new ProfileScheduler(applier)) {
            scheduler.scheduleOnce(profile, Duration.ofMillis(10));

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }

        verify(applier).apply(profile);
    }

    @Test
    void close_stopsScheduling() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        ProfileApplier applier = mock(ProfileApplier.class, (Answer<?>) invocation -> {
            callCount.incrementAndGet();
            return null;
        });

        NetworkProfile profile = NetworkProfile.builder().name("test").build();

        ProfileScheduler scheduler = new ProfileScheduler(applier);
        scheduler.schedule(profile, Duration.ofMillis(0), Duration.ofMillis(50));
        Thread.sleep(200); // Let a few fire
        int countAtClose = callCount.get();
        scheduler.close();
        Thread.sleep(200); // Wait to see if more fire

        // Should not have significantly more calls after close
        assertThat(callCount.get()).isLessThanOrEqualTo(countAtClose + 1);
    }

    @Test
    void exceptionInApply_doesNotCrashScheduler() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        ProfileApplier applier = mock(ProfileApplier.class);
        doThrow(new RuntimeException("simulated error"))
                .doAnswer(invocation -> {
                    callCount.incrementAndGet();
                    return null;
                })
                .when(applier)
                .apply(any());

        NetworkProfile profile =
                NetworkProfile.builder().name("error-resilient").build();

        try (ProfileScheduler scheduler = new ProfileScheduler(applier)) {
            scheduler.schedule(profile, Duration.ofMillis(0), Duration.ofMillis(50));
            Thread.sleep(500); // Let several firings happen

            // After the initial failure, subsequent calls should succeed
            assertThat(callCount.get()).isGreaterThanOrEqualTo(2);
        }
    }
}
