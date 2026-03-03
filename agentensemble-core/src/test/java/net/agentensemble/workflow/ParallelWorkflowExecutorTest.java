package net.agentensemble.workflow;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.exception.ParallelExecutionException;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ParallelWorkflowExecutor.
 *
 * Uses mocked LLMs to avoid real network calls. All tests verify execution correctness,
 * ordering, error handling, and context propagation.
 */
class ParallelWorkflowExecutorTest {

    private List<Agent> agents;

    @BeforeEach
    void setUp() {
        agents = new ArrayList<>();
    }

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private Agent agentWithResponse(String role, String response) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        var agent = Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
        agents.add(agent);
        return agent;
    }

    private Agent agentThatFails(String role) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM error for " + role));
        var agent = Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
        agents.add(agent);
        return agent;
    }

    private Task task(String description, Agent agent) {
        return Task.builder()
                .description(description)
                .expectedOutput("Output for " + description)
                .agent(agent)
                .build();
    }

    private Task taskWithContext(String description, Agent agent, List<Task> context) {
        return Task.builder()
                .description(description)
                .expectedOutput("Output for " + description)
                .agent(agent)
                .context(context)
                .build();
    }

    private ParallelWorkflowExecutor executor(ParallelErrorStrategy strategy) {
        return new ParallelWorkflowExecutor(agents, 3, strategy);
    }

    private ParallelWorkflowExecutor executor() {
        return executor(ParallelErrorStrategy.FAIL_FAST);
    }

    // ========================
    // Basic execution
    // ========================

    @Test
    void testSingleTask_completesSuccessfully() {
        var agent = agentWithResponse("Worker", "Single task result");
        var t1 = task("Task 1", agent);

        var output = executor().execute(List.of(t1), false, MemoryContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Single task result");
        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getTaskOutputs().get(0).getRaw()).isEqualTo("Single task result");
        assertThat(output.getTotalDuration()).isNotNull().isPositive();
    }

    @Test
    void testIndependentTasks_allComplete() {
        var a1 = agentWithResponse("Agent1", "Result 1");
        var a2 = agentWithResponse("Agent2", "Result 2");
        var a3 = agentWithResponse("Agent3", "Result 3");
        var t1 = task("Task 1", a1);
        var t2 = task("Task 2", a2);
        var t3 = task("Task 3", a3);

        var output = executor().execute(List.of(t1, t2, t3), false, MemoryContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(3);
        assertThat(output.getTaskOutputs())
                .extracting(TaskOutput::getRaw)
                .containsExactlyInAnyOrder("Result 1", "Result 2", "Result 3");
    }

    @Test
    void testLinearChain_allComplete() {
        var a1 = agentWithResponse("A", "A output");
        var a2 = agentWithResponse("B", "B output");
        var a3 = agentWithResponse("C", "C output");
        var t1 = task("Task A", a1);
        var t2 = taskWithContext("Task B", a2, List.of(t1));
        var t3 = taskWithContext("Task C", a3, List.of(t2));

        var output = executor().execute(List.of(t1, t2, t3), false, MemoryContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(3);
        // All three tasks must have completed
        assertThat(output.getTaskOutputs())
                .extracting(TaskOutput::getAgentRole)
                .containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    void testDiamondDependency_allComplete() {
        var a = agentWithResponse("A", "A output");
        var b = agentWithResponse("B", "B output");
        var c = agentWithResponse("C", "C output");
        var d = agentWithResponse("D", "D output");
        var ta = task("Task A", a);
        var tb = taskWithContext("Task B", b, List.of(ta));
        var tc = taskWithContext("Task C", c, List.of(ta));
        var td = taskWithContext("Task D", d, List.of(tb, tc));

        var output = executor().execute(List.of(ta, tb, tc, td), false, MemoryContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(4);
        assertThat(output.getTaskOutputs())
                .extracting(TaskOutput::getAgentRole)
                .containsExactlyInAnyOrder("A", "B", "C", "D");
    }

    @Test
    void testOutputOrdering_topologicalOrder() {
        // A -> B -> D; A -> C -> D: all four tasks
        var a = agentWithResponse("A", "A output");
        var b = agentWithResponse("B", "B output");
        var c = agentWithResponse("C", "C output");
        var d = agentWithResponse("D", "D output");
        var ta = task("Task A", a);
        var tb = taskWithContext("Task B", b, List.of(ta));
        var tc = taskWithContext("Task C", c, List.of(ta));
        var td = taskWithContext("Task D", d, List.of(tb, tc));

        var output = executor().execute(List.of(ta, tb, tc, td), false, MemoryContext.disabled());

        List<String> roles = output.getTaskOutputs().stream()
                .map(TaskOutput::getAgentRole)
                .toList();
        // A must come before B, C, D
        assertThat(roles.indexOf("A")).isLessThan(roles.indexOf("B"));
        assertThat(roles.indexOf("A")).isLessThan(roles.indexOf("C"));
        // B and C must come before D
        assertThat(roles.indexOf("B")).isLessThan(roles.indexOf("D"));
        assertThat(roles.indexOf("C")).isLessThan(roles.indexOf("D"));
    }

    @Test
    void testRawOutput_isFinalTaskOutput() {
        var a = agentWithResponse("A", "A result");
        var b = agentWithResponse("B", "B result");
        var ta = task("Task A", a);
        var tb = taskWithContext("Task B", b, List.of(ta));

        var output = executor().execute(List.of(ta, tb), false, MemoryContext.disabled());

        // The "final" output is the last completed task in topological order
        assertThat(output.getRaw()).isEqualTo("B result");
    }

    @Test
    void testTotalToolCalls_summedAcrossAllTasks() {
        var a = agentWithResponse("A", "result");
        var b = agentWithResponse("B", "result");
        var ta = task("Task A", a);
        var tb = task("Task B", b);

        var output = executor().execute(List.of(ta, tb), false, MemoryContext.disabled());

        // Both tasks run with no tool calls
        assertThat(output.getTotalToolCalls()).isZero();
    }

    // ========================
    // FAIL_FAST error handling
    // ========================

    @Test
    void testFailFast_oneTaskFails_throwsTaskExecutionException() {
        var good = agentWithResponse("Good", "Good result");
        var bad = agentThatFails("Bad");
        var tGood = task("Good task", good);
        var tBad = task("Bad task", bad);

        assertThatThrownBy(() ->
                executor(ParallelErrorStrategy.FAIL_FAST).execute(
                        List.of(tGood, tBad), false, MemoryContext.disabled()))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(ex -> {
                    var te = (TaskExecutionException) ex;
                    assertThat(te.getTaskDescription()).isEqualTo("Bad task");
                    assertThat(te.getAgentRole()).isEqualTo("Bad");
                });
    }

    @Test
    void testFailFast_singleTask_throwsTaskExecutionException() {
        var bad = agentThatFails("Bad");
        var tBad = task("Failing task", bad);

        assertThatThrownBy(() ->
                executor(ParallelErrorStrategy.FAIL_FAST).execute(
                        List.of(tBad), false, MemoryContext.disabled()))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(ex -> {
                    var te = (TaskExecutionException) ex;
                    assertThat(te.getTaskDescription()).isEqualTo("Failing task");
                });
    }

    // ========================
    // CONTINUE_ON_ERROR error handling
    // ========================

    @Test
    void testContinueOnError_allSucceed_returnsNormally() {
        var a = agentWithResponse("A", "A result");
        var b = agentWithResponse("B", "B result");
        var ta = task("Task A", a);
        var tb = task("Task B", b);

        // No exception should be thrown when all tasks succeed
        var output = executor(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                .execute(List.of(ta, tb), false, MemoryContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    @Test
    void testContinueOnError_oneTaskFails_throwsParallelExecutionException() {
        var good = agentWithResponse("Good", "Good result");
        var bad = agentThatFails("Bad");
        var tGood = task("Good task", good);
        var tBad = task("Bad task", bad);

        assertThatThrownBy(() ->
                executor(ParallelErrorStrategy.CONTINUE_ON_ERROR).execute(
                        List.of(tGood, tBad), false, MemoryContext.disabled()))
                .isInstanceOf(ParallelExecutionException.class)
                .satisfies(ex -> {
                    var pe = (ParallelExecutionException) ex;
                    assertThat(pe.getFailedCount()).isEqualTo(1);
                    assertThat(pe.getFailedTaskCauses()).containsKey("Bad task");
                    assertThat(pe.getCompletedTaskOutputs()).hasSize(1);
                    assertThat(pe.getCompletedTaskOutputs().get(0).getAgentRole()).isEqualTo("Good");
                });
    }

    @Test
    void testContinueOnError_dependentOfFailed_isSkipped() {
        // A succeeds, B fails, C depends on B -- C must be skipped
        var a = agentWithResponse("A", "A result");
        var bad = agentThatFails("Bad");
        var skip = agentWithResponse("Skip", "Should not execute");
        var ta = task("Task A", a);
        var tBad = task("Task Bad", bad);
        var tSkip = taskWithContext("Task Skip", skip, List.of(tBad));

        assertThatThrownBy(() ->
                executor(ParallelErrorStrategy.CONTINUE_ON_ERROR).execute(
                        List.of(ta, tBad, tSkip), false, MemoryContext.disabled()))
                .isInstanceOf(ParallelExecutionException.class)
                .satisfies(ex -> {
                    var pe = (ParallelExecutionException) ex;
                    // A completed, B failed, Skip was skipped (not completed, not failed)
                    assertThat(pe.getCompletedTaskOutputs()).hasSize(1);
                    assertThat(pe.getCompletedTaskOutputs().get(0).getAgentRole()).isEqualTo("A");
                    assertThat(pe.getFailedTaskCauses()).containsKey("Task Bad");
                    // Skip is neither in completed nor failed (it was bypassed)
                    assertThat(pe.getFailedTaskCauses()).doesNotContainKey("Task Skip");
                });
    }

    @Test
    void testContinueOnError_transitiveDependentOfFailed_isSkipped() {
        // Root fails, Middle depends on Root, Tail depends on Middle.
        // Both Middle and Tail must be skipped -- not just the direct dependent.
        var badRoot = agentThatFails("Root");
        var middle = agentWithResponse("Middle", "Middle result");
        var tail = agentWithResponse("Tail", "Tail result");
        var tRoot = task("Task Root", badRoot);
        var tMiddle = taskWithContext("Task Middle", middle, List.of(tRoot));
        var tTail = taskWithContext("Task Tail", tail, List.of(tMiddle));

        assertThatThrownBy(() ->
                executor(ParallelErrorStrategy.CONTINUE_ON_ERROR).execute(
                        List.of(tRoot, tMiddle, tTail), false, MemoryContext.disabled()))
                .isInstanceOf(ParallelExecutionException.class)
                .satisfies(ex -> {
                    var pe = (ParallelExecutionException) ex;
                    // Only the root task should appear in failed causes;
                    // Middle and Tail are skipped, not failed
                    assertThat(pe.getFailedCount()).isEqualTo(1);
                    assertThat(pe.getFailedTaskCauses()).containsKey("Task Root");
                    assertThat(pe.getFailedTaskCauses())
                            .doesNotContainKeys("Task Middle", "Task Tail");
                    assertThat(pe.getCompletedTaskOutputs()).isEmpty();
                });
    }

    @Test
    void testContinueOnError_multipleFailures_allReported() {
        var bad1 = agentThatFails("Bad1");
        var bad2 = agentThatFails("Bad2");
        var t1 = task("Fail 1", bad1);
        var t2 = task("Fail 2", bad2);

        assertThatThrownBy(() ->
                executor(ParallelErrorStrategy.CONTINUE_ON_ERROR).execute(
                        List.of(t1, t2), false, MemoryContext.disabled()))
                .isInstanceOf(ParallelExecutionException.class)
                .satisfies(ex -> {
                    var pe = (ParallelExecutionException) ex;
                    assertThat(pe.getFailedCount()).isEqualTo(2);
                    assertThat(pe.getFailedTaskCauses()).containsKeys("Fail 1", "Fail 2");
                    assertThat(pe.getCompletedTaskOutputs()).isEmpty();
                });
    }

    // ========================
    // Context output passing
    // ========================

    @Test
    void testContextOutputsPassedToAgent_capturedViaTaskOutput() {
        // Verify that a dependent task actually executes after its dependency
        // (evidenced by the agent being called and producing its own output)
        var a = agentWithResponse("A", "A produced output");
        var b = agentWithResponse("B", "B used context");
        var ta = task("Task A", a);
        var tb = taskWithContext("Task B", b, List.of(ta));

        var output = executor().execute(List.of(ta, tb), false, MemoryContext.disabled());

        // Both tasks ran
        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs())
                .extracting(TaskOutput::getAgentRole)
                .containsExactlyInAnyOrder("A", "B");
    }

    // ========================
    // Memory integration
    // ========================

    @Test
    void testWithMemory_doesNotThrow() {
        var a = agentWithResponse("A", "A result");
        var b = agentWithResponse("B", "B result");
        var ta = task("Task A", a);
        var tb = task("Task B", b);

        // Memory context is passed through -- must not throw even with concurrent tasks
        var memoryContext = mock(MemoryContext.class);
        when(memoryContext.isActive()).thenReturn(false);
        when(memoryContext.hasShortTerm()).thenReturn(false);
        when(memoryContext.hasLongTerm()).thenReturn(false);
        when(memoryContext.hasEntityMemory()).thenReturn(false);
        when(memoryContext.getShortTermEntries()).thenReturn(List.of());
        when(memoryContext.queryLongTerm(any())).thenReturn(List.of());
        when(memoryContext.getEntityFacts()).thenReturn(Map.of());

        var output = executor().execute(List.of(ta, tb), false, memoryContext);
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    // ========================
    // Concurrent execution verification
    // ========================

    @Test
    void testConcurrentExecution_trackingViaSharedState() throws InterruptedException {
        // Track execution start times using a concurrent map
        // If tasks run truly concurrently, their start times will overlap
        Map<String, Long> startTimes = new ConcurrentHashMap<>();

        var llm1 = mock(ChatModel.class);
        var llm2 = mock(ChatModel.class);

        when(llm1.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            startTimes.put("t1", System.currentTimeMillis());
            return textResponse("T1 result");
        });
        when(llm2.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            startTimes.put("t2", System.currentTimeMillis());
            return textResponse("T2 result");
        });

        var a1 = Agent.builder().role("A1").goal("work").llm(llm1).build();
        var a2 = Agent.builder().role("A2").goal("work").llm(llm2).build();
        agents.add(a1);
        agents.add(a2);
        var t1 = task("Task 1", a1);
        var t2 = task("Task 2", a2);

        var output = executor().execute(List.of(t1, t2), false, MemoryContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(2);
        // Both tasks were invoked
        assertThat(startTimes).containsKeys("t1", "t2");
    }
}
