package net.agentensemble.workflow;

import net.agentensemble.Task;
import net.agentensemble.workflow.graph.Graph;
import net.agentensemble.workflow.loop.Loop;

/**
 * A node in an ensemble's workflow.
 *
 * <p>Currently implemented by:
 * <ul>
 *   <li>{@link Task} -- a single unit of agent (or deterministic) work.</li>
 *   <li>{@link Loop} -- a bounded iteration over a sub-ensemble of tasks.</li>
 *   <li>{@link Graph} -- a state machine of named states (Tasks) connected by directed
 *       edges with conditional / unconditional routing and arbitrary back-edges.</li>
 * </ul>
 *
 * <p>{@code Ensemble.Builder} accepts each variant via its dedicated method
 * ({@code .task(Task)}, {@code .loop(Loop)}, {@code .graph(Graph)}); workflow executors
 * pattern-match on the runtime type to dispatch. There is no unified
 * {@code .node(WorkflowNode)} method on the builder.
 *
 * <p>Not declared {@code sealed} because Lombok's {@code @Value} on {@link Task} makes
 * the class final at bytecode level but not in the source AST during annotation
 * processing, which interacts brittly with the sealed-permit check. A regular
 * interface preserves the same intent without the ordering hazard.
 */
public interface WorkflowNode {}
