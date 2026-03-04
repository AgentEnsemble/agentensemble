package net.agentensemble.delegation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DelegationResponseTest {

    private DelegationResponse successResponse() {
        return new DelegationResponse(
                "task-id-1",
                DelegationStatus.SUCCESS,
                "Researcher",
                "Research results",
                null,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyMap(),
                Duration.ofMillis(500));
    }

    private DelegationResponse failureResponse() {
        return new DelegationResponse(
                "task-id-2",
                DelegationStatus.FAILURE,
                "Analyst",
                null,
                null,
                Collections.emptyMap(),
                List.of("Agent execution failed"),
                Collections.emptyMap(),
                Duration.ofMillis(100));
    }

    // ========================
    // Field accessor tests
    // ========================

    @Test
    void testTaskId_returnsProvided() {
        assertThat(successResponse().taskId()).isEqualTo("task-id-1");
    }

    @Test
    void testStatus_successResponse_returnsSuccess() {
        assertThat(successResponse().status()).isEqualTo(DelegationStatus.SUCCESS);
    }

    @Test
    void testStatus_failureResponse_returnsFailure() {
        assertThat(failureResponse().status()).isEqualTo(DelegationStatus.FAILURE);
    }

    @Test
    void testWorkerRole_returnsProvided() {
        assertThat(successResponse().workerRole()).isEqualTo("Researcher");
    }

    @Test
    void testRawOutput_successResponse_returnsOutput() {
        assertThat(successResponse().rawOutput()).isEqualTo("Research results");
    }

    @Test
    void testRawOutput_failureResponse_isNull() {
        assertThat(failureResponse().rawOutput()).isNull();
    }

    @Test
    void testParsedOutput_defaultsToNull() {
        assertThat(successResponse().parsedOutput()).isNull();
    }

    @Test
    void testArtifacts_emptyByDefault() {
        assertThat(successResponse().artifacts()).isEmpty();
    }

    @Test
    void testErrors_successResponse_isEmpty() {
        assertThat(successResponse().errors()).isEmpty();
    }

    @Test
    void testErrors_failureResponse_containsMessage() {
        assertThat(failureResponse().errors()).containsExactly("Agent execution failed");
    }

    @Test
    void testMetadata_emptyByDefault() {
        assertThat(successResponse().metadata()).isEmpty();
    }

    @Test
    void testDuration_returnsProvided() {
        assertThat(successResponse().duration()).isEqualTo(Duration.ofMillis(500));
    }

    // ========================
    // Record equality
    // ========================

    @Test
    void testEquals_sameFields_equal() {
        DelegationResponse r1 = new DelegationResponse(
                "id",
                DelegationStatus.SUCCESS,
                "Researcher",
                "output",
                null,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyMap(),
                Duration.ofMillis(200));
        DelegationResponse r2 = new DelegationResponse(
                "id",
                DelegationStatus.SUCCESS,
                "Researcher",
                "output",
                null,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyMap(),
                Duration.ofMillis(200));
        assertThat(r1).isEqualTo(r2);
    }

    @Test
    void testEquals_differentStatus_notEqual() {
        DelegationResponse r1 = new DelegationResponse(
                "id",
                DelegationStatus.SUCCESS,
                "Researcher",
                "output",
                null,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyMap(),
                Duration.ofMillis(200));
        DelegationResponse r2 = new DelegationResponse(
                "id",
                DelegationStatus.FAILURE,
                "Researcher",
                null,
                null,
                Collections.emptyMap(),
                List.of("error"),
                Collections.emptyMap(),
                Duration.ofMillis(200));
        assertThat(r1).isNotEqualTo(r2);
    }

    // ========================
    // With artifacts / metadata
    // ========================

    @Test
    void testArtifacts_canBePopulated() {
        DelegationResponse response = new DelegationResponse(
                "id",
                DelegationStatus.SUCCESS,
                "Researcher",
                "output",
                null,
                Map.of("report.pdf", "/tmp/report.pdf"),
                Collections.emptyList(),
                Collections.emptyMap(),
                Duration.ofMillis(300));

        assertThat(response.artifacts()).containsEntry("report.pdf", "/tmp/report.pdf");
    }

    @Test
    void testMetadata_canBePopulated() {
        DelegationResponse response = new DelegationResponse(
                "id",
                DelegationStatus.SUCCESS,
                "Researcher",
                "output",
                null,
                Collections.emptyMap(),
                Collections.emptyList(),
                Map.of("trace-id", "abc-123"),
                Duration.ofMillis(300));

        assertThat(response.metadata()).containsEntry("trace-id", "abc-123");
    }

    // ========================
    // Correlation with DelegationRequest
    // ========================

    @Test
    void testTaskId_correlatesWithRequest() {
        DelegationRequest request = DelegationRequest.builder()
                .taskId("corr-id")
                .agentRole("Analyst")
                .taskDescription("Task")
                .build();

        DelegationResponse response = new DelegationResponse(
                request.getTaskId(),
                DelegationStatus.SUCCESS,
                "Analyst",
                "output",
                null,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyMap(),
                Duration.ofMillis(100));

        assertThat(response.taskId()).isEqualTo(request.getTaskId());
    }
}
