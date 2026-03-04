package net.agentensemble.delegation;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Immutable record describing the outcome of a completed delegation.
 *
 * <p>A {@code DelegationResponse} is produced internally by the framework after each
 * delegation attempt, whether via {@code AgentDelegationTool} (peer delegation) or
 * {@code DelegateTaskTool} (hierarchical delegation). It correlates with the originating
 * {@link DelegationRequest} through the shared {@link #taskId}.
 *
 * <p>Callers can access all responses after execution via
 * {@code AgentDelegationTool.getDelegationResponses()} or
 * {@code DelegateTaskTool.getDelegationResponses()}.
 *
 * @param taskId       correlation ID matching the originating {@link DelegationRequest#getTaskId()}
 * @param status       outcome of the delegation
 * @param workerRole   role of the agent that executed the delegated task
 * @param rawOutput    raw text output produced by the worker agent; may be null on failure
 * @param parsedOutput parsed Java object when the task was configured with a structured output type;
 *                     null when no structured output was requested or parsing was not performed
 * @param artifacts    named artefacts produced during the delegation (file paths, data blobs, etc.);
 *                     empty map when none were produced
 * @param errors       error messages accumulated during the delegation; empty list on success
 * @param metadata     arbitrary key-value metadata for observability or downstream processing;
 *                     empty map when none was attached
 * @param duration     wall-clock time from delegation start to completion
 */
public record DelegationResponse(
        String taskId,
        DelegationStatus status,
        String workerRole,
        String rawOutput,
        Object parsedOutput,
        Map<String, Object> artifacts,
        List<String> errors,
        Map<String, Object> metadata,
        Duration duration) {}
