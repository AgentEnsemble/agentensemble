package net.agentensemble.trace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;

/**
 * Record of a single LLM chat() call within the agent's ReAct loop.
 *
 * <p>Each call to {@code ChatModel.chat()} that the agent makes produces one
 * {@code LlmInteraction}. When the LLM requests tool calls, the corresponding
 * {@link ToolCallTrace} records are collected in the {@code toolCalls} list.
 * When the LLM produces a final answer, {@code responseText} contains the
 * response and {@code toolCalls} is empty.
 *
 * <p>Contained in {@link TaskTrace}.
 */
@Value
@Builder(toBuilder = true)
public class LlmInteraction {

    /**
     * Zero-based index of this interaction within the task's ReAct loop.
     * The first LLM call is index {@code 0}.
     */
    int iterationIndex;

    /** Wall-clock instant when the {@code chat()} request was sent to the LLM. */
    @NonNull
    Instant startedAt;

    /** Wall-clock instant when the LLM response was received. */
    @NonNull
    Instant completedAt;

    /** Elapsed time waiting for the LLM ({@code completedAt - startedAt}). */
    @NonNull
    Duration latency;

    /**
     * Number of input (prompt) tokens consumed by this LLM call.
     * {@code -1} when the provider did not return usage metadata.
     */
    @Builder.Default
    long inputTokens = -1;

    /**
     * Number of output (completion) tokens produced by this LLM call.
     * {@code -1} when the provider did not return usage metadata.
     */
    @Builder.Default
    long outputTokens = -1;

    /** Whether this interaction resulted in tool calls or a final answer. */
    @NonNull
    LlmResponseType responseType;

    /**
     * The LLM's final text response.
     * Non-null only when {@link LlmResponseType#FINAL_ANSWER}.
     */
    String responseText;

    /**
     * Tool calls requested by the LLM in this iteration, with their execution results.
     * Empty when {@link LlmResponseType#FINAL_ANSWER}.
     */
    @Singular
    List<ToolCallTrace> toolCalls;
}
