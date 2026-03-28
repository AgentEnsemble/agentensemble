package net.agentensemble.network.transport.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.web.protocol.WorkRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class KafkaTopicIngressTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void name_returnsKafkaTopicPrefixed() {
        KafkaConsumer<String, String> mockConsumer = mock(KafkaConsumer.class);
        KafkaTopicIngress ingress = new KafkaTopicIngress("my-topic", mockConsumer);

        assertThat(ingress.name()).isEqualTo("kafka-topic:my-topic");
    }

    @Test
    void stop_setsRunningFalse() {
        KafkaConsumer<String, String> mockConsumer = mock(KafkaConsumer.class);
        // Use thenAnswer to avoid re-stubbing issues with concurrent access
        when(mockConsumer.poll(any(Duration.class))).thenAnswer(inv -> {
            Thread.sleep(50);
            return ConsumerRecords.empty();
        });

        KafkaTopicIngress ingress = new KafkaTopicIngress("test-topic", mockConsumer);

        // Not started yet
        assertThat(ingress.isRunning()).isFalse();

        ingress.start(request -> {});

        assertThat(ingress.isRunning()).isTrue();

        ingress.stop();

        assertThat(ingress.isRunning()).isFalse();
    }

    @Test
    void start_deliversRecordsToSink() throws Exception {
        KafkaConsumer<String, String> mockConsumer = mock(KafkaConsumer.class);
        WorkRequest request =
                new WorkRequest("req-1", "ens-a", "task-1", "ctx", null, null, null, null, null, null, null);
        String json = mapper.writeValueAsString(request);

        TopicPartition tp = new TopicPartition("ingress-topic", 0);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("ingress-topic", 0, 0L, "req-1", json);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(tp, List.of(record)));

        CountDownLatch latch = new CountDownLatch(1);
        CopyOnWriteArrayList<WorkRequest> received = new CopyOnWriteArrayList<>();

        // Return records on first poll, then empty records on subsequent polls.
        // Use thenAnswer to avoid re-stubbing issues with concurrent access.
        java.util.concurrent.atomic.AtomicBoolean firstPoll = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(mockConsumer.poll(any(Duration.class))).thenAnswer(inv -> {
            if (firstPoll.compareAndSet(true, false)) {
                return records;
            }
            Thread.sleep(50);
            return ConsumerRecords.empty();
        });

        KafkaTopicIngress ingress = new KafkaTopicIngress("ingress-topic", mockConsumer);
        ingress.start(req -> {
            received.add(req);
            latch.countDown();
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).requestId()).isEqualTo("req-1");

        ingress.stop();
    }

    @Test
    void start_whenAlreadyRunning_throwsIllegalState() {
        KafkaConsumer<String, String> mockConsumer = mock(KafkaConsumer.class);
        // Use thenAnswer to avoid re-stubbing issues with concurrent access
        when(mockConsumer.poll(any(Duration.class))).thenAnswer(inv -> {
            Thread.sleep(50);
            return ConsumerRecords.empty();
        });

        KafkaTopicIngress ingress = new KafkaTopicIngress("topic", mockConsumer);
        ingress.start(request -> {});

        assertThatIllegalStateException().isThrownBy(() -> ingress.start(request -> {}));

        ingress.stop();
    }

    @Test
    void stop_whenNotStarted_isNoOp() {
        KafkaConsumer<String, String> mockConsumer = mock(KafkaConsumer.class);
        KafkaTopicIngress ingress = new KafkaTopicIngress("topic", mockConsumer);

        // Should not throw
        ingress.stop();
        assertThat(ingress.isRunning()).isFalse();
    }

    @Test
    void constructor_nullTopicName_throws() {
        KafkaConsumer<String, String> mockConsumer = mock(KafkaConsumer.class);
        assertThatNullPointerException().isThrownBy(() -> new KafkaTopicIngress((String) null, mockConsumer));
    }
}
