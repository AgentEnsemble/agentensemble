package net.agentensemble.transport.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.network.transport.Transport;
import net.agentensemble.web.protocol.WorkRequest;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration tests for Redis-backed durable transport via
 * {@link Transport#durable(String, net.agentensemble.network.transport.RequestQueue,
 * net.agentensemble.network.transport.ResultStore)}.
 */
@Testcontainers
class RedisTransportIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private io.lettuce.core.RedisClient redisClient;

    @BeforeEach
    void setUp() {
        redisClient =
                io.lettuce.core.RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        // Flush Redis between tests for isolation
        try (var conn = redisClient.connect()) {
            conn.sync().flushall();
        }
    }

    @AfterEach
    void tearDown() {
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    // ========================
    // Full round-trip
    // ========================

    @Test
    void durable_transport_fullRoundTrip() {
        try (Transport transport = Transport.durable(
                "kitchen", RedisRequestQueue.create(redisClient), RedisResultStore.create(redisClient))) {

            // Send a work request
            WorkRequest request = workRequest("req-e2e", "prepare-meal");
            transport.send(request);

            // Receive it from the queue
            WorkRequest received = transport.receive(Duration.ofSeconds(5));
            assertThat(received).isNotNull();
            assertThat(received.requestId()).isEqualTo("req-e2e");
            assertThat(received.task()).isEqualTo("prepare-meal");

            // Deliver a response
            WorkResponse response = new WorkResponse("req-e2e", "COMPLETED", "meal ready", null, 2000L);
            transport.deliver(response);
        }
    }

    @Test
    void durable_transport_subscribe_notification() throws Exception {
        RedisResultStore store = RedisResultStore.create(redisClient);
        AtomicReference<WorkResponse> captured = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try (Transport transport = Transport.durable("kitchen", RedisRequestQueue.create(redisClient), store)) {

            // Subscribe before delivering
            store.subscribe("req-notify", response -> {
                captured.set(response);
                latch.countDown();
            });

            // Small delay to let subscription activate
            Thread.sleep(100);

            // Deliver a response through the transport
            transport.deliver(new WorkResponse("req-notify", "COMPLETED", "notified", null, 100L));

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().requestId()).isEqualTo("req-notify");
            assertThat(captured.get().result()).isEqualTo("notified");
        }
    }

    @Test
    void multiple_ensembles_independent() {
        try (Transport kitchen = Transport.durable(
                        "kitchen",
                        RedisRequestQueue.create(redisClient, "kitchen-consumer"),
                        RedisResultStore.create(redisClient));
                Transport maintenance = Transport.durable(
                        "maintenance",
                        RedisRequestQueue.create(redisClient, "maintenance-consumer"),
                        RedisResultStore.create(redisClient))) {

            kitchen.send(workRequest("req-k", "cook"));
            maintenance.send(workRequest("req-m", "repair"));

            // Each transport only receives from its own ensemble's queue
            WorkRequest fromKitchen = kitchen.receive(Duration.ofSeconds(2));
            WorkRequest fromMaintenance = maintenance.receive(Duration.ofSeconds(2));

            assertThat(fromKitchen).isNotNull();
            assertThat(fromKitchen.requestId()).isEqualTo("req-k");

            assertThat(fromMaintenance).isNotNull();
            assertThat(fromMaintenance.requestId()).isEqualTo("req-m");
        }
    }

    @Test
    void close_releasesResources() {
        Transport transport = Transport.durable(
                "kitchen", RedisRequestQueue.create(redisClient), RedisResultStore.create(redisClient));

        // Should not throw
        transport.close();
        transport.close(); // idempotent
    }

    // ========================
    // Helpers
    // ========================

    private static WorkRequest workRequest(String requestId, String task) {
        return new WorkRequest(requestId, "test-ensemble", task, null, null, null, null, null, null, null);
    }
}
