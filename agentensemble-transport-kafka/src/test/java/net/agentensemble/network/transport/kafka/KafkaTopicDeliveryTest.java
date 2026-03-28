package net.agentensemble.network.transport.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("unchecked")
class KafkaTopicDeliveryTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private KafkaProducer<String, String> mockProducer;
    private KafkaTopicDelivery delivery;

    @BeforeEach
    void setUp() {
        mockProducer = mock(KafkaProducer.class);
        delivery = new KafkaTopicDelivery(mockProducer);
    }

    @Test
    void method_returnsTopic() {
        assertThat(delivery.method()).isEqualTo(DeliveryMethod.TOPIC);
    }

    @Test
    void deliver_producesToAddress() throws Exception {
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "result-data", null, 42L);
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.TOPIC, "response-topic");

        delivery.deliver(spec, response);

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture());
        verify(mockProducer).flush();

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("response-topic");
        assertThat(record.key()).isEqualTo("req-1");

        WorkResponse deserialized = mapper.readValue(record.value(), WorkResponse.class);
        assertThat(deserialized.requestId()).isEqualTo("req-1");
        assertThat(deserialized.status()).isEqualTo("COMPLETED");
        assertThat(deserialized.result()).isEqualTo("result-data");
    }

    @Test
    void deliver_nullAddress_throwsIAE() {
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "result", null, null);
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.TOPIC, null);

        assertThatIllegalArgumentException().isThrownBy(() -> delivery.deliver(spec, response));
    }

    @Test
    void deliver_blankAddress_throwsIAE() {
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "result", null, null);
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.TOPIC, "   ");

        assertThatIllegalArgumentException().isThrownBy(() -> delivery.deliver(spec, response));
    }

    @Test
    void deliver_nullSpec_throws() {
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "result", null, null);
        assertThatNullPointerException().isThrownBy(() -> delivery.deliver(null, response));
    }

    @Test
    void deliver_nullResponse_throws() {
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.TOPIC, "topic");
        assertThatNullPointerException().isThrownBy(() -> delivery.deliver(spec, null));
    }

    @Test
    void close_closesProducer() {
        delivery.close();
        verify(mockProducer).close();
    }
}
