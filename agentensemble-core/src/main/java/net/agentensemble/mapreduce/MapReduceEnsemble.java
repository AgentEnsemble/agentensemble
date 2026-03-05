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
 * A builder that constructs a static tree-reduction DAG and executes it via a single
 * {@link Ensemble#run()} with {@link Workflow#PARALLEL}.
 *
 * <p>When using {@link Workflow#PARALLEL} to fan out to N agents and aggregate their outputs
 * with a single reduce task, the aggregator's context grows as {@code N * avg_output_size}.
 * For large N or verbose agent output, this approaches or exceeds the model's context window.
 * {@code MapReduceEnsemble} solves this by building a multi-level tree-reduction DAG
 * automatically, keeping each reducer's context bounded by {@code chunkSize}.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * EnsembleOutput output = MapReduceEnsemble.&lt;OrderItem&gt;builder()
 *     .items(order.getItems())
 *
 *     // Map phase: one agent + task per item
 *     .mapAgent(item -&gt; Agent.builder()
 *         .role(item.getDish() + " Chef")
 *         .goal("Prepare " + item.getDish())
 *         .llm(model)
 *         .build())
 *     .mapTask((item, agent) -&gt; Task.builder()
 *         .description("Execute the recipe for: " + item.getDish())
 *         .expectedOutput("Recipe with ingredients, steps, and timing")
 *         .agent(agent)
 *         .outputType(DishResult.class)
 *         .build())
 *
 *     // Reduce phase: consolidate groups of chunkSize outputs
 *     .reduceAgent(() -&gt; Agent.builder()
 *         .role("Sub-Chef")
 *         .goal("Consolidate dish preparations")
 *         .llm(model)
 *         .build())
 *     .reduceTask((agent, chunkTasks) -&gt; Task.builder()
 *         .description("Consolidate these dish preparations.")
 *         .expectedOutput("Consolidated plan")
 *         .agent(agent)
 *         .context(chunkTasks)   // must wire context explicitly
 *         .build())
 *
 *     .chunkSize(3)
 *     .verbose(true)
 *     .build()
 *     .run();
 * </pre>
 *
 * <h2>Static DAG construction</h2>
 *
 * <p>Given N items and chunkSize K, the DAG has O(log_K(N)) levels:
 * <ul>
 *   <li>If N &lt;= K: N map tasks + 1 final reduce (context = all map tasks).</li>
 *   <li>If N &gt; K: N map tasks + intermediate reduce levels (one task per group of at
 *       most K) until the current level size &lt;= K, then 1 final reduce.</li>
 * </ul>
 *
 * <h2>Inspection</h2>
 *
 * <p>Call {@link #toEnsemble()} to access the pre-built inner {@link Ensemble} for devtools
 * inspection or DAG export before execution.
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

    /** MapReduce mode identifier reported to devtools. */
    public static final String MAP_REDUCE_MODE = "STATIC";

    private final Ensemble ensemble;
    private final Map<String, String> inputs;
    private final IdentityHashMap<Task, String> nodeTypes;
    private final IdentityHashMap<Task, Integer> mapReduceLevels;

    private MapReduceEnsemble(
            Ensemble ensemble,
            Map<String, String> inputs,
            IdentityHashMap<Task, String> nodeTypes,
            IdentityHashMap<Task, Integer> mapReduceLevels) {
        this.ensemble = ensemble;
        this.inputs = inputs;
        this.nodeTypes = nodeTypes;
        this.mapReduceLevels = mapReduceLevels;
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
     * @return the aggregated {@link EnsembleOutput} from the final reduce task
     * @throws net.agentensemble.exception.ValidationException if the ensemble configuration
     *     is invalid
     */
    public EnsembleOutput run() {
        return ensemble.run();
    }

    /**
     * Execute the ensemble, merging the supplied run-time inputs with the builder inputs.
     * When the same key appears in both, the run-time value takes precedence.
     *
     * @param runtimeInputs additional or overriding template variable values
     * @return the aggregated {@link EnsembleOutput} from the final reduce task
     * @throws net.agentensemble.exception.ValidationException if the ensemble configuration
     *     is invalid
     */
    public EnsembleOutput run(Map<String, String> runtimeInputs) {
        return ensemble.run(runtimeInputs);
    }

    /**
     * Returns the pre-built inner {@link Ensemble} for inspection or devtools export.
     *
     * <p>The returned ensemble represents the full tree-reduction DAG and can be passed to
     * {@code DagExporter.build(MapReduceEnsemble)} (or {@code DagExporter.build(Ensemble)})
     * to export the planned execution graph before running.
     *
     * @return the pre-built inner {@link Ensemble}; never {@code null}
     */
    public Ensemble toEnsemble() {
        return ensemble;
    }

    /**
     * Returns a map from task identity to node type string for devtools enrichment.
     *
     * <p>Values are one of {@link #NODE_TYPE_MAP}, {@link #NODE_TYPE_REDUCE}, or
     * {@link #NODE_TYPE_FINAL_REDUCE}.
     *
     * @return an identity-keyed map of task to node type; never {@code null}
     */
    public IdentityHashMap<Task, String> getNodeTypes() {
        return nodeTypes;
    }

    /**
     * Returns a map from task identity to map-reduce level for devtools enrichment.
     *
     * <p>Level 0 = map tasks, 1+ = intermediate reduce levels, final reduce = the highest
     * level. The exact highest level depends on the tree depth (O(log_K(N))).
     *
     * @return an identity-keyed map of task to level; never {@code null}
     */
    public IdentityHashMap<Task, Integer> getMapReduceLevels() {
        return mapReduceLevels;
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
     * @param <T> the type of each input item
     */
    public static final class Builder<T> {

        private List<T> items;
        private Function<T, Agent> mapAgentFactory;
        private BiFunction<T, Agent, Task> mapTaskFactory;
        private Supplier<Agent> reduceAgentFactory;
        private BiFunction<Agent, List<Task>, Task> reduceTaskFactory;

        private int chunkSize = 5;
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
         * Input items to fan out over. Each item produces one map agent and one map task.
         * Must not be null or empty.
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
         * Called exactly once per item during {@link #build()}. Must not be null.
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
         * Receives the item and the agent produced by {@link #mapAgent(Function)}.
         * Must not be null.
         *
         * @param factory a function from (item, agent) to {@link Task}
         * @return this builder
         */
        public Builder<T> mapTask(BiFunction<T, Agent, Task> factory) {
            this.mapTaskFactory = factory;
            return this;
        }

        /**
         * Factory that creates one {@link Agent} per reduce group. Called once per group at
         * every reduce level, including the final reduce. Must not be null.
         *
         * @param factory a supplier of {@link Agent}
         * @return this builder
         */
        public Builder<T> reduceAgent(Supplier<Agent> factory) {
            this.reduceAgentFactory = factory;
            return this;
        }

        /**
         * Factory that creates one {@link Task} per reduce group. Receives the agent produced
         * by {@link #reduceAgent(Supplier)} and the list of upstream tasks for that group.
         * The factory <strong>must</strong> wire {@code .context(chunkTasks)} on the returned
         * task; the framework does not mutate the returned task.
         * Must not be null.
         *
         * @param factory a function from (agent, chunkTasks) to {@link Task}
         * @return this builder
         */
        public Builder<T> reduceTask(BiFunction<Agent, List<Task>, Task> factory) {
            this.reduceTaskFactory = factory;
            return this;
        }

        /**
         * Maximum number of tasks per reduce group. Groups of at most {@code chunkSize}
         * upstream tasks are fed to each reduce agent. Must be &gt;= 2.
         * Default: {@code 5}.
         *
         * @param chunkSize the maximum group size
         * @return this builder
         */
        public Builder<T> chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        /**
         * When {@code true}, elevates execution logging to INFO level.
         * Passed through to the inner {@link Ensemble}. Default: {@code false}.
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
         * May be called multiple times; all listeners are accumulated.
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
         * Depth of data collection during each run.
         * Passed through to the inner {@link Ensemble}. Default: {@link CaptureMode#OFF}.
         *
         * @param captureMode the capture mode to use
         * @return this builder
         */
        public Builder<T> captureMode(CaptureMode captureMode) {
            this.captureMode = captureMode;
            return this;
        }

        /**
         * Error handling strategy when map or reduce tasks fail in parallel execution.
         * Passed through to the inner {@link Ensemble}.
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
         * Optional per-token cost rates for cost estimation.
         * Passed through to the inner {@link Ensemble}. Default: {@code null}.
         *
         * @param costConfiguration the cost configuration to use
         * @return this builder
         */
        public Builder<T> costConfiguration(CostConfiguration costConfiguration) {
            this.costConfiguration = costConfiguration;
            return this;
        }

        /**
         * Optional exporter called after the run with the complete execution trace.
         * Passed through to the inner {@link Ensemble}. Default: {@code null}.
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
         * When not set, the inner {@link Ensemble} uses its default virtual-thread executor.
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
         * When not set, the inner {@link Ensemble} uses its default no-op metrics.
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
         * Applied on every {@link MapReduceEnsemble#run()} call.
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
         * Applied on every {@link MapReduceEnsemble#run()} call.
         *
         * @param inputs the variables to add
         * @return this builder
         */
        public Builder<T> inputs(Map<String, String> inputs) {
            this.inputs.putAll(inputs);
            return this;
        }

        /**
         * Validate the configuration, build the static tree-reduction DAG, and return a
         * configured {@link MapReduceEnsemble} ready to run.
         *
         * @return a {@link MapReduceEnsemble} instance
         * @throws ValidationException if any required field is missing or invalid
         */
        public MapReduceEnsemble<T> build() {
            validate();
            return buildEnsemble();
        }

        private void validate() {
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
            if (chunkSize < 2) {
                throw new ValidationException("chunkSize must be >= 2, got: " + chunkSize);
            }
        }

        private MapReduceEnsemble<T> buildEnsemble() {
            IdentityHashMap<Task, String> nodeTypes = new IdentityHashMap<>();
            IdentityHashMap<Task, Integer> mapReduceLevels = new IdentityHashMap<>();

            List<Agent> allAgents = new ArrayList<>();
            List<Task> allTasks = new ArrayList<>();

            // Step 1: Create N map agents and tasks (all independent, no context).
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

            // Steps 2-5: Build reduce levels.
            // When N <= K: final reduce gets context of all map tasks directly (no intermediate).
            // When N > K: build intermediate reduce levels until currentLevel.size() <= K,
            //             then create the final reduce.
            if (items.size() <= chunkSize) {
                // Direct path: no intermediate reduce levels.
                Agent finalAgent = reduceAgentFactory.get();
                Task finalTask = reduceTaskFactory.apply(finalAgent, Collections.unmodifiableList(mapTasks));
                allAgents.add(finalAgent);
                allTasks.add(finalTask);
                nodeTypes.put(finalTask, NODE_TYPE_FINAL_REDUCE);
                mapReduceLevels.put(finalTask, 1);
            } else {
                // Build one or more intermediate reduce levels.
                List<Task> currentLevel = mapTasks;
                int reduceLevel = 1;

                while (currentLevel.size() > chunkSize) {
                    List<Task> nextLevel = new ArrayList<>();
                    List<List<Task>> groups = partition(currentLevel, chunkSize);
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

                // Final reduce: context = all tasks in the last intermediate level.
                Agent finalAgent = reduceAgentFactory.get();
                Task finalTask = reduceTaskFactory.apply(finalAgent, Collections.unmodifiableList(currentLevel));
                allAgents.add(finalAgent);
                allTasks.add(finalTask);
                nodeTypes.put(finalTask, NODE_TYPE_FINAL_REDUCE);
                mapReduceLevels.put(finalTask, reduceLevel);
            }

            // Build the inner Ensemble with Workflow.PARALLEL.
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
            Map<String, String> immutableInputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            return new MapReduceEnsemble<>(ensemble, immutableInputs, nodeTypes, mapReduceLevels);
        }

        /**
         * Partition a list into sublists of at most {@code size} elements.
         *
         * <p>The last sublist may be smaller than {@code size} when the list length is not
         * evenly divisible. The returned sublists are views of the original list.
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
