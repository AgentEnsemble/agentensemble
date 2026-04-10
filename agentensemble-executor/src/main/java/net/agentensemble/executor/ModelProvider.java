package net.agentensemble.executor;

import dev.langchain4j.model.chat.ChatModel;

/**
 * Provides {@link ChatModel} instances to {@link TaskExecutor} and
 * {@link EnsembleExecutor} at execution time.
 *
 * <p>Implementations are configured on the worker side and are never serialized into workflow
 * history. Models are resolved by the name carried in {@code TaskRequest.getModelName()} or
 * {@code EnsembleRequest.getModelName()}. When no name is specified in the request,
 * {@link #getDefault()} is used.
 *
 * <p>The built-in {@link SimpleModelProvider} covers most use cases:
 *
 * <pre>
 * ModelProvider provider = SimpleModelProvider.builder()
 *     .model("gpt-4o-mini", cheapModel)
 *     .model("gpt-4o", premiumModel)
 *     .defaultModel(cheapModel)
 *     .build();
 *
 * TaskExecutor executor = new TaskExecutor(provider);
 * </pre>
 */
public interface ModelProvider {

    /**
     * Returns the default {@link ChatModel} used when a request does not specify a model name.
     *
     * @return the default model; never null
     * @throws IllegalStateException if no default model has been configured
     */
    ChatModel getDefault();

    /**
     * Returns the {@link ChatModel} registered under the given name.
     *
     * @param name the model name, as specified in {@code TaskRequest.getModelName()}
     * @return the model; never null
     * @throws IllegalArgumentException if no model is registered under {@code name}
     */
    ChatModel get(String name);
}
