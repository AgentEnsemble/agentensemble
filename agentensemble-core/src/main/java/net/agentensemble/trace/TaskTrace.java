package net.agentensemble.trace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;
import net.agentensemble.metrics.TaskMetrics;

/**
 * Complete execution trace for a single task within an ensemble run.
 *
 * <p>Contains every LLM call, every tool invocation, all delegation events, the exact
 * prompts sent, and the agent's final output. Together with {@link TaskMetrics} it
 * provides a full record suitable for debugging, cost analysis, and visualization.
 *
 * <p>Contained in {@link ExecutionTrace} and available directly via
 * {@code TaskOutput.getTrace()}.
 */
@Value
@Builder(toBuilder = true)
public class TaskTrace {

    /** Description of the task that was executed. */
    @NonNull
    String taskDescription;

    /** Expected output as configured on the task (for comparison with {@link #getFinalOutput()}). */
    @NonNull
    String expectedOutput;

    /** Role of the agent that executed this task. */
    @NonNull
    String agentRole;

    /** Wall-clock instant when the agent began executing. */
    @NonNull
    Instant startedAt;

    /** Wall-clock instant when the agent completed (or failed). */
    @NonNull
    Instant completedAt;

    /** Total elapsed time for this task ({@code completedAt - startedAt}). */
    @NonNull
    Duration duration;

    /**
     * The system and user prompts that were sent to the LLM.
     * {@code null} only in degenerate cases where prompt building failed before capture.
     */
    TaskPrompts prompts;

    /**
     * Ordered list of LLM interactions (ReAct iterations).
     * Each entry corresponds to one call to {@code ChatModel.chat()}, together with
     * the tool calls that were executed in response.
     */
    @Singular
    List<LlmInteraction> llmInteractions;

    /**
     * Delegations initiated by this agent during task execution.
     *
     * <p>Populated for peer delegations ({@code AgentDelegationTool}). Each entry includes
     * the worker's full execution trace. For hierarchical workflow
     * delegations via {@code DelegateTaskTool}, worker traces are accessible as separate
     * {@link TaskTrace} entries in {@link ExecutionTrace}.
     */
    @Singular
    List<DelegationTrace> delegations;

    /**
     * The agent's final text response to the task.
     * Empty string if the task failed before producing output.
     */
    @NonNull
    String finalOutput;

    /**
     * The parsed structured output object, when the task was configured with
     * {@link net.agentensemble.Task#getOutputType()}.
     * {@code null} when no structured output was configured or parsing failed.
     */
    Object parsedOutput;

    /** Metrics summary for this task (token counts, latency, costs, etc.). */
    @NonNull
    TaskMetrics metrics;

    /**
     * Optional caller-supplied metadata attached to this task trace.
     * Empty by default; usable by framework extensions or application code.
     */
    @Singular("metadataEntry")
    Map<String, Object> metadata;
}
