package net.agentensemble.web;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Registry mapping model aliases to {@link ChatModel} instances for the Ensemble Control API.
 *
 * <p>Models registered here form the server-side allowlist for API-submitted runs. Clients
 * reference models by alias in API requests; only registered models can be used. This prevents
 * references to arbitrary or unconfigured LLM providers.
 *
 * <pre>
 * ModelCatalog catalog = ModelCatalog.builder()
 *     .model("sonnet", sonnetModel)
 *     .model("opus", opusModel)
 *     .model("haiku", haikuModel, haikuStreamingModel)
 *     .build();
 * </pre>
 *
 * <p>Registered via {@link WebDashboard.Builder#modelCatalog(ModelCatalog)}:
 *
 * <pre>
 * WebDashboard dashboard = WebDashboard.builder()
 *     .port(7329)
 *     .modelCatalog(catalog)
 *     .build();
 * </pre>
 */
public final class ModelCatalog {

    private final Map<String, ChatModel> models;
    private final Map<String, StreamingChatModel> streamingModels;

    private ModelCatalog(Builder builder) {
        this.models = Collections.unmodifiableMap(new LinkedHashMap<>(builder.models));
        this.streamingModels = Collections.unmodifiableMap(new LinkedHashMap<>(builder.streamingModels));
    }

    /**
     * Returns a new builder for constructing a {@link ModelCatalog}.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Resolves the chat model with the given alias.
     *
     * @param alias the model alias; must not be null
     * @return the registered {@link ChatModel}
     * @throws NoSuchElementException if no model is registered with the given alias
     */
    public ChatModel resolve(String alias) {
        ChatModel model = models.get(alias);
        if (model == null) {
            List<String> available = models.keySet().stream().sorted().toList();
            throw new NoSuchElementException("Unknown model '" + alias + "'. Available: " + available);
        }
        return model;
    }

    /**
     * Returns the chat model with the given alias, or empty if not found.
     *
     * @param alias the model alias
     * @return an Optional containing the model, or empty
     */
    public Optional<ChatModel> find(String alias) {
        return Optional.ofNullable(models.get(alias));
    }

    /**
     * Returns the streaming model registered under the given alias, or {@code null} if no
     * streaming variant was registered for that alias.
     *
     * @param alias the model alias
     * @return the streaming model, or null if not registered
     */
    public StreamingChatModel resolveStreaming(String alias) {
        return streamingModels.get(alias);
    }

    /**
     * Returns a list of all registered model descriptions, in registration order.
     *
     * @return unmodifiable list of {@link ModelInfo} records
     */
    public List<ModelInfo> list() {
        return models.entrySet().stream()
                .map(e -> new ModelInfo(e.getKey(), deriveProvider(e.getValue())))
                .toList();
    }

    /**
     * Returns the number of registered models.
     *
     * @return model count
     */
    public int size() {
        return models.size();
    }

    /**
     * Derives a human-readable provider name from the model's class name.
     *
     * <p>For example, a class named {@code OpenAiChatModel} returns {@code "openai"},
     * {@code AnthropicChatModel} returns {@code "anthropic"}.
     *
     * @param model the chat model instance
     * @return a lowercase provider name, or {@code "unknown"} if not recognized
     */
    static String deriveProvider(ChatModel model) {
        String className = model.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (className.contains("openai")) return "openai";
        if (className.contains("anthropic")) return "anthropic";
        if (className.contains("gemini") || className.contains("vertexai")) return "google";
        if (className.contains("google")) return "google";
        if (className.contains("mistral")) return "mistral";
        if (className.contains("ollama")) return "ollama";
        if (className.contains("cohere")) return "cohere";
        if (className.contains("azure")) return "azure";
        if (className.contains("bedrock")) return "bedrock";
        if (className.contains("huggingface")) return "huggingface";
        return "unknown";
    }

    /**
     * Descriptive snapshot of a registered model returned by {@link #list()}.
     *
     * @param alias    the model alias clients use in API requests
     * @param provider the inferred provider name (e.g. {@code "anthropic"}, {@code "openai"})
     */
    public record ModelInfo(String alias, String provider) {}

    /**
     * Fluent builder for {@link ModelCatalog}.
     *
     * <p>Models are stored in insertion order. Attempting to register the same alias twice
     * throws {@link IllegalArgumentException} at builder time (fail-fast).
     */
    public static final class Builder {

        private final Map<String, ChatModel> models = new LinkedHashMap<>();
        private final Map<String, StreamingChatModel> streamingModels = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Registers a chat model under the given alias.
         *
         * @param alias the alias clients use to reference this model; must not be null or blank
         * @param model the chat model implementation; must not be null
         * @return this builder
         * @throws IllegalArgumentException if the alias is null/blank or already registered,
         *                                  or if the model is null
         */
        public Builder model(String alias, ChatModel model) {
            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException("model alias must not be null or blank");
            }
            if (model == null) {
                throw new IllegalArgumentException("model must not be null (alias: '" + alias + "')");
            }
            if (models.containsKey(alias)) {
                throw new IllegalArgumentException("duplicate model alias: '" + alias + "'");
            }
            models.put(alias, model);
            return this;
        }

        /**
         * Registers a chat model and its streaming counterpart under the given alias.
         *
         * @param alias          the alias clients use to reference this model
         * @param model          the chat model implementation; must not be null
         * @param streamingModel the streaming variant; must not be null
         * @return this builder
         * @throws IllegalArgumentException if any argument is invalid or the alias is already registered
         */
        public Builder model(String alias, ChatModel model, StreamingChatModel streamingModel) {
            if (streamingModel == null) {
                throw new IllegalArgumentException(
                        "streamingModel must not be null when provided (alias: '" + alias + "')");
            }
            model(alias, model);
            streamingModels.put(alias, streamingModel);
            return this;
        }

        /**
         * Builds and returns the {@link ModelCatalog}.
         *
         * @return a new, immutable ModelCatalog
         */
        public ModelCatalog build() {
            return new ModelCatalog(this);
        }
    }
}
