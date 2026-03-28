package net.agentensemble.network.transport.kafka;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for Kafka transport components.
 *
 * <p>Use the {@link #builder()} to construct instances fluently. At minimum,
 * {@code bootstrapServers} and {@code consumerGroupId} must be set.
 *
 * @param bootstrapServers   Kafka bootstrap servers; must not be null
 * @param consumerGroupId    consumer group ID for request queue consumers; must not be null
 * @param topicPrefix        prefix for auto-created topic names (default: {@code "agentensemble."})
 * @param producerProperties additional Kafka producer properties; never null
 * @param consumerProperties additional Kafka consumer properties; never null
 */
public record KafkaTransportConfig(
        String bootstrapServers,
        String consumerGroupId,
        String topicPrefix,
        Map<String, String> producerProperties,
        Map<String, String> consumerProperties) {

    /**
     * Compact constructor with validation and defensive copies.
     *
     * @throws NullPointerException if {@code bootstrapServers} or {@code consumerGroupId} is null
     */
    public KafkaTransportConfig {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers must not be null");
        Objects.requireNonNull(consumerGroupId, "consumerGroupId must not be null");
        if (topicPrefix == null) topicPrefix = "agentensemble.";
        if (producerProperties == null) producerProperties = Map.of();
        if (consumerProperties == null) consumerProperties = Map.of();
        producerProperties = Map.copyOf(producerProperties);
        consumerProperties = Map.copyOf(consumerProperties);
    }

    /**
     * Returns a new {@link Builder} for constructing {@code KafkaTransportConfig} instances.
     *
     * @return a new builder; never null
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link KafkaTransportConfig}.
     */
    public static final class Builder {

        private String bootstrapServers;
        private String consumerGroupId;
        private String topicPrefix;
        private Map<String, String> producerProperties = new HashMap<>();
        private Map<String, String> consumerProperties = new HashMap<>();

        Builder() {}

        /**
         * Sets the Kafka bootstrap servers.
         *
         * @param bootstrapServers bootstrap server addresses (e.g., {@code "localhost:9092"})
         * @return this builder
         */
        public Builder bootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
            return this;
        }

        /**
         * Sets the consumer group ID.
         *
         * @param consumerGroupId the consumer group identifier
         * @return this builder
         */
        public Builder consumerGroupId(String consumerGroupId) {
            this.consumerGroupId = consumerGroupId;
            return this;
        }

        /**
         * Sets the topic name prefix.
         *
         * @param topicPrefix prefix prepended to queue/topic names (default: {@code "agentensemble."})
         * @return this builder
         */
        public Builder topicPrefix(String topicPrefix) {
            this.topicPrefix = topicPrefix;
            return this;
        }

        /**
         * Sets additional Kafka producer properties.
         *
         * @param producerProperties the producer properties
         * @return this builder
         */
        public Builder producerProperties(Map<String, String> producerProperties) {
            this.producerProperties = producerProperties == null ? new HashMap<>() : new HashMap<>(producerProperties);
            return this;
        }

        /**
         * Sets additional Kafka consumer properties.
         *
         * @param consumerProperties the consumer properties
         * @return this builder
         */
        public Builder consumerProperties(Map<String, String> consumerProperties) {
            this.consumerProperties = consumerProperties == null ? new HashMap<>() : new HashMap<>(consumerProperties);
            return this;
        }

        /**
         * Builds a new {@link KafkaTransportConfig}.
         *
         * @return the config; never null
         * @throws NullPointerException if required fields are null
         */
        public KafkaTransportConfig build() {
            return new KafkaTransportConfig(
                    bootstrapServers, consumerGroupId, topicPrefix, producerProperties, consumerProperties);
        }
    }
}
