package net.agentensemble.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.agentensemble.Ensemble;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.execution.RunOptions;
import net.agentensemble.metrics.ExecutionMetrics;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.web.RunState.Status;
import net.agentensemble.web.RunState.TaskOutputSnapshot;
import net.agentensemble.web.protocol.RunResultMessage;
import net.agentensemble.web.protocol.RunResultMessage.MetricsDto;
import net.agentensemble.web.protocol.RunResultMessage.TaskOutputDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates the lifecycle of API-submitted ensemble runs.
 *
 * <p>RunManager handles:
 * <ul>
 *   <li>Concurrency limiting via a fair {@link Semaphore} (configurable max concurrent runs)</li>
 *   <li>Asynchronous execution on virtual threads</li>
 *   <li>Run state tracking in a {@link ConcurrentHashMap}</li>
 *   <li>Eviction of oldest completed runs when {@code maxRetainedRuns} is exceeded</li>
 *   <li>Completion notification via an optional callback</li>
 * </ul>
 *
 * <p>Thread safety: all public methods are safe for concurrent use. Run state transitions
 * use atomic operations and volatile fields.
 *
 * <p>Instances should be shut down via {@link #shutdown()} when the dashboard stops.
 */
public final class RunManager {

    private static final Logger log = LoggerFactory.getLogger(RunManager.class);

    private final ConcurrentHashMap<String, RunState> runs = new ConcurrentHashMap<>();
    private final Semaphore concurrencyLimit;
    private final ExecutorService executor;
    private final int maxConcurrentRuns;
    private final int maxRetainedRuns;

    /**
     * Creates a RunManager with the given concurrency and retention limits.
     *
     * @param maxConcurrentRuns the maximum number of runs that may execute simultaneously;
     *                          must be &ge; 1
     * @param maxRetainedRuns   the maximum number of completed (and failed/cancelled) runs
     *                          to keep in memory for state queries; must be &ge; 1
     * @throws IllegalArgumentException if either limit is less than 1
     */
    public RunManager(int maxConcurrentRuns, int maxRetainedRuns) {
        if (maxConcurrentRuns < 1) {
            throw new IllegalArgumentException("maxConcurrentRuns must be >= 1; got: " + maxConcurrentRuns);
        }
        if (maxRetainedRuns < 1) {
            throw new IllegalArgumentException("maxRetainedRuns must be >= 1; got: " + maxRetainedRuns);
        }
        this.maxConcurrentRuns = maxConcurrentRuns;
        this.maxRetainedRuns = maxRetainedRuns;
        this.concurrencyLimit = new Semaphore(maxConcurrentRuns, /* fair= */ true);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Submits a run for asynchronous execution.
     *
     * <p>If the concurrency limit is reached, returns a {@link RunState} with status
     * {@link Status#REJECTED} immediately (no execution scheduled). The rejected state is
     * not retained in the run map.
     *
     * <p>Otherwise, returns a state with status {@link Status#ACCEPTED} and schedules
     * execution on a virtual thread. The callback (if provided) is invoked from the
     * execution thread once the run completes.
     *
     * @param template        the template ensemble to run; must not be null
     * @param inputs          the input variables for template substitution; may be null
     * @param tags            metadata tags for filtering and querying; may be null
     * @param options         per-run execution options; may be null (uses defaults)
     * @param originSessionId the WebSocket session ID that submitted the run; null for HTTP
     * @param onComplete      callback invoked with the final result message; may be null
     * @return the initial {@link RunState} (ACCEPTED or REJECTED)
     */
    public RunState submitRun(
            Ensemble template,
            Map<String, String> inputs,
            Map<String, Object> tags,
            RunOptions options,
            String originSessionId,
            Consumer<RunResultMessage> onComplete) {

        String runId = generateRunId();

        if (!concurrencyLimit.tryAcquire()) {
            log.debug("Run {} rejected: concurrency limit ({}) reached", runId, maxConcurrentRuns);
            return new RunState(runId, Status.REJECTED, Instant.now(), inputs, tags, 0, null, originSessionId);
        }

        int taskCount = (template.getTasks() != null) ? template.getTasks().size() : 0;
        RunState state =
                new RunState(runId, Status.ACCEPTED, Instant.now(), inputs, tags, taskCount, null, originSessionId);
        runs.put(runId, state);

        Map<String, String> effectiveInputs = inputs != null ? Map.copyOf(inputs) : Map.of();
        RunOptions effectiveOptions = options != null ? options : RunOptions.DEFAULT;

        executor.submit(() -> executeRun(state, template, effectiveInputs, effectiveOptions, onComplete));

        log.debug("Run {} accepted ({} task(s))", runId, taskCount);
        return state;
    }

    /**
     * Returns the {@link RunState} for the given run ID, or empty if not found.
     *
     * @param runId the run identifier
     * @return the state, or empty
     */
    public Optional<RunState> getRun(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    /**
     * Returns all retained runs matching the given filter criteria, ordered by start time
     * descending (most recent first).
     *
     * @param statusFilter if non-null, only runs with this status are returned
     * @param tagKey       if non-null, only runs with this tag key are returned
     * @param tagValue     if non-null (along with {@code tagKey}), only runs where the tag
     *                     value equals this string are returned
     * @return a list of matching {@link RunState} records
     */
    public List<RunState> listRuns(Status statusFilter, String tagKey, String tagValue) {
        return runs.values().stream()
                .filter(s -> statusFilter == null || s.getStatus() == statusFilter)
                .filter(s -> {
                    if (tagKey == null) return true;
                    Object val = s.getTags().get(tagKey);
                    return val != null && (tagValue == null || tagValue.equals(val.toString()));
                })
                .sorted(Comparator.comparing(RunState::getStartedAt).reversed())
                .toList();
    }

    /**
     * Returns all retained runs, ordered by start time descending.
     *
     * @return all retained run states
     */
    public List<RunState> listAllRuns() {
        return listRuns(null, null, null);
    }

    /**
     * Returns the number of currently active (ACCEPTED or RUNNING) runs.
     *
     * @return count of active runs
     */
    public int getActiveCount() {
        return maxConcurrentRuns - concurrencyLimit.availablePermits();
    }

    /**
     * Returns the configured maximum concurrent runs limit.
     *
     * @return max concurrent runs
     */
    public int getMaxConcurrentRuns() {
        return maxConcurrentRuns;
    }

    /**
     * Shuts down the internal virtual-thread executor, waiting up to 2 seconds for
     * in-progress work to finish. Should be called when the dashboard stops.
     */
    public void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("RunManager executor did not terminate within 2 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ========================
    // Private execution logic
    // ========================

    private void executeRun(
            RunState state,
            Ensemble template,
            Map<String, String> inputs,
            RunOptions options,
            Consumer<RunResultMessage> onComplete) {

        state.setEnsemble(template);
        state.transitionTo(Status.RUNNING);
        log.debug("Run {} started", state.getRunId());

        EnsembleOutput output = null;
        Exception runException = null;
        try {
            output = template.run(inputs, options);
        } catch (Exception e) {
            runException = e;
            log.warn("Run {} failed with exception: {}", state.getRunId(), e.getMessage(), e);
        } finally {
            concurrencyLimit.release();
        }

        Instant completedAt = Instant.now();
        state.setCompletedAt(completedAt);
        long durationMs = completedAt.toEpochMilli() - state.getStartedAt().toEpochMilli();

        RunResultMessage resultMessage;

        if (runException != null) {
            state.transitionTo(Status.FAILED);
            state.setError(runException.getMessage());
            resultMessage = new RunResultMessage(
                    state.getRunId(), Status.FAILED.name(), List.of(), durationMs, null, runException.getMessage());
        } else {
            Status finalStatus = state.isCancelled() ? Status.CANCELLED : Status.COMPLETED;
            state.transitionTo(finalStatus);

            List<TaskOutputDto> taskDtos = buildTaskOutputDtos(output);
            MetricsDto metricsDto = buildMetricsDto(output);

            if (output != null) {
                state.setMetrics(output.getMetrics());
                populateTaskOutputSnapshots(state, output);
            }

            resultMessage =
                    new RunResultMessage(state.getRunId(), finalStatus.name(), taskDtos, durationMs, metricsDto, null);
        }

        log.debug("Run {} finished with status {}", state.getRunId(), state.getStatus());

        // Evict before notifying so the map is already clean when the callback fires.
        evictCompletedRunsIfNeeded();

        if (onComplete != null) {
            try {
                onComplete.accept(resultMessage);
            } catch (Exception e) {
                log.warn("Run completion callback threw for runId={}", state.getRunId(), e);
            }
        }
    }

    private List<TaskOutputDto> buildTaskOutputDtos(EnsembleOutput output) {
        if (output == null
                || output.getTaskOutputs() == null
                || output.getTaskOutputs().isEmpty()) {
            return List.of();
        }
        List<TaskOutputDto> dtos = new ArrayList<>();
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            Long durationMs =
                    taskOutput.getDuration() != null ? taskOutput.getDuration().toMillis() : null;
            dtos.add(new TaskOutputDto(taskOutput.getTaskDescription(), taskOutput.getRaw(), durationMs));
        }
        return List.copyOf(dtos);
    }

    private MetricsDto buildMetricsDto(EnsembleOutput output) {
        if (output == null) return null;
        ExecutionMetrics m = output.getMetrics();
        if (m == null) return null;
        return new MetricsDto(m.getTotalTokens(), m.getTotalToolCalls());
    }

    private void populateTaskOutputSnapshots(RunState state, EnsembleOutput output) {
        if (output == null || output.getTaskOutputs() == null) return;
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            Long durationMs =
                    taskOutput.getDuration() != null ? taskOutput.getDuration().toMillis() : null;
            long tokenCount =
                    taskOutput.getMetrics() != null ? taskOutput.getMetrics().getTotalTokens() : -1L;
            state.addTaskOutput(new TaskOutputSnapshot(
                    null,
                    taskOutput.getTaskDescription(),
                    taskOutput.getRaw(),
                    durationMs,
                    tokenCount,
                    taskOutput.getToolCallCount()));
        }
    }

    private void evictCompletedRunsIfNeeded() {
        List<RunState> terminal = runs.values().stream()
                .filter(s -> {
                    Status st = s.getStatus();
                    return st == Status.COMPLETED || st == Status.FAILED || st == Status.CANCELLED;
                })
                .sorted(Comparator.comparing(RunState::getCompletedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        int excess = terminal.size() - maxRetainedRuns;
        if (excess > 0) {
            terminal.subList(0, excess).forEach(evicted -> {
                runs.remove(evicted.getRunId());
                log.debug("Evicted completed run {} from state map", evicted.getRunId());
            });
        }
    }

    private static String generateRunId() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return "run-" + uuid.substring(0, 8);
    }
}
