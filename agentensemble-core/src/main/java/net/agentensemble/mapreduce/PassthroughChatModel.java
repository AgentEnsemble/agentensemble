package net.agentensemble.mapreduce;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

/**
 * A {@link ChatModel} that returns a fixed, pre-determined response.
 *
 * <p>Used by {@link MapReduceAdaptiveExecutor} to create "carrier" tasks that propagate
 * output text from one reduction level to the next. Each carrier task is backed by a
 * {@code PassthroughChatModel} whose response is the task output from the previous level.
 *
 * <p>When the {@code AgentExecutor} calls {@link #chat}, it receives a plain text response
 * with no tool calls, which causes the agent to treat the fixed text as its final answer.
 * The framework's context mechanism then makes this output available to any downstream
 * tasks that list the carrier task in their {@code context} list.
 *
 * <p>The returned {@link ChatResponse} sets {@code TokenUsage} to zero, so the
 * carrier task's {@code TaskMetrics} will have {@code outputTokens = 0}. This
 * is intentional: carrier tasks do not represent actual LLM calls, and their token counts
 * should not inflate the aggregated execution metrics.
 *
 * <p>This class overrides {@code doChat(ChatRequest)} rather than {@code chat(ChatRequest)}
 * to align with the LangChain4j 1.x API convention where {@code doChat} is the intended
 * override point. The default {@code chat} implementation handles listener notification and
 * parameter merging before delegating to {@code doChat}.
 */
final class PassthroughChatModel implements ChatModel {

    private final String fixedResponse;

    PassthroughChatModel(String fixedResponse) {
        this.fixedResponse = fixedResponse;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(fixedResponse))
                .finishReason(FinishReason.STOP)
                .tokenUsage(new TokenUsage(0, 0, 0))
                .build();
    }
}
