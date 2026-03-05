package net.agentensemble;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.guardrail.InputGuardrail;
import net.agentensemble.guardrail.OutputGuardrail;
import net.agentensemble.memory.MemoryScope;

/**
 * A unit of work assigned to an agent.
 *
 * Tasks are immutable value objects. Use the builder to construct instances.
 * Most validation is performed at build time (blank fields, null agent, null context
 * elements, self-referencing context). Context ordering and membership are validated
 * at {@code ensemble.run()} time by the workflow executor.
 *
 * Task descriptions and expected outputs may contain {variable} placeholders
 * that are resolved at {@code ensemble.run(inputs)} time.
 *
 * To request structured (typed) output from the agent, set {@link #outputType}
 * to the target Java class. The agent will be prompted to produce JSON matching
 * the schema, and the result will be automatically parsed. Use
 * {@link net.agentensemble.task.TaskOutput#getParsedOutput(Class)} to access the
 * typed result after execution.
 *
 * Example with structured output:
 * <pre>
 * record ResearchReport(String title, List{@code <String>} findings, String conclusion) {}
 *
 * Task task = Task.builder()
 *     .description("Research {topic} developments in {year}")
 *     .expectedOutput("A structured research report")
 *     .agent(researcher)
 *     .outputType(ResearchReport.class)
 *     .build();
 * </pre>
 *
 * Example without structured output:
 * <pre>
 * Task task = Task.builder()
 *     .description("Research {topic} developments in {year}")
 *     .expectedOutput("A detailed report on {topic}")
 *     .agent(researcher)
 *     .build();
 * </pre>
 */
@Builder(toBuilder = true)
@Value
public class Task {

    /**
     * What the agent should do. Supports {variable} template placeholders
     * resolved at ensemble.run(inputs) time. Required.
     */
    String description;

    /**
     * What the output should look like. Included in the agent's user prompt
     * so the agent knows the expected format and content. Supports templates.
     * Required.
     */
    String expectedOutput;

    /** The agent assigned to execute this task. Required. */
    Agent agent;

    /**
     * Tasks whose outputs should be included as context when executing this task.
     * All referenced tasks must be executed before this one (validated at
     * ensemble.run() time by the workflow executor).
     * Default: empty list.
     */
    List<Task> context;

    /**
     * The Java class to deserialize the agent's output into.
     *
     * When set, the agent is instructed to produce JSON matching the schema
     * derived from this class, and the output is automatically parsed and
     * validated after execution. If parsing fails, the framework retries up to
     * {@link #maxOutputRetries} times before throwing
     * {@link net.agentensemble.exception.OutputParsingException}.
     *
     * Supported types: records, POJOs with declared fields, and common JDK
     * types (String, numeric wrappers, boolean, List, Map, enums, nested objects).
     *
     * Unsupported types: primitives, void, and top-level arrays.
     *
     * Default: {@code null} (raw text output only).
     */
    Class<?> outputType;

    /**
     * Maximum number of retry attempts if structured output parsing fails.
     *
     * On each retry the LLM is shown the parse error and the required schema,
     * and asked to produce a corrected JSON response. This field has no effect
     * when {@link #outputType} is {@code null}.
     *
     * Default: 3. Must be &gt;= 0.
     */
    int maxOutputRetries;

    /**
     * Guardrails evaluated before this task is executed.
     *
     * Each guardrail receives a {@link net.agentensemble.guardrail.GuardrailInput}
     * containing the task description, expected output, context outputs, and agent role.
     * If any guardrail returns a failure result, a
     * {@link net.agentensemble.guardrail.GuardrailViolationException} is thrown before
     * any LLM call is made.
     *
     * Guardrails are evaluated in order; the first failure stops evaluation.
     *
     * Default: empty list (no input validation).
     */
    List<InputGuardrail> inputGuardrails;

    /**
     * Guardrails evaluated after this task's agent produces a response.
     *
     * Each guardrail receives a {@link net.agentensemble.guardrail.GuardrailOutput}
     * containing the raw response text, the parsed output (if any), the task
     * description, and the agent role. If any guardrail returns a failure result,
     * a {@link net.agentensemble.guardrail.GuardrailViolationException} is thrown.
     * When {@link #outputType} is set, output guardrails run after structured output
     * parsing completes.
     *
     * Guardrails are evaluated in order; the first failure stops evaluation.
     *
     * Default: empty list (no output validation).
     */
    List<OutputGuardrail> outputGuardrails;

    /**
     * Named memory scopes this task reads from and writes to.
     *
     * At task startup, the framework retrieves entries from each declared scope and
     * injects them into the agent prompt. At task completion, the task output is
     * stored into each declared scope.
     *
     * Use the {@link TaskBuilder#memory(String)},
     * {@link TaskBuilder#memory(String...)}, or
     * {@link TaskBuilder#memory(MemoryScope)} builder methods to declare scopes.
     *
     * Default: empty list (no scoped memory).
     */
    List<MemoryScope> memoryScopes;

    /**
     * Custom builder that sets defaults and validates the Task configuration.
     */
    public static class TaskBuilder {

        // Default values
        private List<Task> context = List.of();
        private Class<?> outputType = null;
        private int maxOutputRetries = 3;
        private List<InputGuardrail> inputGuardrails = List.of();
        private List<OutputGuardrail> outputGuardrails = List.of();
        private List<MemoryScope> memoryScopes = new ArrayList<>();

        /**
         * Declare a single named memory scope for this task.
         *
         * <p>May be called multiple times; each call adds a scope to the list.
         *
         * @param scope the scope name; must not be blank
         * @return this builder
         */
        public TaskBuilder memory(String scope) {
            if (this.memoryScopes == null) {
                this.memoryScopes = new ArrayList<>();
            }
            this.memoryScopes.add(MemoryScope.of(scope));
            return this;
        }

        /**
         * Declare multiple named memory scopes for this task.
         *
         * <p>May be called multiple times; each call adds all supplied scopes.
         *
         * @param scopes one or more scope names; each must not be blank
         * @return this builder
         */
        public TaskBuilder memory(String... scopes) {
            if (this.memoryScopes == null) {
                this.memoryScopes = new ArrayList<>();
            }
            for (String s : scopes) {
                this.memoryScopes.add(MemoryScope.of(s));
            }
            return this;
        }

        /**
         * Declare a fully configured memory scope for this task.
         *
         * <p>May be called multiple times; each call adds the scope to the list.
         *
         * @param scope the configured scope; must not be null
         * @return this builder
         */
        public TaskBuilder memory(MemoryScope scope) {
            if (scope == null) {
                throw new IllegalArgumentException("MemoryScope must not be null");
            }
            if (this.memoryScopes == null) {
                this.memoryScopes = new ArrayList<>();
            }
            this.memoryScopes.add(scope);
            return this;
        }

        public Task build() {
            validateDescription();
            validateExpectedOutput();
            validateAgent();
            List<Task> effectiveContext = context != null ? context : List.of();
            validateContext(effectiveContext);
            validateOutputType();
            validateMaxOutputRetries();
            context = List.copyOf(effectiveContext);
            List<InputGuardrail> effectiveInputGuardrails = inputGuardrails != null ? inputGuardrails : List.of();
            List<OutputGuardrail> effectiveOutputGuardrails = outputGuardrails != null ? outputGuardrails : List.of();
            List<MemoryScope> effectiveMemoryScopes = memoryScopes != null ? memoryScopes : List.of();
            inputGuardrails = List.copyOf(effectiveInputGuardrails);
            outputGuardrails = List.copyOf(effectiveOutputGuardrails);
            return new Task(
                    description,
                    expectedOutput,
                    agent,
                    context,
                    outputType,
                    maxOutputRetries,
                    inputGuardrails,
                    outputGuardrails,
                    List.copyOf(effectiveMemoryScopes));
        }

        private void validateDescription() {
            if (description == null || description.isBlank()) {
                throw new ValidationException("Task description must not be blank");
            }
        }

        private void validateExpectedOutput() {
            if (expectedOutput == null || expectedOutput.isBlank()) {
                throw new ValidationException("Task expectedOutput must not be blank");
            }
        }

        private void validateAgent() {
            if (agent == null) {
                throw new ValidationException("Task agent must not be null");
            }
        }

        // Agent comparison uses reference equality intentionally: two Agent objects
        // with identical fields are distinct agents; only the same instance is "self".
        @SuppressWarnings("ReferenceEquality")
        private void validateContext(List<Task> ctx) {
            for (int i = 0; i < ctx.size(); i++) {
                Task contextTask = ctx.get(i);
                if (contextTask == null) {
                    throw new ValidationException("Task context element at index " + i + " must not be null");
                }
                // Self-reference: context contains a task with the same identity fields
                // (description, expectedOutput, agent) as the task being built.
                // Comparing agent by identity (==) because two distinct Agent objects
                // with identical fields represent distinct agents.
                if (contextTask.getDescription().equals(description)
                        && contextTask.getExpectedOutput().equals(expectedOutput)
                        && contextTask.getAgent() == agent) {
                    throw new ValidationException("Task cannot reference itself in context");
                }
            }
        }

        private void validateOutputType() {
            if (outputType == null) {
                return;
            }
            if (outputType.isPrimitive()) {
                throw new ValidationException("Task outputType must not be a primitive type: " + outputType.getName());
            }
            if (outputType == Void.class) {
                throw new ValidationException("Task outputType must not be Void");
            }
            if (outputType.isArray()) {
                throw new ValidationException(
                        "Task outputType must not be an array type. " + "Wrap the array in a record or class.");
            }
        }

        private void validateMaxOutputRetries() {
            if (maxOutputRetries < 0) {
                throw new ValidationException("Task maxOutputRetries must be >= 0, got: " + maxOutputRetries);
            }
        }
    }
}
