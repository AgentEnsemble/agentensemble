package net.agentensemble.devtools.dag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.mapreduce.MapReduceEnsemble;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.trace.ExecutionTrace;
import net.agentensemble.trace.TaskTrace;
import net.agentensemble.workflow.TaskDependencyGraph;

/**
 * Builds a {@link DagModel} from an {@link Ensemble} configuration.
 *
 * <p>This exporter performs a pre-execution analysis of the ensemble's task dependency graph,
 * computing topological levels (parallel groups) and the critical path so that a visualization
 * tool can render the planned execution structure before any tasks are run.
 *
 * <p>Use {@link #build(Ensemble)} to obtain a {@link DagModel}, then call
 * {@link DagModel#toJson(java.nio.file.Path)} to write it to a file.
 *
 * <pre>
 * Ensemble ensemble = Ensemble.builder()
 *     .agents(...)
 *     .tasks(...)
 *     .workflow(Workflow.PARALLEL)
 *     .build();
 *
 * // Export the planned dependency graph (no execution)
 * DagModel dag = DagExporter.build(ensemble);
 * dag.toJson(Path.of("./traces/my-run.dag.json"));
 * </pre>
 */
public final class DagExporter {

    private DagExporter() {}

    /**
     * Build a {@link DagModel} from the given ensemble configuration.
     *
     * <p>Analyzes the task list and each task's {@code context} list to construct the
     * dependency graph, then computes topological levels and the critical path.
     *
     * @param ensemble the ensemble to analyze; must not be {@code null}
     * @return an immutable {@link DagModel} ready for serialization
     * @throws IllegalArgumentException if ensemble is null or has no tasks
     */
    public static DagModel build(Ensemble ensemble) {
        if (ensemble == null) {
            throw new IllegalArgumentException("ensemble must not be null");
        }
        List<Task> tasks = ensemble.getTasks();
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("ensemble must have at least one task");
        }

        // Assign stable, index-based IDs to each task (identity-safe)
        Map<Task, String> taskIds = buildTaskIds(tasks);

        // Build the dependency graph (same algorithm used by the parallel executor)
        TaskDependencyGraph graph = new TaskDependencyGraph(tasks);

        // Compute topological levels: level 0 = roots, level N = depends on level N-1
        Map<Task, Integer> levels = computeLevels(tasks, graph);

        // Build parallel groups list: each index is a level, value is task IDs at that level
        List<List<String>> parallelGroups = buildParallelGroups(tasks, taskIds, levels);

        // Compute the critical path (longest chain from any root to any leaf)
        List<String> criticalPath = computeCriticalPath(tasks, taskIds, levels, graph);
        Set<String> criticalPathSet = new HashSet<>(criticalPath);

        // Build agent summaries
        List<DagAgentNode> agentNodes = buildAgentNodes(ensemble.getAgents());

        // Build task nodes
        List<DagTaskNode> taskNodes = buildTaskNodes(tasks, taskIds, graph, levels, criticalPathSet);

        return DagModel.builder()
                .workflow(ensemble.getWorkflow().name())
                .generatedAt(Instant.now())
                .agents(agentNodes)
                .tasks(taskNodes)
                .parallelGroups(parallelGroups)
                .criticalPath(criticalPath)
                .build();
    }

    /**
     * Build a {@link DagModel} from a {@link MapReduceEnsemble} configuration, enriching
     * each task node with {@code nodeType} and {@code mapReduceLevel} metadata for
     * map-reduce-aware visualization.
     *
     * <p>Equivalent to {@link #build(Ensemble)} on {@code mapReduceEnsemble.toEnsemble()},
     * but additionally populates:
     * <ul>
     *   <li>{@code DagTaskNode.nodeType} -- {@code "map"}, {@code "reduce"}, or
     *       {@code "final-reduce"}</li>
     *   <li>{@code DagTaskNode.mapReduceLevel} -- 0 for map tasks, 1+ for reduce levels</li>
     *   <li>{@code DagModel.mapReduceMode} -- {@code "STATIC"}</li>
     * </ul>
     *
     * @param mapReduceEnsemble the map-reduce ensemble to analyze; must not be {@code null}
     * @return an immutable {@link DagModel} with map-reduce metadata
     * @throws IllegalArgumentException if {@code mapReduceEnsemble} is null
     */
    public static DagModel build(MapReduceEnsemble<?> mapReduceEnsemble) {
        if (mapReduceEnsemble == null) {
            throw new IllegalArgumentException("mapReduceEnsemble must not be null");
        }

        Ensemble inner = mapReduceEnsemble.toEnsemble();
        DagModel base = build(inner);

        List<Task> tasks = inner.getTasks();
        Map<Task, String> nodeTypes = mapReduceEnsemble.getNodeTypes();
        Map<Task, Integer> mapReduceLevels = mapReduceEnsemble.getMapReduceLevels();

        // Enrich task nodes: task list index aligns with base.getTasks() order.
        List<DagTaskNode> enrichedTasks = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            DagTaskNode baseNode = base.getTasks().get(i);
            enrichedTasks.add(DagTaskNode.builder()
                    .id(baseNode.getId())
                    .description(baseNode.getDescription())
                    .expectedOutput(baseNode.getExpectedOutput())
                    .agentRole(baseNode.getAgentRole())
                    .dependsOn(baseNode.getDependsOn())
                    .parallelGroup(baseNode.getParallelGroup())
                    .onCriticalPath(baseNode.isOnCriticalPath())
                    .nodeType(nodeTypes.get(task))
                    .mapReduceLevel(mapReduceLevels.get(task))
                    .build());
        }

        DagModel.DagModelBuilder builder = DagModel.builder()
                .workflow(base.getWorkflow())
                .generatedAt(base.getGeneratedAt())
                .parallelGroups(base.getParallelGroups())
                .criticalPath(base.getCriticalPath())
                .mapReduceMode(MapReduceEnsemble.MAP_REDUCE_MODE);

        for (DagAgentNode agentNode : base.getAgents()) {
            builder.agent(agentNode);
        }
        for (DagTaskNode enrichedNode : enrichedTasks) {
            builder.task(enrichedNode);
        }

        return builder.build();
    }

    /**
     * Build a {@link DagModel} from a post-execution {@link ExecutionTrace}, enabling
     * visualization of adaptive map-reduce runs whose DAG shape is only known after execution.
     *
     * <p>The returned {@link DagModel} includes:
     * <ul>
     *   <li>{@code DagModel.mapReduceMode} -- {@code "ADAPTIVE"} when the trace contains
     *       map-reduce level data; {@code null} otherwise.</li>
     *   <li>{@code DagTaskNode.nodeType} -- from {@link TaskTrace#getNodeType()}</li>
     *   <li>{@code DagTaskNode.mapReduceLevel} -- from {@link TaskTrace#getMapReduceLevel()}</li>
     *   <li>Task dependency relationships are inferred from level ordering: tasks at level N
     *       are treated as dependent on tasks at level N-1. Exact context wiring is not
     *       preserved in the trace, so {@code DagTaskNode.dependsOn} is empty for each node.</li>
     * </ul>
     *
     * <p>For non-map-reduce traces (standard {@code SEQUENTIAL} or {@code PARALLEL} runs),
     * a simplified {@link DagModel} is returned with {@code mapReduceMode = null}.
     *
     * @param trace the execution trace to export; must not be {@code null}
     * @return an immutable {@link DagModel} derived from the trace
     * @throws IllegalArgumentException if trace is null or contains no task traces
     */
    public static DagModel build(ExecutionTrace trace) {
        if (trace == null) {
            throw new IllegalArgumentException("trace must not be null");
        }
        List<TaskTrace> taskTraces = trace.getTaskTraces();
        if (taskTraces == null || taskTraces.isEmpty()) {
            throw new IllegalArgumentException("trace must contain at least one task trace");
        }

        boolean isAdaptive = MapReduceEnsemble.MAP_REDUCE_MODE_ADAPTIVE.equals(trace.getWorkflow())
                || !trace.getMapReduceLevels().isEmpty();

        // Build agent nodes from trace agent summaries
        List<DagAgentNode> agentNodes = trace.getAgents().stream()
                .map(a -> DagAgentNode.builder()
                        .role(a.getRole())
                        .goal(a.getGoal())
                        .background(a.getBackground())
                        .toolNames(a.getToolNames() != null ? a.getToolNames() : List.of())
                        .allowDelegation(a.isAllowDelegation())
                        .build())
                .collect(Collectors.toList());

        // Build task nodes from task traces
        List<DagTaskNode> taskNodes = new ArrayList<>(taskTraces.size());
        for (int i = 0; i < taskTraces.size(); i++) {
            TaskTrace taskTrace = taskTraces.get(i);
            Integer level = taskTrace.getMapReduceLevel();
            taskNodes.add(DagTaskNode.builder()
                    .id(String.valueOf(i))
                    .description(taskTrace.getTaskDescription())
                    .expectedOutput(taskTrace.getExpectedOutput())
                    .agentRole(taskTrace.getAgentRole())
                    // Dependency information is not preserved in the trace; leave empty.
                    .dependsOn(List.of())
                    .parallelGroup(level != null ? level : 0)
                    .onCriticalPath(false)
                    .nodeType(taskTrace.getNodeType())
                    .mapReduceLevel(level)
                    .build());
        }

        // Build parallel groups from level information
        int maxLevel =
                taskNodes.stream().mapToInt(DagTaskNode::getParallelGroup).max().orElse(0);
        List<List<String>> parallelGroups = new ArrayList<>(maxLevel + 1);
        for (int i = 0; i <= maxLevel; i++) {
            parallelGroups.add(new ArrayList<>());
        }
        for (DagTaskNode node : taskNodes) {
            parallelGroups.get(node.getParallelGroup()).add(node.getId());
        }
        List<List<String>> immutableGroups =
                parallelGroups.stream().map(List::copyOf).collect(Collectors.toUnmodifiableList());

        DagModel.DagModelBuilder builder = DagModel.builder()
                .workflow(trace.getWorkflow())
                .generatedAt(trace.getCompletedAt())
                .parallelGroups(immutableGroups)
                .criticalPath(List.of())
                .mapReduceMode(isAdaptive ? MapReduceEnsemble.MAP_REDUCE_MODE_ADAPTIVE : null);

        for (DagAgentNode agentNode : agentNodes) {
            builder.agent(agentNode);
        }
        for (DagTaskNode taskNode : taskNodes) {
            builder.task(taskNode);
        }

        return builder.build();
    }

    // ========================
    // Private helpers
    // ========================

    private static Map<Task, String> buildTaskIds(List<Task> tasks) {
        Map<Task, String> taskIds = new IdentityHashMap<>();
        for (int i = 0; i < tasks.size(); i++) {
            taskIds.put(tasks.get(i), String.valueOf(i));
        }
        return taskIds;
    }

    /**
     * Compute the topological level of every task.
     *
     * <p>Level 0 = root tasks (no in-graph dependencies).
     * Level N = depends on at least one task at level N-1 (and none at level N+).
     * Uses memoized recursion via the {@code levels} map.
     */
    private static Map<Task, Integer> computeLevels(List<Task> tasks, TaskDependencyGraph graph) {
        Map<Task, Integer> levels = new IdentityHashMap<>();
        for (Task task : tasks) {
            computeLevel(task, graph, levels);
        }
        return levels;
    }

    private static int computeLevel(Task task, TaskDependencyGraph graph, Map<Task, Integer> levels) {
        Integer cached = levels.get(task);
        if (cached != null) {
            return cached;
        }

        // Collect in-graph dependencies only
        List<Task> inGraphDeps =
                task.getContext().stream().filter(graph::isInGraph).collect(Collectors.toList());

        if (inGraphDeps.isEmpty()) {
            levels.put(task, 0);
            return 0;
        }

        int maxDepLevel = -1;
        for (Task dep : inGraphDeps) {
            int depLevel = computeLevel(dep, graph, levels);
            if (depLevel > maxDepLevel) {
                maxDepLevel = depLevel;
            }
        }

        int level = maxDepLevel + 1;
        levels.put(task, level);
        return level;
    }

    private static List<List<String>> buildParallelGroups(
            List<Task> tasks, Map<Task, String> taskIds, Map<Task, Integer> levels) {
        int maxLevel =
                levels.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        List<List<String>> groups = new ArrayList<>(maxLevel + 1);
        for (int i = 0; i <= maxLevel; i++) {
            groups.add(new ArrayList<>());
        }

        for (Task task : tasks) {
            int level = levels.get(task);
            groups.get(level).add(taskIds.get(task));
        }

        return groups.stream().map(List::copyOf).collect(Collectors.toUnmodifiableList());
    }

    /**
     * Compute the critical path through the DAG.
     *
     * <p>Strategy: the task with the highest topological level is the endpoint of the critical
     * path (or one of them, if there are ties — we pick the last one in the task list to get
     * a deterministic result). Then we backtrack by always following the in-graph dependency
     * with the highest level until we reach a root.
     */
    private static List<String> computeCriticalPath(
            List<Task> tasks, Map<Task, String> taskIds, Map<Task, Integer> levels, TaskDependencyGraph graph) {
        if (tasks.isEmpty()) {
            return List.of();
        }

        // Find the task with the maximum level (end of the critical path).
        // Use the last such task in list order to get a deterministic result for ties.
        Task endTask = null;
        int maxLevel = -1;
        for (Task task : tasks) {
            int level = levels.get(task);
            if (level >= maxLevel) {
                maxLevel = level;
                endTask = task;
            }
        }

        if (endTask == null) {
            return List.of();
        }

        // Backtrack from endTask to a root by always picking the in-graph dep with max level.
        // Insert at the front to build the path in forward order.
        List<String> path = new ArrayList<>();
        Task current = endTask;
        while (current != null) {
            path.add(0, taskIds.get(current));
            Task finalCurrent = current;
            // Pick the in-graph dependency with the highest level
            current = finalCurrent.getContext().stream()
                    .filter(graph::isInGraph)
                    .max(Comparator.comparingInt(t -> levels.getOrDefault(t, -1)))
                    .orElse(null);
        }

        return List.copyOf(path);
    }

    private static List<DagAgentNode> buildAgentNodes(List<Agent> agents) {
        return agents.stream()
                .map(agent -> {
                    List<String> toolNames = agent.getTools().stream()
                            .filter(tool -> tool instanceof AgentTool)
                            .map(tool -> ((AgentTool) tool).name())
                            .collect(Collectors.toList());

                    return DagAgentNode.builder()
                            .role(agent.getRole())
                            .goal(agent.getGoal())
                            .background(agent.getBackground())
                            .toolNames(toolNames)
                            .allowDelegation(agent.isAllowDelegation())
                            .build();
                })
                .collect(Collectors.toList());
    }

    private static List<DagTaskNode> buildTaskNodes(
            List<Task> tasks,
            Map<Task, String> taskIds,
            TaskDependencyGraph graph,
            Map<Task, Integer> levels,
            Set<String> criticalPathSet) {
        List<DagTaskNode> nodes = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            String id = taskIds.get(task);

            List<String> depIds = task.getContext().stream()
                    .filter(graph::isInGraph)
                    .map(taskIds::get)
                    .collect(Collectors.toList());

            nodes.add(DagTaskNode.builder()
                    .id(id)
                    .description(task.getDescription())
                    .expectedOutput(task.getExpectedOutput())
                    .agentRole(task.getAgent().getRole())
                    .dependsOn(depIds)
                    .parallelGroup(levels.get(task))
                    .onCriticalPath(criticalPathSet.contains(id))
                    .build());
        }
        return nodes;
    }
}
