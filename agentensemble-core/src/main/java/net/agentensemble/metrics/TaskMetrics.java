package net.agentensemble.metrics;

import java.time.Duration;
import lombok.Builder;
import lombok.Value;

/**
 * Execution metrics captured for a single task.
 *
 * <p>Token counts use {@code -1} to indicate an unknown value (the LLM provider did not
 * populate usage metadata). A value of {@code 0} means zero tokens, not unknown.
 *
 * <p>Instances are immutable. Use {@link TaskMetrics#EMPTY} as a safe default when metrics
 * were not collected.
 *
 * <p>Available via {@code TaskOutput.getMetrics()}.
 */
@Value
@Builder
public class TaskMetrics {

    /**
     * Number of input (prompt) tokens consumed across all LLM calls for this task.
     * {@code -1} when the provider did not return usage data.
     */
    @Builder.Default
    long inputTokens = -1;

    /**
     * Number of output (completion) tokens produced across all LLM calls for this task.
     * {@code -1} when the provider did not return usage data.
     */
    @Builder.Default
    long outputTokens = -1;

    /**
     * Total tokens ({@code inputTokens + outputTokens}).
     * {@code -1} when either component is unknown.
     */
    @Builder.Default
    long totalTokens = -1;

    /** Cumulative time spent waiting for LLM responses across all ReAct iterations. */
    @Builder.Default
    Duration llmLatency = Duration.ZERO;

    /** Cumulative time spent executing tools across all ReAct iterations. */
    @Builder.Default
    Duration toolExecutionTime = Duration.ZERO;

    /** Time spent querying memory stores (long-term and entity retrieval). */
    @Builder.Default
    Duration memoryRetrievalTime = Duration.ZERO;

    /** Time spent building the system and user prompts before the first LLM call. */
    @Builder.Default
    Duration promptBuildTime = Duration.ZERO;

    /** Number of LLM chat() round-trips (ReAct iterations) for this task. */
    int llmCallCount;

    /**
     * Number of tool invocations (includes delegation tool calls).
     * Mirrors {@link net.agentensemble.task.TaskOutput#getToolCallCount()}.
     */
    int toolCallCount;

    /** Number of agent-to-agent delegation calls initiated by this agent. */
    int delegationCount;

    /** Memory operation counts (all zeros when memory is not configured). */
    @Builder.Default
    MemoryOperationCounts memoryOperations = MemoryOperationCounts.ZERO;

    /**
     * Estimated monetary cost for this task's LLM usage.
     * {@code null} when no {@link CostConfiguration} is configured on the ensemble.
     */
    CostEstimate costEstimate;

    /**
     * A safe zero/empty instance used when metrics were not collected.
     * All durations are {@link Duration#ZERO}, all token counts are {@code -1},
     * and all other numeric fields are {@code 0}.
     */
    public static final TaskMetrics EMPTY = TaskMetrics.builder().build();
}
