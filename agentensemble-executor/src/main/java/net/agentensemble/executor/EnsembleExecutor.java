package net.agentensemble.executor;

import dev.langchain4j.model.chat.ChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.Workflow;

/**
 * Executes an {@link EnsembleRequest} in-process by building and running a full multi-task
 * AgentEnsemble.
 *
 * <p>Use {@code EnsembleExecutor} when you want AgentEnsemble's internal orchestration engine
 * (sequential, parallel, or hierarchical) to handle a complete pipeline within a single
 * external-workflow activity. For finer-grained control -- where each AgentEnsemble task maps
 * to a separate Temporal activity -- use {@link TaskExecutor} instead.
 *
 * <h2>Usage in a Temporal Activity</h2>
 * <pre>
 * public class ResearchPipelineActivityImpl implements ResearchPipelineActivity {
 *
 *     private final EnsembleExecutor executor = new EnsembleExecutor(
 *         SimpleModelProvider.of(OpenAiChatModel.builder()
 *             .apiKey(System.getenv("OPENAI_API_KEY"))
 *             .modelName("gpt-4o-mini")
 *             .build())
 *     );
 *
 *     {@literal @}Override
 *     public EnsembleResult run(EnsembleRequest request) {
 *         return executor.execute(request, Activity.getExecutionContext()::heartbeat);
 *     }
 * }
 * </pre>
 *
 * <h2>Building a request</h2>
 * <pre>
 * EnsembleRequest request = EnsembleRequest.builder()
 *     .task(TaskRequest.of("Research {topic}", "A comprehensive research summary"))
 *     .task(TaskRequest.of("Write a blog post about {topic}", "A polished blog post"))
 *     .workflow("SEQUENTIAL")
 *     .inputs(Map.of("topic", "Artificial Intelligence"))
 *     .build();
 *
 * EnsembleResult result = executor.execute(request);
 * System.out.println(result.finalOutput());
 * </pre>
 *
 * <p>Thread safety: {@code EnsembleExecutor} is thread-safe when the underlying
 * {@link ModelProvider} and {@link ToolProvider} are thread-safe.
 */
public class EnsembleExecutor {

    private final ModelProvider modelProvider;
    private final ToolProvider toolProvider;

    /**
     * Creates an {@code EnsembleExecutor} that uses the given model provider. No tools are
     * available to agents; use this constructor for LLM-only pipelines.
     *
     * @param modelProvider provides the {@link ChatModel} for each task; must not be null
     * @throws NullPointerException if modelProvider is null
     */
    public EnsembleExecutor(ModelProvider modelProvider) {
        this(modelProvider, SimpleToolProvider.empty());
    }

    /**
     * Creates an {@code EnsembleExecutor} with both a model provider and a tool provider.
     *
     * @param modelProvider provides the {@link ChatModel} for each task; must not be null
     * @param toolProvider  resolves tool instances by name from {@code AgentSpec.getToolNames()};
     *                      must not be null
     * @throws NullPointerException if either argument is null
     */
    public EnsembleExecutor(ModelProvider modelProvider, ToolProvider toolProvider) {
        this.modelProvider = Objects.requireNonNull(modelProvider, "modelProvider must not be null");
        this.toolProvider = Objects.requireNonNull(toolProvider, "toolProvider must not be null");
    }

    /**
     * Executes the given ensemble request synchronously without heartbeating.
     *
     * <p>Equivalent to {@code execute(request, null)}.
     *
     * @param request the ensemble to run; must not be null and must contain at least one task
     * @return the ensemble result; never null
     * @throws NullPointerException     if request is null
     * @throws IllegalArgumentException if the request contains no tasks
     */
    public EnsembleResult execute(EnsembleRequest request) {
        return execute(request, null);
    }

    /**
     * Executes the given ensemble request synchronously, emitting {@link HeartbeatDetail} events
     * to the given consumer throughout execution.
     *
     * <p>For Temporal: {@code executor.execute(request, Activity.getExecutionContext()::heartbeat)}.
     * Heartbeats keep the activity alive across long-running multi-task LLM pipelines.
     *
     * @param request           the ensemble to run; must not be null and must contain at least one task
     * @param heartbeatConsumer receives {@link HeartbeatDetail} instances during execution;
     *                          null disables heartbeating
     * @return the ensemble result; never null
     * @throws NullPointerException     if request is null
     * @throws IllegalArgumentException if the request contains no tasks
     */
    public EnsembleResult execute(EnsembleRequest request, Consumer<Object> heartbeatConsumer) {
        Objects.requireNonNull(request, "request must not be null");

        if (request.getTasks() == null || request.getTasks().isEmpty()) {
            throw new IllegalArgumentException("EnsembleRequest must contain at least one task");
        }

        ChatModel defaultModel = resolveModel(request.getModelName());

        var ensembleBuilder = Ensemble.builder().chatLanguageModel(defaultModel);

        // Apply global inputs first (lowest precedence; per-task inputs applied below).
        if (request.getInputs() != null) {
            request.getInputs().forEach(ensembleBuilder::input);
        }

        // Build each task and accumulate its inputs.
        for (TaskRequest taskRequest : request.getTasks()) {
            ChatModel taskModel =
                    taskRequest.getModelName() != null ? modelProvider.get(taskRequest.getModelName()) : defaultModel;

            ensembleBuilder.task(buildTask(taskRequest, taskModel));

            // Per-task context entries (lower precedence within the task).
            if (taskRequest.getContext() != null) {
                taskRequest.getContext().forEach(ensembleBuilder::input);
            }
            // Per-task explicit inputs (higher precedence, override context and global inputs).
            if (taskRequest.getInputs() != null) {
                taskRequest.getInputs().forEach(ensembleBuilder::input);
            }
        }

        // Apply workflow mode when explicitly specified.
        if (request.getWorkflow() != null) {
            ensembleBuilder.workflow(Workflow.valueOf(request.getWorkflow()));
        }

        if (heartbeatConsumer != null) {
            ensembleBuilder.listener(new HeartbeatEnsembleListener(heartbeatConsumer));
        }

        EnsembleOutput output = ensembleBuilder.build().run();

        return toEnsembleResult(output);
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

    private static EnsembleResult toEnsembleResult(EnsembleOutput output) {
        List<String> taskOutputStrings = new ArrayList<>();
        if (output.getTaskOutputs() != null) {
            for (TaskOutput taskOutput : output.getTaskOutputs()) {
                taskOutputStrings.add(taskOutput.getRaw());
            }
        }

        long durationMs =
                output.getTotalDuration() != null ? output.getTotalDuration().toMillis() : 0L;
        String exitReason =
                output.getExitReason() != null ? output.getExitReason().name() : "COMPLETED";

        return new EnsembleResult(
                output.getRaw(), List.copyOf(taskOutputStrings), durationMs, output.getTotalToolCalls(), exitReason);
    }
}
