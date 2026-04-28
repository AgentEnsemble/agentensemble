package net.agentensemble.callback;

import java.time.Duration;
import net.agentensemble.task.TaskOutput;

/**
 * Event fired by {@link net.agentensemble.workflow.graph.GraphExecutor} immediately after
 * a state's Task completes, with the routed-to next state already decided.
 *
 * <p>Useful for live dashboards (highlight the current state in real time), per-state
 * metrics, and progress logging.
 *
 * <p>Listeners must not block — the executor proceeds to the next state on the same thread
 * immediately after dispatching this event.
 *
 * @param graphName             the graph's name (from {@code Graph.builder().name(...)})
 * @param stateName             name of the state whose Task just ran
 * @param stepNumber            1-based step counter within this graph execution
 * @param maxSteps              the graph's configured cap (so listeners can render N/M)
 * @param output                output produced by the state's Task on this visit
 * @param nextState             the state the executor routed to next, or {@code Graph.END}
 *                              if this step terminated the graph
 * @param stepDuration          wall-clock duration of this step (Task execution + routing)
 */
public record GraphStateCompletedEvent(
        String graphName,
        String stateName,
        int stepNumber,
        int maxSteps,
        TaskOutput output,
        String nextState,
        Duration stepDuration) {}
