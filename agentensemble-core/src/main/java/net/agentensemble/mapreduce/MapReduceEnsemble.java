package net.agentensemble.mapreduce;

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
 * <h2>Usage (static)</h2>
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
 * <h2>Usage (adaptive)</h2>
 *
 * <pre>
 * EnsembleOutput output = MapReduceEnsemble.&lt;OrderItem&gt;builder()
 *     .items(order.getItems())
 *     // ...same factories as above...
 *     .targetTokenBudget(8_000)   // or .contextWindowSize(128_000).budgetRatio(0.5)
 *     .maxReduceLevels(10)
 *     .build()
 *     .run();
 * </pre>
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
     * Returns a map from task identity to node type string for devtools enrichment.
     *
     * <p>Available in static mode only. Returns {@code null} in adaptive mode.
     *
     * @return an identity-keyed map of task to node type, or {@code null} in adaptive mode
     */
    public IdentityHashMap<Task, String> getNodeTypes() {
        return nodeTypes;
    }

    /**
     * Returns a map from task identity to map-reduce level for devtools enrichment.
     *
     * <p>Available in static mode only. Returns {@code null} in adaptive mode.
     *
     * @return an identity-keyed map of task to level, or {@code null} in adaptive mode
     */
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
     * <p>Required fields: {@code items}, {@code mapAgent}, {@code mapTask},
     * {@code reduceAgent}, {@code reduceTask}.
     *
     * <p>Strategy selection: set {@code chunkSize} for static mode, or
     * {@code targetTokenBudget} / {@code contextWindowSize} + {@code budgetRatio} for
     * adaptive mode. These are mutually exclusive. Default (neither set): static with
     * {@code chunkSize=5}.
     *
     * @param <T> the type of each input item
     */
    public static final class Builder<T> {

        private List<T> items;
        private Function<T, Agent> mapAgentFactory;
        private BiFunction<T, Agent, Task> mapTaskFactory;
        private Supplier<Agent> reduceAgentFactory;
        private BiFunction<Agent, List<Task>, Task> reduceTaskFactory;

        // Short-circuit fields (adaptive mode only)
        private Supplier<Agent> directAgentFactory = null;
        private BiFunction<Agent, List<T>, Task> directTaskFactory = null;
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
         * Factory that creates one {@link Agent} per input item for the map phase.
         *
         * @param factory a function from item to {@link Agent}
         * @return this builder
         */
        public Builder<T> mapAgent(Function<T, Agent> factory) {
            this.mapAgentFactory = factory;
            return this;
        }

        /**
         * Factory that creates one {@link Task} per input item for the map phase.
         *
         * @param factory a function from (item, agent) to {@link Task}
         * @return this builder
         */
        public Builder<T> mapTask(BiFunction<T, Agent, Task> factory) {
            this.mapTaskFactory = factory;
            return this;
        }

        /**
         * Factory that creates one {@link Agent} per reduce group.
         *
         * @param factory a supplier of {@link Agent}
         * @return this builder
         */
        public Builder<T> reduceAgent(Supplier<Agent> factory) {
            this.reduceAgentFactory = factory;
            return this;
        }

        /**
         * Factory that creates one {@link Task} per reduce group. The factory
         * <strong>must</strong> wire {@code .context(chunkTasks)} on the returned task.
         *
         * @param factory a function from (agent, chunkTasks) to {@link Task}
         * @return this builder
         */
        public Builder<T> reduceTask(BiFunction<Agent, List<Task>, Task> factory) {
            this.reduceTaskFactory = factory;
            return this;
        }

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

        /**
         * <b>Adaptive mode, short-circuit:</b> factory for the agent that handles the
         * direct task when the total estimated input size fits within
         * {@code targetTokenBudget}. Must be set together with {@link #directTask}.
         *
         * <p>When both {@code directAgent} and {@code directTask} are configured and the
         * estimated input token count does not exceed {@code targetTokenBudget}, the entire
         * map-reduce pipeline is bypassed in favour of a single direct LLM call.
         *
         * @param factory a supplier that returns the {@link Agent} for the direct task
         * @return this builder
         */
        public Builder<T> directAgent(Supplier<Agent> factory) {
            this.directAgentFactory = factory;
            return this;
        }

        /**
         * <b>Adaptive mode, short-circuit:</b> factory for the task executed when the
         * short-circuit fires. Receives the direct agent and the complete {@code List<T>}
         * of all items. Must be set together with {@link #directAgent}.
         *
         * @param factory a function from (agent, allItems) to {@link Task}
         * @return this builder
         */
        public Builder<T> directTask(BiFunction<Agent, List<T>, Task> factory) {
            this.directTaskFactory = factory;
            return this;
        }

        /**
         * <b>Adaptive mode, short-circuit:</b> function that converts each input item to
         * a text representation used for input size estimation. When not provided, defaults
         * to {@code Object::toString}.
         *
         * <p>Providing a compact representation (e.g., a JSON summary) allows more accurate
         * short-circuit decisions when the full {@code toString()} is verbose.
         *
         * @param estimator function from item to its text representation
         * @return this builder
         */
        public Builder<T> inputEstimator(Function<T, String> estimator) {
            this.inputEstimator = estimator;
            return this;
        }

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
            validateCommonFields();
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

        private void validateDirectFields(int resolvedBudget) {
            boolean hasDirect = directAgentFactory != null || directTaskFactory != null;
            if (!hasDirect) {
                return; // Nothing to validate
            }

            // directAgent and directTask must both be set or both be null
            if (directAgentFactory != null && directTaskFactory == null) {
                throw new ValidationException("directAgent requires directTask to also be set. "
                        + "Both must be configured together for the short-circuit optimization.");
            }
            if (directTaskFactory != null && directAgentFactory == null) {
                throw new ValidationException("directTask requires directAgent to also be set. "
                        + "Both must be configured together for the short-circuit optimization.");
            }

            // Short-circuit only applies to adaptive mode
            if (resolvedBudget == 0) {
                throw new ValidationException("directAgent and directTask are only supported in adaptive mode. "
                        + "Short-circuit optimization requires targetTokenBudget (or "
                        + "contextWindowSize + budgetRatio) to be set. "
                        + "Remove directAgent/directTask or switch to adaptive mode to use "
                        + "this feature in static mode.");
            }
        }

        private void validateCommonFields() {
            if (items == null || items.isEmpty()) {
                throw new ValidationException("items must not be null or empty");
            }
            if (mapAgentFactory == null) {
                throw new ValidationException("mapAgent factory must not be null");
            }
            if (mapTaskFactory == null) {
                throw new ValidationException("mapTask factory must not be null");
            }
            if (reduceAgentFactory == null) {
                throw new ValidationException("reduceAgent factory must not be null");
            }
            if (reduceTaskFactory == null) {
                throw new ValidationException("reduceTask factory must not be null");
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

            // Value range is validated in resolveTargetTokenBudget() for direct budget
            // and contextWindowSize/budgetRatio derivation path.

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
                    mapAgentFactory,
                    mapTaskFactory,
                    reduceAgentFactory,
                    reduceTaskFactory,
                    directAgentFactory,
                    directTaskFactory,
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

            List<Agent> allAgents = new ArrayList<>();
            List<Task> allTasks = new ArrayList<>();

            // Step 1: Create N map agents and tasks
            List<Task> mapTasks = new ArrayList<>(items.size());
            for (T item : items) {
                Agent agent = mapAgentFactory.apply(item);
                Task task = mapTaskFactory.apply(item, agent);
                allAgents.add(agent);
                allTasks.add(task);
                mapTasks.add(task);
                nodeTypes.put(task, NODE_TYPE_MAP);
                mapReduceLevels.put(task, 0);
            }

            // Steps 2+: Build reduce levels
            if (items.size() <= effectiveChunkSize) {
                Agent finalAgent = reduceAgentFactory.get();
                Task finalTask = reduceTaskFactory.apply(finalAgent, Collections.unmodifiableList(mapTasks));
                allAgents.add(finalAgent);
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
                        Agent agent = reduceAgentFactory.get();
                        Task task = reduceTaskFactory.apply(agent, Collections.unmodifiableList(group));
                        allAgents.add(agent);
                        allTasks.add(task);
                        nextLevel.add(task);
                        nodeTypes.put(task, NODE_TYPE_REDUCE);
                        mapReduceLevels.put(task, reduceLevel);
                    }
                    currentLevel = nextLevel;
                    reduceLevel++;
                }

                Agent finalAgent = reduceAgentFactory.get();
                Task finalTask = reduceTaskFactory.apply(finalAgent, Collections.unmodifiableList(currentLevel));
                allAgents.add(finalAgent);
                allTasks.add(finalTask);
                nodeTypes.put(finalTask, NODE_TYPE_FINAL_REDUCE);
                mapReduceLevels.put(finalTask, reduceLevel);
            }

            Ensemble.EnsembleBuilder ensembleBuilder = Ensemble.builder()
                    .workflow(Workflow.PARALLEL)
                    .verbose(verbose)
                    .parallelErrorStrategy(parallelErrorStrategy)
                    .captureMode(captureMode);

            for (Agent agent : allAgents) {
                ensembleBuilder.agent(agent);
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
