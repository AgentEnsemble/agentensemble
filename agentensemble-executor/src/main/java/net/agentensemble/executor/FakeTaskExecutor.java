package net.agentensemble.executor;

import dev.langchain4j.model.chat.DisabledChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A test double for {@link TaskExecutor} that returns configurable fake results without
 * invoking any language model.
 *
 * <p>Because {@code FakeTaskExecutor} extends {@code TaskExecutor}, it can be injected
 * anywhere a {@code TaskExecutor} is expected -- including Temporal activity implementations,
 * AWS Step Functions handlers, and Spring Batch tasklets.
 *
 * <h2>Basic usage -- single fixed response</h2>
 *
 * <pre>
 * // Returns the same output for every execute() call.
 * FakeTaskExecutor fake = FakeTaskExecutor.alwaysReturns("Research complete: AI is growing.");
 * </pre>
 *
 * <h2>Rule-based responses</h2>
 *
 * <pre>
 * FakeTaskExecutor fake = FakeTaskExecutor.builder()
 *     .whenDescriptionContains("Research", "AI is advancing rapidly in 2026.")
 *     .whenDescriptionContains("Write",    "Here is your polished article.")
 *     .whenAgentRole("Summarizer",         "Summary: AI is transforming industry.")
 *     .defaultOutput("Generic fake output for unmatched requests")
 *     .build();
 * </pre>
 *
 * <h2>Temporal activity test example</h2>
 *
 * <pre>
 * // Production activity accepts TaskExecutor (FakeTaskExecutor is a subtype):
 * public class ResearchActivityImpl implements ResearchActivity {
 *     private final TaskExecutor executor;
 *
 *     public ResearchActivityImpl() { this.executor = realExecutor(); }
 *     ResearchActivityImpl(TaskExecutor executor) { this.executor = executor; } // test hook
 *
 *     {@literal @}Override
 *     public TaskResult research(TaskRequest request) {
 *         return executor.execute(request, Activity.getExecutionContext()::heartbeat);
 *     }
 * }
 *
 * // In the JUnit test (with Temporal TestWorkflowEnvironment):
 * FakeTaskExecutor fake = FakeTaskExecutor.builder()
 *     .whenDescriptionContains("Research", "AI is growing fast.")
 *     .whenDescriptionContains("Write",    "Article: AI transforms society.")
 *     .build();
 *
 * testEnv.newWorker(TASK_QUEUE).registerActivitiesImplementations(
 *     new ResearchActivityImpl(fake));
 *
 * ResearchWorkflow workflow = testEnv.newWorkflowStub(ResearchWorkflow.class, options);
 * String result = workflow.run("AI");
 * assertThat(result).contains("AI transforms society.");
 * </pre>
 *
 * <p>All results have {@code exitReason = "COMPLETED"}, {@code toolCallCount = 0}, and
 * {@code durationMs = 1L} so {@link TaskResult#isComplete()} always returns {@code true}.
 *
 * <p>Heartbeat callbacks are not fired -- tests that need to verify heartbeat events should
 * use a real {@link TaskExecutor} with a mocked {@link ModelProvider}.
 */
public final class FakeTaskExecutor extends TaskExecutor {

    /**
     * No-op provider used only to satisfy the TaskExecutor constructor.
     * It is never invoked because execute() is fully overridden in this class.
     */
    private static final SimpleModelProvider NO_OP_PROVIDER = SimpleModelProvider.of(new DisabledChatModel());

    private final List<Rule> rules;
    private final String defaultOutput;

    private FakeTaskExecutor(List<Rule> rules, String defaultOutput) {
        super(NO_OP_PROVIDER);
        this.rules = List.copyOf(rules);
        this.defaultOutput = Objects.requireNonNull(defaultOutput, "defaultOutput must not be null");
    }

    /**
     * Creates a fake executor that returns the given output string for every
     * {@link #execute} call, regardless of the request.
     *
     * @param output the output to return; must not be null
     * @return a configured fake executor
     */
    public static FakeTaskExecutor alwaysReturns(String output) {
        Objects.requireNonNull(output, "output must not be null");
        return new FakeTaskExecutor(List.of(), output);
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
    public TaskResult execute(TaskRequest request) {
        return execute(request, null);
    }

    /**
     * Returns a fake {@link TaskResult} without invoking any language model.
     * The heartbeat consumer is accepted but no events are emitted.
     *
     * @param request           the task request; must not be null
     * @param heartbeatConsumer accepted but ignored in the fake
     * @return a completed {@link TaskResult} with the configured fake output
     */
    @Override
    public TaskResult execute(TaskRequest request, Consumer<Object> heartbeatConsumer) {
        Objects.requireNonNull(request, "request must not be null");

        String output = rules.stream()
                .filter(r -> r.matches(request))
                .findFirst()
                .map(r -> r.output)
                .orElse(defaultOutput);

        return new TaskResult(output, 1L, 0, "COMPLETED");
    }

    // ========================
    // Builder
    // ========================

    /**
     * Fluent builder for {@link FakeTaskExecutor}.
     */
    public static final class Builder {

        private final List<Rule> rules = new ArrayList<>();
        private String defaultOutput = "Fake task output.";

        private Builder() {}

        /**
         * Returns the configured output when the request's description contains the given
         * substring (case-sensitive).
         *
         * <p>Rules are evaluated in registration order; the first match wins.
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
         * Returns the configured output when the request's description satisfies the given
         * predicate.
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
         * Returns the configured output when the request's agent spec has the given role
         * (exact match, case-sensitive).
         *
         * @param role   the agent role to match
         * @param output output to return when matched; must not be null
         * @return this builder
         */
        public Builder whenAgentRole(String role, String output) {
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(output, "output must not be null");
            rules.add(new Rule(
                    req -> req.getAgent() != null && role.equals(req.getAgent().getRole()), output));
            return this;
        }

        /**
         * Sets the output returned when no rule matches.
         * Defaults to {@code "Fake task output."} when not set.
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
         * @return the configured {@link FakeTaskExecutor}
         */
        public FakeTaskExecutor build() {
            return new FakeTaskExecutor(rules, defaultOutput);
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
