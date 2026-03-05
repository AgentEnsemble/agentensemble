package net.agentensemble.callback;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import net.agentensemble.delegation.DelegationRequest;
import net.agentensemble.delegation.DelegationResponse;
import net.agentensemble.delegation.DelegationStatus;
import org.junit.jupiter.api.Test;

class DelegationEventsTest {

    private static DelegationRequest sampleRequest() {
        return DelegationRequest.builder()
                .agentRole("Analyst")
                .taskDescription("Analyse Q3 data")
                .build();
    }

    private static DelegationResponse sampleSuccessResponse(String taskId) {
        return new DelegationResponse(
                taskId,
                DelegationStatus.SUCCESS,
                "Analyst",
                "analysis complete",
                null,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyMap(),
                Duration.ofMillis(200));
    }

    private static DelegationResponse sampleFailureResponse(String taskId) {
        return new DelegationResponse(
                taskId,
                DelegationStatus.FAILURE,
                "Analyst",
                null,
                null,
                Collections.emptyMap(),
                List.of("error"),
                Collections.emptyMap(),
                Duration.ofMillis(10));
    }

    // ========================
    // DelegationStartedEvent
    // ========================

    @Test
    void delegationStartedEvent_storesAllFields() {
        DelegationRequest req = sampleRequest();
        DelegationStartedEvent event =
                new DelegationStartedEvent("task-id-1", "Manager", "Analyst", "Analyse Q3 data", 1, req);

        assertThat(event.delegationId()).isEqualTo("task-id-1");
        assertThat(event.delegatingAgentRole()).isEqualTo("Manager");
        assertThat(event.workerRole()).isEqualTo("Analyst");
        assertThat(event.taskDescription()).isEqualTo("Analyse Q3 data");
        assertThat(event.delegationDepth()).isEqualTo(1);
        assertThat(event.request()).isSameAs(req);
    }

    @Test
    void delegationStartedEvent_recordEquality() {
        DelegationRequest req = sampleRequest();
        DelegationStartedEvent e1 = new DelegationStartedEvent("id", "Manager", "Analyst", "task", 1, req);
        DelegationStartedEvent e2 = new DelegationStartedEvent("id", "Manager", "Analyst", "task", 1, req);

        assertThat(e1).isEqualTo(e2);
        assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
    }

    // ========================
    // DelegationCompletedEvent
    // ========================

    @Test
    void delegationCompletedEvent_storesAllFields() {
        DelegationResponse resp = sampleSuccessResponse("task-id-2");
        DelegationCompletedEvent event =
                new DelegationCompletedEvent("task-id-2", "Manager", "Analyst", resp, Duration.ofMillis(150));

        assertThat(event.delegationId()).isEqualTo("task-id-2");
        assertThat(event.delegatingAgentRole()).isEqualTo("Manager");
        assertThat(event.workerRole()).isEqualTo("Analyst");
        assertThat(event.response()).isSameAs(resp);
        assertThat(event.duration()).isEqualTo(Duration.ofMillis(150));
    }

    @Test
    void delegationCompletedEvent_recordEquality() {
        DelegationResponse resp = sampleSuccessResponse("id");
        Duration dur = Duration.ofMillis(100);
        DelegationCompletedEvent e1 = new DelegationCompletedEvent("id", "M", "A", resp, dur);
        DelegationCompletedEvent e2 = new DelegationCompletedEvent("id", "M", "A", resp, dur);

        assertThat(e1).isEqualTo(e2);
    }

    // ========================
    // DelegationFailedEvent
    // ========================

    @Test
    void delegationFailedEvent_storesAllFields() {
        DelegationResponse resp = sampleFailureResponse("task-id-3");
        RuntimeException cause = new RuntimeException("oops");
        DelegationFailedEvent event = new DelegationFailedEvent(
                "task-id-3", "Manager", "Analyst", "worker threw exception", cause, resp, Duration.ofMillis(30));

        assertThat(event.delegationId()).isEqualTo("task-id-3");
        assertThat(event.delegatingAgentRole()).isEqualTo("Manager");
        assertThat(event.workerRole()).isEqualTo("Analyst");
        assertThat(event.failureReason()).isEqualTo("worker threw exception");
        assertThat(event.cause()).isSameAs(cause);
        assertThat(event.response()).isSameAs(resp);
        assertThat(event.duration()).isEqualTo(Duration.ofMillis(30));
    }

    @Test
    void delegationFailedEvent_nullCause_allowedForGuardFailures() {
        DelegationResponse resp = sampleFailureResponse("id");
        // Guard/policy failures have no exception cause
        DelegationFailedEvent event =
                new DelegationFailedEvent("id", "Manager", "Analyst", "depth limit", null, resp, Duration.ZERO);

        assertThat(event.cause()).isNull();
        assertThat(event.failureReason()).isEqualTo("depth limit");
    }
}
