package net.agentensemble.reflection;

import dev.langchain4j.model.chat.ChatModel;
import java.util.Objects;

/**
 * Configuration for task reflection, controlling which model and strategy to use.
 *
 * <p>Attach to a {@code Task} via the builder's {@code .reflect()} methods:
 * <pre>
 * // Enable reflection with all defaults
 * Task.builder()
 *     .description("Analyse market trends")
 *     .expectedOutput("A structured analysis report")
 *     .reflect(true)
 *     .build();
 *
 * // Use a specific (cheaper) model for the reflection LLM call
 * Task.builder()
 *     .description("Analyse market trends")
 *     .expectedOutput("A structured analysis report")
 *     .reflect(ReflectionConfig.builder()
 *         .model(cheapModel)
 *         .build())
 *     .build();
 *
 * // Fully custom strategy
 * Task.builder()
 *     .description("Analyse market trends")
 *     .expectedOutput("A structured analysis report")
 *     .reflect(ReflectionConfig.builder()
 *         .strategy(new MyReflectionStrategy())
 *         .build())
 *     .build();
 * </pre>
 *
 * <h2>Model Resolution</h2>
 * The LLM used for reflection is resolved in this order:
 * <ol>
 *   <li>{@code ReflectionConfig.model} — if set here</li>
 *   <li>{@code Task.chatLanguageModel} — if set on the task</li>
 *   <li>{@code Ensemble.model} — the ensemble-level model</li>
 * </ol>
 * Using a cheaper or faster model for reflection is often appropriate since reflection
 * is a meta-analysis step, not the primary task.
 *
 * <h2>Strategy Resolution</h2>
 * If no strategy is provided, {@code LlmReflectionStrategy} is used with the resolved model.
 */
public final class ReflectionConfig {

    /**
     * Singleton default configuration with no model override and no custom strategy.
     * This is what {@code Task.builder().reflect(true)} uses internally.
     */
    public static final ReflectionConfig DEFAULT = new ReflectionConfig(null, null);

    private final ChatModel model;
    private final ReflectionStrategy strategy;

    private ReflectionConfig(ChatModel model, ReflectionStrategy strategy) {
        this.model = model;
        this.strategy = strategy;
    }

    /**
     * Returns the optional model override for the reflection LLM call.
     *
     * @return the model, or {@code null} if not set (falls back to task or ensemble model)
     */
    public ChatModel model() {
        return model;
    }

    /**
     * Returns the optional custom reflection strategy.
     *
     * @return the strategy, or {@code null} if the default {@code LlmReflectionStrategy} should be used
     */
    public ReflectionStrategy strategy() {
        return strategy;
    }

    /**
     * Returns a new builder for {@code ReflectionConfig}.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@code ReflectionConfig}.
     */
    public static final class Builder {

        private ChatModel model;
        private ReflectionStrategy strategy;

        private Builder() {}

        /**
         * Sets the LLM to use for the reflection analysis call.
         *
         * <p>Using a cheaper or faster model for reflection is often appropriate since
         * reflection is a meta-level task and does not require the full capability
         * of the task's primary model.
         *
         * @param model the chat model to use; must not be null
         * @return this builder
         */
        public Builder model(ChatModel model) {
            this.model = Objects.requireNonNull(model, "model must not be null");
            return this;
        }

        /**
         * Sets a custom reflection strategy that controls how the reflection analysis is
         * performed.
         *
         * <p>When set, the {@code strategy} is used instead of the default
         * {@code LlmReflectionStrategy}. The configured {@code model} is ignored when a
         * custom strategy is provided — the strategy is fully responsible for producing
         * the {@link TaskReflection}.
         *
         * @param strategy the custom strategy; must not be null
         * @return this builder
         */
        public Builder strategy(ReflectionStrategy strategy) {
            this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
            return this;
        }

        /**
         * Builds and returns the {@code ReflectionConfig}.
         *
         * @return a new {@code ReflectionConfig}
         */
        public ReflectionConfig build() {
            return new ReflectionConfig(model, strategy);
        }
    }
}
