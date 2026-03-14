package net.agentensemble.mapreduce;

import dev.langchain4j.model.chat.ChatModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.metrics.CostConfiguration;
import net.agentensemble.tool.ToolMetrics;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.trace.export.ExecutionTraceExporter;
import net.agentensemble.workflow.ParallelErrorStrategy;
import net.agentensemble.workflow.Workflow;

/**
 * A builder that constructs and executes a tree-reduction DAG using either a
 * <b>static</b> ({@code chunkSize}) or <b>adaptive</b> ({@code targetTokenBudget}) strategy.
 *
 * <h2>Task-first API (v2.0.0)</h2>
 *
 * <p>In the task-first paradigm, agent declarations are optional. Supply task-factory
 * lambdas and a {@code chatLanguageModel}; the framework synthesises agents automatically
 * from each task's description using the ensemble's configured {@code AgentSynthesizer}
 * (default: template-based, no extra LLM call).
 *
 * <pre>
 * // Zero-ceremony factory -- simplest possible usage:
 * EnsembleOutput output = MapReduceEnsemble.of(
 *     model, dishes,
 *     "Prepare a recipe for",
 *     "Combine the individual recipes into a cohesive meal plan");
 *
 * // Task-first builder -- full control over task configuration, no agent declarations:
 * EnsembleOutput output = MapReduceEnsemble.&lt;OrderItem&gt;builder()
 *     .chatLanguageModel(model)
 *     .items(order.getItems())
 *     .mapTask(item -&gt; Task.of("Prepare recipe for " + item.getDish()))
 *     .reduceTask(chunkTasks -&gt; Task.builder()
 *         .description("Consolidate these recipes into a meal plan")
 *         .expectedOutput("A coordinated meal plan")
 *         .context(chunkTasks)
 *         .build())
 *     .chunkSize(3)
 *     .build()
 *     .run();
 * </pre>
 *
 * <h2>Agent-first API (power-user escape hatch)</h2>
 *
 * <p>When you need full control over agent personas, supply explicit agent factories
 * alongside the task factories. The two styles are mutually exclusive per phase.
 *
 * <pre>
 * EnsembleOutput output = MapReduceEnsemble.&lt;OrderItem&gt;builder()
 *     .items(order.getItems())
 *     .mapAgent(item -&gt; Agent.builder().role(item.getDish() + " Chef").llm(model).build())
 *     .mapTask((item, agent) -&gt; Task.builder()
 *         .description("Prepare " + item.getDish())
 *         .expectedOutput("Recipe")
 *         .agent(agent)
 *         .build())
 *     .reduceAgent(() -&gt; Agent.builder().role("Sub-Chef").llm(model).build())
 *     .reduceTask((agent, chunkTasks) -&gt; Task.builder()
 *         .description("Consolidate")
 *         .expectedOutput("Plan")
 *         .agent(agent)
 *         .context(chunkTasks)
 *         .build())
 *     .chunkSize(3)
 *     .build()
 *     .run();
 * </pre>
 *
 * <h2>Static mode ({@code chunkSize})</h2>
 *
 * <p>The entire DAG is pre-built at {@code build()} time. Each reduce level groups at most
 * {@code chunkSize} upstream tasks. Call {@link #toEnsemble()} to inspect the pre-built
 * structure before execution.
 *
 * <h2>Adaptive mode ({@code targetTokenBudget})</h2>
 *
 * <p>The DAG is built level-by-level at runtime. After each level executes, output token
 * counts are measured and outputs are bin-packed into groups that fit within
 * {@code targetTokenBudget}. {@link #toEnsemble()} is not available in adaptive mode (throws
 * {@link UnsupportedOperationException}) because the DAG shape is unknown until runtime.
 *
 * @param <T> the type of each input item
 */
public final class MapReduceEnsemble<T> {

    /** Node type identifier for map-phase tasks. */
    public static final String NODE_TYPE_MAP = "map";

    /** Node type identifier for intermediate reduce tasks. */
    public static final String NODE_TYPE_REDUCE = "reduce";

    /** Node type identifier for the final reduce task. */
    public static final String NODE_TYPE_FINAL_REDUCE = "final-reduce";

    /** Node type identifier for the direct (short-circuit) task. */
    public static final String NODE_TYPE_DIRECT = "direct";

    /** MapReduce mode identifier for static mode (reported to devtools). */
    public static final String MAP_REDUCE_MODE = "STATIC";

    /** MapReduce mode identifier for adaptive mode (reported to devtools). */
    public static final String MAP_REDUCE_MODE_ADAPTIVE = "ADAPTIVE";

    // Static mode fields (null in adaptive mode)
    private final Ensemble ensemble;
    private final IdentityHashMap<Task, String> nodeTypes;
    private final IdentityHashMap<Task, Integer> mapReduceLevels;

    // Adaptive mode field (null in static mode)
    private final MapReduceAdaptiveExecutor<T> adaptiveExecutor;

    // Common
    private final Map<String, String> inputs;
    private final boolean adaptiveMode;

    private MapReduceEnsemble(
            Ensemble ensemble,
            Map<String, String> inputs,
            IdentityHashMap<Task, String> nodeTypes,
            IdentityHashMap<Task, Integer> mapReduceLevels) {
        this.ensemble = ensemble;
        this.inputs = inputs;
        this.nodeTypes = nodeTypes;
        this.mapReduceLevels = mapReduceLevels;
        this.adaptiveExecutor = null;
        this.adaptiveMode = false;
    }

    private MapReduceEnsemble(MapReduceAdaptiveExecutor<T> adaptiveExecutor, Map<String, String> inputs) {
        this.adaptiveExecutor = adaptiveExecutor;
        this.inputs = inputs;
        this.ensemble = null;
        this.nodeTypes = null;
        this.mapReduceLevels = null;
        this.adaptiveMode = true;
    }

    /**
     * Returns a new {@link Builder} for constructing a {@code MapReduceEnsemble}.
     *
     * @param <T> the type of each input item
     * @return a fresh builder instance
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Zero-ceremony factory: create, run, and return results from a {@code MapReduceEnsemble}
     * in a single call.
     *
     * <p>Agents are synthesised automatically from the task descriptions using the default
     * {@code AgentSynthesizer} (template-based, no extra LLM call). Static mode with a
     * {@code chunkSize} of {@code 5} is used.
     *
     * <p>For each item, the map task description is:
     * {@code mapDescription + ": " + item.toString()}. The reduce task uses
     * {@code reduceDescription} and receives all map outputs as context.
     *
     * <pre>
     * EnsembleOutput output = MapReduceEnsemble.of(
     *     model,
     *     List.of("Risotto", "Duck", "Salmon"),
     *     "Prepare a recipe for",
     *     "Combine these individual recipes into a cohesive dinner menu");
     * </pre>
     *
     * @param <T>              the type of each input item
     * @param model            the LLM for all synthesised agents; must not be null
     * @param items            the items to fan out over; must not be null or empty
     * @param mapDescription   prefix used to build each map task description; must not be blank
     * @param reduceDescription description for the final reduce task; must not be blank
     * @return the aggregated {@link EnsembleOutput} from the full map-reduce run
     * @throws ValidationException if any parameter is invalid
     */
    public static <T> EnsembleOutput of(
            ChatModel model, List<T> items, String mapDescription, String reduceDescription) {
        if (model == null) {
            throw new ValidationException("model must not be null");
        }
        if (items == null) {
            throw new ValidationException("items must not be null");
        }
        if (items.isEmpty()) {
            throw new ValidationException("items must not be empty");
        }
        if (mapDescription == null || mapDescription.isBlank()) {
            throw new ValidationException("mapDescription must not be blank");
        }
        if (reduceDescription == null || reduceDescription.isBlank()) {
            throw new ValidationException("reduceDescription must not be blank");
        }
        return MapReduceEnsemble.<T>builder()
                .chatLanguageModel(model)
                .items(items)
                .mapTask(item -> Task.of(mapDescription + ": " + String.valueOf(item)))
                .reduceTask(chunkTasks -> Task.builder()
                        .description(reduceDescription)
                        .expectedOutput(Task.DEFAULT_EXPECTED_OUTPUT)
                        .context(chunkTasks)
                        .build())
                .build()
                .run();
    }

    /**
     * Execute the ensemble using the inputs configured on the builder.
     *
     * @return the aggregated {@link EnsembleOutput}
     */
    public EnsembleOutput run() {
        if (adaptiveMode) {
            return adaptiveExecutor.run(inputs);
        }
        return ensemble.run();
    }

    /**
     * Execute the ensemble, merging the supplied run-time inputs with the builder inputs.
     *
     * @param runtimeInputs additional or overriding template variable values
     * @return the aggregated {@link EnsembleOutput}
     */
    public EnsembleOutput run(Map<String, String> runtimeInputs) {
        if (adaptiveMode) {
            Map<String, String> merged = new LinkedHashMap<>(inputs);
            if (runtimeInputs != null) {
                merged.putAll(runtimeInputs);
            }
            return adaptiveExecutor.run(Collections.unmodifiableMap(merged));
        }
        return ensemble.run(runtimeInputs);
    }

    /**
     * Returns the pre-built inner {@link Ensemble} for inspection or devtools export.
     *
     * <p><b>Static mode only.</b> In adaptive mode, the DAG shape is not known until
     * runtime, so this method throws {@link UnsupportedOperationException}.
     *
     * @return the pre-built inner {@link Ensemble}
     * @throws UnsupportedOperationException if called in adaptive mode
     */
    public Ensemble toEnsemble() {
        if (adaptiveMode) {
            throw new UnsupportedOperationException("toEnsemble() is not supported in adaptive mode. "
                    + "The DAG shape is determined at runtime based on actual output token counts. "
                    + "Inspect the ExecutionTrace returned by run() to understand the "
                    + "actual reduction structure.");
        }
        return ensemble;
    }

    /**
     * Returns an identity-keyed map from task to node type string for devtools enrichment.
     *
     * <p>Available in static mode only. Returns {@code null} in adaptive mode.
     *
     * <p>The returned map uses identity-based ({@code ==}) key comparison, consistent with
     * the framework's {@code IdentityHashMap} convention for task instances.
     *
     * @return an identity-keyed map of task to node type, or {@code null} in adaptive mode
     */
    @SuppressWarnings(
            "PMD.LooseCoupling") // IdentityHashMap is part of the API contract: callers must use identity comparison
    public IdentityHashMap<Task, String> getNodeTypes() {
        return nodeTypes;
    }

    /**
     * Returns an identity-keyed map from task to map-reduce level for devtools enrichment.
     *
     * <p>Available in static mode only. Returns {@code null} in adaptive mode.
     *
     * <p>The returned map uses identity-based ({@code ==}) key comparison, consistent with
     * the framework's {@code IdentityHashMap} convention for task instances.
     *
     * @return an identity-keyed map of task to level, or {@code null} in adaptive mode
     */
    @SuppressWarnings(
            "PMD.LooseCoupling") // IdentityHashMap is part of the API contract: callers must use identity comparison
    public IdentityHashMap<Task, Integer> getMapReduceLevels() {
        return mapReduceLevels;
    }

    /**
     * Returns {@code true} if this instance was built in adaptive mode
     * ({@code targetTokenBudget} or {@code contextWindowSize}/{@code budgetRatio} was set).
     *
     * @return {@code true} for adaptive mode, {@code false} for static mode
     */
    public boolean isAdaptiveMode() {
        return adaptiveMode;
    }

    // ========================
    // Builder
    // ========================

    /**
     * Builder for {@link MapReduceEnsemble}.
     *
     * <p><b>Task-first usage (v2.0.0, recommended):</b> Required fields: {@code items},
     * {@code mapTask(Function)}, {@code reduceTask(Function)}. Agents are synthesised
     * automatically when {@code chatLanguageModel} is set.
     *
     * <p><b>Agent-first usage (power-user escape hatch):</b> Required fields: {@code items},
     * {@code mapAgent}, {@code mapTask(BiFunction)}, {@code reduceAgent},
     * {@code reduceTask(BiFunction)}. The two styles are mutually exclusive per phase: you
     * cannot combine, for example, {@code mapTask(Function)} and {@code mapAgent} in the
     * same builder.
     *
     * <p><b>Strategy selection:</b> Set {@code chunkSize} for static mode, or
     * {@code targetTokenBudget} / {@code contextWindowSize} + {@code budgetRatio} for
     * adaptive mode. These are mutually exclusive. Default (neither set): static with
     * {@code chunkSize=5}.
     *
     * @param <T> the type of each input item
     */
    public static final class Builder<T> {

        private List<T> items;

        // Agent-first factories (power-user)
        private Function<T, Agent> mapAgentFactory;
        private BiFunction<T, Agent, Task> mapTaskFactory;
        private Supplier<Agent> reduceAgentFactory;
        private BiFunction<Agent, List<Task>, Task> reduceTaskFactory;

        // Task-first factories (v2.0.0, agent synthesised automatically)
        private Function<T, Task> mapTaskOnlyFactory;
        private Function<List<Task>, Task> reduceTaskOnlyFactory;

        // Default LLM for agent synthesis when using task-first factories
        private ChatModel chatLanguageModel;

        // Short-circuit fields (adaptive mode only)
        private Supplier<Agent> directAgentFactory = null;
        private BiFunction<Agent, List<T>, Task> directTaskFactory = null;
        private Function<List<T>, Task> directTaskOnlyFactory = null;
        private Function<T, String> inputEstimator = null;

        // Static mode
        private Integer chunkSize = null; // null = not explicitly set; defaults to 5

        // Adaptive mode
        private Integer targetTokenBudget = null; // null = not explicitly set
        private int contextWindowSize = 0; // 0 = not set
        private double budgetRatio = 0.0; // 0.0 = not set (valid range (0.0, 1.0])
        private int maxReduceLevels = 10;
        private Function<String, Integer> tokenEstimator;

        // Common passthrough
        private boolean verbose = false;
        private final List<EnsembleListener> listeners = new ArrayList<>();
        private CaptureMode captureMode = CaptureMode.OFF;
        private ParallelErrorStrategy parallelErrorStrategy = ParallelErrorStrategy.FAIL_FAST;
        private CostConfiguration costConfiguration;
        private ExecutionTraceExporter traceExporter;
        private Executor toolExecutor;
        private ToolMetrics toolMetrics;
        private final Map<String, String> inputs = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Input items to fan out over. Must not be null or empty.
         *
         * @param items the items to process
         * @return this builder
         */
        public Builder<T> items(List<T> items) {
            this.items = items;
            return this;
        }

        /**
         * Default LLM used to synthesise agents for tasks that have no explicit agent.
         *
         * <p>Optional. When set, this model is passed to each inner {@code Ensemble} so
         * that {@code AgentSynthesizer} can resolve agents for agentless tasks. If not set,
         * each task produced by the task-first factories must carry its own
         * {@code chatLanguageModel}; otherwise synthesis will fail with a
         * {@code ValidationException} at {@code run()} time.
         *
         * <p>Has no effect when all tasks carry explicit agents (agent-first API).
         *
         * @param model the LLM for agent synthesis; may be {@code null} (deferred to per-task LLMs)
         * @return this builder
         */
        public Builder<T> chatLanguageModel(ChatModel model) {
            this.chatLanguageModel = model;
            return this;
        }

        // ========================
        // Agent-first API (power-user)
        // ========================

        /**
         * <b>Agent-first:</b> factory that creates one {@link Agent} per input item for the
         * map phase.
         *
         * <p>Must be used together with {@link #mapTask(BiFunction)}. Mutually exclusive
         * with {@link #mapTask(Function)}.
         *
         * @param factory a function from item to {@link Agent}
         * @return this builder
         */
        public Builder<T> mapAgent(Function<T, Agent> factory) {
            this.mapAgentFactory = factory;
            return this;
        }

        /**
         * <b>Agent-first:</b> factory that creates one {@link Task} per input item for the
         * map phase. The task factory receives the item and the agent produced by
         * {@link #mapAgent(Function)}.
         *
         * <p>Must be used together with {@link #mapAgent(Function)}. Mutually exclusive
         * with {@link #mapTask(Function)}.
         *
         * @param factory a function from (item, agent) to {@link Task}
         * @return this builder
         */
        public Builder<T> mapTask(BiFunction<T, Agent, Task> factory) {
            this.mapTaskFactory = factory;
            return this;
        }

        /**
         * <b>Agent-first:</b> factory that creates one {@link Agent} per reduce group.
         *
         * <p>Must be used together with {@link #reduceTask(BiFunction)}. Mutually exclusive
         * with {@link #reduceTask(Function)}.
         *
         * @param factory a supplier of {@link Agent}
         * @return this builder
         */
        public Builder<T> reduceAgent(Supplier<Agent> factory) {
            this.reduceAgentFactory = factory;
            return this;
        }

        /**
         * <b>Agent-first:</b> factory that creates one {@link Task} per reduce group. The
         * factory <strong>must</strong> wire {@code .context(chunkTasks)} on the returned task.
         *
         * <p>Must be used together with {@link #reduceAgent(Supplier)}. Mutually exclusive
         * with {@link #reduceTask(Function)}.
         *
         * @param factory a function from (agent, chunkTasks) to {@link Task}
         * @return this builder
         */
        public Builder<T> reduceTask(BiFunction<Agent, List<Task>, Task> factory) {
            this.reduceTaskFactory = factory;
            return this;
        }

        // ========================
        // Task-first API (v2.0.0)
        // ========================

        /**
         * <b>Task-first:</b> factory that creates one {@link Task} per input item for the
         * map phase. No explicit agent is required; the framework synthesises one from the
         * task description using the ensemble's configured {@code AgentSynthesizer}.
         *
         * <p>Mutually exclusive with the agent-first pair
         * {@link #mapAgent(Function)} + {@link #mapTask(BiFunction)}.
         *
         * <pre>
         * .mapTask(item -&gt; Task.of("Analyse " + item.getName()))
         * </pre>
         *
         * @param factory a function from item to {@link Task}; must not be null
         * @return this builder
         */
        public Builder<T> mapTask(Function<T, Task> factory) {
            this.mapTaskOnlyFactory = factory;
            return this;
        }

        /**
         * <b>Task-first:</b> factory that creates one {@link Task} per reduce group. The
         * factory <strong>must</strong> wire {@code .context(chunkTasks)} on the returned task.
         * No explicit agent is required; the framework synthesises one automatically.
         *
         * <p>Mutually exclusive with the agent-first pair
         * {@link #reduceAgent(Supplier)} + {@link #reduceTask(BiFunction)}.
         *
         * <pre>
         * .reduceTask(chunkTasks -&gt; Task.builder()
         *     .description("Combine these results")
         *     .expectedOutput("A consolidated summary")
         *     .context(chunkTasks)
         *     .build())
         * </pre>
         *
         * @param factory a function from chunkTasks to {@link Task}; must not be null
         * @return this builder
         */
        public Builder<T> reduceTask(Function<List<Task>, Task> factory) {
            this.reduceTaskOnlyFactory = factory;
            return this;
        }

        // ========================
        // Strategy selection
        // ========================

        /**
         * <b>Static mode:</b> maximum number of tasks per reduce group. Must be &gt;= 2.
         * Default: {@code 5}. Mutually exclusive with {@code targetTokenBudget}.
         *
         * @param chunkSize the maximum group size
         * @return this builder
         */
        public Builder<T> chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        /**
         * <b>Adaptive mode:</b> token budget per reduce group. After each level, outputs
         * are bin-packed so that each bin's total token count does not exceed this value.
         * Must be &gt; 0. Mutually exclusive with {@code chunkSize}.
         *
         * @param targetTokenBudget the per-group token limit
         * @return this builder
         */
        public Builder<T> targetTokenBudget(int targetTokenBudget) {
            this.targetTokenBudget = targetTokenBudget;
            return this;
        }

        /**
         * <b>Adaptive mode (convenience):</b> derive {@code targetTokenBudget} from
         * {@code contextWindowSize * budgetRatio}. Must be set together with
         * {@link #budgetRatio(double)}.
         *
         * @param contextWindowSize the model's context window size in tokens
         * @return this builder
         */
        public Builder<T> contextWindowSize(int contextWindowSize) {
            this.contextWindowSize = contextWindowSize;
            return this;
        }

        /**
         * <b>Adaptive mode (convenience):</b> fraction of context window to use as
         * {@code targetTokenBudget}. Must be in range {@code (0.0, 1.0]}. Default: {@code 0.5}.
         * Must be set together with {@link #contextWindowSize(int)}.
         *
         * @param budgetRatio the fraction of context window for reduce input
         * @return this builder
         */
        public Builder<T> budgetRatio(double budgetRatio) {
            this.budgetRatio = budgetRatio;
            return this;
        }

        /**
         * <b>Adaptive mode:</b> maximum number of reduce iterations before the final
         * reduce is forced regardless of remaining token count. Must be &gt;= 1.
         * Default: {@code 10}.
         *
         * @param maxReduceLevels the maximum adaptive reduce levels
         * @return this builder
         */
        public Builder<T> maxReduceLevels(int maxReduceLevels) {
            this.maxReduceLevels = maxReduceLevels;
            return this;
        }

        /**
         * <b>Adaptive mode:</b> custom token estimator function. When provided, this
         * function is used when the LLM provider does not return token counts, overriding
         * the default heuristic ({@code rawText.length() / 4}).
         *
         * @param tokenEstimator function mapping raw text to estimated token count
         * @return this builder
         */
        public Builder<T> tokenEstimator(Function<String, Integer> tokenEstimator) {
            this.tokenEstimator = tokenEstimator;
            return this;
        }

        // ========================
        // Short-circuit (adaptive mode only)
        // ========================

        /**
         * <b>Agent-first, adaptive mode, short-circuit:</b> factory for the agent that
         * handles the direct task when the total estimated input size fits within
         * {@code targetTokenBudget}. Must be set together with
         * {@link #directTask(BiFunction)}.
         *
         * <p>Mutually exclusive with {@link #directTask(Function)} (task-first).
         *
         * @param factory a supplier that returns the {@link Agent} for the direct task
         * @return this builder
         */
        public Builder<T> directAgent(Supplier<Agent> factory) {
            this.directAgentFactory = factory;
            return this;
        }

        /**
         * <b>Agent-first, adaptive mode, short-circuit:</b> factory for the task executed
         * when the short-circuit fires. Receives the direct agent and the complete
         * {@code List<T>} of all items. Must be set together with {@link #directAgent}.
         *
         * <p>Mutually exclusive with {@link #directTask(Function)} (task-first).
         *
         * @param factory a function from (agent, allItems) to {@link Task}
         * @return this builder
         */
        public Builder<T> directTask(BiFunction<Agent, List<T>, Task> factory) {
            this.directTaskFactory = factory;
            return this;
        }

        /**
         * <b>Task-first, adaptive mode, short-circuit:</b> factory for the task executed
         * when the short-circuit fires. Receives the complete {@code List<T>} of all items.
         * No explicit agent is required; the framework synthesises one automatically.
         *
         * <p>Mutually exclusive with the agent-first pair
         * {@link #directAgent(Supplier)} + {@link #directTask(BiFunction)}.
         *
         * <pre>
         * .directTask(allItems -&gt; Task.builder()
         *     .description("Analyse all items at once: " + allItems)
         *     .expectedOutput("A complete analysis")
         *     .build())
         * </pre>
         *
         * @param factory a function from allItems to {@link Task}; must not be null
         * @return this builder
         */
        public Builder<T> directTask(Function<List<T>, Task> factory) {
            this.directTaskOnlyFactory = factory;
            return this;
        }

        /**
         * Function that converts each input item to a text representation used for adaptive
         * short-circuit input size estimation. When not provided, defaults to
         * {@code Object::toString}.
         *
         * @param estimator function from item to its text representation
         * @return this builder
         */
        public Builder<T> inputEstimator(Function<T, String> estimator) {
            this.inputEstimator = estimator;
            return this;
        }

        // ========================
        // Common passthrough
        // ========================

        /**
         * When {@code true}, elevates execution logging to INFO level. Default: {@code false}.
         *
         * @param verbose whether to enable verbose logging
         * @return this builder
         */
        public Builder<T> verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }

        /**
         * Register an {@link EnsembleListener} for task lifecycle events.
         *
         * @param listener the listener to register
         * @return this builder
         */
        public Builder<T> listener(EnsembleListener listener) {
            this.listeners.add(listener);
            return this;
        }

        /**
         * Register multiple {@link EnsembleListener}s at once.
         *
         * @param listeners the listeners to register
         * @return this builder
         */
        public Builder<T> listeners(List<EnsembleListener> listeners) {
            this.listeners.addAll(listeners);
            return this;
        }

        /**
         * Depth of data collection during each run. Default: {@link CaptureMode#OFF}.
         *
         * @param captureMode the capture mode to use
         * @return this builder
         */
        public Builder<T> captureMode(CaptureMode captureMode) {
            this.captureMode = captureMode;
            return this;
        }

        /**
         * Error handling strategy for parallel execution.
         * Default: {@link ParallelErrorStrategy#FAIL_FAST}.
         *
         * @param strategy the error strategy to use
         * @return this builder
         */
        public Builder<T> parallelErrorStrategy(ParallelErrorStrategy strategy) {
            this.parallelErrorStrategy = strategy;
            return this;
        }

        /**
         * Optional per-token cost rates for cost estimation. Default: {@code null}.
         *
         * @param costConfiguration the cost configuration to use
         * @return this builder
         */
        public Builder<T> costConfiguration(CostConfiguration costConfiguration) {
            this.costConfiguration = costConfiguration;
            return this;
        }

        /**
         * Optional exporter called after each run with the complete execution trace.
         * Default: {@code null}.
         *
         * @param traceExporter the exporter to use
         * @return this builder
         */
        public Builder<T> traceExporter(ExecutionTraceExporter traceExporter) {
            this.traceExporter = traceExporter;
            return this;
        }

        /**
         * Executor for running parallel tool calls within a single LLM turn.
         *
         * @param toolExecutor the executor to use
         * @return this builder
         */
        public Builder<T> toolExecutor(Executor toolExecutor) {
            this.toolExecutor = toolExecutor;
            return this;
        }

        /**
         * Metrics backend for recording tool execution measurements.
         *
         * @param toolMetrics the metrics backend to use
         * @return this builder
         */
        public Builder<T> toolMetrics(ToolMetrics toolMetrics) {
            this.toolMetrics = toolMetrics;
            return this;
        }

        /**
         * Add a single template variable input.
         *
         * @param key   the variable name
         * @param value the variable value
         * @return this builder
         */
        public Builder<T> input(String key, String value) {
            this.inputs.put(key, value);
            return this;
        }

        /**
         * Add multiple template variable inputs.
         *
         * @param inputs the variables to add
         * @return this builder
         */
        public Builder<T> inputs(Map<String, String> inputs) {
            this.inputs.putAll(inputs);
            return this;
        }

        /**
         * Validate the configuration and build a configured {@link MapReduceEnsemble}.
         *
         * @return a {@link MapReduceEnsemble} instance
         * @throws ValidationException if any required field is missing or invalid
         */
        public MapReduceEnsemble<T> build() {
            validateItems();
            validateMapFactories();
            validateReduceFactories();

            int resolvedBudget = resolveTargetTokenBudget();
            validateAdaptiveFields(resolvedBudget);
            validateDirectFields(resolvedBudget);

            Map<String, String> immutableInputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));

            if (resolvedBudget > 0) {
                // Adaptive mode
                return buildAdaptive(resolvedBudget, immutableInputs);
            } else {
                // Static mode
                int effectiveChunkSize = chunkSize != null ? chunkSize : 5;
                validateStaticFields(effectiveChunkSize);
                return buildStatic(effectiveChunkSize, immutableInputs);
            }
        }

        // ========================
        // Validation helpers
        // ========================

        private void validateItems() {
            if (items == null || items.isEmpty()) {
                throw new ValidationException("items must not be null or empty");
            }
        }

        private void validateMapFactories() {
            boolean hasAgentFirst = mapAgentFactory != null || mapTaskFactory != null;
            boolean hasTaskFirst = mapTaskOnlyFactory != null;

            if (hasAgentFirst && hasTaskFirst) {
                throw new ValidationException(
                        "Ambiguous map configuration: use either the task-first mapTask(Function<T, Task>) "
                                + "or the agent-first pair mapAgent + mapTask(BiFunction<T, Agent, Task>), not both.");
            }
            if (!hasAgentFirst && !hasTaskFirst) {
                throw new ValidationException(
                        "mapTask must be set. Use mapTask(Function<T, Task>) for the task-first API "
                                + "(agents are synthesised automatically), or provide both "
                                + "mapAgent and mapTask(BiFunction<T, Agent, Task>) for the agent-first API.");
            }
            if (hasAgentFirst) {
                if (mapAgentFactory == null) {
                    throw new ValidationException("mapAgent factory must not be null when using agent-first map. "
                            + "Either add mapAgent(...) or switch to the task-first "
                            + "mapTask(Function<T, Task>).");
                }
                if (mapTaskFactory == null) {
                    throw new ValidationException(
                            "mapTask(BiFunction<T, Agent, Task>) must not be null when using agent-first map. "
                                    + "Either add mapTask(BiFunction<T, Agent, Task>) or switch to the "
                                    + "task-first mapTask(Function<T, Task>).");
                }
            }
        }

        private void validateReduceFactories() {
            boolean hasAgentFirst = reduceAgentFactory != null || reduceTaskFactory != null;
            boolean hasTaskFirst = reduceTaskOnlyFactory != null;

            if (hasAgentFirst && hasTaskFirst) {
                throw new ValidationException("Ambiguous reduce configuration: use either the task-first "
                        + "reduceTask(Function<List<Task>, Task>) or the agent-first pair "
                        + "reduceAgent + reduceTask(BiFunction<Agent, List<Task>, Task>), not both.");
            }
            if (!hasAgentFirst && !hasTaskFirst) {
                throw new ValidationException(
                        "reduceTask must be set. Use reduceTask(Function<List<Task>, Task>) for the task-first API "
                                + "(agents are synthesised automatically), or provide both "
                                + "reduceAgent and reduceTask(BiFunction<Agent, List<Task>, Task>) for the agent-first API.");
            }
            if (hasAgentFirst) {
                if (reduceAgentFactory == null) {
                    throw new ValidationException("reduceAgent factory must not be null when using agent-first reduce. "
                            + "Either add reduceAgent(...) or switch to the task-first "
                            + "reduceTask(Function<List<Task>, Task>).");
                }
                if (reduceTaskFactory == null) {
                    throw new ValidationException(
                            "reduceTask(BiFunction<Agent, List<Task>, Task>) must not be null when using agent-first reduce. "
                                    + "Either add reduceTask(BiFunction<Agent, List<Task>, Task>) or switch to the "
                                    + "task-first reduceTask(Function<List<Task>, Task>).");
                }
            }
        }

        private void validateDirectFields(int resolvedBudget) {
            boolean hasAgentFirstDirect = directAgentFactory != null || directTaskFactory != null;
            boolean hasTaskFirstDirect = directTaskOnlyFactory != null;

            if (hasAgentFirstDirect && hasTaskFirstDirect) {
                throw new ValidationException("Ambiguous direct task configuration: use either the task-first "
                        + "directTask(Function<List<T>, Task>) or the agent-first pair "
                        + "directAgent + directTask(BiFunction<Agent, List<T>, Task>), not both.");
            }

            boolean hasDirect = hasAgentFirstDirect || hasTaskFirstDirect;
            if (!hasDirect) {
                return; // No direct configuration: OK
            }

            if (hasAgentFirstDirect) {
                if (directAgentFactory != null && directTaskFactory == null) {
                    throw new ValidationException(
                            "directAgent requires directTask(BiFunction<Agent, List<T>, Task>) to also be set. "
                                    + "Both must be configured together for the short-circuit optimization.");
                }
                if (directTaskFactory != null && directAgentFactory == null) {
                    throw new ValidationException(
                            "directTask(BiFunction<Agent, List<T>, Task>) requires directAgent to also be set. "
                                    + "Both must be configured together for the short-circuit optimization.");
                }
            }

            // Short-circuit only applies to adaptive mode, not static (chunkSize) mode
            if (resolvedBudget == 0) {
                throw new ValidationException("directAgent/directTask are not supported in static (chunkSize) mode. "
                        + "Short-circuit optimization requires targetTokenBudget (or "
                        + "contextWindowSize + budgetRatio) to be set. "
                        + "Remove the direct task configuration or switch to adaptive mode.");
            }
        }

        private void validateStaticFields(int effectiveChunkSize) {
            if (effectiveChunkSize < 2) {
                throw new ValidationException("chunkSize must be >= 2, got: " + effectiveChunkSize);
            }
        }

        /**
         * Resolve {@code targetTokenBudget}:
         * <ul>
         *   <li>If {@code contextWindowSize > 0} and {@code budgetRatio > 0}: derive budget.</li>
         *   <li>Else if {@code targetTokenBudget > 0}: use directly.</li>
         *   <li>Else: return 0 (static mode).</li>
         * </ul>
         */
        private int resolveTargetTokenBudget() {
            boolean hasContextWindow = contextWindowSize > 0;
            boolean hasBudgetRatio = budgetRatio > 0.0;
            boolean hasDirectBudget = targetTokenBudget != null; // null = not explicitly set

            // contextWindowSize and budgetRatio must both be set or neither
            if (hasContextWindow && !hasBudgetRatio) {
                throw new ValidationException("budgetRatio must be set when contextWindowSize is specified. "
                        + "Example: .contextWindowSize(128_000).budgetRatio(0.5)");
            }
            if (hasBudgetRatio && !hasContextWindow) {
                throw new ValidationException("contextWindowSize must be set when budgetRatio is specified. "
                        + "Example: .contextWindowSize(128_000).budgetRatio(0.5)");
            }

            if (hasContextWindow) {
                // Validate budgetRatio range: (0.0, 1.0]
                if (budgetRatio <= 0.0 || budgetRatio > 1.0) {
                    throw new ValidationException("budgetRatio must be in range (0.0, 1.0], got: " + budgetRatio);
                }
                int derived = (int) (contextWindowSize * budgetRatio);
                if (derived <= 0) {
                    throw new ValidationException(
                            "targetTokenBudget derived from contextWindowSize * budgetRatio must be > 0, " + "got: "
                                    + contextWindowSize + " * " + budgetRatio + " = " + derived);
                }
                // Mutual exclusivity: cannot set chunkSize AND contextWindowSize/budgetRatio
                if (chunkSize != null) {
                    throw new ValidationException(
                            "chunkSize and targetTokenBudget (derived from contextWindowSize * budgetRatio) "
                                    + "are mutually exclusive. Use chunkSize for static mode or "
                                    + "contextWindowSize/budgetRatio for adaptive mode, not both.");
                }
                return derived;
            }

            if (hasDirectBudget) {
                // Validate the explicitly-set value: must be > 0
                if (targetTokenBudget <= 0) {
                    throw new ValidationException("targetTokenBudget must be > 0, got: " + targetTokenBudget);
                }
                // Mutual exclusivity: cannot set chunkSize AND targetTokenBudget
                if (chunkSize != null) {
                    throw new ValidationException("chunkSize and targetTokenBudget are mutually exclusive. "
                            + "Use chunkSize for static mode or targetTokenBudget for adaptive mode, "
                            + "not both.");
                }
                return targetTokenBudget;
            }

            // Neither adaptive field set: static mode
            return 0;
        }

        private void validateAdaptiveFields(int resolvedBudget) {
            if (resolvedBudget == 0) {
                return; // Static mode: no adaptive field validation needed
            }

            if (maxReduceLevels < 1) {
                throw new ValidationException("maxReduceLevels must be >= 1, got: " + maxReduceLevels);
            }
        }

        // ========================
        // Build helpers
        // ========================

        private MapReduceEnsemble<T> buildAdaptive(int resolvedBudget, Map<String, String> immutableInputs) {
            MapReduceAdaptiveExecutor<T> executor = new MapReduceAdaptiveExecutor<>(
                    Collections.unmodifiableList(new ArrayList<>(items)),
                    chatLanguageModel,
                    mapAgentFactory,
                    mapTaskFactory,
                    mapTaskOnlyFactory,
                    reduceAgentFactory,
                    reduceTaskFactory,
                    reduceTaskOnlyFactory,
                    directAgentFactory,
                    directTaskFactory,
                    directTaskOnlyFactory,
                    inputEstimator,
                    resolvedBudget,
                    maxReduceLevels,
                    tokenEstimator,
                    verbose,
                    Collections.unmodifiableList(new ArrayList<>(listeners)),
                    captureMode,
                    parallelErrorStrategy,
                    costConfiguration,
                    traceExporter,
                    toolExecutor,
                    toolMetrics);
            return new MapReduceEnsemble<>(executor, immutableInputs);
        }

        private MapReduceEnsemble<T> buildStatic(int effectiveChunkSize, Map<String, String> immutableInputs) {
            IdentityHashMap<Task, String> nodeTypes = new IdentityHashMap<>();
            IdentityHashMap<Task, Integer> mapReduceLevels = new IdentityHashMap<>();

            List<Task> allTasks = new ArrayList<>();

            // Step 1: Create N map tasks (task-first or agent-first)
            List<Task> mapTasks = new ArrayList<>(items.size());
            for (T item : items) {
                Task task = createMapTask(item);
                allTasks.add(task);
                mapTasks.add(task);
                nodeTypes.put(task, NODE_TYPE_MAP);
                mapReduceLevels.put(task, 0);
            }

            // Steps 2+: Build reduce levels
            if (items.size() <= effectiveChunkSize) {
                Task finalTask = createReduceTask(Collections.unmodifiableList(mapTasks));
                allTasks.add(finalTask);
                nodeTypes.put(finalTask, NODE_TYPE_FINAL_REDUCE);
                mapReduceLevels.put(finalTask, 1);
            } else {
                List<Task> currentLevel = mapTasks;
                int reduceLevel = 1;

                while (currentLevel.size() > effectiveChunkSize) {
                    List<Task> nextLevel = new ArrayList<>();
                    List<List<Task>> groups = partition(currentLevel, effectiveChunkSize);
                    for (List<Task> group : groups) {
                        Task task = createReduceTask(Collections.unmodifiableList(group));
                        allTasks.add(task);
                        nextLevel.add(task);
                        nodeTypes.put(task, NODE_TYPE_REDUCE);
                        mapReduceLevels.put(task, reduceLevel);
                    }
                    currentLevel = nextLevel;
                    reduceLevel++;
                }

                Task finalTask = createReduceTask(Collections.unmodifiableList(currentLevel));
                allTasks.add(finalTask);
                nodeTypes.put(finalTask, NODE_TYPE_FINAL_REDUCE);
                mapReduceLevels.put(finalTask, reduceLevel);
            }

            Ensemble.EnsembleBuilder ensembleBuilder = Ensemble.builder()
                    .workflow(Workflow.PARALLEL)
                    .verbose(verbose)
                    .parallelErrorStrategy(parallelErrorStrategy)
                    .captureMode(captureMode);

            if (chatLanguageModel != null) {
                ensembleBuilder.chatLanguageModel(chatLanguageModel);
            }
            for (Task task : allTasks) {
                ensembleBuilder.task(task);
            }
            for (EnsembleListener listener : listeners) {
                ensembleBuilder.listener(listener);
            }
            for (Map.Entry<String, String> entry : inputs.entrySet()) {
                ensembleBuilder.input(entry.getKey(), entry.getValue());
            }
            if (costConfiguration != null) {
                ensembleBuilder.costConfiguration(costConfiguration);
            }
            if (traceExporter != null) {
                ensembleBuilder.traceExporter(traceExporter);
            }
            if (toolExecutor != null) {
                ensembleBuilder.toolExecutor(toolExecutor);
            }
            if (toolMetrics != null) {
                ensembleBuilder.toolMetrics(toolMetrics);
            }

            Ensemble ensemble = ensembleBuilder.build();
            return new MapReduceEnsemble<>(ensemble, immutableInputs, nodeTypes, mapReduceLevels);
        }

        /**
         * Create one map task for the given item, using whichever factory style is configured.
         */
        private Task createMapTask(T item) {
            if (mapTaskOnlyFactory != null) {
                return mapTaskOnlyFactory.apply(item);
            }
            Agent agent = mapAgentFactory.apply(item);
            return mapTaskFactory.apply(item, agent);
        }

        /**
         * Create one reduce task for the given chunk of upstream tasks, using whichever
         * factory style is configured.
         */
        private Task createReduceTask(List<Task> chunkTasks) {
            if (reduceTaskOnlyFactory != null) {
                return reduceTaskOnlyFactory.apply(chunkTasks);
            }
            Agent agent = reduceAgentFactory.get();
            return reduceTaskFactory.apply(agent, chunkTasks);
        }

        /**
         * Partition a list into sublists of at most {@code size} elements.
         */
        private static <E> List<List<E>> partition(List<E> list, int size) {
            List<List<E>> result = new ArrayList<>();
            for (int i = 0; i < list.size(); i += size) {
                result.add(list.subList(i, Math.min(i + size, list.size())));
            }
            return result;
        }
    }
}
