package net.agentensemble.delegation;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/**
 * Immutable, typed contract describing a single delegation request.
 *
 * <p>A {@code DelegationRequest} is constructed internally by the framework each time an agent
 * delegates a subtask (via {@code AgentDelegationTool}) or the Manager delegates to a worker
 * (via {@code DelegateTaskTool}). It provides a structured, correlated representation of the
 * delegation that can be consumed by observability, audit, or custom tooling.
 *
 * <p>The {@link #taskId} is auto-generated as a UUID v4 when not explicitly provided via the
 * builder. This enables correlation with the matching {@link DelegationResponse}.
 *
 * <p>Build via the {@link DelegationRequest#builder()} factory:
 * <pre>
 * DelegationRequest request = DelegationRequest.builder()
 *     .agentRole("Analyst")
 *     .taskDescription("Analyse Q3 financial data")
 *     .priority(DelegationPriority.HIGH)
 *     .build();
 * // request.getTaskId() is auto-populated with a UUID
 * </pre>
 */
@Value
@Builder(toBuilder = true)
public class DelegationRequest {

    /**
     * Unique identifier for this delegation, auto-populated as a UUID v4 when not set.
     * Used to correlate with the matching {@link DelegationResponse#taskId()}.
     */
    @Builder.Default
    @NonNull
    String taskId = UUID.randomUUID().toString();

    /**
     * Role of the target agent that should execute the delegated task.
     * Must match a registered agent role (case-insensitive).
     */
    @NonNull
    String agentRole;

    /**
     * Human-readable description of the subtask for the target agent to complete.
     */
    @NonNull
    String taskDescription;

    /**
     * Optional key-value scope providing bounded context for this delegation.
     * May carry variables like project identifiers, time bounds, or domain constraints.
     * Defaults to an empty map when not set.
     */
    @Builder.Default
    Map<String, Object> scope = Collections.emptyMap();

    /**
     * Priority hint for this delegation.
     * Defaults to {@link DelegationPriority#NORMAL}.
     */
    @Builder.Default
    DelegationPriority priority = DelegationPriority.NORMAL;

    /**
     * Optional description of the expected output schema or format.
     * The framework does not enforce this schema; it is informational for the target agent.
     */
    String expectedOutputSchema;

    /**
     * Maximum number of times the framework should retry output parsing if structured
     * output is requested. Zero or negative means no retries.
     */
    @Builder.Default
    int maxOutputRetries = 0;

    /**
     * Arbitrary key-value metadata attached to this delegation for observability or
     * downstream processing. Defaults to an empty map when not set.
     */
    @Builder.Default
    Map<String, Object> metadata = Collections.emptyMap();
}
