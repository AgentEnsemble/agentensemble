package net.agentensemble;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.guardrail.InputGuardrail;
import net.agentensemble.guardrail.OutputGuardrail;
import net.agentensemble.memory.MemoryScope;
import net.agentensemble.ratelimit.RateLimit;
import net.agentensemble.ratelimit.RateLimitedChatModel;
import net.agentensemble.review.Review;
import net.agentensemble.task.TaskHandler;
import net.agentensemble.tool.AgentTool;

/**
 * A unit of work, optionally assigned to an explicit agent.
 *
 * <p>Tasks are immutable value objects. Use the builder to construct instances, or the
 * convenience factories {@link #of(String)} and {@link #of(String, String)} for
 * zero-ceremony use.
 *
 * <p>When no explicit {@link #agent} is set, the framework auto-synthesizes one from
 * the task description using the ensemble's configured {@code AgentSynthesizer}
 * (default: template-based derivation, no extra LLM call). Set {@link #agent} explicitly
 * only as a power-user escape hatch when you need full control over the agent persona.
 *
 * <p>Task descriptions and expected outputs may contain {variable} placeholders
 * that are resolved at {@code ensemble.run(inputs)} time.
 *
 * <p>To request structured (typed) output from the agent, set {@link #outputType}
 * to the target Java class. The agent will be prompted to produce JSON matching
 * the schema, and the result will be automatically parsed. Use
 * {@link net.agentensemble.task.TaskOutput#getParsedOutput(Class)} to access the
 * typed result after execution.
 *
 * Example -- zero-ceremony (agent synthesized automatically):
 * <pre>
 * EnsembleOutput result = Ensemble.run(model,
 *     Task.of("Research AI trends and write a comprehensive report"));
 * </pre>
 *
 * Example -- task-level LLM and tools (agent synthesized with these settings):
 * <pre>
 * Task task = Task.builder()
 *     .description("Research {topic} developments in {year}")
 *     .expectedOutput("A detailed report on {topic}")
 *     .chatLanguageModel(researchModel)
 *     .tools(webSearchTool, calculatorTool)
 *     .build();
 * </pre>
 *
 * Example -- explicit agent (power-user escape hatch):
 * <pre>
 * Task task = Task.builder()
 *     .description("Research {topic} developments in {year}")
 *     .expectedOutput("A detailed report on {topic}")
 *     .agent(researcher)
 *     .build();
 * </pre>
 *
 * Example -- structured output:
 * <pre>
 * record ResearchReport(String title, List{@code <String>} findings, String conclusion) {}
 *
 * Task task = Task.builder()
 *     .description("Research {topic} developments in {year}")
 *     .expectedOutput("A structured research report")
 *     .outputType(ResearchReport.class)
 *     .build();
 * </pre>
 */
@Builder(toBuilder = true)
@Value
public class Task {

    /** Default expectedOutput used by {@link #of(String)}. */
    public static final String DEFAULT_EXPECTED_OUTPUT = "Produce a complete and accurate response to the task.";

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

    /**
     * The agent assigned to execute this task.
     *
     * <p>When null (the default), the framework auto-synthesizes an agent from the task
     * description before execution, using the ensemble's configured
     * {@code AgentSynthesizer}. Set this field only when you need explicit control over
     * the agent persona (role, background, goal, verbose flag, etc.).
     *
     * <p>Default: null (agent is synthesized automatically).
     */
    Agent agent;

    /**
     * Per-task LLM override.
     *
     * <p>When set and no explicit {@link #agent} is provided, this model is used to build
     * the synthesized agent's LLM, taking precedence over the ensemble-level
     * {@code chatLanguageModel}. Ignored when {@link #agent} is set explicitly.
     *
     * <p>Default: null (use the ensemble-level LLM).
     */
    ChatModel chatLanguageModel;

    /**
     * Per-task streaming LLM override for token-by-token generation of the final response.
     *
     * <p>When set and no explicit {@link #agent} is provided, the synthesized agent uses this
     * model to stream the final response. If the task's explicit {@link #agent} has a
     * {@link Agent#streamingLlm}, that takes precedence over this field.
     *
     * <p>Resolution order (first non-null wins):
     * {@code Agent.streamingLlm} &gt; this field &gt; {@code Ensemble.streamingChatLanguageModel}.
     *
     * <p>Default: null (use ensemble-level streaming model, or non-streaming if not set).
     */
    StreamingChatModel streamingChatLanguageModel;

    /**
     * Tools available to this task's agent.
     *
     * <p>When set and no explicit {@link #agent} is provided, these tools are given to the
     * synthesized agent. Each entry must be either an {@link AgentTool} instance or an
     * object with {@code @dev.langchain4j.agent.tool.Tool} annotated methods.
     *
     * <p>Ignored when {@link #agent} is set explicitly (configure tools on the agent
     * builder instead).
     *
     * <p>Default: empty list.
     */
    List<Object> tools;

    /**
     * Maximum number of tool call iterations for this task's agent.
     *
     * <p>When set and no explicit {@link #agent} is provided, this value overrides the
     * default (25) for the synthesized agent. Must be greater than zero when set.
     *
     * <p>Ignored when {@link #agent} is set explicitly (configure {@code maxIterations}
     * on the agent builder instead).
     *
     * <p>Default: null (use the agent default of 25).
     */
    Integer maxIterations;

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
     * After-execution review gate configuration.
     *
     * <p>When set, a review gate fires after the agent completes this task and before
     * the output is passed to the next task. The reviewer may continue, edit the output,
     * or stop the pipeline early.
     *
     * <p>Overrides the ensemble-level {@link net.agentensemble.review.ReviewPolicy}:
     * {@link Review#required()} always fires regardless of policy;
     * {@link Review#skip()} never fires regardless of policy.
     *
     * <p>Default: null (use ensemble-level ReviewPolicy).
     */
    Review review;

    /**
     * Before-execution review gate configuration.
     *
     * <p>When set, a review gate fires before the agent begins executing this task.
     * The reviewer may approve execution or stop the pipeline early.
     * {@link net.agentensemble.review.ReviewDecision.Edit} is treated as
     * {@link net.agentensemble.review.ReviewDecision.Continue} because no output
     * exists yet.
     *
     * <p>Default: null (no before-execution gate).
     */
    Review beforeReview;

    /**
     * Per-task request rate limit applied to the LLM used for agent synthesis.
     *
     * <p>Behaviour depends on whether a task-level {@link #chatLanguageModel} is also set:
     * <ul>
     *   <li>If {@code chatLanguageModel} is set: the model is automatically wrapped with a
     *       {@link RateLimitedChatModel} at build time. The wrapped model is stored in
     *       {@link #chatLanguageModel}; this field is {@code null} on the resulting Task.</li>
     *   <li>If {@code chatLanguageModel} is null (task inherits the ensemble model): this
     *       field is preserved on the Task. {@code Ensemble.resolveAgents()} reads it and
     *       wraps the inherited ensemble model when building the synthesized agent.</li>
     * </ul>
     *
     * <p>Ignored when {@link #agent} is set explicitly (configure rate limiting via
     * {@code Agent.builder().rateLimit()} instead).
     *
     * <p>Mutually exclusive with {@link #handler}.
     *
     * <p>Default: null (no task-level rate limiting).
     */
    RateLimit rateLimit;

    /**
     * Handler for deterministic (non-AI) task execution.
     *
     * <p>When set, workflow executors invoke this handler directly instead of routing
     * through the LLM and the ReAct tool-calling loop. The handler receives a
     * {@link net.agentensemble.task.TaskHandlerContext} and returns a
     * {@link net.agentensemble.tool.ToolResult}.
     *
     * <p>Use the builder's {@link TaskBuilder#handler(TaskHandler)} overload for full
     * context access (description, expected output, prior task outputs), or the
     * {@link TaskBuilder#handler(AgentTool)} overload to wrap any existing {@link AgentTool}
     * or {@link net.agentensemble.tool.ToolPipeline} directly.
     *
     * <p>Mutually exclusive with {@link #agent}, {@link #chatLanguageModel},
     * {@link #streamingChatLanguageModel}, {@link #tools}, {@link #maxIterations},
     * and {@link #rateLimit}.
     *
     * <p>Default: null (AI-backed execution).
     */
    TaskHandler handler;

    // ========================
    // Static convenience factories
    // ========================

    /**
     * Zero-ceremony factory: create a task with only a description.
     *
     * <p>The {@code expectedOutput} is set to a sensible default
     * ({@value #DEFAULT_EXPECTED_OUTPUT}). The agent is synthesized automatically
     * by the ensemble's configured {@code AgentSynthesizer}.
     *
     * <pre>
     * EnsembleOutput result = Ensemble.run(model,
     *     Task.of("Research the latest AI developments in 2025"));
     * </pre>
     *
     * @param description what the agent should do; must not be blank
     * @return a new Task
     */
    public static Task of(String description) {
        return Task.builder()
                .description(description)
                .expectedOutput(DEFAULT_EXPECTED_OUTPUT)
                .build();
    }

    /**
     * Convenience factory: create a task with a description and expected output.
     *
     * <p>The agent is synthesized automatically by the ensemble's configured
     * {@code AgentSynthesizer}.
     *
     * <pre>
     * Task task = Task.of("Research AI trends", "A detailed market overview");
     * </pre>
     *
     * @param description    what the agent should do; must not be blank
     * @param expectedOutput what the output should look like; must not be blank
     * @return a new Task
     */
    public static Task of(String description, String expectedOutput) {
        return Task.builder()
                .description(description)
                .expectedOutput(expectedOutput)
                .build();
    }

    /**
     * Custom builder that sets defaults and validates the Task configuration.
     */
    public static class TaskBuilder {

        // Default values
        private Agent agent = null;
        private ChatModel chatLanguageModel = null;
        private StreamingChatModel streamingChatLanguageModel = null;
        private List<Object> tools = List.of();
        private Integer maxIterations = null;
        private List<Task> context = List.of();
        private Class<?> outputType = null;
        private int maxOutputRetries = 3;
        private List<InputGuardrail> inputGuardrails = List.of();
        private List<OutputGuardrail> outputGuardrails = List.of();
        private List<MemoryScope> memoryScopes = new ArrayList<>();
        private Review review = null;
        private Review beforeReview = null;
        private RateLimit rateLimit = null;
        private TaskHandler handler = null;

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

        /**
         * Apply a request rate limit to this task's LLM.
         *
         * <p>Behaviour at build time:
         * <ul>
         *   <li>If {@code chatLanguageModel} is also set: the model is automatically
         *       wrapped with {@link net.agentensemble.ratelimit.RateLimitedChatModel} using the
         *       default 30-second wait timeout. The {@code rateLimit} is not stored on the
         *       resulting Task.</li>
         *   <li>If no task-level {@code chatLanguageModel} is set: the {@code rateLimit} is
         *       stored on the Task. {@code Ensemble.resolveAgents()} applies it to the inherited
         *       ensemble model when building the synthesized agent.</li>
         * </ul>
         *
         * @param rateLimit the rate limit to enforce; must not be null
         * @return this builder
         */
        public TaskBuilder rateLimit(RateLimit rateLimit) {
            this.rateLimit = rateLimit;
            return this;
        }

        /**
         * Configure a deterministic handler for this task.
         *
         * <p>When set, the workflow executors bypass the LLM entirely and call this handler
         * directly. The handler receives a
         * {@link net.agentensemble.task.TaskHandlerContext} with the resolved description,
         * expected output, and prior task outputs, and must return a
         * {@link net.agentensemble.tool.ToolResult}.
         *
         * <p>Mutually exclusive with {@link #agent}, {@link #chatLanguageModel},
         * {@code streamingChatLanguageModel}, {@link #tools},
         * {@link #maxIterations}, and {@link #rateLimit}.
         *
         * <p>Passing {@code null} is valid and is equivalent to not configuring a handler
         * (the task will be executed by an AI agent). This allows {@code toBuilder()}
         * to correctly copy AI-backed tasks that have no handler.
         *
         * @param handler the handler to invoke; null means no handler (AI-backed execution)
         * @return this builder
         */
        public TaskBuilder handler(TaskHandler handler) {
            this.handler = handler;
            return this;
        }

        /**
         * Wrap an existing {@link AgentTool} as a deterministic handler for this task.
         *
         * <p>When this overload is used, the tool is invoked with a single string input:
         * <ul>
         *   <li>If the task has context outputs, the last context output's raw text
         *       is used as the input.</li>
         *   <li>Otherwise, the task description is used as the input.</li>
         * </ul>
         *
         * <p>This makes it easy to chain an existing {@code AgentTool} or
         * {@link net.agentensemble.tool.ToolPipeline} without an LLM:
         * <pre>
         * Task fetch = Task.builder()
         *     .description("https://api.example.com/data")
         *     .expectedOutput("JSON response")
         *     .handler(httpTool)
         *     .build();
         *
         * Task pipeline = Task.builder()
         *     .description("https://api.example.com/data")
         *     .expectedOutput("Parsed result")
         *     .handler(ToolPipeline.of(httpTool, jsonParserTool))
         *     .build();
         * </pre>
         *
         * @param tool the tool to wrap as a handler; must not be null
         * @return this builder
         */
        public TaskBuilder handler(AgentTool tool) {
            if (tool == null) {
                throw new IllegalArgumentException("Task handler tool must not be null");
            }
            this.handler = ctx -> {
                String input = ctx.contextOutputs().isEmpty()
                        ? ctx.description()
                        : ctx.contextOutputs().getLast().getRaw();
                return tool.execute(input);
            };
            return this;
        }

        public Task build() {
            validateDescription();
            validateExpectedOutput();
            List<Object> effectiveTools = tools != null ? tools : List.of();
            validateTools(effectiveTools);
            validateMaxIterations();
            List<Task> effectiveContext = context != null ? context : List.of();
            validateContext(effectiveContext);
            validateOutputType();
            validateMaxOutputRetries();
            validateHandlerExclusivity(effectiveTools);
            applyRateLimit();
            tools = List.copyOf(effectiveTools);
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
                    chatLanguageModel,
                    streamingChatLanguageModel,
                    tools,
                    maxIterations,
                    context,
                    outputType,
                    maxOutputRetries,
                    inputGuardrails,
                    outputGuardrails,
                    List.copyOf(effectiveMemoryScopes),
                    review,
                    beforeReview,
                    rateLimit,
                    handler);
        }

        /**
         * When {@code rateLimit} is set and {@code chatLanguageModel} is also set, wrap the
         * model at build time. If only {@code rateLimit} is set (no task-level model), the
         * rate limit is preserved on the Task for Ensemble to apply to the inherited model.
         */
        private void applyRateLimit() {
            if (rateLimit != null && chatLanguageModel != null) {
                chatLanguageModel = RateLimitedChatModel.of(chatLanguageModel, rateLimit);
                // Rate limit consumed at build time; not stored on the Task.
                rateLimit = null;
            }
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

        private void validateTools(List<Object> effectiveTools) {
            for (int i = 0; i < effectiveTools.size(); i++) {
                Object tool = effectiveTools.get(i);
                if (tool == null) {
                    throw new ValidationException("Task tool at index " + i + " must not be null");
                }
                if (!(tool instanceof AgentTool) && !hasToolAnnotatedMethods(tool)) {
                    throw new ValidationException(
                            "Task tool at index " + i + " (" + tool.getClass().getSimpleName()
                                    + ") is neither an AgentTool nor has @Tool-annotated methods");
                }
            }
        }

        private static boolean hasToolAnnotatedMethods(Object obj) {
            for (Method method : obj.getClass().getMethods()) {
                if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    return true;
                }
            }
            return false;
        }

        private void validateMaxIterations() {
            if (maxIterations != null && maxIterations <= 0) {
                throw new ValidationException("Task maxIterations must be > 0 when set, got: " + maxIterations);
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
                // Self-reference detection: only reliable when both tasks have an explicit agent.
                // For agentless tasks, description+expectedOutput alone is an unreliable proxy
                // (two distinct Task.of("X") calls would produce identical values but are not the
                // same task). Skip the check when agent is null to avoid false positives.
                if (agent == null) {
                    continue;
                }
                boolean descriptionMatch = contextTask.getDescription().equals(description);
                boolean expectedOutputMatch = contextTask.getExpectedOutput().equals(expectedOutput);
                boolean agentMatch = contextTask.getAgent() == agent;
                if (descriptionMatch && expectedOutputMatch && agentMatch) {
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

        /**
         * Validate that {@code handler} is not combined with fields that are only meaningful
         * for AI-backed tasks.
         *
         * <p>When a handler is configured, the task is executed deterministically and no LLM
         * is needed. Setting LLM-related fields alongside a handler is a misconfiguration that
         * would be silently ignored at runtime, so we reject it at build time instead.
         */
        private void validateHandlerExclusivity(List<Object> effectiveTools) {
            if (handler == null) {
                return;
            }
            if (agent != null) {
                throw new ValidationException("Task cannot have both a handler and an explicit agent. "
                        + "A handler runs deterministically without an LLM; remove the agent.");
            }
            if (chatLanguageModel != null) {
                throw new ValidationException("Task cannot have both a handler and a chatLanguageModel. "
                        + "A handler runs deterministically without an LLM; remove the chatLanguageModel.");
            }
            if (streamingChatLanguageModel != null) {
                throw new ValidationException("Task cannot have both a handler and a streamingChatLanguageModel. "
                        + "A handler runs deterministically without an LLM; "
                        + "remove the streamingChatLanguageModel.");
            }
            if (!effectiveTools.isEmpty()) {
                throw new ValidationException("Task cannot have both a handler and tools. "
                        + "A handler runs deterministically; configure tools inside the handler instead.");
            }
            if (maxIterations != null) {
                throw new ValidationException("Task cannot have both a handler and maxIterations. "
                        + "A handler runs deterministically without an iteration loop; "
                        + "remove the maxIterations.");
            }
            if (rateLimit != null) {
                throw new ValidationException("Task cannot have both a handler and a rateLimit. "
                        + "A handler runs deterministically without an LLM; remove the rateLimit.");
            }
        }
    }
}
