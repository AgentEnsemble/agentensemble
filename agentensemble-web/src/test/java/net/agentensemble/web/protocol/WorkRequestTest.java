package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkRequest}.
 */
class WorkRequestTest {

    @Test
    void constructionWithAllFields() {
        TraceContext trace = new TraceContext("00-trace-span-01", null);
        DeliverySpec delivery = new DeliverySpec(DeliveryMethod.QUEUE, "results-queue");
        WorkRequest req = new WorkRequest(
                "req-1",
                "ensemble-a",
                "prepare-meal",
                "Make pasta",
                Priority.HIGH,
                Duration.ofMinutes(5),
                delivery,
                trace,
                CachePolicy.FORCE_FRESH,
                "cache-key-1",
                null);

        assertThat(req.requestId()).isEqualTo("req-1");
        assertThat(req.from()).isEqualTo("ensemble-a");
        assertThat(req.task()).isEqualTo("prepare-meal");
        assertThat(req.context()).isEqualTo("Make pasta");
        assertThat(req.priority()).isEqualTo(Priority.HIGH);
        assertThat(req.deadline()).isEqualTo(Duration.ofMinutes(5));
        assertThat(req.delivery()).isEqualTo(delivery);
        assertThat(req.traceContext()).isEqualTo(trace);
        assertThat(req.cachePolicy()).isEqualTo(CachePolicy.FORCE_FRESH);
        assertThat(req.cacheKey()).isEqualTo("cache-key-1");
    }

    @Test
    void nullRequestIdThrows() {
        assertThatThrownBy(() -> new WorkRequest(null, "from", "task", null, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullFromThrows() {
        assertThatThrownBy(() -> new WorkRequest("id", null, "task", null, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTaskThrows() {
        assertThatThrownBy(() -> new WorkRequest("id", "from", null, null, null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void defaultPriorityIsNormal() {
        WorkRequest req = new WorkRequest("id", "from", "task", null, null, null, null, null, null, null, null);
        assertThat(req.priority()).isEqualTo(Priority.NORMAL);
    }

    @Test
    void defaultDeliveryIsWebsocket() {
        WorkRequest req = new WorkRequest("id", "from", "task", null, null, null, null, null, null, null, null);
        assertThat(req.delivery().method()).isEqualTo(DeliveryMethod.WEBSOCKET);
        assertThat(req.delivery().address()).isNull();
    }

    @Test
    void jsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        WorkRequest original = new WorkRequest(
                "req-42",
                "ensemble-b",
                "check-inventory",
                "Check tomatoes",
                Priority.HIGH,
                Duration.ofSeconds(30),
                new DeliverySpec(DeliveryMethod.WEBHOOK, "https://example.com/hook"),
                new TraceContext("00-traceparent-01", "vendor=abc"),
                CachePolicy.USE_CACHED,
                "cache-42",
                null);

        String json = mapper.writeValueAsString(original);

        assertThat(json).contains("\"requestId\":\"req-42\"");
        assertThat(json).contains("\"from\":\"ensemble-b\"");
        assertThat(json).contains("\"task\":\"check-inventory\"");
        assertThat(json).contains("\"priority\":\"HIGH\"");

        WorkRequest deserialized = mapper.readValue(json, WorkRequest.class);
        assertThat(deserialized.requestId()).isEqualTo("req-42");
        assertThat(deserialized.from()).isEqualTo("ensemble-b");
        assertThat(deserialized.task()).isEqualTo("check-inventory");
        assertThat(deserialized.context()).isEqualTo("Check tomatoes");
        assertThat(deserialized.priority()).isEqualTo(Priority.HIGH);
        assertThat(deserialized.delivery().method()).isEqualTo(DeliveryMethod.WEBHOOK);
        assertThat(deserialized.traceContext().traceparent()).isEqualTo("00-traceparent-01");
        assertThat(deserialized.cachePolicy()).isEqualTo(CachePolicy.USE_CACHED);
        assertThat(deserialized.cacheKey()).isEqualTo("cache-42");
    }

    @Test
    void unknownFieldsIgnoredDuringDeserialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String json =
                """
                {
                    "requestId": "req-99",
                    "from": "ensemble-x",
                    "task": "do-something",
                    "futureField": "ignored-value",
                    "anotherFutureField": 999
                }
                """;

        WorkRequest deserialized = mapper.readValue(json, WorkRequest.class);
        assertThat(deserialized.requestId()).isEqualTo("req-99");
        assertThat(deserialized.from()).isEqualTo("ensemble-x");
        assertThat(deserialized.task()).isEqualTo("do-something");
        assertThat(deserialized.priority()).isEqualTo(Priority.NORMAL);
    }
}
