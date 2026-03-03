package net.agentensemble.integration;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.exception.ParallelExecutionException;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.ParallelErrorStrategy;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration tests for parallel ensemble execution.
 *
 * Uses mocked LLMs to avoid real network calls. Tests verify correct execution of
 * the dependency graph, error handling strategies, template variable resolution,
 * and output assembly.
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

    private Agent agentThatFails(String role) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM failure for " + role));
        return Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
    }

    // ========================
    // Basic execution
    // ========================

    @Test
    void testSingleTask_completesSuccessfully() {
        var agent = agentWithResponse("Researcher", "Research done");
        var task = Task.builder()
                .description("Research AI trends").expectedOutput("A report").agent(agent).build();

        var output = Ensemble.builder()
                .agent(agent).task(task)
                .workflow(Workflow.PARALLEL)
                .build().run();

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
                .description("Research task").expectedOutput("Research").agent(researcher).build();
        var task2 = Task.builder()
                .description("Analysis task").expectedOutput("Analysis").agent(analyst).build();

        var output = Ensemble.builder()
                .agent(researcher).agent(analyst)
                .task(task1).task(task2)
                .workflow(Workflow.PARALLEL)
                .build().run();

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
        var ta = Task.builder().description("Task A").expectedOutput("Out A").agent(a).build();
        var tb = Task.builder().description("Task B").expectedOutput("Out B").agent(b)
                .context(List.of(ta)).build();
        var tc = Task.builder().description("Task C").expectedOutput("Out C").agent(c)
                .context(List.of(ta)).build();
        var td = Task.builder().description("Task D").expectedOutput("Out D").agent(d)
                .context(List.of(tb, tc)).build();

        var output = Ensemble.builder()
                .agent(a).agent(b).agent(c).agent(d)
                .task(ta).task(tb).task(tc).task(td)
                .workflow(Workflow.PARALLEL)
                .build().run();

        assertThat(output.getTaskOutputs()).hasSize(4);

        List<String> roles = output.getTaskOutputs().stream()
                .map(TaskOutput::getAgentRole).toList();

        // A must come before B and C; B and C must come before D
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
        var ta = Task.builder().description("Task A").expectedOutput("Out A").agent(a).build();
        var tb = Task.builder().description("Task B").expectedOutput("Out B").agent(b)
                .context(List.of(ta)).build();
        var tc = Task.builder().description("Task C").expectedOutput("Out C").agent(c)
                .context(List.of(tb)).build();

        var output = Ensemble.builder()
                .agent(a).agent(b).agent(c)
                .task(ta).task(tb).task(tc)
                .workflow(Workflow.PARALLEL)
                .build().run();

        assertThat(output.getTaskOutputs()).hasSize(3);
        assertThat(output.getRaw()).isEqualTo("C output");
    }

    @Test
    void testTaskListOrder_irrelevantForParallel_depsResolveCorrectly() {
        // Supply tasks in "reverse" topological order -- PARALLEL should still work
        var a = agentWithResponse("A", "A output");
        var b = agentWithResponse("B", "B output");
        var ta = Task.builder().description("Task A").expectedOutput("Out A").agent(a).build();
        // tb depends on ta, but is listed FIRST
        var tb = Task.builder().description("Task B").expectedOutput("Out B").agent(b)
                .context(List.of(ta)).build();

        var output = Ensemble.builder()
                .agent(a).agent(b)
                .task(tb).task(ta)  // tb listed before ta (would fail SEQUENTIAL)
                .workflow(Workflow.PARALLEL)
                .build().run();

        assertThat(output.getTaskOutputs()).hasSize(2);
        List<String> roles = output.getTaskOutputs().stream()
                .map(TaskOutput::getAgentRole).toList();
        assertThat(roles.indexOf("A")).isLessThan(roles.indexOf("B"));
    }

    // ========================
    // Validation
    // ========================

    @Test
    void testParallel_forwardContextReference_doesNotThrowValidation() {
        // Parallel workflow skips the context ordering check: forward references are allowed
        var a = agentWithResponse("A", "A output");
        var b = agentWithResponse("B", "B output");
        var ta = Task.builder().description("Task A").expectedOutput("Out A").agent(a).build();
        var tb = Task.builder().description("Task B").expectedOutput("Out B").agent(b)
                .context(List.of(ta)).build();

        // tb listed before ta -- would fail SEQUENTIAL validation but is fine for PARALLEL
        var ensemble = Ensemble.builder()
                .agent(a).agent(b)
                .task(tb).task(ta)
                .workflow(Workflow.PARALLEL)
                .build();

        // Should not throw ValidationException
        var output = ensemble.run();
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    @Test
    void testNoTasks_throwsValidationException() {
        var agent = agentWithResponse("A", "out");
        assertThatThrownBy(() ->
                Ensemble.builder().agent(agent).workflow(Workflow.PARALLEL).build().run())
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
                .agent(researcher).agent(writer)
                .task(task1).task(task2)
                .workflow(Workflow.PARALLEL)
                .build()
                .run(Map.of("topic", "AI Agents"));

        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs())
                .extracting(TaskOutput::getTaskDescription)
                .containsExactlyInAnyOrder("Research AI Agents", "Write about AI Agents");
    }

    // ========================
    // FAIL_FAST error handling
    // ========================

    @Test
    void testFailFast_oneTaskFails_throwsTaskExecutionException() {
        var good = agentWithResponse("Good", "Good result");
        var bad = agentThatFails("Bad");
        var tGood = Task.builder().description("Good task").expectedOutput("Good out").agent(good).build();
        var tBad = Task.builder().description("Bad task").expectedOutput("Bad out").agent(bad).build();

        assertThatThrownBy(() ->
                Ensemble.builder()
                        .agent(good).agent(bad)
                        .task(tGood).task(tBad)
                        .workflow(Workflow.PARALLEL)
                        .parallelErrorStrategy(ParallelErrorStrategy.FAIL_FAST)
                        .build().run())
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(ex -> {
                    var te = (TaskExecutionException) ex;
                    assertThat(te.getTaskDescription()).isEqualTo("Bad task");
                    assertThat(te.getAgentRole()).isEqualTo("Bad");
                });
    }

    @Test
    void testFailFast_isDefaultStrategy() {
        var bad = agentThatFails("Bad");
        var tBad = Task.builder().description("Failing task").expectedOutput("Out").agent(bad).build();

        // No explicit parallelErrorStrategy set -- default is FAIL_FAST
        assertThatThrownBy(() ->
                Ensemble.builder()
                        .agent(bad).task(tBad)
                        .workflow(Workflow.PARALLEL)
                        .build().run())
                .isInstanceOf(TaskExecutionException.class);
    }

    // ========================
    // CONTINUE_ON_ERROR error handling
    // ========================

    @Test
    void testContinueOnError_partialSuccess_throwsParallelExecutionException() {
        var good = agentWithResponse("Good", "Good result");
        var bad = agentThatFails("Bad");
        var tGood = Task.builder().description("Good task").expectedOutput("Good out").agent(good).build();
        var tBad = Task.builder().description("Bad task").expectedOutput("Bad out").agent(bad).build();

        assertThatThrownBy(() ->
                Ensemble.builder()
                        .agent(good).agent(bad)
                        .task(tGood).task(tBad)
                        .workflow(Workflow.PARALLEL)
                        .parallelErrorStrategy(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                        .build().run())
                .isInstanceOf(ParallelExecutionException.class)
                .satisfies(ex -> {
                    var pe = (ParallelExecutionException) ex;
                    assertThat(pe.getCompletedTaskOutputs()).hasSize(1);
                    assertThat(pe.getCompletedTaskOutputs().get(0).getRaw()).isEqualTo("Good result");
                    assertThat(pe.getFailedTaskCauses()).containsKey("Bad task");
                    assertThat(pe.getFailedCount()).isEqualTo(1);
                    assertThat(pe.getCompletedCount()).isEqualTo(1);
                });
    }

    @Test
    void testContinueOnError_dependentTaskSkipped() {
        var good = agentWithResponse("Good", "Good result");
        var bad = agentThatFails("Bad");
        var skip = agentWithResponse("Skip", "Should not run");
        var tGood = Task.builder().description("Good task").expectedOutput("Good out").agent(good).build();
        var tBad = Task.builder().description("Bad task").expectedOutput("Bad out").agent(bad).build();
        var tSkip = Task.builder().description("Skip task").expectedOutput("Skip out").agent(skip)
                .context(List.of(tBad)).build();

        assertThatThrownBy(() ->
                Ensemble.builder()
                        .agent(good).agent(bad).agent(skip)
                        .task(tGood).task(tBad).task(tSkip)
                        .workflow(Workflow.PARALLEL)
                        .parallelErrorStrategy(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                        .build().run())
                .isInstanceOf(ParallelExecutionException.class)
                .satisfies(ex -> {
                    var pe = (ParallelExecutionException) ex;
                    // Only Good completed; Bad failed; Skip was skipped
                    assertThat(pe.getCompletedTaskOutputs()).hasSize(1);
                    assertThat(pe.getFailedTaskCauses()).containsKey("Bad task");
                    assertThat(pe.getFailedTaskCauses()).doesNotContainKey("Skip task");
                });
    }

    @Test
    void testContinueOnError_allSucceed_returnsNormally() {
        var a = agentWithResponse("A", "A result");
        var b = agentWithResponse("B", "B result");
        var ta = Task.builder().description("Task A").expectedOutput("Out A").agent(a).build();
        var tb = Task.builder().description("Task B").expectedOutput("Out B").agent(b).build();

        var output = Ensemble.builder()
                .agent(a).agent(b)
                .task(ta).task(tb)
                .workflow(Workflow.PARALLEL)
                .parallelErrorStrategy(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                .build().run();

        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    // ========================
    // Output structure
    // ========================

    @Test
    void testEnsembleOutput_rawIsLastTopologicalTask() {
        var a = agentWithResponse("A", "A result");
        var b = agentWithResponse("B", "B result");
        var ta = Task.builder().description("Task A").expectedOutput("Out A").agent(a).build();
        var tb = Task.builder().description("Task B").expectedOutput("Out B").agent(b)
                .context(List.of(ta)).build();

        var output = Ensemble.builder()
                .agent(a).agent(b)
                .task(ta).task(tb)
                .workflow(Workflow.PARALLEL)
                .build().run();

        // B depends on A, so B is last in topological order
        assertThat(output.getRaw()).isEqualTo("B result");
    }

    @Test
    void testEnsembleOutput_totalToolCallsAggregated() {
        var a = agentWithResponse("A", "result");
        var b = agentWithResponse("B", "result");
        var ta = Task.builder().description("Task A").expectedOutput("Out").agent(a).build();
        var tb = Task.builder().description("Task B").expectedOutput("Out").agent(b).build();

        var output = Ensemble.builder()
                .agent(a).agent(b)
                .task(ta).task(tb)
                .workflow(Workflow.PARALLEL)
                .build().run();

        // No tool calls in this test (mocked LLMs don't invoke tools)
        assertThat(output.getTotalToolCalls()).isZero();
    }

    @Test
    void testVerboseMode_doesNotAffectOutput() {
        var agent = agentWithResponse("Agent", "Output");
        var task = Task.builder().description("Task").expectedOutput("Out").agent(agent).build();

        var output = Ensemble.builder()
                .agent(agent).task(task)
                .workflow(Workflow.PARALLEL)
                .verbose(true)
                .build().run();

        assertThat(output.getRaw()).isEqualTo("Output");
    }
}
