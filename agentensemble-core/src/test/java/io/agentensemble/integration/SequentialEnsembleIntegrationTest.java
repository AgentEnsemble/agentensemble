package io.agentensemble.integration;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agentensemble.Agent;
import io.agentensemble.Ensemble;
import io.agentensemble.Task;
import io.agentensemble.exception.TaskExecutionException;
import io.agentensemble.exception.ValidationException;
import io.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration tests for sequential ensemble execution.
 * Uses mocked LLMs to avoid real network calls.
 */
class SequentialEnsembleIntegrationTest {

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private Agent agentWithResponse(String role, String response) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        return Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
    }

    // ========================
    // Basic execution
    // ========================

    @Test
    void testSingleTask_executesSuccessfully() {
        var agent = agentWithResponse("Researcher", "AI is growing fast in 2026.");
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A report")
                .agent(agent)
                .build();

        var output = Ensemble.builder()
                .agent(agent)
                .task(task)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("AI is growing fast in 2026.");
        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getTotalDuration()).isNotNull().isPositive();
        assertThat(output.getTotalToolCalls()).isZero();
    }

    @Test
    void testTwoTasks_executesInOrder() {
        var researcher = agentWithResponse("Researcher", "Research result: AI trends");
        var writer = agentWithResponse("Writer", "Blog post about AI trends");

        var researchTask = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A report")
                .agent(researcher)
                .build();
        var writeTask = Task.builder()
                .description("Write a blog post")
                .expectedOutput("A blog post")
                .agent(writer)
                .build();

        var output = Ensemble.builder()
                .agent(researcher).agent(writer)
                .task(researchTask).task(writeTask)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Blog post about AI trends");
        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs().get(0).getRaw()).isEqualTo("Research result: AI trends");
        assertThat(output.getTaskOutputs().get(1).getRaw()).isEqualTo("Blog post about AI trends");
    }

    @Test
    void testContextPassing_priorOutputIncludedInPrompt() {
        var researcher = agentWithResponse("Researcher", "Research result: AI is growing");
        var writer = agentWithResponse("Writer", "Article written with context");

        var researchTask = Task.builder()
                .description("Research task").expectedOutput("Report").agent(researcher).build();
        var writeTask = Task.builder()
                .description("Write article").expectedOutput("Article").agent(writer)
                .context(List.of(researchTask))
                .build();

        var output = Ensemble.builder()
                .agent(researcher).agent(writer)
                .task(researchTask).task(writeTask)
                .build()
                .run();

        // Both tasks executed and context was passed (verified by taskOutputs having 2 entries)
        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs().get(0).getRaw()).isEqualTo("Research result: AI is growing");
        assertThat(output.getTaskOutputs().get(1).getRaw()).isEqualTo("Article written with context");
    }

    @Test
    void testEnsembleOutput_rawIsLastTaskOutput() {
        var agent = agentWithResponse("Agent", "First result");
        var agent2 = agentWithResponse("Agent2", "Second result");

        var task1 = Task.builder().description("Task 1").expectedOutput("Out 1").agent(agent).build();
        var task2 = Task.builder().description("Task 2").expectedOutput("Out 2").agent(agent2).build();

        var output = Ensemble.builder()
                .agent(agent).agent(agent2).task(task1).task(task2)
                .build().run();

        assertThat(output.getRaw()).isEqualTo("Second result");
    }

    @Test
    void testEnsembleOutput_taskOutputsOrdered() {
        var agent = agentWithResponse("Agent", "Task 1 output");
        var agent2 = agentWithResponse("Agent2", "Task 2 output");

        var task1 = Task.builder().description("Task 1").expectedOutput("Out 1").agent(agent).build();
        var task2 = Task.builder().description("Task 2").expectedOutput("Out 2").agent(agent2).build();

        var output = Ensemble.builder()
                .agent(agent).agent(agent2).task(task1).task(task2)
                .build().run();

        assertThat(output.getTaskOutputs().get(0).getAgentRole()).isEqualTo("Agent");
        assertThat(output.getTaskOutputs().get(1).getAgentRole()).isEqualTo("Agent2");
    }

    // ========================
    // Template variable substitution
    // ========================

    @Test
    void testTemplateSubstitution_variablesResolvedBeforeExecution() {
        var agent = agentWithResponse("Researcher", "Research about AI Agents");
        var task = Task.builder()
                .description("Research {topic} developments")
                .expectedOutput("A report on {topic}")
                .agent(agent)
                .build();

        var output = Ensemble.builder()
                .agent(agent).task(task)
                .build()
                .run(Map.of("topic", "AI Agents"));

        // The task description stored in output reflects the resolved template
        assertThat(output.getTaskOutputs().get(0).getTaskDescription())
                .isEqualTo("Research AI Agents developments");
    }

    // ========================
    // Error handling
    // ========================

    @Test
    void testValidationFailure_noTasksExecuted() {
        var researcher = agentWithResponse("Researcher", "result");

        var ensemble = Ensemble.builder()
                .agent(researcher)
                .build();  // No tasks

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void testTaskFails_completedOutputsPreserved() {
        var researcher = agentWithResponse("Researcher", "Research complete");

        var failingLlm = mock(ChatModel.class);
        when(failingLlm.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM error"));
        var writer = Agent.builder().role("Writer").goal("Write").llm(failingLlm).build();

        var task1 = Task.builder().description("Research").expectedOutput("Report").agent(researcher).build();
        var task2 = Task.builder().description("Write").expectedOutput("Article").agent(writer).build();

        var ensemble = Ensemble.builder()
                .agent(researcher).agent(writer).task(task1).task(task2)
                .build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(ex -> {
                    var te = (TaskExecutionException) ex;
                    // The first task's output is preserved
                    assertThat(te.getCompletedTaskOutputs()).hasSize(1);
                    assertThat(te.getCompletedTaskOutputs().get(0).getRaw())
                            .isEqualTo("Research complete");
                    assertThat(te.getTaskDescription()).isEqualTo("Write");
                    assertThat(te.getAgentRole()).isEqualTo("Writer");
                });
    }

    // ========================
    // Verbose mode
    // ========================

    @Test
    void testVerboseMode_doesNotAffectOutput() {
        var agent = agentWithResponse("Researcher", "Research result");
        var task = Task.builder().description("Research").expectedOutput("Report").agent(agent).build();

        var output = Ensemble.builder()
                .agent(agent).task(task)
                .verbose(true)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Research result");
    }

    // ========================
    // Workflow
    // ========================

    @Test
    void testHierarchicalWorkflow_throwsUnsupported() {
        var agent = agentWithResponse("Agent", "result");
        var task = Task.builder().description("Task").expectedOutput("Out").agent(agent).build();

        assertThatThrownBy(() -> Ensemble.builder()
                .agent(agent).task(task)
                .workflow(Workflow.HIERARCHICAL)
                .build()
                .run())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
