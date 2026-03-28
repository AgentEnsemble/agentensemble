package net.agentensemble.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.callback.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EnsembleListener that records audit events based on the configured AuditPolicy.
 *
 * <p>Audit level mapping:
 * <ul>
 *   <li>MINIMAL: delegation events only</li>
 *   <li>STANDARD: + task start/complete/fail, review decisions</li>
 *   <li>FULL: + tool calls</li>
 * </ul>
 */
public final class AuditingListener implements EnsembleListener {
    private static final Logger log = LoggerFactory.getLogger(AuditingListener.class);

    /** Shared daemon scheduler for all AuditingListener instances to avoid thread leaks. */
    private static final ScheduledExecutorService SHARED_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "audit-escalation");
        t.setDaemon(true);
        return t;
    });

    private final AuditPolicy policy;
    private final List<AuditSink> sinks;
    private final String ensembleId;
    private final AtomicReference<AuditLevel> currentLevel;
    /** Generation counter to prevent stale escalation reverts from overwriting newer escalations. */
    private final AtomicLong escalationGeneration = new AtomicLong(0);

    public AuditingListener(AuditPolicy policy, List<AuditSink> sinks, String ensembleId) {
        this.policy = policy;
        this.sinks = List.copyOf(sinks);
        this.ensembleId = ensembleId;
        this.currentLevel = new AtomicReference<>(policy.defaultLevel());
    }

    public AuditLevel currentLevel() {
        return currentLevel.get();
    }

    public void escalate(AuditLevel level, Duration duration) {
        long generation = escalationGeneration.incrementAndGet();
        currentLevel.set(level);
        if (duration != null && !duration.isZero()) {
            AuditLevel revertTo = policy.defaultLevel();
            SHARED_SCHEDULER.schedule(
                    () -> {
                        // Only revert if no newer escalation has occurred since this one was scheduled
                        if (escalationGeneration.get() == generation) {
                            currentLevel.set(revertTo);
                        }
                    },
                    duration.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void onTaskStart(TaskStartEvent event) {
        if (currentLevel.get().isAtLeast(AuditLevel.STANDARD)) {
            emit(
                    "task.start",
                    "Task started: " + event.taskDescription(),
                    Map.of("agentRole", event.agentRole(), "taskIndex", event.taskIndex()));
        }
    }

    @Override
    public void onTaskComplete(TaskCompleteEvent event) {
        if (currentLevel.get().isAtLeast(AuditLevel.STANDARD)) {
            emit(
                    "task.complete",
                    "Task completed: " + event.taskDescription(),
                    Map.of(
                            "agentRole",
                            event.agentRole(),
                            "durationMs",
                            event.duration().toMillis()));
        }
    }

    @Override
    public void onTaskFailed(TaskFailedEvent event) {
        String reason = event.cause() != null ? event.cause().getMessage() : "unknown";
        AuditLevel level = policy.effectiveLevel(ensembleId, "task_failed");
        if (level.ordinal() > currentLevel.get().ordinal()) {
            AuditRule matchingRule = policy.rules().stream()
                    .filter(r -> r.matches("task_failed") && r.appliesTo(ensembleId))
                    .findFirst()
                    .orElse(null);
            if (matchingRule != null) {
                escalate(level, matchingRule.duration());
            }
        }
        if (currentLevel.get().isAtLeast(AuditLevel.STANDARD)) {
            emit(
                    "task.failed",
                    "Task failed: " + event.taskDescription(),
                    Map.of("agentRole", event.agentRole(), "reason", reason));
        }
    }

    @Override
    public void onToolCall(ToolCallEvent event) {
        if (currentLevel.get().isAtLeast(AuditLevel.FULL)) {
            emit(
                    "tool.call",
                    event.toolName() + " called by " + event.agentRole(),
                    Map.of(
                            "toolName",
                            event.toolName(),
                            "durationMs",
                            event.duration().toMillis()));
        }
    }

    @Override
    public void onDelegationStarted(DelegationStartedEvent event) {
        if (currentLevel.get().isAtLeast(AuditLevel.MINIMAL)) {
            emit(
                    "delegation.start",
                    "Delegation to " + event.workerRole() + ": " + event.taskDescription(),
                    Map.of("delegationId", event.delegationId(), "workerRole", event.workerRole()));
        }
    }

    @Override
    public void onDelegationCompleted(DelegationCompletedEvent event) {
        if (currentLevel.get().isAtLeast(AuditLevel.MINIMAL)) {
            emit(
                    "delegation.complete",
                    "Delegation completed: " + event.workerRole(),
                    Map.of(
                            "delegationId",
                            event.delegationId(),
                            "durationMs",
                            event.duration().toMillis()));
        }
    }

    @Override
    public void onDelegationFailed(DelegationFailedEvent event) {
        if (currentLevel.get().isAtLeast(AuditLevel.MINIMAL)) {
            emit(
                    "delegation.failed",
                    "Delegation failed: " + event.workerRole(),
                    Map.of("delegationId", event.delegationId(), "reason", event.failureReason()));
        }
    }

    @Override
    public void onToken(TokenEvent event) {
        // Token events are too granular for even FULL audit -- skip
    }

    private void emit(String category, String summary, Map<String, Object> details) {
        AuditRecord record =
                new AuditRecord(Instant.now(), currentLevel.get(), ensembleId, category, summary, null, details);
        for (AuditSink sink : sinks) {
            try {
                sink.write(record);
            } catch (Exception e) {
                log.warn("AuditSink {} failed: {}", sink.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
