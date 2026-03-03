package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests for the callback system.
 *
 * Uses mocked LLMs to exercise the full listener event lifecycle without
 * requiring real API keys. Verifies that events are fired correctly from
 * SequentialWorkflowExecutor and AgentExecutor, that multiple listeners
 * all receive events, and that listener exceptions do not abort execution.
 */
class CallbackIntegrationTest {

    // ========================
    // Helpers
    // ========================

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private ChatResponse toolCallResponse(String toolName, String arguments) {
        var request = ToolExecutionRequest.builder()
                .id("call-1")
                .name(toolName)
                .arguments(arguments)
                .build();
        return ChatResponse.builder().aiMessage(new AiMessage(List.of(request))).build();
    }

    private Agent mockAgent(String role, ChatModel llm) {
        return Agent.builder().role(role).goal("Do work").llm(llm).build();
    }

    private Task task(String description, Agent agent) {
        return Task.builder()
                .description(description)
                .expectedOutput("Output")
                .agent(agent)
                .build();
    }

    // ========================
    // TaskStartEvent
    // ========================

    @Test
    void onTaskStart_firedBeforeExecution() {
        var llm = mock(ChatModel.class);
        List<String> eventOrder = new ArrayList<>();

        when(llm.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            eventOrder.add("llm_called");
            return textResponse("result");
        });

        var agent = mockAgent("Researcher", llm);
        var t = task("Research AI", agent);

        Ensemble.builder()
                .agent(agent)
                .task(t)
                .onTaskStart(event -> eventOrder.add("task_start"))
                .build()
                .run();

        // task_start must appear before llm_called
        assertThat(eventOrder.indexOf("task_start")).isLessThan(eventOrder.indexOf("llm_called"));
    }

    @Test
    void onTaskStart_eventHasCorrectFields() {
        var llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

        var agent = mockAgent("Researcher", llm);
        var t = task("Research AI trends", agent);

        List<TaskStartEvent> events = new ArrayList<>();
        Ensemble.builder().agent(agent).task(t).onTaskStart(events::add).build().run();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).taskDescription()).isEqualTo("Research AI trends");
        assertThat(events.get(0).agentRole()).isEqualTo("Researcher");
        assertThat(events.get(0).taskIndex()).isEqualTo(1);
        assertThat(events.get(0).totalTasks()).isEqualTo(1);
    }

    @Test
    void onTaskStart_firedForEachTask_inOrder() {
        var llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

        var agent = mockAgent("Worker", llm);
        var t1 = task("Task 1", agent);
        var t2 = task("Task 2", agent);

        List<Integer> taskIndices = new ArrayList<>();
        Ensemble.builder()
                .agent(agent)
                .task(t1)
                .task(t2)
                .onTaskStart(event -> taskIndices.add(event.taskIndex()))
                .build()
                .run();

        assertThat(taskIndices).containsExactly(1, 2);
    }

    // ========================
    // TaskCompleteEvent
    // ========================

    @Test
    void onTaskComplete_firedAfterSuccessfulExecution() {
        var llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class))).thenReturn(textResponse("success output"));

        var agent = mockAgent("Researcher", llm);
        var t = task("Research AI", agent);

        List<TaskCompleteEvent> events = new ArrayList<>();
        Ensemble.builder()
                .agent(agent)
                .task(t)
                .onTaskComplete(events::add)
                .build()
                .run();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).taskOutput().getRaw()).isEqualTo("success output");
        assertThat(events.get(0).agentRole()).isEqualTo("Researcher");
        assertThat(events.get(0).duration()).isNotNull().isPositive();
    }

    @Test
    void onTaskComplete_firedForEachTask() {
        var llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

        var agent = mockAgent("Worker", llm);
        var t1 = task("Task 1", agent);
        var t2 = task("Task 2", agent);

        AtomicInteger count = new AtomicInteger(0);
        Ensemble.builder()
                .agent(agent)
                .task(t1)
                .task(t2)
                .onTaskComplete(event -> count.incrementAndGet())
                .build()
                .run();

        assertThat(count.get()).isEqualTo(2);
    }

    // ========================
    // TaskFailedEvent
    // ========================

    @Test
    void onTaskFailed_firedWhenTaskFails_beforeExceptionPropagates() {
        var llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM error"));

        var agent = mockAgent("Researcher", llm);
        var t = task("Failing task", agent);

        List<TaskFailedEvent> failedEvents = new ArrayList<>();

        assertThatThrownBy(() -> Ensemble.builder()
                        .agent(agent)
                        .task(t)
                        .onTaskFailed(failedEvents::add)
                        .build()
                        .run())
                .isInstanceOf(TaskExecutionException.class);

        // Listener was called even though exception was thrown
        assertThat(failedEvents).hasSize(1);
        assertThat(failedEvents.get(0).taskDescription()).isEqualTo("Failing task");
        assertThat(failedEvents.get(0).agentRole()).isEqualTo("Researcher");
        assertThat(failedEvents.get(0).cause()).hasMessage("Agent 'Researcher' failed: LLM error");
    }

    @Test
    void onTaskComplete_notFiredWhenTaskFails() {
        var llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM error"));

        var agent = mockAgent("Researcher", llm);
        var t = task("Failing task", agent);

        AtomicBoolean completeCalled = new AtomicBoolean(false);

        assertThatThrownBy(() -> Ensemble.builder()
                        .agent(agent)
                        .task(t)
                        .onTaskComplete(event -> completeCalled.set(true))
                        .build()
                        .run())
                .isInstanceOf(TaskExecutionException.class);

        assertThat(completeCalled.get()).isFalse();
    }

    // ========================
    // ToolCallEvent
    // ========================

    @Test
    void onToolCall_firedAfterToolExecution() {
        var llm = mock(ChatModel.class);
        var tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("search");
        when(tool.description()).thenReturn("Search the web");
        when(tool.execute("AI trends")).thenReturn(ToolResult.success("Top AI trends"));

        when(llm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse("search", "{\"input\": \"AI trends\"}"))
                .thenReturn(textResponse("Based on search: Top AI trends"));

        var agent = Agent.builder()
                .role("Researcher")
                .goal("Research")
                .tools(List.of(tool))
                .llm(llm)
                .build();
        var t = task("Research AI", agent);

        List<ToolCallEvent> toolEvents = new ArrayList<>();
        Ensemble.builder()
                .agent(agent)
                .task(t)
                .onToolCall(toolEvents::add)
                .build()
                .run();

        assertThat(toolEvents).hasSize(1);
        assertThat(toolEvents.get(0).toolName()).isEqualTo("search");
        assertThat(toolEvents.get(0).toolResult()).isEqualTo("Top AI trends");
        assertThat(toolEvents.get(0).agentRole()).isEqualTo("Researcher");
        assertThat(toolEvents.get(0).duration()).isNotNull().isPositive();
    }

    // ========================
    // Multiple listeners
    // ========================

    @Test
    void multipleListeners_allReceiveEvents() {
        var llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

        var agent = mockAgent("Worker", llm);
        var t = task("Task", agent);

        AtomicInteger l1Count = new AtomicInteger(0);
        AtomicInteger l2Count = new AtomicInteger(0);
        AtomicInteger l3Count = new AtomicInteger(0);

        Ensemble.builder()
                .agent(agent)
                .task(t)
                .onTaskStart(event -> l1Count.incrementAndGet())
                .onTaskStart(event -> l2Count.incrementAndGet())
                .listener(new EnsembleListener() {
                    @Override
                    public void onTaskStart(TaskStartEvent event) {
                        l3Count.incrementAndGet();
                    }
                })
                .build()
                .run();

        assertThat(l1Count.get()).isEqualTo(1);
        assertThat(l2Count.get()).isEqualTo(1);
        assertThat(l3Count.get()).isEqualTo(1);
    }

    @Test
    void listenerException_doesNotAbortExecution() {
        var llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

        var agent = mockAgent("Worker", llm);
        var t = task("Task", agent);

        AtomicBoolean secondListenerCalled = new AtomicBoolean(false);

        // First listener throws; second should still be called and execution should complete
        var output = Ensemble.builder()
                .agent(agent)
                .task(t)
                .onTaskStart(event -> {
                    throw new RuntimeException("Listener boom");
                })
                .onTaskStart(event -> secondListenerCalled.set(true))
                .build()
                .run();

        // Execution completed despite listener exception
        assertThat(output.getRaw()).isEqualTo("result");
        // Second listener still called
        assertThat(secondListenerCalled.get()).isTrue();
    }

    // ========================
    // Full lifecycle in order
    // ========================

    @Test
    void fullLifecycle_eventsFireInCorrectOrder() {
        var llm = mock(ChatModel.class);
        List<String> events = new ArrayList<>();

        when(llm.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            events.add("llm");
            return textResponse("result");
        });

        var agent = mockAgent("Worker", llm);
        var t = task("Task", agent);

        Ensemble.builder()
                .agent(agent)
                .task(t)
                .onTaskStart(event -> events.add("start"))
                .onTaskComplete(event -> events.add("complete"))
                .build()
                .run();

        assertThat(events).containsExactly("start", "llm", "complete");
    }
}
