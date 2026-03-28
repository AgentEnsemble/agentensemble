package net.agentensemble.network.transport.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import net.agentensemble.web.protocol.WorkRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("unchecked")
class KafkaRequestQueueTest {

    private static final KafkaTransportConfig CONFIG = KafkaTransportConfig.builder()
            .bootstrapServers("localhost:9092")
            .consumerGroupId("test-group")
            .topicPrefix("test.")
            .build();

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private KafkaProducer<String, String> mockProducer;
    private KafkaRequestQueue queue;

    @BeforeEach
    void setUp() {
        mockProducer = mock(KafkaProducer.class);
        queue = new KafkaRequestQueue(CONFIG, mockProducer);
    }

    @AfterEach
    void tearDown() {
        // Don't call queue.close() since mockProducer is a mock
    }

    @Test
    void enqueue_producesToCorrectTopic() throws Exception {
        WorkRequest request =
                new WorkRequest("req-1", "ensemble-a", "task-1", "context", null, null, null, null, null, null, null);

        queue.enqueue("inbox", request);

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture());
        verify(mockProducer).flush();

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("test.inbox");
        assertThat(record.key()).isEqualTo("req-1");

        WorkRequest deserialized = mapper.readValue(record.value(), WorkRequest.class);
        assertThat(deserialized.requestId()).isEqualTo("req-1");
        assertThat(deserialized.task()).isEqualTo("task-1");
    }

    @Test
    void dequeue_returnsDeserializedRequest() throws Exception {
        WorkRequest original =
                new WorkRequest("req-2", "ensemble-b", "task-2", "ctx", null, null, null, null, null, null, null);
        String json = mapper.writeValueAsString(original);

        KafkaConsumer<String, String> mockConsumer = mock(KafkaConsumer.class);
        TopicPartition tp = new TopicPartition("test.inbox", 0);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test.inbox", 0, 0L, "req-2", json);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(tp, List.of(record)));
        when(mockConsumer.poll(any(Duration.class))).thenReturn(records);

        queue.registerConsumer("inbox", mockConsumer);

        WorkRequest result = queue.dequeue("inbox", Duration.ofSeconds(1));

        assertThat(result).isNotNull();
        assertThat(result.requestId()).isEqualTo("req-2");
        assertThat(result.from()).isEqualTo("ensemble-b");
        assertThat(result.task()).isEqualTo("task-2");
    }

    @Test
    void dequeue_emptyPoll_returnsNull() {
        KafkaConsumer<String, String> mockConsumer = mock(KafkaConsumer.class);
        when(mockConsumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());

        queue.registerConsumer("empty-queue", mockConsumer);

        WorkRequest result = queue.dequeue("empty-queue", Duration.ofMillis(100));

        assertThat(result).isNull();
    }

    @Test
    void acknowledge_commitsSyncOnConsumer() {
        KafkaConsumer<String, String> mockConsumer = mock(KafkaConsumer.class);
        queue.registerConsumer("ack-queue", mockConsumer);

        queue.acknowledge("ack-queue", "req-3");

        verify(mockConsumer).commitSync();
    }

    @Test
    void acknowledge_unknownQueue_doesNotThrow() {
        // Should not throw even when no consumer exists for the queue
        queue.acknowledge("unknown", "req-x");
    }

    @Test
    void close_closesProducerAndConsumers() {
        KafkaConsumer<String, String> mockConsumer1 = mock(KafkaConsumer.class);
        KafkaConsumer<String, String> mockConsumer2 = mock(KafkaConsumer.class);
        queue.registerConsumer("q1", mockConsumer1);
        queue.registerConsumer("q2", mockConsumer2);

        queue.close();

        verify(mockProducer).close();
        verify(mockConsumer1).close();
        verify(mockConsumer2).close();
        assertThat(queue.consumers()).isEmpty();
    }

    @Test
    void enqueue_nullQueueName_throws() {
        WorkRequest request = new WorkRequest("req-1", "ens", "task", null, null, null, null, null, null, null, null);
        assertThatNullPointerException().isThrownBy(() -> queue.enqueue(null, request));
    }

    @Test
    void enqueue_nullRequest_throws() {
        assertThatNullPointerException().isThrownBy(() -> queue.enqueue("q", null));
    }

    @Test
    void dequeue_nullQueueName_throws() {
        assertThatNullPointerException().isThrownBy(() -> queue.dequeue(null, Duration.ofSeconds(1)));
    }

    @Test
    void dequeue_nullTimeout_throws() {
        assertThatNullPointerException().isThrownBy(() -> queue.dequeue("q", null));
    }

    @Test
    void acknowledge_nullQueueName_throws() {
        assertThatNullPointerException().isThrownBy(() -> queue.acknowledge(null, "req"));
    }

    @Test
    void acknowledge_nullRequestId_throws() {
        assertThatNullPointerException().isThrownBy(() -> queue.acknowledge("q", null));
    }

    @Test
    void create_factoryMethod() {
        // Verifies the factory method signature -- cannot create a real producer without Kafka,
        // so we just test the constructor path via the mock-based constructor.
        KafkaRequestQueue fromMock = new KafkaRequestQueue(CONFIG, mockProducer);
        assertThat(fromMock).isNotNull();
    }
}
