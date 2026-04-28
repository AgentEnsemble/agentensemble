package net.agentensemble.devtools.dag;

import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/**
 * Representation of a single task node within a {@link DagModel}.
 *
 * <p>Contains the task metadata, dependency relationships, and computed layout
 * hints (parallel group and critical path membership) needed for visualization.
 *
 * <p>When this node is part of a {@link net.agentensemble.mapreduce.MapReduceEnsemble} DAG,
 * the {@link #nodeType} and {@link #mapReduceLevel} fields are populated to enable
 * map-reduce-aware rendering in the visualization layer.
 */
@Value
@Builder
public class DagTaskNode {

    /**
     * Unique identifier for this task within the DAG.
     * Assigned as the zero-based index of the task in the ensemble's task list
     * (e.g., {@code "0"}, {@code "1"}, {@code "2"}).
     */
    @NonNull
    String id;

    /** The task description as configured on the {@link net.agentensemble.Task}. */
    @NonNull
    String description;

    /** The expected output format/content as configured on the task. */
    @NonNull
    String expectedOutput;

    /** Role of the agent assigned to this task. */
    @NonNull
    String agentRole;

    /**
     * IDs of tasks that this task depends on (its context list, filtered to in-graph tasks).
     * Empty for root tasks (those with no in-graph dependencies).
     */
    @Builder.Default
    List<String> dependsOn = List.of();

    /**
     * Topological level of this task in the dependency graph, starting at 0.
     *
     * <p>Tasks at the same level can potentially run in parallel (if the workflow
     * supports parallelism). Level 0 tasks have no in-graph dependencies and can
     * start immediately.
     */
    int parallelGroup;

    /**
     * Whether this task is part of the critical path through the DAG.
     *
     * <p>The critical path is the longest chain of dependent tasks from any root to
     * any leaf. It represents the minimum number of sequential steps required to
     * complete the ensemble, and thus the bottleneck for parallel execution.
     */
    boolean onCriticalPath;

    /**
     * Node type discriminator for visualization. One of:
     * <ul>
     *   <li>{@code null} -- a standard task node (default)</li>
     *   <li>{@code "map"} -- a map-phase task in a {@link net.agentensemble.mapreduce.MapReduceEnsemble} DAG</li>
     *   <li>{@code "reduce"} -- an intermediate reduce task</li>
     *   <li>{@code "final-reduce"} -- the terminal reduce task</li>
     *   <li>{@code "loop"} -- a {@link net.agentensemble.workflow.loop.Loop} super-node;
     *       additional fields {@link #loopMaxIterations} and {@link #loopBody} are populated</li>
     * </ul>
     */
    String nodeType;

    /**
     * Map-reduce tree level for visualization.
     *
     * <p>0 = map phase, 1+ = reduce levels. {@code null} for standard tasks.
     * Populated only when this task belongs to a
     * {@link net.agentensemble.mapreduce.MapReduceEnsemble} DAG.
     */
    Integer mapReduceLevel;

    /**
     * For {@code nodeType == "loop"} nodes: the configured {@code maxIterations} cap.
     * {@code null} for non-loop nodes.
     */
    Integer loopMaxIterations;

    /**
     * For {@code nodeType == "loop"} nodes: the body tasks rendered as nested DAG nodes.
     * Visualization tools render loop nodes as a collapsible super-node containing this
     * sub-DAG. {@code null} for non-loop nodes.
     */
    List<DagTaskNode> loopBody;
}
