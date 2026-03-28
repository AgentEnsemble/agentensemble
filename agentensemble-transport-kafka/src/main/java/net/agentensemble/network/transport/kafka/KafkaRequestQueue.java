package net.agentensemble.network.transport.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import net.agentensemble.network.transport.RequestQueue;
import net.agentensemble.web.protocol.WorkRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka-backed {@link RequestQueue} implementation.
 *
 * <p>Produces work requests to Kafka topics named {@code <prefix><queueName>} and
 * consumes them using per-queue consumers with manual offset commits. Each queue name
 * gets its own dedicated {@link KafkaConsumer} instance stored in a thread-safe map.
 *
 * <p>This implementation uses manual offset commits ({@code enable.auto.commit = false}).
 * Call {@link #acknowledge(String, String)} after successful processing to commit the
 * consumer offsets.
 *
 * <p>Implements {@link AutoCloseable} -- call {@link #close()} to release producer and
 * consumer resources.
 *
 * @see RequestQueue
 * @see KafkaTransportConfig
 */
public class KafkaRequestQueue implements RequestQueue, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaRequestQueue.class);

    private final KafkaProducer<String, String> producer;
    private final ConcurrentHashMap<String, KafkaConsumer<String, String>> consumers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<TopicPartition, OffsetAndMetadata>> pendingOffsets =
            new ConcurrentHashMap<>();
    private final KafkaTransportConfig config;
    private final ObjectMapper mapper;

    /**
     * Creates a new Kafka request queue with the given config, creating a new producer.
     *
     * @param config the Kafka transport configuration; must not be null
     */
    KafkaRequestQueue(KafkaTransportConfig config) {
        this(config, createProducer(config));
    }

    /**
     * Package-private constructor for testing with a mock producer.
     *
     * @param config   the Kafka transport configuration; must not be null
     * @param producer the Kafka producer to use; must not be null
     */
    KafkaRequestQueue(KafkaTransportConfig config, KafkaProducer<String, String> producer) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.producer = Objects.requireNonNull(producer, "producer must not be null");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    /**
     * Creates a new {@link KafkaRequestQueue} with the given configuration.
     *
     * @param config the Kafka transport configuration; must not be null
     * @return a new request queue; never null
     */
    public static KafkaRequestQueue create(KafkaTransportConfig config) {
        return new KafkaRequestQueue(config);
    }

    @Override
    public void enqueue(String queueName, WorkRequest request) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(request, "request must not be null");
        try {
            String topic = config.topicPrefix() + queueName;
            String json = mapper.writeValueAsString(request);
            producer.send(new ProducerRecord<>(topic, request.requestId(), json));
            producer.flush();
            LOG.debug("Enqueued request {} to topic {}", request.requestId(), topic);
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue work request: " + request.requestId(), e);
        }
    }

    @Override
    public WorkRequest dequeue(String queueName, Duration timeout) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        try {
            KafkaConsumer<String, String> consumer = getOrCreateConsumer(queueName);
            ConsumerRecords<String, String> records = consumer.poll(timeout);
            for (ConsumerRecord<String, String> record : records) {
                WorkRequest request = mapper.readValue(record.value(), WorkRequest.class);
                // Track the specific offset for this record so acknowledge() commits only this offset
                TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                OffsetAndMetadata offset = new OffsetAndMetadata(record.offset() + 1);
                pendingOffsets.put(request.requestId(), Collections.singletonMap(tp, offset));
                LOG.debug("Dequeued request {} from topic {}", request.requestId(), config.topicPrefix() + queueName);
                return request;
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to dequeue work request from queue: " + queueName, e);
        }
    }

    @Override
    public void acknowledge(String queueName, String requestId) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        KafkaConsumer<String, String> consumer = consumers.get(queueName);
        if (consumer != null) {
            Map<TopicPartition, OffsetAndMetadata> offsets = pendingOffsets.remove(requestId);
            if (offsets != null) {
                consumer.commitSync(offsets);
            } else {
                consumer.commitSync();
            }
            LOG.debug("Acknowledged request {} on queue {}", requestId, queueName);
        }
    }

    /**
     * Closes the producer and all consumer instances.
     */
    @Override
    public void close() {
        producer.close();
        consumers.values().forEach(KafkaConsumer::close);
        consumers.clear();
        LOG.debug("Closed KafkaRequestQueue");
    }

    /**
     * Returns the consumer map. Package-private for testing.
     *
     * @return the consumer map; never null
     */
    ConcurrentHashMap<String, KafkaConsumer<String, String>> consumers() {
        return consumers;
    }

    /**
     * Registers a consumer for the given queue name. Package-private for testing.
     *
     * @param queueName the queue name
     * @param consumer  the consumer to register
     */
    void registerConsumer(String queueName, KafkaConsumer<String, String> consumer) {
        consumers.put(queueName, consumer);
    }

    private KafkaConsumer<String, String> getOrCreateConsumer(String queueName) {
        return consumers.computeIfAbsent(queueName, name -> {
            String topic = config.topicPrefix() + name;
            KafkaConsumer<String, String> consumer = createConsumer(config);
            consumer.subscribe(List.of(topic));
            LOG.debug("Created consumer for topic {}", topic);
            return consumer;
        });
    }

    private static KafkaProducer<String, String> createProducer(KafkaTransportConfig config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.producerProperties().forEach(props::put);
        return new KafkaProducer<>(props);
    }

    private static KafkaConsumer<String, String> createConsumer(KafkaTransportConfig config) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.consumerGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        config.consumerProperties().forEach(props::put);
        return new KafkaConsumer<>(props);
    }
}
