package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkResponse}.
 */
class WorkResponseTest {

    @Test
    void constructionWithAllFields() {
        WorkResponse resp = new WorkResponse("req-1", "COMPLETED", "output text", null, 1500L);

        assertThat(resp.requestId()).isEqualTo("req-1");
        assertThat(resp.status()).isEqualTo("COMPLETED");
        assertThat(resp.result()).isEqualTo("output text");
        assertThat(resp.error()).isNull();
        assertThat(resp.durationMs()).isEqualTo(1500L);
    }

    @Test
    void constructionWithErrorFields() {
        WorkResponse resp = new WorkResponse("req-2", "FAILED", null, "Something broke", 200L);

        assertThat(resp.requestId()).isEqualTo("req-2");
        assertThat(resp.status()).isEqualTo("FAILED");
        assertThat(resp.result()).isNull();
        assertThat(resp.error()).isEqualTo("Something broke");
        assertThat(resp.durationMs()).isEqualTo(200L);
    }

    @Test
    void nullRequestIdThrows() {
        assertThatThrownBy(() -> new WorkResponse(null, "COMPLETED", null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("requestId");
    }

    @Test
    void nullStatusThrows() {
        assertThatThrownBy(() -> new WorkResponse("req-1", null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status");
    }

    @Test
    void nullOptionalFieldsAllowed() {
        WorkResponse resp = new WorkResponse("req-3", "REJECTED", null, null, null);

        assertThat(resp.result()).isNull();
        assertThat(resp.error()).isNull();
        assertThat(resp.durationMs()).isNull();
    }

    @Test
    void jsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        WorkResponse original = new WorkResponse("req-42", "COMPLETED", "Meal prepared", null, 25000L);

        String json = mapper.writeValueAsString(original);

        assertThat(json).contains("\"requestId\":\"req-42\"");
        assertThat(json).contains("\"status\":\"COMPLETED\"");
        assertThat(json).contains("\"result\":\"Meal prepared\"");
        assertThat(json).doesNotContain("\"error\"");

        WorkResponse deserialized = mapper.readValue(json, WorkResponse.class);
        assertThat(deserialized.requestId()).isEqualTo("req-42");
        assertThat(deserialized.status()).isEqualTo("COMPLETED");
        assertThat(deserialized.result()).isEqualTo("Meal prepared");
        assertThat(deserialized.error()).isNull();
        assertThat(deserialized.durationMs()).isEqualTo(25000L);
    }

    @Test
    void unknownFieldsIgnoredDuringDeserialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String json =
                """
                {
                    "requestId": "req-99",
                    "status": "FAILED",
                    "error": "timeout",
                    "futureField": "ignored-value",
                    "anotherFutureField": 999
                }
                """;

        WorkResponse deserialized = mapper.readValue(json, WorkResponse.class);
        assertThat(deserialized.requestId()).isEqualTo("req-99");
        assertThat(deserialized.status()).isEqualTo("FAILED");
        assertThat(deserialized.error()).isEqualTo("timeout");
        assertThat(deserialized.result()).isNull();
    }
}
