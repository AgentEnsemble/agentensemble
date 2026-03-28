package net.agentensemble.network.transport.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Objects;
import java.util.Properties;
import net.agentensemble.network.transport.DeliveryHandler;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka-backed {@link DeliveryHandler} that publishes work responses to Kafka topics.
 *
 * <p>Supports the {@link DeliveryMethod#TOPIC} delivery method. The topic name is taken
 * from the {@link DeliverySpec#address()} field. Responses are serialized to JSON and
 * produced to the specified topic.
 *
 * <p>Implements {@link AutoCloseable} -- call {@link #close()} to release the producer.
 *
 * @see DeliveryHandler
 * @see DeliveryMethod#TOPIC
 */
public class KafkaTopicDelivery implements DeliveryHandler, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicDelivery.class);

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper;

    /**
     * Creates a new Kafka topic delivery handler with the given configuration.
     *
     * @param config the Kafka transport configuration; must not be null
     */
    public KafkaTopicDelivery(KafkaTransportConfig config) {
        this(createProducer(Objects.requireNonNull(config, "config must not be null")));
    }

    /**
     * Package-private constructor for testing with a mock producer.
     *
     * @param producer the Kafka producer to use; must not be null
     */
    KafkaTopicDelivery(KafkaProducer<String, String> producer) {
        this.producer = Objects.requireNonNull(producer, "producer must not be null");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    @Override
    public DeliveryMethod method() {
        return DeliveryMethod.TOPIC;
    }

    /**
     * Delivers the work response to the Kafka topic specified by the delivery spec address.
     *
     * @param spec     the delivery specification; must not be null, address must not be null or blank
     * @param response the work response to deliver; must not be null
     * @throws IllegalArgumentException if the spec address is null or blank
     * @throws RuntimeException         if serialization or delivery fails
     */
    @Override
    public void deliver(DeliverySpec spec, WorkResponse response) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(response, "response must not be null");
        if (spec.address() == null || spec.address().isBlank()) {
            throw new IllegalArgumentException("DeliverySpec address must not be null or blank for TOPIC delivery");
        }
        try {
            String json = mapper.writeValueAsString(response);
            producer.send(new ProducerRecord<>(spec.address(), response.requestId(), json));
            producer.flush();
            LOG.debug("Delivered response {} to topic {}", response.requestId(), spec.address());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deliver work response: " + response.requestId(), e);
        }
    }

    /**
     * Closes the underlying Kafka producer.
     */
    @Override
    public void close() {
        producer.close();
        LOG.debug("Closed KafkaTopicDelivery");
    }

    private static KafkaProducer<String, String> createProducer(KafkaTransportConfig config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.producerProperties().forEach(props::put);
        return new KafkaProducer<>(props);
    }
}
