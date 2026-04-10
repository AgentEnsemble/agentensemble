package net.agentensemble.executor;

import dev.langchain4j.model.chat.DisabledChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A test double for {@link EnsembleExecutor} that returns configurable fake results without
 * invoking any language model.
 *
 * <p>Because {@code FakeEnsembleExecutor} extends {@code EnsembleExecutor}, it can be injected
 * anywhere an {@code EnsembleExecutor} is expected.
 *
 * <p>For each task in the {@link EnsembleRequest}, the fake evaluates its rules in registration
 * order and returns the first matching output. The {@code EnsembleResult.finalOutput()} is taken
 * from the last task's output, mirroring the behaviour of the real executor.
 *
 * <h2>Basic usage</h2>
 *
 * <pre>
 * FakeEnsembleExecutor fake = FakeEnsembleExecutor.alwaysReturns("Pipeline complete.");
 * EnsembleResult result = fake.execute(request);
 * assertThat(result.isComplete()).isTrue();
 * </pre>
 *
 * <h2>Rule-based responses</h2>
 *
 * <pre>
 * FakeEnsembleExecutor fake = FakeEnsembleExecutor.builder()
 *     .whenDescriptionContains("Research", "Research output: AI is growing.")
 *     .whenDescriptionContains("Write",    "Final article: AI transforms society.")
 *     .defaultOutput("Generic output")
 *     .build();
 *
 * // Two-task ensemble -- each task gets its matching output
 * EnsembleResult result = fake.execute(twoTaskRequest);
 * assertThat(result.taskOutputs()).hasSize(2);
 * assertThat(result.finalOutput()).isEqualTo("Final article: AI transforms society.");
 * </pre>
 *
 * <p>All results have {@code exitReason = "COMPLETED"}, {@code totalToolCalls = 0}, and
 * {@code totalDurationMs = 1L} so {@link EnsembleResult#isComplete()} always returns {@code true}.
 *
 * <p>Heartbeat callbacks are accepted but no events are emitted.
 */
public final class FakeEnsembleExecutor extends EnsembleExecutor {

    /**
     * No-op provider used only to satisfy the EnsembleExecutor constructor.
     * It is never invoked because execute() is fully overridden in this class.
     */
    private static final SimpleModelProvider NO_OP_PROVIDER = SimpleModelProvider.of(new DisabledChatModel());

    private final List<Rule> rules;
    private final String defaultOutput;

    private FakeEnsembleExecutor(List<Rule> rules, String defaultOutput) {
        super(NO_OP_PROVIDER);
        this.rules = List.copyOf(rules);
        this.defaultOutput = Objects.requireNonNull(defaultOutput, "defaultOutput must not be null");
    }

    /**
     * Creates a fake executor that returns the given output string for every task in every
     * {@link #execute} call.
     *
     * @param output the output to return for each task; must not be null
     * @return a configured fake executor
     */
    public static FakeEnsembleExecutor alwaysReturns(String output) {
        Objects.requireNonNull(output, "output must not be null");
        return new FakeEnsembleExecutor(List.of(), output);
    }

    /**
     * Returns a builder for configuring rule-based fake responses.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public EnsembleResult execute(EnsembleRequest request) {
        return execute(request, null);
    }

    /**
     * Returns a fake {@link EnsembleResult} without invoking any language model.
     * Each task in the request is matched against the configured rules independently.
     * The heartbeat consumer is accepted but no events are emitted.
     *
     * @param request           the ensemble request; must not be null and must contain at least one task
     * @param heartbeatConsumer accepted but ignored in the fake
     * @return a completed {@link EnsembleResult} with per-task fake outputs
     * @throws IllegalArgumentException if the request contains no tasks
     */
    @Override
    public EnsembleResult execute(EnsembleRequest request, Consumer<Object> heartbeatConsumer) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.getTasks() == null || request.getTasks().isEmpty()) {
            throw new IllegalArgumentException("EnsembleRequest must contain at least one task");
        }

        List<String> taskOutputs = new ArrayList<>();
        for (TaskRequest taskRequest : request.getTasks()) {
            String output = rules.stream()
                    .filter(r -> r.matches(taskRequest))
                    .findFirst()
                    .map(r -> r.output)
                    .orElse(defaultOutput);
            taskOutputs.add(output);
        }

        String finalOutput = taskOutputs.getLast();
        return new EnsembleResult(finalOutput, List.copyOf(taskOutputs), 1L, 0, "COMPLETED");
    }

    // ========================
    // Builder
    // ========================

    /**
     * Fluent builder for {@link FakeEnsembleExecutor}.
     */
    public static final class Builder {

        private final List<Rule> rules = new ArrayList<>();
        private String defaultOutput = "Fake ensemble output.";

        private Builder() {}

        /**
         * Returns the configured output when a task's description contains the given
         * substring (case-sensitive).
         *
         * <p>Rules are evaluated in registration order; the first match wins per task.
         *
         * @param substring substring to search for in {@code TaskRequest.getDescription()}
         * @param output    output to return when matched; must not be null
         * @return this builder
         */
        public Builder whenDescriptionContains(String substring, String output) {
            Objects.requireNonNull(substring, "substring must not be null");
            Objects.requireNonNull(output, "output must not be null");
            rules.add(new Rule(
                    req -> req.getDescription() != null && req.getDescription().contains(substring), output));
            return this;
        }

        /**
         * Returns the configured output when a task's description satisfies the given predicate.
         *
         * @param predicate applied to {@code TaskRequest.getDescription()}; must not be null
         * @param output    output to return when matched; must not be null
         * @return this builder
         */
        public Builder whenDescription(Predicate<String> predicate, String output) {
            Objects.requireNonNull(predicate, "predicate must not be null");
            Objects.requireNonNull(output, "output must not be null");
            rules.add(new Rule(req -> req.getDescription() != null && predicate.test(req.getDescription()), output));
            return this;
        }

        /**
         * Sets the output returned when no rule matches a task.
         * Defaults to {@code "Fake ensemble output."} when not set.
         *
         * @param output the default output; must not be null
         * @return this builder
         */
        public Builder defaultOutput(String output) {
            this.defaultOutput = Objects.requireNonNull(output, "output must not be null");
            return this;
        }

        /**
         * Builds the fake executor.
         *
         * @return the configured {@link FakeEnsembleExecutor}
         */
        public FakeEnsembleExecutor build() {
            return new FakeEnsembleExecutor(rules, defaultOutput);
        }
    }

    // ========================
    // Internal
    // ========================

    private record Rule(Predicate<TaskRequest> predicate, String output) {
        boolean matches(TaskRequest request) {
            return predicate.test(request);
        }
    }
}
