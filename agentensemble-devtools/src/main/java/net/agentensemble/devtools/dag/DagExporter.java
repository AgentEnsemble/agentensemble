package net.agentensemble.devtools.dag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.tool.AgentTool;
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
        IdentityHashMap<Task, String> taskIds = buildTaskIds(tasks);

        // Build the dependency graph (same algorithm used by the parallel executor)
        TaskDependencyGraph graph = new TaskDependencyGraph(tasks);

        // Compute topological levels: level 0 = roots, level N = depends on level N-1
        IdentityHashMap<Task, Integer> levels = computeLevels(tasks, graph);

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

    // ========================
    // Private helpers
    // ========================

    private static IdentityHashMap<Task, String> buildTaskIds(List<Task> tasks) {
        IdentityHashMap<Task, String> taskIds = new IdentityHashMap<>();
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
    private static IdentityHashMap<Task, Integer> computeLevels(List<Task> tasks, TaskDependencyGraph graph) {
        IdentityHashMap<Task, Integer> levels = new IdentityHashMap<>();
        for (Task task : tasks) {
            computeLevel(task, graph, levels);
        }
        return levels;
    }

    private static int computeLevel(Task task, TaskDependencyGraph graph, IdentityHashMap<Task, Integer> levels) {
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
            List<Task> tasks, IdentityHashMap<Task, String> taskIds, IdentityHashMap<Task, Integer> levels) {
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
            List<Task> tasks,
            IdentityHashMap<Task, String> taskIds,
            IdentityHashMap<Task, Integer> levels,
            TaskDependencyGraph graph) {
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
            IdentityHashMap<Task, String> taskIds,
            TaskDependencyGraph graph,
            IdentityHashMap<Task, Integer> levels,
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
