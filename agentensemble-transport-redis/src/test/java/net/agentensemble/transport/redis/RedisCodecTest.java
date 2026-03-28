package net.agentensemble.transport.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.time.Duration;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.Priority;
import net.agentensemble.web.protocol.TraceContext;
import net.agentensemble.web.protocol.WorkRequest;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RedisCodec}.
 */
class RedisCodecTest {

    private final RedisCodec codec = new RedisCodec();

    // ========================
    // WorkRequest round-trip
    // ========================

    @Test
    void workRequest_minimalFields_roundTrip() {
        WorkRequest original = new WorkRequest("req-1", "kitchen", "cook", null, null, null, null, null, null, null);

        String json = codec.serialize(original);
        WorkRequest restored = codec.deserialize(json, WorkRequest.class);

        assertThat(restored.requestId()).isEqualTo("req-1");
        assertThat(restored.from()).isEqualTo("kitchen");
        assertThat(restored.task()).isEqualTo("cook");
        assertThat(restored.priority()).isEqualTo(Priority.NORMAL); // default
    }

    @Test
    void workRequest_allFields_roundTrip() {
        WorkRequest original = new WorkRequest(
                "req-2",
                "maintenance",
                "repair",
                "Fix the boiler",
                Priority.HIGH,
                Duration.ofMinutes(10),
                new DeliverySpec(DeliveryMethod.QUEUE, "maintenance.results"),
                new TraceContext("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "vendor=value"),
                null,
                "cache-key-1");

        String json = codec.serialize(original);
        WorkRequest restored = codec.deserialize(json, WorkRequest.class);

        assertThat(restored.requestId()).isEqualTo("req-2");
        assertThat(restored.from()).isEqualTo("maintenance");
        assertThat(restored.task()).isEqualTo("repair");
        assertThat(restored.context()).isEqualTo("Fix the boiler");
        assertThat(restored.priority()).isEqualTo(Priority.HIGH);
        assertThat(restored.deadline()).isEqualTo(Duration.ofMinutes(10));
        assertThat(restored.delivery().method()).isEqualTo(DeliveryMethod.QUEUE);
        assertThat(restored.delivery().address()).isEqualTo("maintenance.results");
        assertThat(restored.cacheKey()).isEqualTo("cache-key-1");
    }

    // ========================
    // WorkResponse round-trip
    // ========================

    @Test
    void workResponse_success_roundTrip() {
        WorkResponse original = new WorkResponse("req-3", "COMPLETED", "output data", null, 1500L);

        String json = codec.serialize(original);
        WorkResponse restored = codec.deserialize(json, WorkResponse.class);

        assertThat(restored.requestId()).isEqualTo("req-3");
        assertThat(restored.status()).isEqualTo("COMPLETED");
        assertThat(restored.result()).isEqualTo("output data");
        assertThat(restored.error()).isNull();
        assertThat(restored.durationMs()).isEqualTo(1500L);
    }

    @Test
    void workResponse_failure_roundTrip() {
        WorkResponse original = new WorkResponse("req-4", "FAILED", null, "timeout", 5000L);

        String json = codec.serialize(original);
        WorkResponse restored = codec.deserialize(json, WorkResponse.class);

        assertThat(restored.requestId()).isEqualTo("req-4");
        assertThat(restored.status()).isEqualTo("FAILED");
        assertThat(restored.result()).isNull();
        assertThat(restored.error()).isEqualTo("timeout");
    }

    // ========================
    // Duration handling
    // ========================

    @Test
    void duration_serializesAsNumericSeconds() {
        WorkRequest original =
                new WorkRequest("req-5", "test", "task", null, null, Duration.ofSeconds(90), null, null, null, null);

        String json = codec.serialize(original);
        assertThat(json).contains("90"); // Duration serialized as numeric

        WorkRequest restored = codec.deserialize(json, WorkRequest.class);
        assertThat(restored.deadline()).isEqualTo(Duration.ofSeconds(90));
    }

    // ========================
    // Unknown fields tolerance
    // ========================

    @Test
    void deserialize_unknownFields_ignored() {
        String json = "{\"requestId\":\"req-6\",\"status\":\"COMPLETED\",\"unknownField\":\"value\"}";

        WorkResponse restored = codec.deserialize(json, WorkResponse.class);
        assertThat(restored.requestId()).isEqualTo("req-6");
        assertThat(restored.status()).isEqualTo("COMPLETED");
    }

    // ========================
    // Error handling
    // ========================

    @Test
    void deserialize_invalidJson_throwsUncheckedIOException() {
        assertThatThrownBy(() -> codec.deserialize("not json", WorkResponse.class))
                .isInstanceOf(UncheckedIOException.class);
    }
}
