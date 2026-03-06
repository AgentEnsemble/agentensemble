package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationFailedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.task.TaskOutput;
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
                "web_search", "{\"query\":\"AI\"}", "results...", null, "Researcher", Duration.ofMillis(820));
        listener.onToolCall(event);

        assertThat(session.sentMessages()).hasSize(1);
        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"type\":\"tool_called\"");
        assertThat(json).contains("web_search");
        assertThat(json).contains("Researcher");
    }

    @Test
    void onToolCall_outcomeIsNullBecauseToolCallEventHasNoOutcomeField() {
        // ToolCallEvent does not carry a success/failure signal, so the protocol message
        // uses null for outcome to avoid misleading "SUCCESS" when the tool may have failed.
        ToolCallEvent event = new ToolCallEvent("calculator", "{}", "42", null, "Analyst", Duration.ofMillis(5));
        listener.onToolCall(event);

        String json = session.sentMessages().get(0);
        assertThat(json).contains("\"outcome\":null");
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

    @Test
    void onToolCall_serializerThrows_doesNotPropagateException() {
        MessageSerializer failingSerializer = mock(MessageSerializer.class);
        when(failingSerializer.toJson(any())).thenThrow(new IllegalStateException("boom"));
        WebSocketStreamingListener faultyListener =
                new WebSocketStreamingListener(connectionManager, failingSerializer);

        ToolCallEvent event = new ToolCallEvent("calc", "{}", "42", null, "Agent", Duration.ofMillis(5));
        faultyListener.onToolCall(event);

        assertThat(session.sentMessages()).isEmpty();
    }
}
