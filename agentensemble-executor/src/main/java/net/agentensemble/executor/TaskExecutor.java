package net.agentensemble.executor;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;

/**
 * Executes a single {@link TaskRequest} in-process by building and running a minimal
 * AgentEnsemble.
 *
 * <p>This is the primary entry point for calling AgentEnsemble from an external workflow
 * orchestrator (Temporal, AWS Step Functions, Kafka Streams, etc.) with task-level granularity.
 * Each external activity wraps one {@code TaskExecutor.execute()} call. The orchestrator
 * sequences activities and passes upstream outputs via {@code TaskRequest.getContext()}.
 *
 * <h2>Usage in a Temporal Activity</h2>
 * <pre>
 * // Activity implementation in the user's project:
 * public class ResearchActivityImpl implements ResearchActivity {
 *
 *     private final TaskExecutor executor = new TaskExecutor(
 *         SimpleModelProvider.of(OpenAiChatModel.builder()
 *             .apiKey(System.getenv("OPENAI_API_KEY"))
 *             .modelName("gpt-4o-mini")
 *             .build()),
 *         SimpleToolProvider.builder()
 *             .tool("datetime", new DateTimeTool())
 *             .tool("web-search", new WebSearchTool(apiKey))
 *             .build()
 *     );
 *
 *     {@literal @}Override
 *     public TaskResult research(TaskRequest request) {
 *         return executor.execute(request, Activity.getExecutionContext()::heartbeat);
 *     }
 * }
 * </pre>
 *
 * <h2>Context passing from upstream tasks</h2>
 * <pre>
 * // In the Temporal Workflow:
 * TaskResult research = researchActivity.research(
 *     TaskRequest.builder()
 *         .description("Research {topic}")
 *         .expectedOutput("A comprehensive research summary")
 *         .inputs(Map.of("topic", topic))
 *         .build());
 *
 * TaskResult article = writeActivity.write(
 *     TaskRequest.builder()
 *         .description("Write an article about {topic} based on: {research}")
 *         .expectedOutput("A polished, well-structured article")
 *         .context(Map.of("research", research.output()))
 *         .inputs(Map.of("topic", topic))
 *         .build());
 * </pre>
 *
 * <p>Thread safety: {@code TaskExecutor} is thread-safe when the underlying
 * {@link ModelProvider} and {@link ToolProvider} are thread-safe. A single instance
 * may be shared across concurrent activity invocations.
 */
public class TaskExecutor {

    private final ModelProvider modelProvider;
    private final ToolProvider toolProvider;

    /**
     * Creates a {@code TaskExecutor} that uses the given model provider. No tools are
     * available to agents; use this constructor for LLM-only (tool-free) agents.
     *
     * @param modelProvider provides the {@link ChatModel} for each task; must not be null
     * @throws NullPointerException if modelProvider is null
     */
    public TaskExecutor(ModelProvider modelProvider) {
        this(modelProvider, SimpleToolProvider.empty());
    }

    /**
     * Creates a {@code TaskExecutor} with both a model provider and a tool provider.
     *
     * @param modelProvider provides the {@link ChatModel} for each task; must not be null
     * @param toolProvider  resolves tool instances by name from {@code AgentSpec.getToolNames()};
     *                      must not be null
     * @throws NullPointerException if either argument is null
     */
    public TaskExecutor(ModelProvider modelProvider, ToolProvider toolProvider) {
        this.modelProvider = Objects.requireNonNull(modelProvider, "modelProvider must not be null");
        this.toolProvider = Objects.requireNonNull(toolProvider, "toolProvider must not be null");
    }

    /**
     * Executes the given task request synchronously without heartbeating.
     *
     * <p>Equivalent to {@code execute(request, null)}.
     *
     * @param request the task to execute; must not be null
     * @return the task result; never null
     * @throws NullPointerException if request is null
     */
    public TaskResult execute(TaskRequest request) {
        return execute(request, null);
    }

    /**
     * Executes the given task request synchronously, emitting {@link HeartbeatDetail} events
     * to the given consumer throughout execution.
     *
     * <p>For Temporal: {@code executor.execute(request, Activity.getExecutionContext()::heartbeat)}.
     * Heartbeats keep the activity alive across long-running LLM and tool-call chains.
     *
     * <p>The consumer receives a {@link HeartbeatDetail} instance for each lifecycle event
     * (task started, tool calls, LLM iterations, task completed/failed). Exceptions thrown
     * by the consumer are caught and logged but do not abort execution.
     *
     * @param request           the task to execute; must not be null
     * @param heartbeatConsumer receives {@link HeartbeatDetail} instances during execution;
     *                          null disables heartbeating
     * @return the task result; never null
     * @throws NullPointerException if request is null
     */
    public TaskResult execute(TaskRequest request, Consumer<Object> heartbeatConsumer) {
        Objects.requireNonNull(request, "request must not be null");

        ChatModel model = resolveModel(request.getModelName());

        Task task = buildTask(request, model);

        var ensembleBuilder = Ensemble.builder().chatLanguageModel(model).task(task);

        // Inject context entries first (lower precedence) so explicit inputs can override them.
        if (request.getContext() != null) {
            request.getContext().forEach(ensembleBuilder::input);
        }
        // Explicit inputs take precedence over context entries on key collision.
        if (request.getInputs() != null) {
            request.getInputs().forEach(ensembleBuilder::input);
        }

        if (heartbeatConsumer != null) {
            ensembleBuilder.listener(new HeartbeatEnsembleListener(heartbeatConsumer));
        }

        EnsembleOutput output = ensembleBuilder.build().run();

        return toTaskResult(output);
    }

    // ========================
    // Helpers
    // ========================

    private ChatModel resolveModel(String modelName) {
        return modelName != null ? modelProvider.get(modelName) : modelProvider.getDefault();
    }

    private Task buildTask(TaskRequest request, ChatModel model) {
        var taskBuilder = Task.builder().description(request.getDescription());

        if (request.getExpectedOutput() != null) {
            taskBuilder.expectedOutput(request.getExpectedOutput());
        }

        if (request.getAgent() != null) {
            taskBuilder.agent(buildAgent(request.getAgent(), model));
        }

        return taskBuilder.build();
    }

    private Agent buildAgent(AgentSpec spec, ChatModel model) {
        var agentBuilder =
                Agent.builder().role(spec.getRole()).goal(spec.getGoal()).llm(model);

        if (spec.getBackground() != null) {
            agentBuilder.background(spec.getBackground());
        }
        if (spec.getMaxIterations() != null) {
            agentBuilder.maxIterations(spec.getMaxIterations());
        }

        List<String> toolNames = spec.getToolNames();
        if (toolNames != null && !toolNames.isEmpty()) {
            agentBuilder.tools(toolProvider.get(toolNames));
        }

        return agentBuilder.build();
    }

    private static TaskResult toTaskResult(EnsembleOutput output) {
        long durationMs =
                output.getTotalDuration() != null ? output.getTotalDuration().toMillis() : 0L;
        String exitReason =
                output.getExitReason() != null ? output.getExitReason().name() : "COMPLETED";
        return new TaskResult(output.getRaw(), durationMs, output.getTotalToolCalls(), exitReason);
    }
}
