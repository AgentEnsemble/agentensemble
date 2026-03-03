package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.concurrent.CopyOnWriteArrayList;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * Integration tests verifying that listeners receive expected events across ensemble runs.
 */
class ListenerEnsembleIntegrationTest {

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

    private Agent agentWithResponse(String role, String response) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        return Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
    }

    // ========================
    // TaskStartEvent
    // ========================

    @Test
    void testSequentialEnsemble_listenerReceivesTaskStartEventsForAllTasks() {
        var researcher = agentWithResponse("Researcher", "Research result");
        var writer = agentWithResponse("Writer", "Blog post");

        var task1 = Task.builder()
                .description("Research AI trends")
                .expectedOutput("Report")
                .agent(researcher)
                .build();
        var task2 = Task.builder()
                .description("Write blog post")
                .expectedOutput("Article")
                .agent(writer)
                .build();

        List<TaskStartEvent> startEvents = new ArrayList<>();

        var ensemble = Ensemble.builder()
                .agent(researcher)
                .agent(writer)
                .task(task1)
                .task(task2)
                .listener(new EnsembleListener() {
                    @Override
                    public void onTaskStart(TaskStartEvent event) {
                        startEvents.add(event);
                    }
                })
                .build();

        ensemble.run();

        assertThat(startEvents).hasSize(2);
        assertThat(startEvents.get(0).taskDescription()).isEqualTo("Research AI trends");
        assertThat(startEvents.get(0).agentRole()).isEqualTo("Researcher");
        assertThat(startEvents.get(0).taskIndex()).isEqualTo(1);
        assertThat(startEvents.get(0).totalTasks()).isEqualTo(2);

        assertThat(startEvents.get(1).taskDescription()).isEqualTo("Write blog post");
        assertThat(startEvents.get(1).agentRole()).isEqualTo("Writer");
        assertThat(startEvents.get(1).taskIndex()).isEqualTo(2);
        assertThat(startEvents.get(1).totalTasks()).isEqualTo(2);
    }

    // ========================
    // TaskCompleteEvent
    // ========================

    @Test
    void testSequentialEnsemble_listenerReceivesTaskCompleteEventsForAllTasks() {
        var researcher = agentWithResponse("Researcher", "Research complete");
        var writer = agentWithResponse("Writer", "Article complete");

        var task1 = Task.builder()
                .description("Research")
                .expectedOutput("Report")
                .agent(researcher)
                .build();
        var task2 = Task.builder()
                .description("Write")
                .expectedOutput("Article")
                .agent(writer)
                .build();

        List<TaskCompleteEvent> completeEvents = new ArrayList<>();

        var ensemble = Ensemble.builder()
                .agent(researcher)
                .agent(writer)
                .task(task1)
                .task(task2)
                .listener(new EnsembleListener() {
                    @Override
                    public void onTaskComplete(TaskCompleteEvent event) {
                        completeEvents.add(event);
                    }
                })
                .build();

        ensemble.run();

        assertThat(completeEvents).hasSize(2);
        assertThat(completeEvents.get(0).taskDescription()).isEqualTo("Research");
        assertThat(completeEvents.get(0).agentRole()).isEqualTo("Researcher");
        assertThat(completeEvents.get(0).taskOutput().getRaw()).isEqualTo("Research complete");
        assertThat(completeEvents.get(0).duration()).isNotNull();
        assertThat(completeEvents.get(0).taskIndex()).isEqualTo(1);
        assertThat(completeEvents.get(0).totalTasks()).isEqualTo(2);

        assertThat(completeEvents.get(1).taskDescription()).isEqualTo("Write");
        assertThat(completeEvents.get(1).agentRole()).isEqualTo("Writer");
        assertThat(completeEvents.get(1).taskOutput().getRaw()).isEqualTo("Article complete");
        assertThat(completeEvents.get(1).taskIndex()).isEqualTo(2);
    }

    // ========================
    // ToolCallEvent
    // ========================

    @Test
    void testSequentialEnsemble_listenerReceivesToolCallEvent() {
        var mockLlm = mock(ChatModel.class);
        var mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("search");
        when(mockTool.description()).thenReturn("Search the web");
        when(mockTool.execute("AI trends")).thenReturn(ToolResult.success("Top AI trends 2026"));

        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse("search", "{\"input\": \"AI trends\"}"))
                .thenReturn(textResponse("Based on search: AI trends 2026"));

        var agent = Agent.builder()
                .role("Researcher")
                .goal("Find info")
                .tools(List.of(mockTool))
                .llm(mockLlm)
                .build();
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A report")
                .agent(agent)
                .build();

        List<ToolCallEvent> toolCallEvents = new ArrayList<>();

        var ensemble = Ensemble.builder()
                .agent(agent)
                .task(task)
                .listener(new EnsembleListener() {
                    @Override
                    public void onToolCall(ToolCallEvent event) {
                        toolCallEvents.add(event);
                    }
                })
                .build();

        ensemble.run();

        assertThat(toolCallEvents).hasSize(1);
        assertThat(toolCallEvents.get(0).toolName()).isEqualTo("search");
        assertThat(toolCallEvents.get(0).agentRole()).isEqualTo("Researcher");
        assertThat(toolCallEvents.get(0).toolArguments()).contains("AI trends");
        assertThat(toolCallEvents.get(0).toolResult()).isEqualTo("Top AI trends 2026");
        assertThat(toolCallEvents.get(0).duration()).isNotNull();
    }

    // ========================
    // onTaskStart / onTaskComplete / onToolCall convenience methods
    // ========================

    @Test
    void testOnTaskStart_convenienceMethod_receivesEvents() {
        var agent = agentWithResponse("Researcher", "Result");
        var task = Task.builder()
                .description("Research")
                .expectedOutput("Report")
                .agent(agent)
                .build();

        List<String> received = new ArrayList<>();

        Ensemble ensemble = Ensemble.builder()
                .agent(agent)
                .task(task)
                .build()
                .onTaskStart(event -> received.add("start:" + event.taskDescription()));

        ensemble.run();

        assertThat(received).containsExactly("start:Research");
    }

    @Test
    void testOnTaskComplete_convenienceMethod_receivesEvents() {
        var agent = agentWithResponse("Researcher", "Research complete");
        var task = Task.builder()
                .description("Research")
                .expectedOutput("Report")
                .agent(agent)
                .build();

        List<String> received = new ArrayList<>();

        Ensemble ensemble = Ensemble.builder()
                .agent(agent)
                .task(task)
                .build()
                .onTaskComplete(
                        event -> received.add("done:" + event.taskOutput().getRaw()));

        ensemble.run();

        assertThat(received).containsExactly("done:Research complete");
    }

    @Test
    void testOnToolCall_convenienceMethod_receivesEvents() {
        var mockLlm = mock(ChatModel.class);
        var mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("calculator");
        when(mockTool.description()).thenReturn("Calculate");
        when(mockTool.execute("2+2")).thenReturn(ToolResult.success("4"));

        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse("calculator", "{\"input\": \"2+2\"}"))
                .thenReturn(textResponse("The answer is 4"));

        var agent = Agent.builder()
                .role("Calculator")
                .goal("Calculate")
                .tools(List.of(mockTool))
                .llm(mockLlm)
                .build();
        var task = Task.builder()
                .description("Calculate 2+2")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        List<String> received = new ArrayList<>();

        Ensemble ensemble = Ensemble.builder()
                .agent(agent)
                .task(task)
                .build()
                .onToolCall(event -> received.add("tool:" + event.toolName()));

        ensemble.run();

        assertThat(received).containsExactly("tool:calculator");
    }

    // ========================
    // Multiple listeners
    // ========================

    @Test
    void testMultipleListeners_allReceiveEvents() {
        var agent = agentWithResponse("Researcher", "Result");
        var task = Task.builder()
                .description("Research")
                .expectedOutput("Report")
                .agent(agent)
                .build();

        List<String> log1 = new ArrayList<>();
        List<String> log2 = new ArrayList<>();

        var ensemble = Ensemble.builder()
                .agent(agent)
                .task(task)
                .listener(new EnsembleListener() {
                    @Override
                    public void onTaskStart(TaskStartEvent e) {
                        log1.add("l1-start");
                    }

                    @Override
                    public void onTaskComplete(TaskCompleteEvent e) {
                        log1.add("l1-complete");
                    }
                })
                .listener(new EnsembleListener() {
                    @Override
                    public void onTaskStart(TaskStartEvent e) {
                        log2.add("l2-start");
                    }

                    @Override
                    public void onTaskComplete(TaskCompleteEvent e) {
                        log2.add("l2-complete");
                    }
                })
                .build();

        ensemble.run();

        assertThat(log1).containsExactly("l1-start", "l1-complete");
        assertThat(log2).containsExactly("l2-start", "l2-complete");
    }

    // ========================
    // Exception safety
    // ========================

    @Test
    void testListenerException_doesNotAbortExecution() {
        var agent = agentWithResponse("Researcher", "Research result");
        var task = Task.builder()
                .description("Research")
                .expectedOutput("Report")
                .agent(agent)
                .build();

        List<String> log = new ArrayList<>();

        var ensemble = Ensemble.builder()
                .agent(agent)
                .task(task)
                .listener(new EnsembleListener() {
                    @Override
                    public void onTaskStart(TaskStartEvent e) {
                        throw new RuntimeException("Listener explodes!");
                    }
                })
                .listener(new EnsembleListener() {
                    @Override
                    public void onTaskComplete(TaskCompleteEvent e) {
                        log.add("safe-complete");
                    }
                })
                .build();

        // Execution must complete without throwing
        var output = ensemble.run();

        assertThat(output.getRaw()).isEqualTo("Research result");
        // The second listener's onTaskComplete must have been called
        assertThat(log).containsExactly("safe-complete");
    }

    // ========================
    // Parallel workflow events
    // ========================

    @Test
    void testParallelEnsemble_listenerReceivesEventsForAllTasks() {
        var agent1 = agentWithResponse("A", "Result A");
        var agent2 = agentWithResponse("B", "Result B");

        var task1 = Task.builder()
                .description("Task A")
                .expectedOutput("Output A")
                .agent(agent1)
                .build();
        var task2 = Task.builder()
                .description("Task B")
                .expectedOutput("Output B")
                .agent(agent2)
                .build();

        List<String> startRoles = new CopyOnWriteArrayList<>();
        List<String> completeRoles = new CopyOnWriteArrayList<>();

        var ensemble = Ensemble.builder()
                .agent(agent1)
                .agent(agent2)
                .task(task1)
                .task(task2)
                .workflow(Workflow.PARALLEL)
                .listener(new EnsembleListener() {
                    @Override
                    public void onTaskStart(TaskStartEvent e) {
                        startRoles.add(e.agentRole());
                    }

                    @Override
                    public void onTaskComplete(TaskCompleteEvent e) {
                        completeRoles.add(e.agentRole());
                    }
                })
                .build();

        ensemble.run();

        assertThat(startRoles).containsExactlyInAnyOrder("A", "B");
        assertThat(completeRoles).containsExactlyInAnyOrder("A", "B");
    }
}
