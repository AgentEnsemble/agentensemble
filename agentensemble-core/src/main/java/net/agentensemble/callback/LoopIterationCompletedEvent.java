package net.agentensemble.callback;

import java.time.Duration;
import java.util.Map;
import net.agentensemble.task.TaskOutput;

/**
 * Event fired immediately after a {@link net.agentensemble.workflow.loop.Loop} completes an
 * iteration of its body, before the loop's predicate is evaluated.
 *
 * <p>Useful for observability (per-iteration progress reporting), live dashboards (showing
 * loop progression in real time), and metrics (per-iteration latency / token attribution).
 *
 * <p>Fired exactly once per iteration. Listeners should not block in their handlers --
 * the loop executor proceeds to predicate evaluation on the same thread immediately after
 * dispatching this event.
 *
 * @param loopName              the loop's name (from {@code Loop.builder().name(...)})
 * @param iterationNumber       1-based iteration counter
 * @param maxIterations         the configured cap (so listeners can render progress as N/M)
 * @param iterationOutputs      per-body-task outputs for the iteration that just completed,
 *                              keyed by body-task name (or description fallback when name is null);
 *                              insertion order matches body-declaration order
 * @param iterationDuration     wall-clock duration of the iteration (sum of body-task durations
 *                              under SEQUENTIAL body execution)
 */
public record LoopIterationCompletedEvent(
        String loopName,
        int iterationNumber,
        int maxIterations,
        Map<String, TaskOutput> iterationOutputs,
        Duration iterationDuration) {}
