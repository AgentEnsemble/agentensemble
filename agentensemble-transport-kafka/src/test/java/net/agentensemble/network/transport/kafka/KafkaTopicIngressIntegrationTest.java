package net.agentensemble.network.transport.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.web.protocol.WorkRequest;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for {@link KafkaTopicIngress} using Testcontainers.
 *
 * <p>Requires Docker to be running. Tests are automatically skipped if Docker is unavailable.
 */
@Testcontainers
class KafkaTopicIngressIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private KafkaTopicIngress ingress;

    @AfterEach
    void tearDown() {
        if (ingress != null) {
            ingress.stop();
        }
    }

    @Test
    void ingress_consumesFromTopic() throws Exception {
        String topicName = "ingress-it-" + UUID.randomUUID();

        KafkaTransportConfig config = KafkaTransportConfig.builder()
                .bootstrapServers(KAFKA.getBootstrapServers())
                .consumerGroupId("ingress-test-" + UUID.randomUUID())
                .build();

        // Produce a message to the topic first
        WorkRequest request = new WorkRequest(
                "req-ing-1", "ensemble-x", "task-ingress", "data", null, null, null, null, null, null, null);
        String json = mapper.writeValueAsString(request);

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
            producer.send(new ProducerRecord<>(topicName, "req-ing-1", json));
            producer.flush();
        }

        // Start the ingress and collect messages
        CountDownLatch latch = new CountDownLatch(1);
        CopyOnWriteArrayList<WorkRequest> received = new CopyOnWriteArrayList<>();

        ingress = new KafkaTopicIngress(config, topicName);
        ingress.start(req -> {
            received.add(req);
            latch.countDown();
        });

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).requestId()).isEqualTo("req-ing-1");
        assertThat(received.get(0).from()).isEqualTo("ensemble-x");
        assertThat(received.get(0).task()).isEqualTo("task-ingress");
    }
}
