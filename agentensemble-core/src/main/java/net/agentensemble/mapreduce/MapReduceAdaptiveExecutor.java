package net.agentensemble.mapreduce;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ParallelExecutionException;
import net.agentensemble.metrics.CostConfiguration;
import net.agentensemble.metrics.CostEstimate;
import net.agentensemble.metrics.ExecutionMetrics;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.ToolMetrics;
import net.agentensemble.trace.AgentSummary;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.trace.ExecutionTrace;
import net.agentensemble.trace.MapReduceLevelSummary;
import net.agentensemble.trace.TaskTrace;
import net.agentensemble.trace.export.ExecutionTraceExporter;
import net.agentensemble.workflow.ParallelErrorStrategy;
import net.agentensemble.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptive map-reduce executor that drives tree reduction based on actual output
 * token counts rather than a fixed chunk size.
 *
 * <p>Implements the adaptive execution algorithm from the design doc (section 7):
 * <ol>
 *   <li>Map phase: run N independent map tasks in parallel.</li>
 *   <li>Check if total output tokens fit within {@code targetTokenBudget}. If yes,
 *       run a single final reduce and return.</li>
 *   <li>Adaptive reduce loop: bin-pack current outputs, run a reduce Ensemble per
 *       bin, repeat until budget is satisfied or {@code maxReduceLevels} is reached.</li>
 *   <li>Final reduce: run one reduce task over all current outputs.</li>
 *   <li>Aggregate all traces and metrics into a single {@link EnsembleOutput}.</li>
 * </ol>
 *
 * <p>Each level is an independent {@link Ensemble} with {@link Workflow#PARALLEL}.
 * Context from one level to the next is propagated via "carrier" tasks backed by
 * {@link PassthroughChatModel}, which return the previous level's output text without
 * making real LLM calls. Carrier tasks are filtered from the aggregated trace.
 *
 * @param <T> the type of each input item
 */
final class MapReduceAdaptiveExecutor<T> {

    private static final Logger log = LoggerFactory.getLogger(MapReduceAdaptiveExecutor.class);

    private static final String WORKFLOW_ADAPTIVE = "MAP_REDUCE_ADAPTIVE";

    // ========================
    // Configuration
    // ========================

    private final List<T> items;
    private final Function<T, Agent> mapAgentFactory;
    private final BiFunction<T, Agent, Task> mapTaskFactory;
    private final Supplier<Agent> reduceAgentFactory;
    private final BiFunction<Agent, List<Task>, Task> reduceTaskFactory;

    // Short-circuit fields (null when not configured)
    private final Supplier<Agent> directAgentFactory;
    private final BiFunction<Agent, List<T>, Task> directTaskFactory;
    private final Function<T, String> inputEstimatorFn;

    private final int targetTokenBudget;
    private final int maxReduceLevels;
    private final MapReduceTokenEstimator tokenEstimator;

    // Passthrough fields for each inner Ensemble
    private final boolean verbose;
    private final List<EnsembleListener> listeners;
    private final CaptureMode captureMode;
    private final ParallelErrorStrategy parallelErrorStrategy;
    private final CostConfiguration costConfiguration;
    private final ExecutionTraceExporter traceExporter;
    private final Executor toolExecutor;
    private final ToolMetrics toolMetrics;

    MapReduceAdaptiveExecutor(
            List<T> items,
            Function<T, Agent> mapAgentFactory,
            BiFunction<T, Agent, Task> mapTaskFactory,
            Supplier<Agent> reduceAgentFactory,
            BiFunction<Agent, List<Task>, Task> reduceTaskFactory,
            Supplier<Agent> directAgentFactory,
            BiFunction<Agent, List<T>, Task> directTaskFactory,
            Function<T, String> inputEstimatorFn,
            int targetTokenBudget,
            int maxReduceLevels,
            Function<String, Integer> customTokenEstimator,
            boolean verbose,
            List<EnsembleListener> listeners,
            CaptureMode captureMode,
            ParallelErrorStrategy parallelErrorStrategy,
            CostConfiguration costConfiguration,
            ExecutionTraceExporter traceExporter,
            Executor toolExecutor,
            ToolMetrics toolMetrics) {
        this.items = items;
        this.mapAgentFactory = mapAgentFactory;
        this.mapTaskFactory = mapTaskFactory;
        this.reduceAgentFactory = reduceAgentFactory;
        this.reduceTaskFactory = reduceTaskFactory;
        this.directAgentFactory = directAgentFactory;
        this.directTaskFactory = directTaskFactory;
        this.inputEstimatorFn = inputEstimatorFn;
        this.targetTokenBudget = targetTokenBudget;
        this.maxReduceLevels = maxReduceLevels;
        this.tokenEstimator = customTokenEstimator != null
                ? MapReduceTokenEstimator.withCustomEstimator(customTokenEstimator)
                : MapReduceTokenEstimator.defaultEstimator();
        this.verbose = verbose;
        this.listeners = listeners;
        this.captureMode = captureMode;
        this.parallelErrorStrategy = parallelErrorStrategy;
        this.costConfiguration = costConfiguration;
        this.traceExporter = traceExporter;
        this.toolExecutor = toolExecutor;
        this.toolMetrics = toolMetrics;
    }

    /**
     * Execute the adaptive map-reduce run.
     *
     * <p>Before the map phase, checks whether the total estimated input size fits within
     * {@code targetTokenBudget}. If both {@code directAgent} and {@code directTask} are
     * configured and the estimate is within budget, a single direct task is executed
     * instead of the full map-reduce pipeline (short-circuit optimization).
     *
     * @param inputs template variable inputs
     * @return aggregated {@link EnsembleOutput} covering all levels
     */
    EnsembleOutput run(Map<String, String> inputs) {
        String ensembleId = UUID.randomUUID().toString();

        // SHORT-CIRCUIT: if direct factories are configured and total input fits in budget,
        // bypass the entire map-reduce pipeline and run a single direct task.
        if (directAgentFactory != null && directTaskFactory != null) {
            long estimatedInputTokens = estimateInputTokens();
            log.debug(
                    "Adaptive MapReduce: estimated input tokens = {} (budget = {})",
                    estimatedInputTokens,
                    targetTokenBudget);
            if (estimatedInputTokens <= targetTokenBudget) {
                log.info("Adaptive MapReduce: short-circuit fires, running direct task for {} items", items.size());
                return runDirectPhase(ensembleId, inputs);
            }
            log.debug("Adaptive MapReduce: estimated input exceeds budget, running normal map-reduce pipeline");
        }

        List<LevelRun> levelRuns = new ArrayList<>();

        // STEP 1: Map phase
        log.info("Adaptive MapReduce: starting map phase for {} items", items.size());
        EnsembleOutput mapOutput = runMapPhase(inputs);
        levelRuns.add(new LevelRun(0, MapReduceEnsemble.NODE_TYPE_MAP, mapOutput));

        List<TaskOutput> currentOutputs = new ArrayList<>(mapOutput.getTaskOutputs());

        // STEP 2: Check if single reduce is sufficient
        long totalTokens = sumTokens(currentOutputs);
        log.info(
                "Adaptive MapReduce: map phase complete, total output tokens = {} (budget = {})",
                totalTokens,
                targetTokenBudget);

        if (totalTokens <= targetTokenBudget) {
            // All outputs fit within budget: run a single final reduce
            log.info("Adaptive MapReduce: total tokens within budget, running single final reduce");
            int finalLevel = 1;
            EnsembleOutput finalOutput = runFinalReducePhase(currentOutputs, inputs, finalLevel);
            levelRuns.add(new LevelRun(finalLevel, MapReduceEnsemble.NODE_TYPE_FINAL_REDUCE, finalOutput));
            return aggregate(ensembleId, levelRuns, inputs, mapOutput.getTaskOutputs());
        }

        // STEP 3: Adaptive reduce loop
        int reduceLevel = 1;
        while (sumTokens(currentOutputs) > targetTokenBudget && reduceLevel <= maxReduceLevels) {
            log.info(
                    "Adaptive MapReduce: starting intermediate reduce level {} (tokens={}, budget={})",
                    reduceLevel,
                    sumTokens(currentOutputs),
                    targetTokenBudget);

            List<List<TaskOutput>> bins = MapReduceBinPacker.pack(currentOutputs, targetTokenBudget, tokenEstimator);

            EnsembleOutput reduceOutput = runIntermediateReducePhase(bins, inputs, reduceLevel);
            levelRuns.add(new LevelRun(reduceLevel, MapReduceEnsemble.NODE_TYPE_REDUCE, reduceOutput));

            currentOutputs = new ArrayList<>(reduceOutput.getTaskOutputs());
            reduceLevel++;
        }

        if (sumTokens(currentOutputs) > targetTokenBudget) {
            log.warn(
                    "Adaptive MapReduce: maxReduceLevels ({}) reached but total tokens ({}) "
                            + "still exceed budget ({}). Proceeding with final reduce. "
                            + "Consider increasing targetTokenBudget or maxReduceLevels.",
                    maxReduceLevels,
                    sumTokens(currentOutputs),
                    targetTokenBudget);
        }

        // STEP 4: Final reduce
        log.info("Adaptive MapReduce: running final reduce at level {}", reduceLevel);
        EnsembleOutput finalOutput = runFinalReducePhase(currentOutputs, inputs, reduceLevel);
        levelRuns.add(new LevelRun(reduceLevel, MapReduceEnsemble.NODE_TYPE_FINAL_REDUCE, finalOutput));

        // STEP 5: Aggregate
        return aggregate(ensembleId, levelRuns, inputs, mapOutput.getTaskOutputs());
    }

    // ========================
    // Phase runners
    // ========================

    /**
     * Run the direct (short-circuit) phase.
     *
     * <p>Creates a single agent via {@code directAgentFactory}, builds a task via
     * {@code directTaskFactory} with the complete item list, and runs it as a single-task
     * ensemble. The resulting {@link EnsembleOutput} has exactly one {@link TaskOutput} and
     * a trace with {@code nodeType = "direct"} and {@code mapReduceLevel = 0}.
     *
     * @param ensembleId the ensemble ID for trace correlation
     * @param inputs     template variable inputs
     * @return the {@link EnsembleOutput} from the direct task
     */
    private EnsembleOutput runDirectPhase(String ensembleId, Map<String, String> inputs) {
        Agent directAgent = directAgentFactory.get();
        Task directTask = directTaskFactory.apply(directAgent, Collections.unmodifiableList(items));

        Ensemble.EnsembleBuilder builder = newEnsembleBuilder();
        builder.agent(directAgent).task(directTask);

        Instant start = Instant.now();
        EnsembleOutput raw = build(builder).run(inputs);
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);

        // Annotate task traces with nodeType="direct" and mapReduceLevel=0
        List<TaskTrace> annotatedTraces = new ArrayList<>();
        ExecutionTrace rawTrace = raw.getTrace();
        if (rawTrace != null) {
            for (TaskTrace t : rawTrace.getTaskTraces()) {
                annotatedTraces.add(t.toBuilder()
                        .nodeType(MapReduceEnsemble.NODE_TYPE_DIRECT)
                        .mapReduceLevel(0)
                        .build());
            }
        }

        // Build a level summary for the direct level
        MapReduceLevelSummary directLevelSummary = MapReduceLevelSummary.builder()
                .level(0)
                .taskCount(1)
                .duration(duration)
                .workflow(net.agentensemble.workflow.Workflow.PARALLEL.name())
                .build();

        // Build aggregated agent summaries (non-carrier)
        List<AgentSummary> agentSummaries = new ArrayList<>();
        if (rawTrace != null) {
            for (AgentSummary summary : rawTrace.getAgents()) {
                if (!summary.getRole().startsWith("__carry__:")) {
                    agentSummaries.add(summary);
                }
            }
        }

        ExecutionMetrics metrics = ExecutionMetrics.from(raw.getTaskOutputs());
        CostEstimate totalCost = metrics.getTotalCostEstimate();

        ExecutionTrace.ExecutionTraceBuilder traceBuilder = ExecutionTrace.builder()
                .ensembleId(ensembleId)
                .workflow(WORKFLOW_ADAPTIVE)
                .captureMode(captureMode)
                .startedAt(rawTrace != null ? rawTrace.getStartedAt() : start)
                .completedAt(rawTrace != null ? rawTrace.getCompletedAt() : end)
                .totalDuration(duration)
                .inputs(inputs != null ? Map.copyOf(inputs) : Map.of())
                .metrics(metrics)
                .mapReduceLevel(directLevelSummary);

        for (AgentSummary summary : agentSummaries) {
            traceBuilder.agent(summary);
        }
        for (TaskTrace t : annotatedTraces) {
            traceBuilder.taskTrace(t);
        }
        if (totalCost != null) {
            traceBuilder.totalCostEstimate(totalCost);
        }

        ExecutionTrace aggregatedTrace = traceBuilder.build();

        int toolCalls = raw.getTaskOutputs().stream()
                .mapToInt(TaskOutput::getToolCallCount)
                .sum();

        return EnsembleOutput.builder()
                .raw(raw.getRaw())
                .taskOutputs(raw.getTaskOutputs())
                .totalDuration(duration)
                .totalToolCalls(toolCalls)
                .metrics(metrics)
                .trace(aggregatedTrace)
                .build();
    }

    /**
     * Estimate the total token count for all input items.
     *
     * <p>For each item, converts it to a text representation via {@code inputEstimatorFn}
     * (or {@code Object::toString} if not configured), then applies the heuristic
     * {@code text.length() / 4} to estimate token count. The sum across all items is
     * the total estimated input size.
     *
     * @return the total estimated input token count
     */
    private long estimateInputTokens() {
        long total = 0;
        for (T item : items) {
            String text = inputEstimatorFn != null ? inputEstimatorFn.apply(item) : item.toString();
            // Use the same heuristic as MapReduceTokenEstimator's fallback: length / 4
            total += text.length() / 4;
        }
        return total;
    }

    private EnsembleOutput runMapPhase(Map<String, String> inputs) {
        Ensemble.EnsembleBuilder builder = newEnsembleBuilder();

        for (T item : items) {
            Agent agent = mapAgentFactory.apply(item);
            Task task = mapTaskFactory.apply(item, agent);
            builder.agent(agent).task(task);
        }

        try {
            return build(builder).run(inputs);
        } catch (ParallelExecutionException e) {
            // ParallelExecutionException is thrown by CONTINUE_ON_ERROR when any tasks fail.
            // Extract the surviving outputs; re-throw if all map tasks failed.
            if (parallelErrorStrategy == ParallelErrorStrategy.CONTINUE_ON_ERROR) {
                List<TaskOutput> survivors = e.getCompletedTaskOutputs();
                if (survivors.isEmpty()) {
                    throw e; // All map tasks failed: propagate
                }
                log.warn(
                        "Adaptive MapReduce: {} map task(s) failed with CONTINUE_ON_ERROR; "
                                + "{} surviving outputs proceed to reduce phase.",
                        e.getFailedCount(),
                        survivors.size());
                return buildSurvivorOutput(survivors);
            }
            throw e; // FAIL_FAST: propagate
        }
    }

    /**
     * Build a synthetic {@link EnsembleOutput} from surviving task outputs after a
     * partial map-phase failure with {@code CONTINUE_ON_ERROR}.
     *
     * <p>The {@code trace} is {@code null} because the per-level trace is not available
     * when a {@link ParallelExecutionException} is thrown. The aggregation step handles
     * null traces by skipping trace annotation for the level.
     *
     * @param survivors the task outputs from map tasks that completed successfully
     * @return a synthetic {@link EnsembleOutput} with only the surviving outputs
     */
    private EnsembleOutput buildSurvivorOutput(List<TaskOutput> survivors) {
        String raw = survivors.get(survivors.size() - 1).getRaw();
        int toolCalls =
                survivors.stream().mapToInt(TaskOutput::getToolCallCount).sum();
        ExecutionMetrics metrics = ExecutionMetrics.from(survivors);
        return EnsembleOutput.builder()
                .raw(raw)
                .taskOutputs(survivors)
                .totalDuration(Duration.ZERO)
                .totalToolCalls(toolCalls)
                .metrics(metrics)
                // trace is null: handled in aggregate() by skipping this level's trace
                .build();
    }

    /**
     * Run an intermediate reduce level.
     *
     * <p>For each bin, creates carrier tasks (passthrough) for previous-level outputs,
     * then one reduce task that depends on all carriers in its bin. The carrier tasks
     * are root tasks that return the previous output text without real LLM calls.
     *
     * @param bins       the bin-packed groups from the previous level
     * @param inputs     template variable inputs
     * @param levelIndex the current reduce level index (1-based)
     * @return the {@link EnsembleOutput} from the reduce Ensemble; the {@code taskOutputs}
     *         list contains ONLY the reduce task outputs (carriers are filtered)
     */
    private EnsembleOutput runIntermediateReducePhase(
            List<List<TaskOutput>> bins, Map<String, String> inputs, int levelIndex) {

        Ensemble.EnsembleBuilder builder = newEnsembleBuilder();

        // Track which Task objects are carriers so we can filter their outputs later
        Set<Task> carrierTasks = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (List<TaskOutput> bin : bins) {
            // Create carrier agents/tasks for each item in the bin
            List<Task> chunkTasks = new ArrayList<>(bin.size());
            for (TaskOutput prevOutput : bin) {
                Agent carrierAgent = Agent.builder()
                        .role("__carry__:" + prevOutput.getAgentRole())
                        .goal("carry context from previous level")
                        .llm(new PassthroughChatModel(prevOutput.getRaw()))
                        .build();
                Task carrierTask = Task.builder()
                        .description("carry: " + prevOutput.getTaskDescription())
                        .expectedOutput(prevOutput.getRaw())
                        .agent(carrierAgent)
                        .build();
                builder.agent(carrierAgent).task(carrierTask);
                carrierTasks.add(carrierTask);
                chunkTasks.add(carrierTask);
            }

            // Create the reduce agent and task (depends on all carriers in this bin)
            Agent reduceAgent = reduceAgentFactory.get();
            Task reduceTask = reduceTaskFactory.apply(reduceAgent, Collections.unmodifiableList(chunkTasks));
            builder.agent(reduceAgent).task(reduceTask);
        }

        EnsembleOutput raw = build(builder).run(inputs);

        // Filter out carrier task outputs - retain only real reduce task outputs
        List<TaskOutput> reduceOnlyOutputs = filterCarrierOutputs(raw.getTaskOutputs(), carrierTasks);
        return rebuildOutput(raw, reduceOnlyOutputs);
    }

    /**
     * Run the final reduce phase.
     *
     * <p>Creates carrier tasks for all current outputs, then one final reduce task
     * that depends on all of them. Returns an {@link EnsembleOutput} where
     * {@code taskOutputs} contains only the final reduce task output.
     *
     * @param currentOutputs the outputs from the last intermediate reduce (or map phase)
     * @param inputs         template variable inputs
     * @param levelIndex     the final reduce level index
     * @return the {@link EnsembleOutput} from the final reduce Ensemble
     */
    private EnsembleOutput runFinalReducePhase(
            List<TaskOutput> currentOutputs, Map<String, String> inputs, int levelIndex) {

        Ensemble.EnsembleBuilder builder = newEnsembleBuilder();

        Set<Task> carrierTasks = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        List<Task> chunkTasks = new ArrayList<>(currentOutputs.size());
        for (TaskOutput prevOutput : currentOutputs) {
            Agent carrierAgent = Agent.builder()
                    .role("__carry__:" + prevOutput.getAgentRole())
                    .goal("carry context from previous level")
                    .llm(new PassthroughChatModel(prevOutput.getRaw()))
                    .build();
            Task carrierTask = Task.builder()
                    .description("carry: " + prevOutput.getTaskDescription())
                    .expectedOutput(prevOutput.getRaw())
                    .agent(carrierAgent)
                    .build();
            builder.agent(carrierAgent).task(carrierTask);
            carrierTasks.add(carrierTask);
            chunkTasks.add(carrierTask);
        }

        Agent finalAgent = reduceAgentFactory.get();
        Task finalTask = reduceTaskFactory.apply(finalAgent, Collections.unmodifiableList(chunkTasks));
        builder.agent(finalAgent).task(finalTask);

        EnsembleOutput raw = build(builder).run(inputs);

        // Filter out carrier task outputs
        List<TaskOutput> finalOnlyOutputs = filterCarrierOutputs(raw.getTaskOutputs(), carrierTasks);
        return rebuildOutput(raw, finalOnlyOutputs);
    }

    // ========================
    // Carrier output filtering
    // ========================

    /**
     * Filter out carrier task outputs from a raw ensemble output list.
     *
     * <p>Carrier tasks have agent roles prefixed with {@code "__carry__:"}. We match
     * by role prefix rather than Task identity because the rebuild uses the agentRole
     * on TaskOutput (which is set by AgentExecutor from Agent.getRole()).
     */
    private static List<TaskOutput> filterCarrierOutputs(List<TaskOutput> outputs, Set<Task> carriers) {
        // Use the carrier role prefix as a secondary filter (in case identity tracking is imperfect)
        return outputs.stream()
                .filter(o -> !o.getAgentRole().startsWith("__carry__:"))
                .collect(Collectors.toList());
    }

    /**
     * Rebuild an {@link EnsembleOutput} using a filtered set of task outputs.
     *
     * <p>The {@code raw} field and {@code trace} are derived from the filtered outputs.
     * The last output in {@code filteredOutputs} is used as the {@code raw} text.
     */
    private static EnsembleOutput rebuildOutput(EnsembleOutput original, List<TaskOutput> filteredOutputs) {
        String raw = filteredOutputs.isEmpty()
                ? original.getRaw()
                : filteredOutputs.get(filteredOutputs.size() - 1).getRaw();

        int toolCalls =
                filteredOutputs.stream().mapToInt(TaskOutput::getToolCallCount).sum();

        Duration duration = original.getTotalDuration();
        ExecutionMetrics metrics = ExecutionMetrics.from(filteredOutputs);

        // Re-use the original trace for level-specific trace data; we'll build the
        // aggregated trace from scratch in the aggregate() step.
        return EnsembleOutput.builder()
                .raw(raw)
                .taskOutputs(filteredOutputs)
                .totalDuration(duration)
                .totalToolCalls(toolCalls)
                .metrics(metrics)
                .trace(original.getTrace())
                .build();
    }

    // ========================
    // Token count helpers
    // ========================

    private long sumTokens(List<TaskOutput> outputs) {
        long total = 0;
        for (TaskOutput output : outputs) {
            total += tokenEstimator.estimate(output);
        }
        return total;
    }

    // ========================
    // Ensemble builder helpers
    // ========================

    private Ensemble.EnsembleBuilder newEnsembleBuilder() {
        Ensemble.EnsembleBuilder builder = Ensemble.builder()
                .workflow(Workflow.PARALLEL)
                .verbose(verbose)
                .parallelErrorStrategy(parallelErrorStrategy)
                .captureMode(captureMode);

        for (EnsembleListener listener : listeners) {
            builder.listener(listener);
        }
        if (costConfiguration != null) {
            builder.costConfiguration(costConfiguration);
        }
        if (traceExporter != null) {
            builder.traceExporter(traceExporter);
        }
        if (toolExecutor != null) {
            builder.toolExecutor(toolExecutor);
        }
        if (toolMetrics != null) {
            builder.toolMetrics(toolMetrics);
        }

        return builder;
    }

    private static Ensemble build(Ensemble.EnsembleBuilder builder) {
        return builder.build();
    }

    // ========================
    // Aggregation
    // ========================

    /**
     * Aggregate all level results into a single {@link EnsembleOutput}.
     *
     * <p>The aggregated output uses:
     * <ul>
     *   <li>{@code raw} = the final level's last task output raw text</li>
     *   <li>{@code taskOutputs} = the map phase outputs (the original item-level outputs)</li>
     *   <li>{@code trace.workflow} = {@code "MAP_REDUCE_ADAPTIVE"}</li>
     *   <li>{@code trace.mapReduceLevels} = per-level summaries</li>
     *   <li>{@code trace.taskTraces} = all task traces annotated with level/nodeType</li>
     *   <li>{@code metrics} = summed across all levels</li>
     * </ul>
     */
    private EnsembleOutput aggregate(
            String ensembleId, List<LevelRun> levelRuns, Map<String, String> inputs, List<TaskOutput> mapTaskOutputs) {

        LevelRun finalRun = levelRuns.get(levelRuns.size() - 1);
        String raw = finalRun.output().getRaw();

        // Gather ALL non-carrier task outputs from all levels for metrics aggregation
        List<TaskOutput> allTaskOutputs = new ArrayList<>();
        for (LevelRun run : levelRuns) {
            allTaskOutputs.addAll(run.output().getTaskOutputs());
        }

        ExecutionMetrics aggregatedMetrics = ExecutionMetrics.from(allTaskOutputs);
        int totalToolCalls =
                allTaskOutputs.stream().mapToInt(TaskOutput::getToolCallCount).sum();

        // Build per-level summaries
        List<MapReduceLevelSummary> levelSummaries = new ArrayList<>();
        for (LevelRun run : levelRuns) {
            levelSummaries.add(MapReduceLevelSummary.builder()
                    .level(run.levelIndex())
                    .taskCount(run.output().getTaskOutputs().size())
                    .duration(run.output().getTotalDuration())
                    .workflow(Workflow.PARALLEL.name())
                    .build());
        }

        // Build aggregated task traces with level/nodeType annotations
        List<TaskTrace> aggregatedTraces = new ArrayList<>();
        Instant overallStart = null;
        Instant overallEnd = null;

        for (LevelRun run : levelRuns) {
            ExecutionTrace levelTrace = run.output().getTrace();
            if (levelTrace == null) {
                continue;
            }

            Instant levelStart = levelTrace.getStartedAt();
            Instant levelEnd = levelTrace.getCompletedAt();
            if (overallStart == null || levelStart.isBefore(overallStart)) {
                overallStart = levelStart;
            }
            if (overallEnd == null || levelEnd.isAfter(overallEnd)) {
                overallEnd = levelEnd;
            }

            for (TaskTrace taskTrace : levelTrace.getTaskTraces()) {
                // Skip carrier task traces (identified by role prefix)
                if (taskTrace.getAgentRole().startsWith("__carry__:")) {
                    continue;
                }
                aggregatedTraces.add(taskTrace.toBuilder()
                        .nodeType(run.nodeType())
                        .mapReduceLevel(run.levelIndex())
                        .build());
            }
        }

        Instant traceStart = overallStart != null ? overallStart : Instant.now();
        Instant traceEnd = overallEnd != null ? overallEnd : Instant.now();
        Duration totalDuration = Duration.between(traceStart, traceEnd);

        // Build aggregated agent summaries (from map phase only, since reduce agents
        // are created per-level and carrier agents are excluded)
        List<AgentSummary> agentSummaries = new ArrayList<>();
        for (LevelRun run : levelRuns) {
            ExecutionTrace levelTrace = run.output().getTrace();
            if (levelTrace == null) {
                continue;
            }
            for (AgentSummary summary : levelTrace.getAgents()) {
                if (!summary.getRole().startsWith("__carry__:")) {
                    agentSummaries.add(summary);
                }
            }
        }

        CostEstimate totalCost = aggregatedMetrics.getTotalCostEstimate();

        ExecutionTrace.ExecutionTraceBuilder traceBuilder = ExecutionTrace.builder()
                .ensembleId(ensembleId)
                .workflow(WORKFLOW_ADAPTIVE)
                .captureMode(captureMode)
                .startedAt(traceStart)
                .completedAt(traceEnd)
                .totalDuration(totalDuration)
                .inputs(inputs != null ? Map.copyOf(inputs) : Map.of())
                .metrics(aggregatedMetrics);

        for (AgentSummary summary : agentSummaries) {
            traceBuilder.agent(summary);
        }
        for (TaskTrace trace : aggregatedTraces) {
            traceBuilder.taskTrace(trace);
        }
        for (MapReduceLevelSummary summary : levelSummaries) {
            traceBuilder.mapReduceLevel(summary);
        }
        if (totalCost != null) {
            traceBuilder.totalCostEstimate(totalCost);
        }

        ExecutionTrace aggregatedTrace = traceBuilder.build();

        return EnsembleOutput.builder()
                .raw(raw)
                .taskOutputs(allTaskOutputs)
                .totalDuration(totalDuration)
                .totalToolCalls(totalToolCalls)
                .metrics(aggregatedMetrics)
                .trace(aggregatedTrace)
                .build();
    }

    // ========================
    // Internal records
    // ========================

    /**
     * Holds the result of a single level execution, used for trace aggregation.
     */
    private record LevelRun(int levelIndex, String nodeType, EnsembleOutput output) {}
}
