package net.agentensemble.network.transport.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class KafkaTransportConfigTest {

    @Test
    void builder_createsValidConfig() {
        KafkaTransportConfig config = KafkaTransportConfig.builder()
                .bootstrapServers("localhost:9092")
                .consumerGroupId("test-group")
                .topicPrefix("custom.")
                .producerProperties(Map.of("acks", "all"))
                .consumerProperties(Map.of("max.poll.records", "100"))
                .build();

        assertThat(config.bootstrapServers()).isEqualTo("localhost:9092");
        assertThat(config.consumerGroupId()).isEqualTo("test-group");
        assertThat(config.topicPrefix()).isEqualTo("custom.");
        assertThat(config.producerProperties()).containsEntry("acks", "all");
        assertThat(config.consumerProperties()).containsEntry("max.poll.records", "100");
    }

    @Test
    void nullBootstrapServers_throws() {
        assertThatNullPointerException().isThrownBy(() -> KafkaTransportConfig.builder()
                .consumerGroupId("test-group")
                .build());
    }

    @Test
    void nullConsumerGroupId_throws() {
        assertThatNullPointerException().isThrownBy(() -> KafkaTransportConfig.builder()
                .bootstrapServers("localhost:9092")
                .build());
    }

    @Test
    void defaultTopicPrefix_isAgentEnsembleDot() {
        KafkaTransportConfig config = KafkaTransportConfig.builder()
                .bootstrapServers("localhost:9092")
                .consumerGroupId("test-group")
                .build();

        assertThat(config.topicPrefix()).isEqualTo("agentensemble.");
    }

    @Test
    void properties_areImmutableCopies() {
        var producerProps = new java.util.HashMap<String, String>();
        producerProps.put("acks", "all");

        var consumerProps = new java.util.HashMap<String, String>();
        consumerProps.put("max.poll.records", "100");

        KafkaTransportConfig config = KafkaTransportConfig.builder()
                .bootstrapServers("localhost:9092")
                .consumerGroupId("test-group")
                .producerProperties(producerProps)
                .consumerProperties(consumerProps)
                .build();

        // Mutating the original maps should not affect the config
        producerProps.put("acks", "0");
        consumerProps.put("max.poll.records", "1");

        assertThat(config.producerProperties()).containsEntry("acks", "all");
        assertThat(config.consumerProperties()).containsEntry("max.poll.records", "100");

        // Config properties should be unmodifiable
        assertThatThrownBy(() -> config.producerProperties().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> config.consumerProperties().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullProperties_defaultToEmptyMaps() {
        KafkaTransportConfig config = new KafkaTransportConfig("localhost:9092", "test-group", null, null, null);

        assertThat(config.producerProperties()).isEmpty();
        assertThat(config.consumerProperties()).isEmpty();
        assertThat(config.topicPrefix()).isEqualTo("agentensemble.");
    }

    @Test
    void builder_nullProducerProperties_defaultsToEmpty() {
        KafkaTransportConfig config = KafkaTransportConfig.builder()
                .bootstrapServers("localhost:9092")
                .consumerGroupId("test-group")
                .producerProperties(null)
                .consumerProperties(null)
                .build();

        assertThat(config.producerProperties()).isEmpty();
        assertThat(config.consumerProperties()).isEmpty();
    }
}
