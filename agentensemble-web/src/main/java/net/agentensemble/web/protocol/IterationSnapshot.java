package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Pairs an {@link LlmIterationStartedMessage} with its corresponding
 * {@link LlmIterationCompletedMessage} for a single LLM ReAct iteration.
 *
 * <p>Used in the {@link HelloMessage#recentIterations()} field to provide
 * late-joining browser clients with conversation history for in-progress tasks.
 *
 * <p>The {@code completed} field may be {@code null} when the iteration is still
 * in progress (the LLM has been called but has not yet responded).
 *
 * @param started   the iteration-started message containing the message buffer sent to the LLM
 * @param completed the iteration-completed message containing the LLM response; null if pending
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IterationSnapshot(LlmIterationStartedMessage started, LlmIterationCompletedMessage completed) {}
