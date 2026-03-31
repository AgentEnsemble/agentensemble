package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationFailedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.callback.FileChangedEvent;
import net.agentensemble.callback.LlmIterationCompletedEvent;
import net.agentensemble.callback.LlmIterationStartedEvent;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskInputEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.TokenEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.trace.CapturedMessage;
import net.agentensemble.trace.LlmInteraction;
import net.agentensemble.trace.TaskTrace;
import net.agentensemble.trace.ToolCallOutcome;
import net.agentensemble.trace.ToolCallTrace;
import net.agentensemble.web.protocol.MessageSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebSocketStreamingListener}.
 *
 * <p>Uses {@link ConnectionManagerTest.MockWsSession} stubs to capture broadcast messages
 * and verify the correct wire-protocol messages are emitted for every lifecycle event.
 */
class WebSocketStreamingListenerTest {

    private ConnectionManager connectionManager;
    private MessageSerializer serializer;
    private WebSocketStreamingListener listener;
    private ConnectionManagerTest.MockWsSession session;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
        connectionManager = new ConnectionManager(serializer);
        session = new ConnectionManagerTest.MockWsSession("s1");
        connectionManager.onConnect(session);
        session.clearMessages();
        listener = new WebSocketStreamingListener(connectionManager, serializer);
    }

    // ========================
    // Task lifecycle events
    // ========================

    @Test
    void onTaskStart_broadcastsTaskStartedMessage() {
        TaskStartEvent event = new TaskStartEvent("Research AI trends", "Researcher", 1, 3);
        listener.onTaskStart(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"task_started\"");
        assertThat(json).contains("Research AI trends");
        assertThat(json).contains("Researcher");
    }

    @Test
    void onTaskComplete_broadcastsTaskCompletedMessage_withZeroToolCalls_whenTaskOutputIsNull() {
        TaskCompleteEvent event = new TaskCompleteEvent("Write report", "Writer", null, Duration.ofSeconds(5), 2, 3);
        listener.onTaskComplete(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"task_completed\"");
        assertThat(json).contains("Write report");
        assertThat(json).contains("\"toolCallCount\":0");
    }

    @Test
    void onTaskComplete_extractsToolCallCountFromTrace() {
        TaskOutput taskOutput = mock(TaskOutput.class);
        TaskTrace trace = mock(TaskTrace.class);
        LlmInteraction interaction = mock(LlmInteraction.class);

        when(taskOutput.getTrace()).thenReturn(trace);
        when(trace.getLlmInteractions()).thenReturn(List.of(interaction, interaction));
        when(interaction.getToolCalls()).thenReturn(List.of(dummyToolCall(), dummyToolCall())); // 2 each x 2 = 4

        TaskCompleteEvent event =
                new TaskCompleteEvent("Analyse data", "Analyst", taskOutput, Duration.ofSeconds(8), 1, 2);
        listener.onTaskComplete(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"toolCallCount\":4");
    }

    @Test
    void onTaskComplete_traceIsNull_toolCallCountIsZero() {
        TaskOutput taskOutput = mock(TaskOutput.class);
        when(taskOutput.getTrace()).thenReturn(null);

        TaskCompleteEvent event =
                new TaskCompleteEvent("Quick task", "Agent", taskOutput, Duration.ofMillis(100), 1, 1);
        listener.onTaskComplete(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"toolCallCount\":0");
    }

    @Test
    void onTaskComplete_llmInteractionsIsNull_toolCallCountIsZero() {
        TaskOutput taskOutput = mock(TaskOutput.class);
        TaskTrace trace = mock(TaskTrace.class);
        when(taskOutput.getTrace()).thenReturn(trace);
        when(trace.getLlmInteractions()).thenReturn(null);

        TaskCompleteEvent event =
                new TaskCompleteEvent("Another task", "Agent", taskOutput, Duration.ofMillis(200), 1, 1);
        listener.onTaskComplete(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"toolCallCount\":0");
    }

    @Test
    void onTaskComplete_interactionWithNullToolCalls_isSkipped() {
        TaskOutput taskOutput = mock(TaskOutput.class);
        TaskTrace trace = mock(TaskTrace.class);
        LlmInteraction interactionWithTools = mock(LlmInteraction.class);
        LlmInteraction interactionWithNullTools = mock(LlmInteraction.class);

        when(taskOutput.getTrace()).thenReturn(trace);
        when(trace.getLlmInteractions()).thenReturn(List.of(interactionWithTools, interactionWithNullTools));
        when(interactionWithTools.getToolCalls()).thenReturn(List.of(dummyToolCall()));
        when(interactionWithNullTools.getToolCalls()).thenReturn(null); // null toolCalls skipped

        TaskCompleteEvent event = new TaskCompleteEvent("Mixed task", "Agent", taskOutput, Duration.ofSeconds(2), 1, 1);
        listener.onTaskComplete(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"toolCallCount\":1");
    }

    @Test
    void onTaskComplete_listContainsNullInteraction_isSkipped() {
        TaskOutput taskOutput = mock(TaskOutput.class);
        TaskTrace trace = mock(TaskTrace.class);
        LlmInteraction validInteraction = mock(LlmInteraction.class);

        when(taskOutput.getTrace()).thenReturn(trace);
        // list contains a null element -- should be skipped by null check
        when(trace.getLlmInteractions()).thenReturn(Arrays.asList(null, validInteraction));
        when(validInteraction.getToolCalls()).thenReturn(List.of(dummyToolCall(), dummyToolCall()));

        TaskCompleteEvent event =
                new TaskCompleteEvent("Null interaction task", "Agent", taskOutput, Duration.ofSeconds(1), 1, 1);
        listener.onTaskComplete(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"toolCallCount\":2");
    }

    @Test
    void onTaskFailed_broadcastsTaskFailedMessage_withExceptionMessage() {
        RuntimeException cause = new RuntimeException("LLM API call failed");
        TaskFailedEvent event = new TaskFailedEvent("Research task", "Researcher", cause, Duration.ofSeconds(2), 1, 2);
        listener.onTaskFailed(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"task_failed\"");
        assertThat(json).contains("Research task");
        assertThat(json).contains("LLM API call failed");
    }

    @Test
    void onTaskFailed_causeMessageIsNull_usesClassSimpleName() {
        // RuntimeException constructed with null message → getMessage() returns null
        RuntimeException causeWithNoMessage = new RuntimeException((String) null);
        TaskFailedEvent event = new TaskFailedEvent("Task", "Agent", causeWithNoMessage, Duration.ofMillis(100), 1, 1);
        listener.onTaskFailed(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("RuntimeException");
    }

    @Test
    void onTaskFailed_causeIsNull_usesUnknownErrorText() {
        TaskFailedEvent event = new TaskFailedEvent("Task", "Agent", null, Duration.ofMillis(50), 1, 1);
        listener.onTaskFailed(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("unknown error");
    }

    // ========================
    // Tool calls
    // ========================

    @Test
    void onToolCall_broadcastsToolCalledMessage() {
        ToolCallEvent event = new ToolCallEvent(
                "web_search",
                "{\"query\":\"AI\"}",
                "results...",
                null,
                "Researcher",
                Duration.ofMillis(820),
                1,
                "SUCCESS");
        listener.onToolCall(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"tool_called\"");
        assertThat(json).contains("web_search");
        assertThat(json).contains("Researcher");
    }

    @Test
    void onToolCall_outcomeIsPopulatedFromEnrichedToolCallEvent() {
        // ToolCallEvent now carries taskIndex and outcome from AgentExecutor.
        ToolCallEvent event =
                new ToolCallEvent("calculator", "{}", "42", null, "Analyst", Duration.ofMillis(5), 2, "SUCCESS");
        listener.onToolCall(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"outcome\":\"SUCCESS\"");
        assertThat(json).contains("\"taskIndex\":2");
    }

    @Test
    void onTaskComplete_tokenCountIsMinusOneToIndicateUnknown() {
        // tokenCount uses -1 as the "unknown" sentinel, consistent with TaskMetrics.totalTokens.
        TaskCompleteEvent event = new TaskCompleteEvent("Task", "Agent", null, Duration.ofSeconds(1), 1, 1);
        listener.onTaskComplete(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"tokenCount\":-1");
    }

    // ========================
    // Delegation lifecycle events
    // ========================

    @Test
    void onDelegationStarted_broadcastsDelegationStartedMessage() {
        DelegationStartedEvent event =
                new DelegationStartedEvent("delegation-1", "Manager", "Researcher", "Research AI", 1, null);
        listener.onDelegationStarted(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"delegation_started\"");
        assertThat(json).contains("delegation-1");
        assertThat(json).contains("Researcher");
    }

    @Test
    void onDelegationCompleted_broadcastsDelegationCompletedMessage() {
        DelegationCompletedEvent event =
                new DelegationCompletedEvent("delegation-1", "Manager", "Researcher", null, Duration.ofSeconds(3));
        listener.onDelegationCompleted(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"delegation_completed\"");
        assertThat(json).contains("delegation-1");
    }

    @Test
    void onDelegationFailed_broadcastsDelegationFailedMessage() {
        DelegationFailedEvent event = new DelegationFailedEvent(
                "delegation-2",
                "Manager",
                "Analyst",
                "Worker threw exception: NullPointerException",
                null,
                null,
                Duration.ofMillis(500));
        listener.onDelegationFailed(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"delegation_failed\"");
        assertThat(json).contains("delegation-2");
        assertThat(json).contains("NullPointerException");
    }

    // ========================
    // Broadcast failure resilience
    // ========================

    @Test
    void onTaskStart_serializerThrows_doesNotPropagateException() {
        // Replace the serializer with one that always throws
        MessageSerializer failingSerializer = mock(MessageSerializer.class);
        when(failingSerializer.toJson(any())).thenThrow(new IllegalStateException("serialize failed"));
        WebSocketStreamingListener faultyListener =
                new WebSocketStreamingListener(connectionManager, failingSerializer);

        TaskStartEvent event = new TaskStartEvent("Task", "Agent", 1, 1);
        // Should not throw -- exceptions in listeners are swallowed and logged
        faultyListener.onTaskStart(event);
        // Session received nothing (serializer threw before send)
        assertThat(session.sentMessages()).isEmpty();
    }

    // ========================
    // Private helpers
    // ========================

    private static ToolCallTrace dummyToolCall() {
        Instant now = Instant.now();
        return ToolCallTrace.builder()
                .toolName("dummy")
                .arguments("{}")
                .startedAt(now)
                .completedAt(now)
                .duration(Duration.ZERO)
                .outcome(ToolCallOutcome.SUCCESS)
                .build();
    }

    // ========================
    // Streaming tokens
    // ========================

    @Test
    void onToken_broadcastsTokenMessage() {
        TokenEvent event = new TokenEvent("Hello ", "Senior Research Analyst", "Research AI trends");
        listener.onToken(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"token\"");
        assertThat(json).contains("\"token\":\"Hello \"");
        assertThat(json).contains("Senior Research Analyst");
        assertThat(json).contains("Research AI trends");
    }

    @Test
    void onToken_doesNotAppendToLateJoinSnapshot() {
        // Token messages are ephemeral -- they must NOT be stored in the snapshot.
        // A late-joining client should rely on task_completed for the authoritative output,
        // not on a replayed token stream.
        TokenEvent event = new TokenEvent("tok", "Writer", "Write a report");
        listener.onToken(event);

        // Message was broadcast to the live session
        assertThat(session.sentMessages()).hasSize(1);

        // Connect a late-joining client -- the hello message snapshot must NOT contain token events
        ConnectionManagerTest.MockWsSession lateSession = new ConnectionManagerTest.MockWsSession("late");
        connectionManager.onConnect(lateSession);

        String helloJson = lateSession.sentMessages().get(0);
        assertThat(helloJson).contains("\"type\":\"hello\"");
        // snapshotTrace should be absent or empty since onToken skips appendToSnapshot
        assertThat(helloJson).doesNotContain("\"type\":\"token\"");
    }

    @Test
    void onToken_multipleTokens_allBroadcast() {
        for (int i = 0; i < 5; i++) {
            listener.onToken(new TokenEvent("tok" + i, "Agent", "Write something"));
        }
        assertThat(session.sentMessages()).hasSize(5);
        assertThat(session.sentMessages()).allMatch(m -> m.contains("\"type\":\"token\""));
    }

    @Test
    void onToken_serializerThrows_doesNotPropagateException() {
        MessageSerializer failingSerializer = mock(MessageSerializer.class);
        when(failingSerializer.toJson(any())).thenThrow(new IllegalStateException("serialize failed"));
        WebSocketStreamingListener faultyListener =
                new WebSocketStreamingListener(connectionManager, failingSerializer);

        // Should not throw
        faultyListener.onToken(new TokenEvent("tok", "Agent", "Some task"));
        assertThat(session.sentMessages()).isEmpty();
    }

    @Test
    void onToolCall_serializerThrows_doesNotPropagateException() {
        MessageSerializer failingSerializer = mock(MessageSerializer.class);
        when(failingSerializer.toJson(any())).thenThrow(new IllegalStateException("boom"));
        WebSocketStreamingListener faultyListener =
                new WebSocketStreamingListener(connectionManager, failingSerializer);

        ToolCallEvent event = new ToolCallEvent("calc", "{}", "42", null, "Agent", Duration.ofMillis(5), 0, "SUCCESS");
        faultyListener.onToolCall(event);

        assertThat(session.sentMessages()).isEmpty();
    }

    // ========================
    // Thread safety
    // ========================

    @Test
    void concurrentCallsFromMultipleThreads_doNotCorruptMessages() throws InterruptedException {
        // Simulate a parallel workflow firing all 7 event types concurrently from
        // virtual threads. All broadcasts must arrive without corruption or loss.
        int threadCount = 8;
        int eventsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int thread = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < eventsPerThread; i++) {
                        listener.onTaskStart(new TaskStartEvent("Task-" + thread + "-" + i, "Agent", 1, 1));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // All events must have arrived; none lost or corrupted
        int expectedCount = threadCount * eventsPerThread;
        assertThat(session.sentMessages()).hasSize(expectedCount);

        // Each message must be valid JSON with the correct type
        List<String> messages = new ArrayList<>(session.sentMessages());
        assertThat(messages).allMatch(m -> m.contains("\"type\":\"task_started\""));
        assertThat(messages).allMatch(m -> m.startsWith("{"));
        assertThat(messages).allMatch(m -> m.endsWith("}"));
    }

    @Test
    void broadcastBuildsSnapshotIncrementally() {
        // Each call to broadcast() should add to the snapshot in ConnectionManager.
        // After 3 events, the snapshot should contain all 3 messages.
        // noteEnsembleStarted must be called first -- appendToSnapshot silently drops
        // pre-run messages (i.e. messages before a run has started).
        connectionManager.noteEnsembleStarted("ens-snap", java.time.Instant.now());
        listener.onTaskStart(new TaskStartEvent("Task 1", "Agent A", 1, 3));
        listener.onTaskStart(new TaskStartEvent("Task 2", "Agent B", 2, 3));
        listener.onTaskStart(new TaskStartEvent("Task 3", "Agent C", 3, 3));

        // Connect a late-joining session and check its hello contains all 3 events
        ConnectionManagerTest.MockWsSession lateSession = new ConnectionManagerTest.MockWsSession("late");
        connectionManager.onConnect(lateSession);

        String helloJson = lateSession.sentMessages().get(0);
        assertThat(helloJson).contains("\"type\":\"hello\"");
        assertThat(helloJson).contains("snapshotTrace");
        // Snapshot is a JSON array containing all 3 task_started events
        int count = 0;
        int idx = 0;
        while ((idx = helloJson.indexOf("task_started", idx)) != -1) {
            count++;
            idx++;
        }
        assertThat(count).isEqualTo(3);
    }

    // ========================
    // LLM iteration lifecycle events
    // ========================

    @Test
    void onLlmIterationStarted_broadcastsLlmIterationStartedMessage() {
        CapturedMessage sysMsg = CapturedMessage.builder()
                .role("system")
                .content("You are helpful")
                .build();
        CapturedMessage userMsg = CapturedMessage.builder()
                .role("user")
                .content("Tell me about AI")
                .build();

        LlmIterationStartedEvent event =
                new LlmIterationStartedEvent("Researcher", "Find AI papers", 0, List.of(sysMsg, userMsg));
        listener.onLlmIterationStarted(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"llm_iteration_started\"");
        assertThat(json).contains("Researcher");
        assertThat(json).contains("Find AI papers");
        assertThat(json).contains("\"iterationIndex\":0");
    }

    @Test
    void onLlmIterationStarted_withToolCallMessages_convertsToolCalls() {
        Map<String, Object> toolCall = new java.util.LinkedHashMap<>();
        toolCall.put("name", "web_search");
        toolCall.put("arguments", "{\"query\":\"AI\"}");
        CapturedMessage assistantMsg =
                CapturedMessage.builder().role("assistant").toolCall(toolCall).build();

        LlmIterationStartedEvent event = new LlmIterationStartedEvent("Agent", "Task", 1, List.of(assistantMsg));
        listener.onLlmIterationStarted(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"llm_iteration_started\"");
        assertThat(json).contains("web_search");
    }

    @Test
    void onLlmIterationStarted_isEphemeral_notInSnapshotTrace() {
        CapturedMessage msg =
                CapturedMessage.builder().role("user").content("hi").build();
        connectionManager.noteEnsembleStarted("ens-1", Instant.now());
        listener.onLlmIterationStarted(new LlmIterationStartedEvent("Agent", "Task", 0, List.of(msg)));

        // Late-joining client should not see llm_iteration_started in the snapshotTrace
        // (it is ephemeral), but it MAY appear in recentIterations (IO-003 ring buffer).
        ConnectionManagerTest.MockWsSession lateSession = new ConnectionManagerTest.MockWsSession("late");
        connectionManager.onConnect(lateSession);

        String helloJson = lateSession.sentMessages().get(0);
        // The snapshotTrace should not contain iteration events (they are ephemeral)
        if (helloJson.contains("snapshotTrace")) {
            int snapshotStart = helloJson.indexOf("snapshotTrace");
            int snapshotEnd = helloJson.indexOf("]", snapshotStart);
            String snapshotSection = helloJson.substring(snapshotStart, snapshotEnd + 1);
            assertThat(snapshotSection).doesNotContain("llm_iteration_started");
        }
    }

    @Test
    void onLlmIterationStarted_includesTotalMessageCount() {
        CapturedMessage msg1 = CapturedMessage.builder()
                .role("system")
                .content("You are helpful")
                .build();
        CapturedMessage msg2 =
                CapturedMessage.builder().role("user").content("Hello").build();

        LlmIterationStartedEvent event = new LlmIterationStartedEvent("Agent", "Task", 0, List.of(msg1, msg2));
        listener.onLlmIterationStarted(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"totalMessageCount\":2");
    }

    @Test
    void onLlmIterationStarted_capsMessagesTo20() {
        // Build a message list with 25 messages
        List<CapturedMessage> messages = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            messages.add(
                    CapturedMessage.builder().role("user").content("msg-" + i).build());
        }

        LlmIterationStartedEvent event = new LlmIterationStartedEvent("Agent", "Task", 5, messages);
        listener.onLlmIterationStarted(event);

        String json = session.sentMessages().get(0);
        // totalMessageCount should be 25 (the full count)
        assertThat(json).contains("\"totalMessageCount\":25");
        // Only the last 20 messages should be in the wire payload (msg-5 through msg-24)
        assertThat(json).doesNotContain("msg-0");
        assertThat(json).doesNotContain("msg-4");
        assertThat(json).contains("msg-5");
        assertThat(json).contains("msg-24");
    }

    @Test
    void onLlmIterationCompleted_broadcastsLlmIterationCompletedMessage() {
        LlmIterationCompletedEvent.ToolCallRequest toolReq =
                new LlmIterationCompletedEvent.ToolCallRequest("calculator", "{\"expr\":\"2+2\"}");
        LlmIterationCompletedEvent event = new LlmIterationCompletedEvent(
                "Analyst",
                "Analyze data",
                2,
                "TOOL_CALLS",
                null,
                List.of(toolReq),
                500L,
                200L,
                Duration.ofMillis(1200));
        listener.onLlmIterationCompleted(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"llm_iteration_completed\"");
        assertThat(json).contains("Analyst");
        assertThat(json).contains("TOOL_CALLS");
        assertThat(json).contains("calculator");
        assertThat(json).contains("\"inputTokens\":500");
        assertThat(json).contains("\"outputTokens\":200");
        assertThat(json).contains("\"latencyMs\":1200");
    }

    @Test
    void onLlmIterationCompleted_finalAnswer_broadcastsCorrectly() {
        LlmIterationCompletedEvent event = new LlmIterationCompletedEvent(
                "Writer",
                "Write report",
                3,
                "FINAL_ANSWER",
                "Here is the report...",
                null,
                1000L,
                800L,
                Duration.ofSeconds(2));
        listener.onLlmIterationCompleted(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"llm_iteration_completed\"");
        assertThat(json).contains("FINAL_ANSWER");
        assertThat(json).contains("Here is the report...");
    }

    @Test
    void onLlmIterationCompleted_nullLatency_broadcastsZero() {
        LlmIterationCompletedEvent event =
                new LlmIterationCompletedEvent("Agent", "Task", 0, "FINAL_ANSWER", "done", null, 100L, 50L, null);
        listener.onLlmIterationCompleted(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"latencyMs\":0");
    }

    @Test
    void onLlmIterationCompleted_nullToolRequests_broadcastsNullToolRequests() {
        LlmIterationCompletedEvent event = new LlmIterationCompletedEvent(
                "Agent", "Task", 0, "FINAL_ANSWER", "done", null, 100L, 50L, Duration.ofMillis(100));
        listener.onLlmIterationCompleted(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"llm_iteration_completed\"");
    }

    @Test
    void onLlmIterationCompleted_isEphemeral_notInSnapshotTrace() {
        connectionManager.noteEnsembleStarted("ens-1", Instant.now());
        listener.onLlmIterationCompleted(new LlmIterationCompletedEvent(
                "Agent", "Task", 0, "FINAL_ANSWER", "done", null, 100L, 50L, Duration.ofMillis(100)));

        ConnectionManagerTest.MockWsSession lateSession = new ConnectionManagerTest.MockWsSession("late");
        connectionManager.onConnect(lateSession);

        String helloJson = lateSession.sentMessages().get(0);
        // The snapshotTrace should not contain iteration events (they are ephemeral).
        // Note: completed without a prior started will not appear in recentIterations either
        // (recordIterationCompleted requires a pending started entry).
        if (helloJson.contains("snapshotTrace")) {
            int snapshotStart = helloJson.indexOf("snapshotTrace");
            int snapshotEnd = helloJson.indexOf("]", snapshotStart);
            String snapshotSection = helloJson.substring(snapshotStart, snapshotEnd + 1);
            assertThat(snapshotSection).doesNotContain("llm_iteration_completed");
        }
    }

    // ========================
    // File change events
    // ========================

    @Test
    void onFileChanged_broadcastsFileChangedMessage() {
        Instant ts = Instant.parse("2026-03-05T14:30:00Z");
        FileChangedEvent event = new FileChangedEvent("Coder", "src/Main.java", "MODIFIED", 10, 3, ts);
        listener.onFileChanged(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"file_changed\"");
        assertThat(json).contains("Coder");
        assertThat(json).contains("src/Main.java");
        assertThat(json).contains("MODIFIED");
        assertThat(json).contains("\"linesAdded\":10");
        assertThat(json).contains("\"linesRemoved\":3");
    }

    @Test
    void onFileChanged_isPersistedInSnapshot() {
        // File change events ARE persisted (unlike ephemeral LLM iteration events)
        connectionManager.noteEnsembleStarted("ens-1", Instant.now());
        listener.onFileChanged(new FileChangedEvent("Coder", "src/App.java", "CREATED", 50, 0, Instant.now()));

        ConnectionManagerTest.MockWsSession lateSession = new ConnectionManagerTest.MockWsSession("late");
        connectionManager.onConnect(lateSession);

        String helloJson = lateSession.sentMessages().get(0);
        assertThat(helloJson).contains("file_changed");
        assertThat(helloJson).contains("src/App.java");
    }

    @Test
    void onFileChanged_multipleFiles_allBroadcast() {
        listener.onFileChanged(new FileChangedEvent("Dev", "a.java", "CREATED", 10, 0, Instant.now()));
        listener.onFileChanged(new FileChangedEvent("Dev", "b.java", "MODIFIED", 5, 2, Instant.now()));
        listener.onFileChanged(new FileChangedEvent("Dev", "c.java", "DELETED", 0, 20, Instant.now()));

        assertThat(session.sentMessages()).hasSize(3);
        assertThat(session.sentMessages()).allMatch(m -> m.contains("\"type\":\"file_changed\""));
    }

    // ========================
    // Task input events
    // ========================

    @Test
    void onTaskInput_broadcastsTaskInputMessage() {
        TaskInputEvent event = new TaskInputEvent(
                1, "Research AI", "A summary", "Researcher", "Find papers", "PhD in AI", List.of("web_search"), "ctx");
        listener.onTaskInput(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"task_input\"");
        assertThat(json).contains("Researcher");
        assertThat(json).contains("web_search");
    }

    @Test
    void onTaskInput_truncatesLargeAssembledContext() {
        // Build a string longer than 8000 chars
        String longContext = "X".repeat(10_000);
        TaskInputEvent event =
                new TaskInputEvent(1, "Task", "Output", "Agent", "Goal", "Background", List.of("tool"), longContext);
        listener.onTaskInput(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"task_input\"");
        // The truncated field should contain the truncation marker
        assertThat(json).contains("... [truncated]");
        // The full 10000-char string should NOT be present
        assertThat(json).doesNotContain(longContext);
    }

    @Test
    void onTaskInput_shortContext_notTruncated() {
        String shortContext = "Short prompt content";
        TaskInputEvent event =
                new TaskInputEvent(1, "Task", "Output", "Agent", "Goal", "Background", List.of("tool"), shortContext);
        listener.onTaskInput(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains(shortContext);
        assertThat(json).doesNotContain("[truncated]");
    }

    @Test
    void onTaskInput_nullContext_handledGracefully() {
        TaskInputEvent event =
                new TaskInputEvent(1, "Task", "Output", "Agent", "Goal", "Background", List.of("tool"), null);
        listener.onTaskInput(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"task_input\"");
    }

    @Test
    void onTaskInput_isPersistedInSnapshot() {
        connectionManager.noteEnsembleStarted("ens-1", Instant.now());
        TaskInputEvent event =
                new TaskInputEvent(1, "Task", "Output", "Agent", "Goal", "Background", List.of("tool"), "prompt");
        listener.onTaskInput(event);

        ConnectionManagerTest.MockWsSession lateSession = new ConnectionManagerTest.MockWsSession("late");
        connectionManager.onConnect(lateSession);

        String helloJson = lateSession.sentMessages().get(0);
        assertThat(helloJson).contains("task_input");
    }

    // ========================
    // File change events (continued)
    // ========================

    @Test
    void onFileChanged_serializerThrows_doesNotPropagateException() {
        MessageSerializer failingSerializer = mock(MessageSerializer.class);
        when(failingSerializer.toJson(any())).thenThrow(new IllegalStateException("serialize failed"));
        WebSocketStreamingListener faultyListener =
                new WebSocketStreamingListener(connectionManager, failingSerializer);

        // Should not throw
        faultyListener.onFileChanged(new FileChangedEvent("Agent", "file.txt", "CREATED", 1, 0, Instant.now()));
        assertThat(session.sentMessages()).isEmpty();
    }

    @Test
    void onLlmIterationStarted_serializerThrows_doesNotPropagateException() {
        MessageSerializer failingSerializer = mock(MessageSerializer.class);
        when(failingSerializer.toJson(any())).thenThrow(new IllegalStateException("serialize failed"));
        WebSocketStreamingListener faultyListener =
                new WebSocketStreamingListener(connectionManager, failingSerializer);

        CapturedMessage msg =
                CapturedMessage.builder().role("user").content("hi").build();
        faultyListener.onLlmIterationStarted(new LlmIterationStartedEvent("Agent", "Task", 0, List.of(msg)));
        assertThat(session.sentMessages()).isEmpty();
    }

    @Test
    void onLlmIterationCompleted_serializerThrows_doesNotPropagateException() {
        MessageSerializer failingSerializer = mock(MessageSerializer.class);
        when(failingSerializer.toJson(any())).thenThrow(new IllegalStateException("serialize failed"));
        WebSocketStreamingListener faultyListener =
                new WebSocketStreamingListener(connectionManager, failingSerializer);

        faultyListener.onLlmIterationCompleted(new LlmIterationCompletedEvent(
                "Agent", "Task", 0, "FINAL_ANSWER", "done", null, 100L, 50L, Duration.ofMillis(100)));
        assertThat(session.sentMessages()).isEmpty();
    }

    // ========================
    // Iteration snapshot recording (IO-003)
    // ========================

    @Test
    void iterationPair_recordedInSnapshotForLateJoiners() {
        connectionManager.noteEnsembleStarted("ens-1", Instant.now());

        CapturedMessage sysMsg = CapturedMessage.builder()
                .role("system")
                .content("You are helpful")
                .build();
        CapturedMessage userMsg = CapturedMessage.builder()
                .role("user")
                .content("Tell me about AI")
                .build();

        listener.onLlmIterationStarted(
                new LlmIterationStartedEvent("Researcher", "Find AI papers", 0, List.of(sysMsg, userMsg)));
        listener.onLlmIterationCompleted(new LlmIterationCompletedEvent(
                "Researcher",
                "Find AI papers",
                0,
                "FINAL_ANSWER",
                "AI is great",
                null,
                500L,
                200L,
                Duration.ofMillis(1200)));

        // Late-joining client should see iteration data in hello
        ConnectionManagerTest.MockWsSession lateSession = new ConnectionManagerTest.MockWsSession("late");
        connectionManager.onConnect(lateSession);

        String helloJson = lateSession.sentMessages().get(0);
        assertThat(helloJson).contains("recentIterations");
        assertThat(helloJson).contains("llm_iteration_started");
        assertThat(helloJson).contains("llm_iteration_completed");
        assertThat(helloJson).contains("AI is great");
    }

    @Test
    void pendingIteration_includedInHelloAsIncomplete() {
        connectionManager.noteEnsembleStarted("ens-1", Instant.now());

        CapturedMessage msg =
                CapturedMessage.builder().role("user").content("Hello").build();

        // Only start, no completed yet
        listener.onLlmIterationStarted(new LlmIterationStartedEvent("Agent", "Task", 0, List.of(msg)));

        ConnectionManagerTest.MockWsSession lateSession = new ConnectionManagerTest.MockWsSession("late");
        connectionManager.onConnect(lateSession);

        String helloJson = lateSession.sentMessages().get(0);
        assertThat(helloJson).contains("recentIterations");
        assertThat(helloJson).contains("llm_iteration_started");
        // completed should be null (omitted by NON_NULL)
        assertThat(helloJson).doesNotContain("llm_iteration_completed");
    }

    @Test
    void iterationSnapshots_clearedOnNewEnsembleStart() {
        connectionManager.noteEnsembleStarted("ens-1", Instant.now());

        CapturedMessage msg =
                CapturedMessage.builder().role("user").content("Hello").build();

        listener.onLlmIterationStarted(new LlmIterationStartedEvent("Agent", "Task", 0, List.of(msg)));
        listener.onLlmIterationCompleted(new LlmIterationCompletedEvent(
                "Agent", "Task", 0, "FINAL_ANSWER", "done", null, 100L, 50L, Duration.ofMillis(100)));

        // Simulate new ensemble start which should clear snapshots
        connectionManager.noteEnsembleStarted("ens-2", Instant.now());
        connectionManager.clearIterationSnapshots();

        ConnectionManagerTest.MockWsSession lateSession = new ConnectionManagerTest.MockWsSession("late");
        connectionManager.onConnect(lateSession);

        String helloJson = lateSession.sentMessages().get(0);
        assertThat(helloJson).doesNotContain("recentIterations");
    }
}
