package net.agentensemble.workflow;

import net.agentensemble.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable directed acyclic graph (DAG) of task dependencies.
 *
 * Built from a list of tasks where each task's {@code context} list defines its
 * dependencies (the tasks whose outputs it requires as input). The graph uses
 * identity-based comparison for tasks, consistent with the rest of the framework,
 * so two {@link Task} objects with identical field values but different identity
 * are treated as distinct nodes.
 *
 * This class provides the scheduling primitives used by {@link ParallelWorkflowExecutor}
 * to determine which tasks can run concurrently and which must wait for prerequisites.
 *
 * Thread-safe: all state is set in the constructor and never mutated after construction.
 */
public class TaskDependencyGraph {

    /**
     * Forward edges: task -> tasks that it depends on (its context list).
     * Used to determine if a task is ready (all dependencies are complete).
     */
    private final Map<Task, List<Task>> dependencies;

    /**
     * Reverse edges: task -> tasks that depend on it.
     * Used to efficiently find which tasks to check after a task completes.
     */
    private final Map<Task, List<Task>> dependents;

    /**
     * Tasks with no dependencies -- they can start immediately.
     */
    private final List<Task> roots;

    /**
     * All tasks in this graph, in the order they were provided.
     */
    private final List<Task> allTasks;

    /**
     * Build a task dependency graph from the given task list.
     *
     * Each task's {@code context} list defines its dependencies. Context tasks
     * that are not present in the task list are treated as external and ignored
     * when computing readiness (the parallel executor assumes they have been
     * resolved before the graph is consulted).
     *
     * @param tasks the list of tasks to build the graph from; must not be null
     * @throws IllegalArgumentException if tasks is null
     */
    public TaskDependencyGraph(List<Task> tasks) {
        if (tasks == null) {
            throw new IllegalArgumentException("tasks must not be null");
        }

        // Use identity-based maps throughout -- consistent with Ensemble's IdentityHashMap usage.
        this.dependencies = new IdentityHashMap<>();
        this.dependents = new IdentityHashMap<>();
        List<Task> rootList = new ArrayList<>();

        // Initialize maps for all tasks in the graph
        for (Task task : tasks) {
            dependencies.putIfAbsent(task, new ArrayList<>());
            dependents.putIfAbsent(task, new ArrayList<>());
        }

        // Build forward (dependency) and reverse (dependent) edges
        for (Task task : tasks) {
            List<Task> context = task.getContext();
            if (context.isEmpty()) {
                rootList.add(task);
            } else {
                for (Task dep : context) {
                    // Record that 'task' depends on 'dep'
                    dependencies.get(task).add(dep);
                    // Record that 'dep' is depended on by 'task' (reverse edge)
                    // Only add reverse edges for tasks that are in the graph
                    if (dependents.containsKey(dep)) {
                        dependents.get(dep).add(task);
                    }
                }
                // A task with all dependencies satisfied is also a root.
                // If all context tasks are outside the graph (not in the tasks list),
                // this task has no in-graph dependencies and can start immediately.
                boolean hasInGraphDep = context.stream().anyMatch(dependencies::containsKey);
                if (!hasInGraphDep) {
                    rootList.add(task);
                }
            }
        }

        this.roots = List.copyOf(rootList);
        this.allTasks = List.copyOf(tasks);

        // Make edge lists unmodifiable
        for (Task task : tasks) {
            dependencies.put(task, List.copyOf(dependencies.get(task)));
            dependents.put(task, List.copyOf(dependents.get(task)));
        }
    }

    /**
     * Return all tasks that have no in-graph dependencies.
     *
     * These tasks are ready to execute immediately at the start of a run.
     *
     * @return an unmodifiable list of root tasks; never null
     */
    public List<Task> getRoots() {
        return roots;
    }

    /**
     * Return all tasks that are ready to execute given the set of already-completed tasks.
     *
     * A task is ready if:
     * <ul>
     *   <li>It is in this graph</li>
     *   <li>It has not already been completed</li>
     *   <li>All of its in-graph dependencies are in the completed set</li>
     * </ul>
     *
     * @param completed the set of tasks that have completed (identity-based); must not be null
     * @return an identity-based set of tasks ready to execute; never null
     * @throws IllegalArgumentException if completed is null
     */
    public Set<Task> getReadyTasks(Set<Task> completed) {
        if (completed == null) {
            throw new IllegalArgumentException("completed set must not be null");
        }

        Set<Task> ready = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Task task : allTasks) {
            if (completed.contains(task)) {
                continue; // Already done -- skip
            }
            // Check if all in-graph dependencies are complete
            List<Task> deps = dependencies.get(task);
            boolean allDepsComplete = deps.stream()
                    .filter(this::isInGraph)
                    .allMatch(completed::contains);
            if (allDepsComplete) {
                ready.add(task);
            }
        }
        return ready;
    }

    /**
     * Return all tasks in this graph that directly depend on the given task.
     *
     * Used to efficiently find which tasks to check after a task completes.
     * If the given task is not in this graph, an empty list is returned.
     *
     * @param task the task to look up dependents for
     * @return an unmodifiable list of tasks that depend on the given task; never null
     */
    public List<Task> getDependents(Task task) {
        List<Task> result = dependents.get(task);
        return result != null ? result : List.of();
    }

    /**
     * Return the total number of tasks in this graph.
     *
     * @return the task count
     */
    public int size() {
        return allTasks.size();
    }

    /**
     * Return true if the given task is a member of this graph (identity comparison).
     *
     * @param task the task to check
     * @return true if the task is in this graph
     */
    public boolean isInGraph(Task task) {
        return dependencies.containsKey(task);
    }

    /**
     * Return all tasks in this graph in the order they were provided at construction time.
     *
     * @return an unmodifiable list of all tasks; never null
     */
    public List<Task> getAllTasks() {
        return allTasks;
    }
}
