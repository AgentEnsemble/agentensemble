package net.agentensemble.network.transport.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import net.agentensemble.web.protocol.WorkRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for {@link KafkaRequestQueue} using Testcontainers.
 *
 * <p>Requires Docker to be running. Tests are automatically skipped if Docker is unavailable.
 */
@Testcontainers
class KafkaRequestQueueIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));

    private KafkaRequestQueue queue;

    @BeforeEach
    void setUp() {
        KafkaTransportConfig config = KafkaTransportConfig.builder()
                .bootstrapServers(KAFKA.getBootstrapServers())
                .consumerGroupId("test-group-" + UUID.randomUUID())
                .topicPrefix("it.")
                .build();
        queue = KafkaRequestQueue.create(config);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
    }

    @Test
    void enqueue_dequeue_roundTrip() {
        String queueName = "roundtrip-" + UUID.randomUUID();
        WorkRequest request = new WorkRequest(
                "req-rt-1", "ensemble-a", "task-roundtrip", "hello", null, null, null, null, null, null, null);

        queue.enqueue(queueName, request);

        WorkRequest result = queue.dequeue(queueName, Duration.ofSeconds(30));

        assertThat(result).isNotNull();
        assertThat(result.requestId()).isEqualTo("req-rt-1");
        assertThat(result.from()).isEqualTo("ensemble-a");
        assertThat(result.task()).isEqualTo("task-roundtrip");
        assertThat(result.context()).isEqualTo("hello");
    }

    @Test
    void acknowledge_commitsOffset() {
        String queueName = "ack-" + UUID.randomUUID();
        WorkRequest request =
                new WorkRequest("req-ack-1", "ensemble-b", "task-ack", "ctx", null, null, null, null, null, null, null);

        queue.enqueue(queueName, request);

        WorkRequest result = queue.dequeue(queueName, Duration.ofSeconds(30));
        assertThat(result).isNotNull();

        // Acknowledge should not throw
        queue.acknowledge(queueName, result.requestId());

        // After acknowledging and re-reading, no new messages should be available
        WorkRequest next = queue.dequeue(queueName, Duration.ofSeconds(2));
        assertThat(next).isNull();
    }

    @Test
    void consumerGroup_multipleConsumers() {
        // Each KafkaRequestQueue gets its own consumer group, so two separate queues
        // with different group IDs should each see the same messages.
        String queueName = "multi-" + UUID.randomUUID();

        KafkaTransportConfig config2 = KafkaTransportConfig.builder()
                .bootstrapServers(KAFKA.getBootstrapServers())
                .consumerGroupId("other-group-" + UUID.randomUUID())
                .topicPrefix("it.")
                .build();

        try (KafkaRequestQueue queue2 = KafkaRequestQueue.create(config2)) {
            WorkRequest request = new WorkRequest(
                    "req-multi-1", "ensemble-c", "task-multi", "data", null, null, null, null, null, null, null);

            queue.enqueue(queueName, request);

            WorkRequest r1 = queue.dequeue(queueName, Duration.ofSeconds(30));
            WorkRequest r2 = queue2.dequeue(queueName, Duration.ofSeconds(30));

            assertThat(r1).isNotNull();
            assertThat(r2).isNotNull();
            assertThat(r1.requestId()).isEqualTo("req-multi-1");
            assertThat(r2.requestId()).isEqualTo("req-multi-1");
        }
    }
}
