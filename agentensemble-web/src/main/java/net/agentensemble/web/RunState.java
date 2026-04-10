package net.agentensemble.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.Ensemble;
import net.agentensemble.metrics.ExecutionMetrics;

/**
 * Mutable per-run state record tracked by {@link RunManager}.
 *
 * <p>Instances are created by {@link RunManager} when a run is submitted and are updated
 * as the run transitions through its lifecycle. Thread safety is maintained via
 * {@link AtomicReference} for status, {@link AtomicInteger} for task counts, and
 * {@code volatile} for other mutable fields.
 *
 * <p>Callers outside the {@code net.agentensemble.web} package should treat instances as
 * read-only; mutators are package-private.
 */
public final class RunState {

    /**
     * Status values for an API-submitted run's lifecycle.
     */
    public enum Status {
        /**
         * Run has been accepted and is queued for execution. The concurrency permit has been
         * acquired; execution has not yet started.
         */
        ACCEPTED,

        /**
         * Run is currently executing on a virtual thread.
         */
        RUNNING,

        /**
         * Run completed successfully (all tasks finished without error).
         */
        COMPLETED,

        /**
         * Run completed with an unhandled exception.
         */
        FAILED,

        /**
         * Run was cancelled cooperatively (flagged before or during execution).
         */
        CANCELLED,

        /**
         * Run was rejected because the maximum concurrent run limit was reached.
         * No concurrency permit was acquired; the run never executed.
         */
        REJECTED
    }

    // ========================
    // Immutable fields
    // ========================

    private final String runId;
    private final Instant startedAt;
    private final Map<String, String> inputs;
    private final Map<String, Object> tags;
    private final int taskCount;
    private final String originSessionId;

    // ========================
    // Mutable fields (thread-safe)
    // ========================

    private final AtomicReference<Status> status;
    private final AtomicInteger completedTasks = new AtomicInteger(0);
    private final List<TaskOutputSnapshot> taskOutputs;

    private volatile Instant completedAt;
    private volatile String workflow;
    private volatile ExecutionMetrics metrics;
    private volatile Ensemble ensemble;
    private volatile boolean cancelled = false;
    private volatile String error;

    RunState(
            String runId,
            Status initialStatus,
            Instant startedAt,
            Map<String, String> inputs,
            Map<String, Object> tags,
            int taskCount,
            String workflow,
            String originSessionId) {
        this.runId = runId;
        this.status = new AtomicReference<>(initialStatus);
        this.startedAt = startedAt;
        this.inputs = inputs != null ? Map.copyOf(inputs) : Map.of();
        this.tags = tags != null ? Map.copyOf(tags) : Map.of();
        this.taskCount = taskCount;
        this.workflow = workflow;
        this.originSessionId = originSessionId;
        this.taskOutputs = Collections.synchronizedList(new ArrayList<>());
    }

    // ========================
    // Public accessors
    // ========================

    /**
     * Returns the unique run identifier (e.g. {@code run-7f3a2b}).
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Returns the current run status.
     */
    public Status getStatus() {
        return status.get();
    }

    /**
     * Returns the instant when the run was submitted (not when execution started).
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * Returns the instant when the run completed, or {@code null} if still in progress.
     */
    public Instant getCompletedAt() {
        return completedAt;
    }

    /**
     * Returns the input variables supplied for this run (for template substitution).
     * May be empty but never null.
     */
    public Map<String, String> getInputs() {
        return inputs;
    }

    /**
     * Returns the metadata tags attached to this run for filtering and querying.
     * May be empty but never null.
     */
    public Map<String, Object> getTags() {
        return tags;
    }

    /**
     * Returns the number of tasks in this run (as determined at submission time).
     */
    public int getTaskCount() {
        return taskCount;
    }

    /**
     * Returns the number of tasks that have completed so far.
     */
    public int getCompletedTasks() {
        return completedTasks.get();
    }

    /**
     * Returns the workflow type (e.g. {@code "SEQUENTIAL"}, {@code "PARALLEL"}),
     * or {@code null} if not yet determined.
     */
    public String getWorkflow() {
        return workflow;
    }

    /**
     * Returns the WebSocket session ID that submitted this run, or {@code null} if
     * the run was submitted via HTTP (not WebSocket).
     */
    public String getOriginSessionId() {
        return originSessionId;
    }

    /**
     * Returns a snapshot list of task outputs accumulated so far, in execution order.
     */
    public List<TaskOutputSnapshot> getTaskOutputs() {
        return Collections.unmodifiableList(taskOutputs);
    }

    /**
     * Returns the aggregated execution metrics for this run, or {@code null} if not yet
     * available (run is still in progress or metrics collection was not enabled).
     */
    public ExecutionMetrics getMetrics() {
        return metrics;
    }

    /**
     * Returns the live {@link Ensemble} reference for control operations (e.g. model switching).
     * May be {@code null} before execution starts.
     */
    public Ensemble getEnsemble() {
        return ensemble;
    }

    /**
     * Returns {@code true} if this run has been flagged for cooperative cancellation.
     * The run will stop at the next task boundary.
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Returns the error message if the run failed, or {@code null} otherwise.
     */
    public String getError() {
        return error;
    }

    // ========================
    // Package-private mutators (called exclusively by RunManager)
    // ========================

    void transitionTo(Status newStatus) {
        status.set(newStatus);
    }

    boolean compareAndSetStatus(Status expected, Status update) {
        return status.compareAndSet(expected, update);
    }

    void setCompletedAt(Instant time) {
        this.completedAt = time;
    }

    void setWorkflow(String workflow) {
        this.workflow = workflow;
    }

    void incrementCompletedTasks() {
        completedTasks.incrementAndGet();
    }

    void setMetrics(ExecutionMetrics metrics) {
        this.metrics = metrics;
    }

    void setEnsemble(Ensemble ensemble) {
        this.ensemble = ensemble;
    }

    void cancel() {
        this.cancelled = true;
    }

    void setError(String error) {
        this.error = error;
    }

    void addTaskOutput(TaskOutputSnapshot snapshot) {
        taskOutputs.add(snapshot);
    }

    // ========================
    // Nested types
    // ========================

    /**
     * Immutable snapshot of a single completed task's output, for API responses.
     *
     * @param taskName       the task name (if set) or description prefix
     * @param taskDescription the full task description
     * @param output         the raw text output from the agent
     * @param durationMs     how long the task took in milliseconds
     * @param tokenCount     total tokens consumed ({@code -1} if unknown)
     * @param toolCallCount  number of tool invocations during execution
     */
    public record TaskOutputSnapshot(
            String taskName,
            String taskDescription,
            String output,
            Long durationMs,
            Long tokenCount,
            int toolCallCount) {}
}
