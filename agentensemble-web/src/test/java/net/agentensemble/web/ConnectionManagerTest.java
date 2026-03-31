package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import net.agentensemble.web.protocol.IterationSnapshot;
import net.agentensemble.web.protocol.LlmIterationCompletedMessage;
import net.agentensemble.web.protocol.LlmIterationStartedMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConnectionManager}.
 *
 * <p>All tests use {@link MockWsSession} stubs to avoid starting a real WebSocket server.
 */
class ConnectionManagerTest {

    private ConnectionManager connectionManager;
    private MessageSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
        connectionManager = new ConnectionManager(serializer);
    }

    @Test
    void newConnectionReceivesHelloMessage() {
        MockWsSession session = new MockWsSession("session-1");
        connectionManager.onConnect(session);

        assertThat(session.sentMessages()).hasSize(1);
        String helloJson = session.sentMessages().get(0);
        assertThat(helloJson).contains("\"type\":\"hello\"");
    }

    @Test
    void newConnectionReceivesEmptySnapshotInHelloWhenNoEventsYet() {
        MockWsSession session = new MockWsSession("session-2");
        connectionManager.onConnect(session);

        String helloJson = session.sentMessages().get(0);
        assertThat(helloJson).contains("\"type\":\"hello\"");
        // No snapshotTrace field when snapshot is empty (NON_NULL omits it)
        assertThat(helloJson).doesNotContain("snapshotTrace");
    }

    @Test
    void lateJoinerReceivesSnapshotOfPastEvents() {
        // Simulate a run that has already broadcast some events
        connectionManager.noteEnsembleStarted("ens-abc", Instant.now());
        connectionManager.appendToSnapshot("{\"type\":\"ensemble_started\",\"ensembleId\":\"ens-abc\"}");
        connectionManager.appendToSnapshot("{\"type\":\"task_started\",\"taskIndex\":1}");

        // Late-joining client
        MockWsSession lateSession = new MockWsSession("late-joiner");
        connectionManager.onConnect(lateSession);

        String helloJson = lateSession.sentMessages().get(0);
        assertThat(helloJson).contains("\"type\":\"hello\"");
        // Snapshot should contain the two past events as a JSON array
        assertThat(helloJson).contains("snapshotTrace");
        assertThat(helloJson).contains("ensemble_started");
        assertThat(helloJson).contains("task_started");
        // ensembleId and startedAt should be set from noteEnsembleStarted
        assertThat(helloJson).contains("\"ensembleId\":\"ens-abc\"");
    }

    @Test
    void noteEnsembleStarted_retainsBothRunsInSnapshotAcrossConsecutiveRuns() {
        // Run 1: accumulate events
        connectionManager.noteEnsembleStarted("ens-run1", Instant.now());
        connectionManager.appendToSnapshot("{\"type\":\"ensemble_started\",\"ensembleId\":\"ens-run1\"}");
        connectionManager.appendToSnapshot("{\"type\":\"task_started\",\"taskIndex\":1}");

        // Run 2: new run starts; run-1 events must still be in the snapshot
        connectionManager.noteEnsembleStarted("ens-run2", Instant.now());
        connectionManager.appendToSnapshot("{\"type\":\"ensemble_started\",\"ensembleId\":\"ens-run2\"}");

        MockWsSession session = new MockWsSession("session-x");
        connectionManager.onConnect(session);
        String helloJson = session.sentMessages().get(0);

        // Both run-1 and run-2 events must be present in the flattened snapshot
        assertThat(helloJson).contains("ens-run1");
        assertThat(helloJson).contains("ens-run2");
        // run-1 events appear before run-2 events within the snapshotTrace array
        // (not the hello header's ensembleId field which always shows the latest run)
        int snapshotStart = helloJson.indexOf("snapshotTrace");
        assertThat(snapshotStart).isGreaterThan(-1);
        int run1InSnapshot = helloJson.indexOf("ens-run1", snapshotStart);
        int run2InSnapshot = helloJson.indexOf("ens-run2", snapshotStart);
        assertThat(run1InSnapshot).isGreaterThan(-1);
        assertThat(run2InSnapshot).isGreaterThan(-1);
        assertThat(run1InSnapshot).isLessThan(run2InSnapshot);
    }

    @Test
    void multiRunSnapshot_evictsOldestRunWhenCapExceeded() {
        // Use a ConnectionManager with maxRetainedRuns=2
        ConnectionManager limited = new ConnectionManager(serializer, 2);

        // Run 1
        limited.noteEnsembleStarted("ens-run1", Instant.now());
        limited.appendToSnapshot("{\"type\":\"ensemble_started\",\"ensembleId\":\"ens-run1\"}");

        // Run 2
        limited.noteEnsembleStarted("ens-run2", Instant.now());
        limited.appendToSnapshot("{\"type\":\"ensemble_started\",\"ensembleId\":\"ens-run2\"}");

        // Run 3: causes run-1 to be evicted (cap is 2)
        limited.noteEnsembleStarted("ens-run3", Instant.now());
        limited.appendToSnapshot("{\"type\":\"ensemble_started\",\"ensembleId\":\"ens-run3\"}");

        MockWsSession session = new MockWsSession("session-cap");
        limited.onConnect(session);
        String helloJson = session.sentMessages().get(0);

        // run-1 should be evicted; run-2 and run-3 should be present
        assertThat(helloJson).doesNotContain("ens-run1");
        assertThat(helloJson).contains("ens-run2");
        assertThat(helloJson).contains("ens-run3");
    }

    @Test
    void constructor_negativeMaxRetainedRuns_throwsIae() {
        assertThatThrownBy(() -> new ConnectionManager(serializer, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetainedRuns must be >= 1");
    }

    @Test
    void constructor_negativeMaxSnapshotIterations_throwsIae() {
        assertThatThrownBy(() -> new ConnectionManager(serializer, 10, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSnapshotIterations must be >= 0");
    }

    @Test
    void constructor_zeroMaxSnapshotIterations_isValid() {
        ConnectionManager cm0 = new ConnectionManager(serializer, 10, 0);
        assertThat(cm0.getMaxSnapshotIterations()).isEqualTo(0);
    }

    // ========================
    // Iteration snapshot ring buffer
    // ========================

    @Test
    void recordIterationStartedAndCompleted_createsSnapshot() {
        String key = "Agent:Task";
        LlmIterationStartedMessage started = new LlmIterationStartedMessage("Agent", "Task", 0, List.of(), 0);
        LlmIterationCompletedMessage completed =
                new LlmIterationCompletedMessage("Agent", "Task", 0, "FINAL_ANSWER", "result", null, 100, 50, 200);

        cm.recordIterationStarted(key, started);
        cm.recordIterationCompleted(key, completed);

        List<IterationSnapshot> snapshots = cm.getRecentIterations();
        assertThat(snapshots).isNotNull();
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).started()).isEqualTo(started);
        assertThat(snapshots.get(0).completed()).isEqualTo(completed);
    }

    @Test
    void recordIterationCompleted_withoutStarted_isNoOp() {
        String key = "Agent:Task";
        LlmIterationCompletedMessage completed =
                new LlmIterationCompletedMessage("Agent", "Task", 0, "FINAL_ANSWER", "result", null, 100, 50, 200);

        cm.recordIterationCompleted(key, completed);

        List<IterationSnapshot> snapshots = cm.getRecentIterations();
        assertThat(snapshots).isNull();
    }

    @Test
    void ringBuffer_evictsOldestWhenCapExceeded() {
        // Create CM with maxSnapshotIterations=2
        ConnectionManager cm2 = new ConnectionManager(serializer, 10, 2);
        String key = "Agent:Task";

        for (int i = 0; i < 3; i++) {
            LlmIterationStartedMessage started = new LlmIterationStartedMessage("Agent", "Task", i, List.of(), 0);
            LlmIterationCompletedMessage completed = new LlmIterationCompletedMessage(
                    "Agent", "Task", i, "FINAL_ANSWER", "result-" + i, null, 100, 50, 200);
            cm2.recordIterationStarted(key, started);
            cm2.recordIterationCompleted(key, completed);
        }

        List<IterationSnapshot> snapshots = cm2.getRecentIterations();
        assertThat(snapshots).isNotNull();
        assertThat(snapshots).hasSize(2);
        // Oldest (iteration 0) should have been evicted
        assertThat(snapshots.get(0).started().iterationIndex()).isEqualTo(1);
        assertThat(snapshots.get(1).started().iterationIndex()).isEqualTo(2);
    }

    @Test
    void clearIterationSnapshots_removesAllData() {
        String key = "Agent:Task";
        LlmIterationStartedMessage started = new LlmIterationStartedMessage("Agent", "Task", 0, List.of(), 0);
        LlmIterationCompletedMessage completed =
                new LlmIterationCompletedMessage("Agent", "Task", 0, "FINAL_ANSWER", "result", null, 100, 50, 200);

        cm.recordIterationStarted(key, started);
        cm.recordIterationCompleted(key, completed);
        assertThat(cm.getRecentIterations()).isNotNull();

        cm.clearIterationSnapshots();

        assertThat(cm.getRecentIterations()).isNull();
    }

    @Test
    void getRecentIterations_includesPendingStartWithoutCompleted() {
        String key = "Agent:Task";
        LlmIterationStartedMessage started = new LlmIterationStartedMessage("Agent", "Task", 0, List.of(), 0);

        cm.recordIterationStarted(key, started);

        List<IterationSnapshot> snapshots = cm.getRecentIterations();
        assertThat(snapshots).isNotNull();
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).started()).isEqualTo(started);
        assertThat(snapshots.get(0).completed()).isNull();
    }

    @Test
    void getRecentIterations_includesMultipleTasks() {
        String key1 = "Agent1:Task1";
        String key2 = "Agent2:Task2";

        LlmIterationStartedMessage started1 = new LlmIterationStartedMessage("Agent1", "Task1", 0, List.of(), 0);
        LlmIterationCompletedMessage completed1 =
                new LlmIterationCompletedMessage("Agent1", "Task1", 0, "FINAL_ANSWER", "r1", null, 100, 50, 200);
        LlmIterationStartedMessage started2 = new LlmIterationStartedMessage("Agent2", "Task2", 0, List.of(), 0);
        LlmIterationCompletedMessage completed2 =
                new LlmIterationCompletedMessage("Agent2", "Task2", 0, "FINAL_ANSWER", "r2", null, 200, 80, 300);

        cm.recordIterationStarted(key1, started1);
        cm.recordIterationCompleted(key1, completed1);
        cm.recordIterationStarted(key2, started2);
        cm.recordIterationCompleted(key2, completed2);

        List<IterationSnapshot> snapshots = cm.getRecentIterations();
        assertThat(snapshots).isNotNull();
        assertThat(snapshots).hasSize(2);
    }

    @Test
    void disabledSnapshots_recordIsNoOp() {
        ConnectionManager cm0 = new ConnectionManager(serializer, 10, 0);
        String key = "Agent:Task";
        LlmIterationStartedMessage started = new LlmIterationStartedMessage("Agent", "Task", 0, List.of(), 0);
        LlmIterationCompletedMessage completed =
                new LlmIterationCompletedMessage("Agent", "Task", 0, "FINAL_ANSWER", "result", null, 100, 50, 200);

        cm0.recordIterationStarted(key, started);
        cm0.recordIterationCompleted(key, completed);

        assertThat(cm0.getRecentIterations()).isNull();
    }

    @Test
    void helloMessage_containsRecentIterations() {
        cm.noteEnsembleStarted("ens-1", Instant.parse("2026-01-01T00:00:00Z"));

        String key = "Agent:Task";
        LlmIterationStartedMessage started = new LlmIterationStartedMessage(
                "Agent", "Task", 0, List.of(new LlmIterationStartedMessage.MessageDto("user", "Hello", null, null)), 1);
        LlmIterationCompletedMessage completed =
                new LlmIterationCompletedMessage("Agent", "Task", 0, "FINAL_ANSWER", "Hi there", null, 100, 50, 200);

        cm.recordIterationStarted(key, started);
        cm.recordIterationCompleted(key, completed);

        MockWsSession late = new MockWsSession("late");
        cm.onConnect(late);

        String helloJson = late.sentMessages().get(0);
        assertThat(helloJson).contains("\"type\":\"hello\"");
        assertThat(helloJson).contains("recentIterations");
        assertThat(helloJson).contains("llm_iteration_started");
        assertThat(helloJson).contains("llm_iteration_completed");
        assertThat(helloJson).contains("FINAL_ANSWER");
        assertThat(helloJson).contains("Hi there");
    }

    @Test
    void helloMessage_omitsRecentIterations_whenNone() {
        cm.noteEnsembleStarted("ens-1", Instant.parse("2026-01-01T00:00:00Z"));

        MockWsSession late = new MockWsSession("late");
        cm.onConnect(late);

        String helloJson = late.sentMessages().get(0);
        assertThat(helloJson).contains("\"type\":\"hello\"");
        assertThat(helloJson).doesNotContain("recentIterations");
    }
}
