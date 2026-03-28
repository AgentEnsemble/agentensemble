package net.agentensemble.network.transport.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.agentensemble.network.transport.IngressSource;
import net.agentensemble.web.protocol.WorkRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka-backed {@link IngressSource} that subscribes to a Kafka topic and pushes
 * incoming {@link WorkRequest} objects to a consumer sink.
 *
 * <p>When {@link #start(Consumer)} is called, a virtual thread is started that continuously
 * polls the Kafka topic and deserializes records into {@code WorkRequest} objects. Call
 * {@link #stop()} to gracefully shut down the polling loop.
 *
 * @see IngressSource
 * @see KafkaTransportConfig
 */
public class KafkaTopicIngress implements IngressSource {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicIngress.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    private final KafkaTransportConfig config;
    private final String topicName;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile KafkaConsumer<String, String> consumer;
    private volatile Thread pollThread;

    /**
     * Creates a new Kafka topic ingress source.
     *
     * @param config    the Kafka transport configuration; must not be null
     * @param topicName the Kafka topic to subscribe to; must not be null
     */
    public KafkaTopicIngress(KafkaTransportConfig config, String topicName) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.topicName = Objects.requireNonNull(topicName, "topicName must not be null");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    /**
     * Package-private constructor for testing with a mock consumer.
     *
     * @param topicName the topic name
     * @param consumer  the mock consumer
     */
    KafkaTopicIngress(String topicName, KafkaConsumer<String, String> consumer) {
        this.config = null;
        this.topicName = Objects.requireNonNull(topicName, "topicName must not be null");
        this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    @Override
    public String name() {
        return "kafka-topic:" + topicName;
    }

    /**
     * Starts the ingress source, polling the Kafka topic on a virtual thread.
     *
     * @param sink consumer that receives incoming work requests; must not be null
     * @throws IllegalStateException if already started
     */
    @Override
    public void start(Consumer<WorkRequest> sink) {
        Objects.requireNonNull(sink, "sink must not be null");
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("KafkaTopicIngress is already running");
        }

        if (consumer == null && config != null) {
            consumer = createConsumer(config);
            consumer.subscribe(List.of(topicName));
        }

        pollThread = Thread.ofVirtual().name("kafka-ingress-" + topicName).start(() -> {
            LOG.info("Starting Kafka ingress for topic {}", topicName);
            try {
                while (running.get()) {
                    ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                    boolean allSucceeded = true;
                    for (ConsumerRecord<String, String> record : records) {
                        try {
                            WorkRequest request = mapper.readValue(record.value(), WorkRequest.class);
                            sink.accept(request);
                            LOG.debug("Ingested request {} from topic {}", request.requestId(), topicName);
                        } catch (Exception e) {
                            LOG.warn(
                                    "Failed to process record from topic {} at offset {}: {}",
                                    topicName,
                                    record.offset(),
                                    e.getMessage());
                            allSucceeded = false;
                        }
                    }
                    // Only commit if all records were successfully processed
                    if (!records.isEmpty() && allSucceeded) {
                        consumer.commitSync();
                    }
                }
            } catch (WakeupException e) {
                if (running.get()) {
                    LOG.warn("Unexpected wakeup on ingress consumer for topic {}", topicName);
                }
            } finally {
                LOG.info("Stopped Kafka ingress for topic {}", topicName);
            }
        });
    }

    /**
     * Stops the ingress source. Idempotent.
     */
    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (consumer != null) {
                consumer.wakeup();
            }
            if (pollThread != null) {
                try {
                    pollThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (consumer != null) {
                consumer.close();
                consumer = null;
            }
        }
    }

    /**
     * Returns whether the ingress source is currently running.
     *
     * @return true if running
     */
    boolean isRunning() {
        return running.get();
    }

    private static KafkaConsumer<String, String> createConsumer(KafkaTransportConfig config) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.consumerGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        config.consumerProperties().forEach(props::put);
        return new KafkaConsumer<>(props);
    }
}
