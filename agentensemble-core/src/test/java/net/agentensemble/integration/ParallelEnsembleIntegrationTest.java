package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Map;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests for parallel ensemble execution.
 *
 * Covers basic execution, dependency graph ordering, validation, template variable
 * resolution, and output structure. Error strategy tests (FAIL_FAST, CONTINUE_ON_ERROR)
 * are in ParallelEnsembleErrorIntegrationTest.
 */
class ParallelEnsembleIntegrationTest {

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
    void testSingleTask_completesSuccessfully() {
        var agent = agentWithResponse("Researcher", "Research done");
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A report")
                .agent(agent)
                .build();

        var output = Ensemble.builder()
                .task(task)
                .workflow(Workflow.PARALLEL)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Research done");
        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getTotalDuration()).isPositive();
        assertThat(output.getTotalToolCalls()).isZero();
    }

    @Test
    void testIndependentTasks_allExecuteSuccessfully() {
        var researcher = agentWithResponse("Researcher", "Research result");
        var analyst = agentWithResponse("Analyst", "Analysis result");
        var task1 = Task.builder()
                .description("Research task")
                .expectedOutput("Research")
                .agent(researcher)
                .build();
        var task2 = Task.builder()
                .description("Analysis task")
                .expectedOutput("Analysis")
                .agent(analyst)
                .build();

        var output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .workflow(Workflow.PARALLEL)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs())
                .extracting(TaskOutput::getRaw)
                .containsExactlyInAnyOrder("Research result", "Analysis result");
    }

    @Test
    void testDiamondDependency_correctTopologicalOrder() {
        var a = agentWithResponse("A", "A output");
        var b = agentWithResponse("B", "B output");
        var c = agentWithResponse("C", "C output");
        var d = agentWithResponse("D", "D output");
        var ta = Task.builder()
                .description("Task A")
                .expectedOutput("Out A")
                .agent(a)
                .build();
        var tb = Task.builder()
                .description("Task B")
                .expectedOutput("Out B")
                .agent(b)
                .context(List.of(ta))
                .build();
        var tc = Task.builder()
                .description("Task C")
                .expectedOutput("Out C")
                .agent(c)
                .context(List.of(ta))
                .build();
        var td = Task.builder()
                .description("Task D")
                .expectedOutput("Out D")
                .agent(d)
                .context(List.of(tb, tc))
                .build();

        var output = Ensemble.builder()
                .task(ta)
                .task(tb)
                .task(tc)
                .task(td)
                .workflow(Workflow.PARALLEL)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).hasSize(4);

        List<String> roles =
                output.getTaskOutputs().stream().map(TaskOutput::getAgentRole).toList();

        assertThat(roles.indexOf("A")).isLessThan(roles.indexOf("B"));
        assertThat(roles.indexOf("A")).isLessThan(roles.indexOf("C"));
        assertThat(roles.indexOf("B")).isLessThan(roles.indexOf("D"));
        assertThat(roles.indexOf("C")).isLessThan(roles.indexOf("D"));
    }

    @Test
    void testLinearChain_serialExecutionPreserved() {
        var a = agentWithResponse("A", "A output");
        var b = agentWithResponse("B", "B output");
        var c = agentWithResponse("C", "C output");
        var ta = Task.builder()
                .description("Task A")
                .expectedOutput("Out A")
                .agent(a)
                .build();
        var tb = Task.builder()
                .description("Task B")
                .expectedOutput("Out B")
                .agent(b)
                .context(List.of(ta))
                .build();
        var tc = Task.builder()
                .description("Task C")
                .expectedOutput("Out C")
                .agent(c)
                .context(List.of(tb))
                .build();

        var output = Ensemble.builder()
                .task(ta)
                .task(tb)
                .task(tc)
                .workflow(Workflow.PARALLEL)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).hasSize(3);
        assertThat(output.getRaw()).isEqualTo("C output");
    }

    @Test
    void testTaskListOrder_irrelevantForParallel_depsResolveCorrectly() {
        var a = agentWithResponse("A", "A output");
        var b = agentWithResponse("B", "B output");
        var ta = Task.builder()
                .description("Task A")
                .expectedOutput("Out A")
                .agent(a)
                .build();
        var tb = Task.builder()
                .description("Task B")
                .expectedOutput("Out B")
                .agent(b)
                .context(List.of(ta))
                .build();

        var output = Ensemble.builder()
                .task(tb)
                .task(ta) // tb listed before ta (would fail SEQUENTIAL)
                .workflow(Workflow.PARALLEL)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).hasSize(2);
        List<String> roles =
                output.getTaskOutputs().stream().map(TaskOutput::getAgentRole).toList();
        assertThat(roles.indexOf("A")).isLessThan(roles.indexOf("B"));
    }

    // ========================
    // Validation
    // ========================

    @Test
    void testParallel_forwardContextReference_doesNotThrowValidation() {
        var a = agentWithResponse("A", "A output");
        var b = agentWithResponse("B", "B output");
        var ta = Task.builder()
                .description("Task A")
                .expectedOutput("Out A")
                .agent(a)
                .build();
        var tb = Task.builder()
                .description("Task B")
                .expectedOutput("Out B")
                .agent(b)
                .context(List.of(ta))
                .build();

        // tb listed before ta -- fine for PARALLEL (no ordering constraint)
        var output = Ensemble.builder()
                .task(tb)
                .task(ta)
                .workflow(Workflow.PARALLEL)
                .build()
                .run();
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    @Test
    void testNoTasks_throwsValidationException() {
        var agent = agentWithResponse("A", "out");
        assertThatThrownBy(() ->
                        Ensemble.builder().workflow(Workflow.PARALLEL).build().run())
                .isInstanceOf(ValidationException.class);
    }

    // ========================
    // Template variables
    // ========================

    @Test
    void testTemplateVariables_resolvedBeforeExecution() {
        var researcher = agentWithResponse("Researcher", "Research about AI Agents");
        var writer = agentWithResponse("Writer", "Article about AI Agents");
        var task1 = Task.builder()
                .description("Research {topic}")
                .expectedOutput("A report on {topic}")
                .agent(researcher)
                .build();
        var task2 = Task.builder()
                .description("Write about {topic}")
                .expectedOutput("An article on {topic}")
                .agent(writer)
                .build();

        var output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .workflow(Workflow.PARALLEL)
                .build()
                .run(Map.of("topic", "AI Agents"));

        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs())
                .extracting(TaskOutput::getTaskDescription)
                .containsExactlyInAnyOrder("Research AI Agents", "Write about AI Agents");
    }

    // ========================
    // Output structure
    // ========================

    @Test
    void testEnsembleOutput_rawIsLastTopologicalTask() {
        var a = agentWithResponse("A", "A result");
        var b = agentWithResponse("B", "B result");
        var ta = Task.builder()
                .description("Task A")
                .expectedOutput("Out A")
                .agent(a)
                .build();
        var tb = Task.builder()
                .description("Task B")
                .expectedOutput("Out B")
                .agent(b)
                .context(List.of(ta))
                .build();

        var output = Ensemble.builder()
                .task(ta)
                .task(tb)
                .workflow(Workflow.PARALLEL)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("B result");
    }

    @Test
    void testEnsembleOutput_totalToolCallsAggregated() {
        var a = agentWithResponse("A", "result");
        var b = agentWithResponse("B", "result");
        var ta = Task.builder()
                .description("Task A")
                .expectedOutput("Out")
                .agent(a)
                .build();
        var tb = Task.builder()
                .description("Task B")
                .expectedOutput("Out")
                .agent(b)
                .build();

        var output = Ensemble.builder()
                .task(ta)
                .task(tb)
                .workflow(Workflow.PARALLEL)
                .build()
                .run();

        assertThat(output.getTotalToolCalls()).isZero();
    }

    @Test
    void testVerboseMode_doesNotAffectOutput() {
        var agent = agentWithResponse("Agent", "Output");
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Out")
                .agent(agent)
                .build();

        var output = Ensemble.builder()
                .task(task)
                .workflow(Workflow.PARALLEL)
                .verbose(true)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Output");
    }
}
