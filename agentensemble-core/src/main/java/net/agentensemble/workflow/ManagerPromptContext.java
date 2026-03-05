package net.agentensemble.workflow;

import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.task.TaskOutput;

/**
 * Immutable context object passed to {@link ManagerPromptStrategy} when generating
 * the system and user prompts for the Manager agent in a hierarchical workflow.
 *
 * <p>The context carries all information a custom strategy implementation needs to
 * produce well-formed prompts:
 * <ul>
 *   <li>{@link #agents} -- the worker agents available for delegation</li>
 *   <li>{@link #tasks} -- the tasks the manager is asked to orchestrate</li>
 *   <li>{@link #previousOutputs} -- outputs from earlier ensemble executions (context chaining)</li>
 *   <li>{@link #workflowDescription} -- an optional ensemble-level description to include in prompts</li>
 * </ul>
 *
 * <p>Both {@link #previousOutputs} and {@link #workflowDescription} may be empty or null when
 * no context chaining or ensemble description is configured.
 *
 * @param agents              all worker agents available for delegation; never null
 * @param tasks               the tasks the manager agent must orchestrate; never null
 * @param previousOutputs     outputs from prior tasks in the workflow (may be empty); never null
 * @param workflowDescription optional ensemble-level description; may be null or blank
 */
public record ManagerPromptContext(
        List<Agent> agents, List<Task> tasks, List<TaskOutput> previousOutputs, String workflowDescription) {}
