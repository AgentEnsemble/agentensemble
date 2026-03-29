package net.agentensemble.network.federation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.web.protocol.CapacityUpdateMessage;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CapacityAdvertiser}.
 */
class CapacityAdvertiserTest {

    @Test
    void start_firesImmediatelyAndPeriodically() throws Exception {
        CopyOnWriteArrayList<CapacityUpdateMessage> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        try (CapacityAdvertiser advertiser =
                new CapacityAdvertiser("kitchen", "hotel-downtown", () -> 0.5, 10, true, msg -> {
                    received.add(msg);
                    latch.countDown();
                })) {

            advertiser.start(Duration.ofMillis(50));

            // Wait for at least 3 broadcasts
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(received).hasSizeGreaterThanOrEqualTo(3);
        CapacityUpdateMessage first = received.get(0);
        assertThat(first.ensemble()).isEqualTo("kitchen");
        assertThat(first.realm()).isEqualTo("hotel-downtown");
        assertThat(first.status()).isEqualTo("available");
        assertThat(first.currentLoad()).isEqualTo(0.5);
        assertThat(first.maxConcurrent()).isEqualTo(10);
        assertThat(first.shareable()).isTrue();
    }

    @Test
    void close_stopsScheduling() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        CapacityAdvertiser advertiser = new CapacityAdvertiser(
                "kitchen", "hotel-downtown", () -> 0.5, 10, true, msg -> callCount.incrementAndGet());

        advertiser.start(Duration.ofMillis(50));
        Thread.sleep(200); // Let a few fire
        int countAtClose = callCount.get();
        advertiser.close();
        Thread.sleep(200); // Wait to see if more fire

        // Should not have significantly more calls after close
        assertThat(callCount.get()).isLessThanOrEqualTo(countAtClose + 1);
    }

    @Test
    void loadSupplierException_doesNotCrashAdvertiser() throws Exception {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger callCount = new AtomicInteger(0);

        try (CapacityAdvertiser advertiser = new CapacityAdvertiser(
                "kitchen",
                "hotel-downtown",
                () -> {
                    int count = callCount.incrementAndGet();
                    if (count == 1) {
                        throw new RuntimeException("simulated error");
                    }
                    return 0.5;
                },
                10,
                true,
                msg -> successCount.incrementAndGet())) {

            advertiser.start(Duration.ofMillis(50));
            Thread.sleep(500); // Let several firings happen

            // After the initial failure, subsequent broadcasts should succeed
            assertThat(successCount.get()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void statusReflectsLoad() throws Exception {
        CopyOnWriteArrayList<CapacityUpdateMessage> received = new CopyOnWriteArrayList<>();
        AtomicDouble load = new AtomicDouble(1.0);
        CountDownLatch latch = new CountDownLatch(1);

        try (CapacityAdvertiser advertiser =
                new CapacityAdvertiser("kitchen", "hotel-downtown", load::get, 10, true, msg -> {
                    received.add(msg);
                    latch.countDown();
                })) {

            advertiser.start(Duration.ofMillis(50));
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(received.get(0).status()).isEqualTo("busy");
    }

    /**
     * Simple AtomicDouble substitute using AtomicInteger with raw bit conversion.
     */
    private static class AtomicDouble {
        private final java.util.concurrent.atomic.AtomicLong bits;

        AtomicDouble(double initialValue) {
            bits = new java.util.concurrent.atomic.AtomicLong(Double.doubleToRawLongBits(initialValue));
        }

        double get() {
            return Double.longBitsToDouble(bits.get());
        }
    }
}
