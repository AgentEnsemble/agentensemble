package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Server-to-client message containing the final result of a completed ensemble run.
 *
 * <p>Sent to the originating session when a run finishes — whether successfully,
 * with an error, or cancelled. The existing {@link EnsembleCompletedMessage} continues
 * to broadcast to <em>all</em> connected sessions (for viz compatibility); this message
 * carries the full task outputs and is targeted only at the submitting session.
 *
 * @param runId      the unique run identifier
 * @param status     the final status: {@code "COMPLETED"}, {@code "FAILED"}, or
 *                   {@code "CANCELLED"}
 * @param outputs    the task outputs in execution order; may be empty on failure
 * @param durationMs total run duration in milliseconds
 * @param metrics    aggregated execution metrics, or {@code null} if not collected
 * @param error      error message if the run failed, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunResultMessage(
        String runId, String status, List<TaskOutputDto> outputs, Long durationMs, MetricsDto metrics, String error)
        implements ServerMessage {

    /**
     * Snapshot of a single task's output within a run result.
     *
     * @param taskName   the task description (used as the name in Phase 1 before
     *                   the optional {@code name} field is added to {@code Task})
     * @param output     the raw text output from the agent
     * @param durationMs how long the task took in milliseconds
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskOutputDto(String taskName, String output, Long durationMs) {}

    /**
     * Aggregated execution metrics for an entire run.
     *
     * @param totalTokens    total tokens consumed across all tasks ({@code -1} if unknown)
     * @param totalToolCalls total tool invocations across all tasks
     */
    public record MetricsDto(long totalTokens, long totalToolCalls) {}
}
