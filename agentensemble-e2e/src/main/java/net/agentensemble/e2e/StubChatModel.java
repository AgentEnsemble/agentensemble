package net.agentensemble.e2e;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic stub implementation of {@link ChatModel} for E2E test scenarios.
 *
 * <p>Returns canned text responses in order. When all configured responses have
 * been used, subsequent calls return the last configured response. This makes
 * tests deterministic and free of real LLM API calls or network dependencies.
 *
 * <p>Thread-safe: the response index is managed via {@link AtomicInteger}.
 */
class StubChatModel implements ChatModel {

    private final String[] responses;
    private final AtomicInteger index = new AtomicInteger(0);

    /**
     * Construct a stub model with the given canned responses.
     *
     * @param responses one or more responses to return in order; must not be null or empty
     */
    StubChatModel(String... responses) {
        if (responses == null || responses.length == 0) {
            throw new IllegalArgumentException("At least one response must be provided");
        }
        this.responses = responses.clone();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int i = index.getAndIncrement();
        String text = responses[Math.min(i, responses.length - 1)];
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }
}
