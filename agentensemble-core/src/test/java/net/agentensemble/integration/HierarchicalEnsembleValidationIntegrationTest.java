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
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for hierarchical ensemble validation and callback behaviour.
 *
 * Basic execution, manager LLM configuration, and output structure tests are in
 * HierarchicalEnsembleIntegrationTest.
 */
class HierarchicalEnsembleValidationIntegrationTest {

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private ChatResponse delegateCallResponse(String agentRole, String taskDescription) {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("d-1")
                .name("delegateTask")
                .arguments(
                        "{\"agentRole\": \"" + agentRole + "\", " + "\"taskDescription\": \"" + taskDescription + "\"}")
                .build();
        return ChatResponse.builder().aiMessage(AiMessage.from(req)).build();
    }

    // ========================
    // Validation still enforced
    // ========================

    @Test
    void testHierarchicalWorkflow_emptyTasks_throwsValidation() {
        ChatModel managerModel = mock(ChatModel.class);
        Agent agent =
                Agent.builder().role("Worker").goal("Work").llm(managerModel).build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .agent(agent)
                        .workflow(Workflow.HIERARCHICAL)
                        .managerLlm(managerModel)
                        .build()
                        .run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("task");
    }

    @Test
    void testHierarchicalWorkflow_emptyAgents_throwsValidation() {
        ChatModel managerModel = mock(ChatModel.class);
        ChatModel workerModel = mock(ChatModel.class);
        Agent agent =
                Agent.builder().role("Worker").goal("Work").llm(workerModel).build();
        Task task = Task.builder()
                .description("Task")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .task(task)
                        .workflow(Workflow.HIERARCHICAL)
                        .managerLlm(managerModel)
                        .build()
                        .run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("agent");
    }

    // ========================
    // Callback events
    // ========================

    @Test
    void testHierarchicalWorkflow_listeners_receiveManagerTaskEvents() {
        ChatModel managerModel = mock(ChatModel.class);
        ChatModel workerModel = mock(ChatModel.class);

        when(workerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Worker result"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI trends"))
                .thenReturn(textResponse("Final synthesis"));

        Agent researcher = Agent.builder()
                .role("Researcher")
                .goal("Research topics")
                .llm(workerModel)
                .build();
        Task task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("AI trend summary")
                .agent(researcher)
                .build();

        List<TaskStartEvent> startEvents = new ArrayList<>();
        List<TaskCompleteEvent> completeEvents = new ArrayList<>();

        Ensemble.builder()
                .agent(researcher)
                .task(task)
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(managerModel)
                .onTaskStart(startEvents::add)
                .onTaskComplete(completeEvents::add)
                .build()
                .run();

        assertThat(startEvents).hasSize(1);
        assertThat(startEvents.get(0).agentRole()).isEqualTo("Manager");
        assertThat(completeEvents).hasSize(1);
        assertThat(completeEvents.get(0).agentRole()).isEqualTo("Manager");
        assertThat(completeEvents.get(0).taskOutput().getRaw()).isEqualTo("Final synthesis");
    }
}
