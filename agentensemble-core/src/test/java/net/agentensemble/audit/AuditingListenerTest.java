package net.agentensemble.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.agentensemble.callback.*;
import org.junit.jupiter.api.Test;

class AuditingListenerTest {

    /**
     * Simple in-memory AuditSink that captures records for assertions.
     */
    private static final class CapturingSink implements AuditSink {
        final CopyOnWriteArrayList<AuditRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void write(AuditRecord record) {
            records.add(record);
        }
    }

    private AuditingListener listener(AuditLevel level) {
        return listener(level, new CapturingSink());
    }

    private AuditingListener listener(AuditLevel level, AuditSink sink) {
        AuditPolicy policy = AuditPolicy.builder().defaultLevel(level).build();
        return new AuditingListener(policy, List.of(sink), "test-ensemble");
    }

    // ========================
    // OFF level: no events recorded
    // ========================

    @Test
    void offLevel_recordsNothing() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.OFF, sink);

        l.onTaskStart(new TaskStartEvent("desc", "researcher", 1, 1));
        l.onTaskComplete(new TaskCompleteEvent("desc", "researcher", null, Duration.ofSeconds(1), 1, 1));
        l.onToolCall(new ToolCallEvent("search", "{}", "result", null, "researcher", Duration.ofMillis(100)));
        l.onDelegationStarted(new DelegationStartedEvent("d1", "manager", "worker", "subtask", 1, null));
        l.onDelegationCompleted(new DelegationCompletedEvent("d1", "manager", "worker", null, Duration.ofSeconds(2)));
        l.onDelegationFailed(
                new DelegationFailedEvent("d1", "manager", "worker", "timeout", null, null, Duration.ofSeconds(3)));

        assertThat(sink.records).isEmpty();
    }

    // ========================
    // MINIMAL level: delegation events only
    // ========================

    @Test
    void minimalLevel_recordsDelegationStarted() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.MINIMAL, sink);

        l.onDelegationStarted(new DelegationStartedEvent("d1", "manager", "worker", "subtask", 1, null));

        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).category()).isEqualTo("delegation.start");
    }

    @Test
    void minimalLevel_recordsDelegationCompleted() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.MINIMAL, sink);

        l.onDelegationCompleted(new DelegationCompletedEvent("d1", "manager", "worker", null, Duration.ofSeconds(2)));

        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).category()).isEqualTo("delegation.complete");
    }

    @Test
    void minimalLevel_recordsDelegationFailed() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.MINIMAL, sink);

        l.onDelegationFailed(
                new DelegationFailedEvent("d1", "manager", "worker", "timeout", null, null, Duration.ofSeconds(3)));

        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).category()).isEqualTo("delegation.failed");
    }

    @Test
    void minimalLevel_doesNotRecordTaskOrToolEvents() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.MINIMAL, sink);

        l.onTaskStart(new TaskStartEvent("desc", "researcher", 1, 1));
        l.onTaskComplete(new TaskCompleteEvent("desc", "researcher", null, Duration.ofSeconds(1), 1, 1));
        l.onToolCall(new ToolCallEvent("search", "{}", "result", null, "researcher", Duration.ofMillis(100)));

        assertThat(sink.records).isEmpty();
    }

    // ========================
    // STANDARD level: delegation + task events
    // ========================

    @Test
    void standardLevel_recordsTaskStart() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.STANDARD, sink);

        l.onTaskStart(new TaskStartEvent("Research AI trends", "researcher", 1, 3));

        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).category()).isEqualTo("task.start");
        assertThat(sink.records.get(0).summary()).contains("Research AI trends");
    }

    @Test
    void standardLevel_recordsTaskComplete() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.STANDARD, sink);

        l.onTaskComplete(new TaskCompleteEvent("Write report", "writer", null, Duration.ofSeconds(5), 2, 3));

        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).category()).isEqualTo("task.complete");
    }

    @Test
    void standardLevel_recordsTaskFailed() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.STANDARD, sink);

        l.onTaskFailed(new TaskFailedEvent(
                "Analyze data", "analyst", new RuntimeException("Out of memory"), Duration.ofSeconds(2), 1, 1));

        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).category()).isEqualTo("task.failed");
    }

    @Test
    void standardLevel_doesNotRecordToolEvents() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.STANDARD, sink);

        l.onToolCall(new ToolCallEvent("search", "{}", "result", null, "researcher", Duration.ofMillis(100)));

        assertThat(sink.records).isEmpty();
    }

    @Test
    void standardLevel_recordsDelegationEvents() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.STANDARD, sink);

        l.onDelegationStarted(new DelegationStartedEvent("d1", "manager", "worker", "subtask", 1, null));

        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).category()).isEqualTo("delegation.start");
    }

    // ========================
    // FULL level: all events
    // ========================

    @Test
    void fullLevel_recordsToolCall() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.FULL, sink);

        l.onToolCall(new ToolCallEvent(
                "web_search", "{\"q\":\"AI\"}", "results", null, "researcher", Duration.ofMillis(250)));

        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).category()).isEqualTo("tool.call");
        assertThat(sink.records.get(0).summary()).contains("web_search");
    }

    @Test
    void fullLevel_recordsAllEventTypes() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.FULL, sink);

        l.onTaskStart(new TaskStartEvent("desc", "role", 1, 1));
        l.onTaskComplete(new TaskCompleteEvent("desc", "role", null, Duration.ofSeconds(1), 1, 1));
        l.onToolCall(new ToolCallEvent("tool", "{}", "res", null, "role", Duration.ofMillis(50)));
        l.onDelegationStarted(new DelegationStartedEvent("d1", "mgr", "wkr", "task", 1, null));
        l.onDelegationCompleted(new DelegationCompletedEvent("d1", "mgr", "wkr", null, Duration.ofSeconds(1)));

        assertThat(sink.records).hasSize(5);
    }

    // ========================
    // Token events are always skipped
    // ========================

    @Test
    void fullLevel_skipsTokenEvents() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.FULL, sink);

        l.onToken(new TokenEvent("Hello", "assistant", "task desc"));

        assertThat(sink.records).isEmpty();
    }

    // ========================
    // Escalation with auto-revert
    // ========================

    @Test
    void escalate_changesLevel() {
        AuditingListener l = listener(AuditLevel.MINIMAL);

        assertThat(l.currentLevel()).isEqualTo(AuditLevel.MINIMAL);

        l.escalate(AuditLevel.FULL, null);

        assertThat(l.currentLevel()).isEqualTo(AuditLevel.FULL);
    }

    @Test
    void escalate_withDuration_revertsAfterTimeout() throws InterruptedException {
        AuditingListener l = listener(AuditLevel.MINIMAL);

        l.escalate(AuditLevel.FULL, Duration.ofMillis(200));

        assertThat(l.currentLevel()).isEqualTo(AuditLevel.FULL);

        // Wait for revert
        Thread.sleep(500);

        assertThat(l.currentLevel()).isEqualTo(AuditLevel.MINIMAL);
    }

    // ========================
    // Sink failure resilience
    // ========================

    @Test
    void sinkException_doesNotPreventOtherSinks() {
        CapturingSink goodSink = new CapturingSink();
        AuditSink badSink = record -> {
            throw new RuntimeException("Sink down");
        };

        AuditPolicy policy =
                AuditPolicy.builder().defaultLevel(AuditLevel.MINIMAL).build();
        AuditingListener l = new AuditingListener(policy, List.of(badSink, goodSink), "test-ens");

        l.onDelegationStarted(new DelegationStartedEvent("d1", "mgr", "wkr", "task", 1, null));

        assertThat(goodSink.records).hasSize(1);
    }

    // ========================
    // Record fields
    // ========================

    @Test
    void emittedRecord_containsEnsembleIdAndDetails() {
        CapturingSink sink = new CapturingSink();
        AuditingListener l = listener(AuditLevel.FULL, sink);

        l.onToolCall(new ToolCallEvent("search_tool", "{}", "result", null, "researcher", Duration.ofMillis(42)));

        AuditRecord record = sink.records.get(0);
        assertThat(record.ensembleId()).isEqualTo("test-ensemble");
        assertThat(record.details()).containsEntry("toolName", "search_tool");
        assertThat(record.details()).containsEntry("durationMs", 42L);
        assertThat(record.timestamp()).isNotNull();
        assertThat(record.level()).isEqualTo(AuditLevel.FULL);
    }

    // ========================
    // Rule-triggered escalation on task failure
    // ========================

    @Test
    void taskFailed_withMatchingRule_escalatesLevel() {
        CapturingSink sink = new CapturingSink();
        AuditPolicy policy = AuditPolicy.builder()
                .defaultLevel(AuditLevel.STANDARD)
                .rule(AuditRule.when("task_failed").escalateTo(AuditLevel.FULL).build())
                .build();
        AuditingListener l = new AuditingListener(policy, List.of(sink), "test-ens");

        assertThat(l.currentLevel()).isEqualTo(AuditLevel.STANDARD);

        l.onTaskFailed(new TaskFailedEvent(
                "failing task", "worker", new RuntimeException("boom"), Duration.ofSeconds(1), 1, 1));

        assertThat(l.currentLevel()).isEqualTo(AuditLevel.FULL);
        assertThat(sink.records).hasSize(1);
        assertThat(sink.records.get(0).category()).isEqualTo("task.failed");
    }
}
