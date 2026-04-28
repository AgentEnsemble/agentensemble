package net.agentensemble.workflow;

import net.agentensemble.Task;
import net.agentensemble.workflow.loop.Loop;

/**
 * A node in an ensemble's workflow.
 *
 * <p>Currently implemented by:
 * <ul>
 *   <li>{@link Task} -- a single unit of agent (or deterministic) work.</li>
 *   <li>{@link Loop} -- a bounded iteration over a sub-ensemble of tasks.</li>
 * </ul>
 *
 * <p>This abstraction lets {@code Ensemble.Builder} accept both via a unified
 * {@code .node(WorkflowNode)} method while letting workflow executors pattern-match
 * on the runtime type to dispatch.
 *
 * <p>Not declared {@code sealed} because Lombok's {@code @Value} on {@link Task} makes
 * the class final at bytecode level but not in the source AST during annotation
 * processing, which interacts brittly with the sealed-permit check. A regular
 * interface preserves the same intent without the ordering hazard.
 */
public interface WorkflowNode {}
