package net.agentensemble.trace.internal;

import dev.langchain4j.model.output.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.metrics.CostConfiguration;
import net.agentensemble.metrics.CostEstimate;
import net.agentensemble.metrics.MemoryOperationCounts;
import net.agentensemble.metrics.TaskMetrics;
import net.agentensemble.trace.DelegationTrace;
import net.agentensemble.trace.LlmInteraction;
import net.agentensemble.trace.LlmResponseType;
import net.agentensemble.trace.TaskPrompts;
import net.agentensemble.trace.TaskTrace;
import net.agentensemble.trace.ToolCallTrace;

/**
 * Mutable accumulator that collects trace and metrics data during a single task execution.
 *
 * <p>An instance is created at the start of {@link net.agentensemble.agent.AgentExecutor#execute}
 * and populated as the agent progresses through prompt building, LLM calls, tool executions,
 * and delegations. At the end of execution, {@link #buildTrace} and {@link #buildMetrics}
 * freeze the collected data into immutable value objects.
 *
 * <p>This class is <em>not</em> thread-safe and is intended to be used exclusively from the
 * thread that drives the agent's ReAct loop. Tool call results accumulated via
 * {@link #addToolCallToCurrentIteration} are always added from the main thread after
 * parallel futures complete.
 *
 * <p>This class is an internal implementation detail; it is not part of the public API.
 */
public final class TaskTraceAccumulator {

    private final String agentRole;
    private final String taskDescription;
    private final String expectedOutput;
    private final Instant startedAt;

    // Prompt
    private TaskPrompts prompts;
    private Duration promptBuildTime = Duration.ZERO;

    // LLM call state (per-iteration)
    private int nextIterationIndex = 0;
    private Instant currentLlmStart;
    private Instant currentLlmEnd;
    private long currentInputTokens = -1;
    private long currentOutputTokens = -1;
    private final List<ToolCallTrace> currentIterationTools = new ArrayList<>();

    // Completed LLM interactions
    private final List<LlmInteraction> interactions = new ArrayList<>();

    // Delegations
    private final List<DelegationTrace> delegations = new ArrayList<>();

    // Aggregated metrics
    private long totalInputTokens = 0;
    private long totalOutputTokens = 0;
    private boolean inputTokensUnknown = false;
    private boolean outputTokensUnknown = false;
    private long llmLatencyNanos = 0;
    private long toolTimeNanos = 0;
    private int llmCallCount = 0;
    private int toolCallCount = 0;
    private int delegationCount = 0;

    // Memory operations
    private int stmWrites = 0;
    private int ltmStores = 0;
    private int ltmRetrievals = 0;
    private int entityLookups = 0;
    private long memoryRetrievalTimeNanos = 0;

    /**
     * Create an accumulator for the given task.
     *
     * @param agentRole       the role of the agent executing the task
     * @param taskDescription the task description
     * @param expectedOutput  the expected output as configured on the task
     * @param startedAt       wall-clock time when the agent began executing
     */
    public TaskTraceAccumulator(String agentRole, String taskDescription, String expectedOutput, Instant startedAt) {
        this.agentRole = agentRole;
        this.taskDescription = taskDescription;
        this.expectedOutput = expectedOutput;
        this.startedAt = startedAt;
    }

    // ========================
    // Prompt recording
    // ========================

    /**
     * Record the prompts that were built before the first LLM call.
     *
     * @param systemPrompt    the system prompt text
     * @param userPrompt      the user prompt text
     * @param buildTime       time spent building the prompts
     */
    public void recordPrompts(String systemPrompt, String userPrompt, Duration buildTime) {
        this.prompts = TaskPrompts.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .build();
        this.promptBuildTime = buildTime;
    }

    // ========================
    // LLM call lifecycle
    // ========================

    /**
     * Signal the start of an LLM chat() call.
     *
     * @param start the time the call was initiated
     */
    public void beginLlmCall(Instant start) {
        this.currentLlmStart = start;
        this.currentIterationTools.clear();
        this.currentInputTokens = -1;
        this.currentOutputTokens = -1;
    }

    /**
     * Record the completion of an LLM chat() call and capture token usage.
     *
     * @param end        the time the response was received
     * @param tokenUsage usage metadata from the response; may be {@code null}
     */
    public void endLlmCall(Instant end, TokenUsage tokenUsage) {
        this.currentLlmEnd = end;
        Duration latency = Duration.between(currentLlmStart, end);
        llmLatencyNanos += latency.toNanos();
        llmCallCount++;

        if (tokenUsage != null && tokenUsage.inputTokenCount() != null) {
            currentInputTokens = tokenUsage.inputTokenCount();
            totalInputTokens += currentInputTokens;
        } else {
            inputTokensUnknown = true;
            currentInputTokens = -1;
        }
        if (tokenUsage != null && tokenUsage.outputTokenCount() != null) {
            currentOutputTokens = tokenUsage.outputTokenCount();
            totalOutputTokens += currentOutputTokens;
        } else {
            outputTokensUnknown = true;
            currentOutputTokens = -1;
        }
    }

    /**
     * Add a tool call that was executed during the current ReAct iteration.
     *
     * <p>Must be called after {@link #endLlmCall} and before {@link #finalizeIteration}.
     * Always called from the main thread (after parallel futures complete).
     *
     * @param trace the completed tool call trace
     */
    public void addToolCallToCurrentIteration(ToolCallTrace trace) {
        currentIterationTools.add(trace);
        toolTimeNanos += trace.getDuration().toNanos();
        toolCallCount++;
    }

    /**
     * Seal the current LLM interaction and add it to the interaction list.
     *
     * <p>Must be called once per LLM call, after all tool calls for that iteration
     * have been added via {@link #addToolCallToCurrentIteration}.
     *
     * @param type         whether this produced tool calls or a final answer
     * @param responseText the final response text (non-null only for FINAL_ANSWER)
     */
    public void finalizeIteration(LlmResponseType type, String responseText) {
        LlmInteraction.LlmInteractionBuilder builder = LlmInteraction.builder()
                .iterationIndex(nextIterationIndex++)
                .startedAt(currentLlmStart)
                .completedAt(currentLlmEnd)
                .latency(Duration.between(currentLlmStart, currentLlmEnd))
                .inputTokens(currentInputTokens)
                .outputTokens(currentOutputTokens)
                .responseType(type)
                .responseText(responseText);
        for (ToolCallTrace tool : currentIterationTools) {
            builder.toolCall(tool);
        }
        interactions.add(builder.build());
        currentIterationTools.clear();
    }

    // ========================
    // Delegation recording
    // ========================

    /**
     * Record a completed delegation to a peer agent.
     *
     * @param trace the delegation trace; must not be {@code null}
     */
    public void addDelegation(DelegationTrace trace) {
        delegations.add(trace);
        delegationCount++;
    }

    // ========================
    // Memory operation recording
    // ========================

    /** Increment the short-term memory write counter. */
    public void incrementStmWrite() {
        stmWrites++;
    }

    /** Increment the long-term memory store counter. */
    public void incrementLtmStore() {
        ltmStores++;
    }

    /**
     * Increment the long-term memory retrieval counter and add retrieval time.
     *
     * @param retrievalTime time spent on the retrieval
     */
    public void incrementLtmRetrieval(Duration retrievalTime) {
        ltmRetrievals++;
        memoryRetrievalTimeNanos += retrievalTime.toNanos();
    }

    /**
     * Increment the entity memory lookup counter and add lookup time.
     *
     * @param lookupTime time spent on the lookup
     */
    public void incrementEntityLookup(Duration lookupTime) {
        entityLookups++;
        memoryRetrievalTimeNanos += lookupTime.toNanos();
    }

    // ========================
    // Build frozen results
    // ========================

    /**
     * Freeze the collected data into an immutable {@link TaskTrace}.
     *
     * @param finalOutput  the agent's text response
     * @param parsedOutput the structured parsed output (may be {@code null})
     * @param completedAt  wall-clock time when the agent finished
     * @param costConfig   optional cost configuration; may be {@code null}
     * @return the complete task trace
     */
    public TaskTrace buildTrace(
            String finalOutput, Object parsedOutput, Instant completedAt, CostConfiguration costConfig) {
        TaskMetrics metrics = buildMetrics(costConfig);
        Duration duration = Duration.between(startedAt, completedAt);

        TaskTrace.TaskTraceBuilder builder = TaskTrace.builder()
                .taskDescription(taskDescription)
                .expectedOutput(expectedOutput)
                .agentRole(agentRole)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .duration(duration)
                .prompts(prompts)
                .finalOutput(finalOutput != null ? finalOutput : "")
                .parsedOutput(parsedOutput)
                .metrics(metrics);

        for (LlmInteraction interaction : interactions) {
            builder.llmInteraction(interaction);
        }
        for (DelegationTrace delegation : delegations) {
            builder.delegation(delegation);
        }

        return builder.build();
    }

    /**
     * Build the metrics summary from accumulated data.
     *
     * @param costConfig optional cost configuration; may be {@code null}
     * @return the task metrics
     */
    public TaskMetrics buildMetrics(CostConfiguration costConfig) {
        long aggIn = inputTokensUnknown ? -1L : totalInputTokens;
        long aggOut = outputTokensUnknown ? -1L : totalOutputTokens;
        long aggTotal = (aggIn < 0 || aggOut < 0) ? -1L : aggIn + aggOut;

        CostEstimate costEstimate = null;
        if (costConfig != null) {
            costEstimate = costConfig.estimate(aggIn, aggOut);
        }

        MemoryOperationCounts memOps = MemoryOperationCounts.builder()
                .shortTermEntriesWritten(stmWrites)
                .longTermStores(ltmStores)
                .longTermRetrievals(ltmRetrievals)
                .entityLookups(entityLookups)
                .build();

        return TaskMetrics.builder()
                .inputTokens(aggIn)
                .outputTokens(aggOut)
                .totalTokens(aggTotal)
                .llmLatency(Duration.ofNanos(llmLatencyNanos))
                .toolExecutionTime(Duration.ofNanos(toolTimeNanos))
                .memoryRetrievalTime(Duration.ofNanos(memoryRetrievalTimeNanos))
                .promptBuildTime(promptBuildTime)
                .llmCallCount(llmCallCount)
                .toolCallCount(toolCallCount)
                .delegationCount(delegationCount)
                .memoryOperations(memOps)
                .costEstimate(costEstimate)
                .build();
    }
}
