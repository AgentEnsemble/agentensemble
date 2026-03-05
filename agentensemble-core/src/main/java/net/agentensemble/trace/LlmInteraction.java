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
 * <p>When {@link CaptureMode#STANDARD} or higher is active, the {@code messages} list
 * is populated with the complete message history that was sent to the LLM for this iteration.
 * This enables a consumer to replay the exact conversation the LLM had, step by step.
 * At {@link CaptureMode#OFF}, {@code messages} is always empty.
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

    /**
     * Complete message history sent to the LLM for this iteration.
     *
     * <p>Populated when {@link CaptureMode#STANDARD} or higher is active. Contains every
     * message in the conversation buffer at the time of the chat() call: the initial system
     * and user messages, any prior assistant+tool turns from earlier iterations, and for
     * multi-iteration runs the assistant/tool messages accumulated so far.
     *
     * <p>Empty list when {@link CaptureMode#OFF} (the default).
     *
     * <p>This field enables a visualization tool to replay the exact conversation the LLM
     * had, step by step, and to reconstruct the full ReAct reasoning chain.
     */
    @Singular
    List<CapturedMessage> messages;
}
