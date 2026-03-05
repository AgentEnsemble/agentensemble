package net.agentensemble.metrics;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import net.agentensemble.task.TaskOutput;

/**
 * Aggregated execution metrics for a complete ensemble run.
 *
 * <p>Summarises token consumption, timing breakdowns, and resource usage across all tasks.
 * Available via {@code EnsembleOutput.getMetrics()}.
 *
 * <p>Token total fields use {@code -1} to indicate that at least one contributing task had
 * an unknown token count (the LLM provider did not return usage metadata for that task). When
 * all tasks have known counts the value is the precise sum.
 */
@Value
@Builder
public class ExecutionMetrics {

    /**
     * Total input (prompt) tokens across all tasks.
     * {@code -1} when any task had an unknown count.
     */
    @Builder.Default
    long totalInputTokens = -1;

    /**
     * Total output (completion) tokens across all tasks.
     * {@code -1} when any task had an unknown count.
     */
    @Builder.Default
    long totalOutputTokens = -1;

    /**
     * Total tokens ({@code totalInputTokens + totalOutputTokens}).
     * {@code -1} when either component is unknown.
     */
    @Builder.Default
    long totalTokens = -1;

    /** Total time spent waiting for LLM responses across all tasks. */
    @Builder.Default
    Duration totalLlmLatency = Duration.ZERO;

    /** Total tool execution time across all tasks. */
    @Builder.Default
    Duration totalToolExecutionTime = Duration.ZERO;

    /** Total time spent querying memory stores across all tasks. */
    @Builder.Default
    Duration totalMemoryRetrievalTime = Duration.ZERO;

    /** Total prompt-building time across all tasks. */
    @Builder.Default
    Duration totalPromptBuildTime = Duration.ZERO;

    /** Total LLM chat() calls across all tasks. */
    int totalLlmCallCount;

    /** Total tool invocations across all tasks. */
    int totalToolCalls;

    /** Total agent-to-agent delegation calls across all tasks. */
    int totalDelegations;

    /** Aggregated memory operation counts across all tasks. */
    @Builder.Default
    MemoryOperationCounts memoryOperations = MemoryOperationCounts.ZERO;

    /**
     * Per-task metrics, keyed by agent role.
     *
     * <p>When multiple tasks share the same agent role, only the metrics from the
     * last task with that role are retained under that key. Access per-task metrics
     * directly via {@link net.agentensemble.ensemble.EnsembleOutput#getTaskOutputs()} when
     * per-task granularity matters.
     *
     * <p>The Lombok-generated builder provides both a bulk {@code taskMetrics(Map)} method
     * (used by {@link #from(java.util.List)}) and a singular {@code taskMetric(String, TaskMetrics)}
     * method for incremental addition. When using the singular form, later calls with the same
     * role key replace earlier entries (last-write-wins for duplicate roles).
     */
    @Singular("taskMetric")
    Map<String, TaskMetrics> taskMetrics;

    /**
     * Aggregated estimated cost across all tasks.
     * {@code null} when no {@link CostConfiguration} was configured.
     */
    CostEstimate totalCostEstimate;

    /**
     * A safe empty instance with zero durations, {@code -1} token counts, and empty maps.
     * Used when the run produced no task outputs.
     */
    public static final ExecutionMetrics EMPTY = ExecutionMetrics.builder().build();

    /**
     * Aggregate metrics from a list of {@link TaskOutput} instances.
     *
     * <p>The {@code taskMetrics} map is keyed by agent role; if multiple tasks share a role,
     * only the last one is stored. All timing and count fields are summed. Token counts follow
     * the {@code -1}-propagation rule: once any task returns {@code -1}, the aggregate is
     * {@code -1}.
     *
     * @param outputs the task outputs to aggregate; must not be {@code null}
     * @return aggregated metrics
     */
    public static ExecutionMetrics from(List<TaskOutput> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return EMPTY;
        }

        long totalIn = 0;
        long totalOut = 0;
        boolean inputUnknown = false;
        boolean outputUnknown = false;
        Duration llmLatency = Duration.ZERO;
        Duration toolTime = Duration.ZERO;
        Duration memTime = Duration.ZERO;
        Duration promptTime = Duration.ZERO;
        int llmCalls = 0;
        int toolCalls = 0;
        int delegations = 0;
        MemoryOperationCounts memOps = MemoryOperationCounts.ZERO;
        Map<String, TaskMetrics> byRole = new LinkedHashMap<>();
        CostEstimate totalCost = null;

        for (TaskOutput output : outputs) {
            TaskMetrics tm = output.getMetrics();

            if (tm.getInputTokens() < 0) {
                inputUnknown = true;
            } else {
                totalIn += tm.getInputTokens();
            }
            if (tm.getOutputTokens() < 0) {
                outputUnknown = true;
            } else {
                totalOut += tm.getOutputTokens();
            }

            llmLatency = llmLatency.plus(tm.getLlmLatency());
            toolTime = toolTime.plus(tm.getToolExecutionTime());
            memTime = memTime.plus(tm.getMemoryRetrievalTime());
            promptTime = promptTime.plus(tm.getPromptBuildTime());
            llmCalls += tm.getLlmCallCount();
            toolCalls += tm.getToolCallCount();
            delegations += tm.getDelegationCount();
            memOps = memOps.add(tm.getMemoryOperations());
            byRole.put(output.getAgentRole(), tm);

            if (tm.getCostEstimate() != null) {
                totalCost = totalCost == null ? tm.getCostEstimate() : totalCost.add(tm.getCostEstimate());
            }
        }

        long aggIn = inputUnknown ? -1L : totalIn;
        long aggOut = outputUnknown ? -1L : totalOut;
        long aggTotal = (aggIn < 0 || aggOut < 0) ? -1L : aggIn + aggOut;

        return ExecutionMetrics.builder()
                .totalInputTokens(aggIn)
                .totalOutputTokens(aggOut)
                .totalTokens(aggTotal)
                .totalLlmLatency(llmLatency)
                .totalToolExecutionTime(toolTime)
                .totalMemoryRetrievalTime(memTime)
                .totalPromptBuildTime(promptTime)
                .totalLlmCallCount(llmCalls)
                .totalToolCalls(toolCalls)
                .totalDelegations(delegations)
                .memoryOperations(memOps)
                .taskMetrics(Map.copyOf(byRole))
                .totalCostEstimate(totalCost)
                .build();
    }
}
