package net.agentensemble.executor;

import dev.langchain4j.model.chat.ChatModel;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A map-backed {@link ModelProvider} that resolves models by name.
 *
 * <p>Build an instance via the fluent {@link Builder}:
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
 *
 * <p>For simple setups with a single model, use the convenience factory:
 *
 * <pre>
 * ModelProvider provider = SimpleModelProvider.of(openAiModel);
 * </pre>
 */
public final class SimpleModelProvider implements ModelProvider {

    private final ChatModel defaultModel;
    private final Map<String, ChatModel> models;

    private SimpleModelProvider(ChatModel defaultModel, Map<String, ChatModel> models) {
        this.defaultModel = defaultModel;
        this.models = Collections.unmodifiableMap(new HashMap<>(models));
    }

    /**
     * Creates a provider with a single model used as the default. The model is not
     * accessible by name; use {@link #of(String, ChatModel)} or the builder when named
     * access is needed.
     *
     * @param model the model to use as default; must not be null
     * @return a provider backed by the given model
     * @throws NullPointerException if model is null
     */
    public static SimpleModelProvider of(ChatModel model) {
        Objects.requireNonNull(model, "model must not be null");
        return new SimpleModelProvider(model, new HashMap<>());
    }

    /**
     * Creates a provider with one named model that is also used as the default.
     *
     * @param name  the model name accessible via {@link #get(String)}
     * @param model the model instance; must not be null
     * @return a provider with the named model registered and set as default
     * @throws NullPointerException if name or model is null
     */
    public static SimpleModelProvider of(String name, ChatModel model) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Map<String, ChatModel> map = new HashMap<>();
        map.put(name, model);
        return new SimpleModelProvider(model, map);
    }

    /**
     * Returns a new builder for constructing a {@code SimpleModelProvider}.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ChatModel getDefault() {
        if (defaultModel == null) {
            throw new IllegalStateException(
                    "No default model configured. Register one via SimpleModelProvider.builder().defaultModel(...)");
        }
        return defaultModel;
    }

    @Override
    public ChatModel get(String name) {
        Objects.requireNonNull(name, "name must not be null");
        ChatModel model = models.get(name);
        if (model == null) {
            throw new IllegalArgumentException(
                    "No model registered with name '" + name + "'. Registered names: " + models.keySet());
        }
        return model;
    }

    /**
     * Fluent builder for {@link SimpleModelProvider}.
     */
    public static final class Builder {

        private ChatModel defaultModel;
        private final Map<String, ChatModel> models = new HashMap<>();

        private Builder() {}

        /**
         * Registers a model under the given name.
         *
         * <p>The name is used in {@code TaskRequest.getModelName()} or
         * {@code EnsembleRequest.getModelName()} to select this model for a specific request.
         *
         * @param name  the model identifier; must not be null
         * @param model the model instance; must not be null
         * @return this builder
         * @throws NullPointerException if name or model is null
         */
        public Builder model(String name, ChatModel model) {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(model, "model must not be null");
            models.put(name, model);
            return this;
        }

        /**
         * Sets the default model returned by {@link ModelProvider#getDefault()} when no
         * model name is specified in a request.
         *
         * @param model the default model; must not be null
         * @return this builder
         * @throws NullPointerException if model is null
         */
        public Builder defaultModel(ChatModel model) {
            this.defaultModel = Objects.requireNonNull(model, "model must not be null");
            return this;
        }

        /**
         * Builds the provider.
         *
         * @return the configured {@link SimpleModelProvider}
         */
        public SimpleModelProvider build() {
            return new SimpleModelProvider(defaultModel, models);
        }
    }
}
