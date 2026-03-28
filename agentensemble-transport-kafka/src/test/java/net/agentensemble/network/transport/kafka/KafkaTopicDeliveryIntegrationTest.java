package net.agentensemble.network.transport.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for {@link KafkaTopicDelivery} using Testcontainers.
 *
 * <p>Requires Docker to be running. Tests are automatically skipped if Docker is unavailable.
 */
@Testcontainers
class KafkaTopicDeliveryIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private KafkaTopicDelivery delivery;

    @BeforeEach
    void setUp() {
        KafkaTransportConfig config = KafkaTransportConfig.builder()
                .bootstrapServers(KAFKA.getBootstrapServers())
                .consumerGroupId("delivery-test-" + UUID.randomUUID())
                .build();
        delivery = new KafkaTopicDelivery(config);
    }

    @AfterEach
    void tearDown() {
        if (delivery != null) {
            delivery.close();
        }
    }

    @Test
    void deliver_producesToTopic() throws Exception {
        String topicName = "delivery-it-" + UUID.randomUUID();
        WorkResponse response = new WorkResponse("req-del-1", "COMPLETED", "output", null, 100L);
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.TOPIC, topicName);

        delivery.deliver(spec, response);

        // Verify by reading from the topic with a raw consumer
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "verify-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topicName));

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(30));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);

            ConsumerRecord<String, String> record = records.iterator().next();
            assertThat(record.key()).isEqualTo("req-del-1");

            WorkResponse deserialized = mapper.readValue(record.value(), WorkResponse.class);
            assertThat(deserialized.requestId()).isEqualTo("req-del-1");
            assertThat(deserialized.status()).isEqualTo("COMPLETED");
            assertThat(deserialized.result()).isEqualTo("output");
        }
    }
}
