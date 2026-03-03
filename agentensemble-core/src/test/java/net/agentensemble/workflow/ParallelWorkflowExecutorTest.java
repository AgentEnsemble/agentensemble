package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.agentensemble.Agent;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ParallelWorkflowExecutor -- basic execution, output ordering,
 * context passing, memory integration, and concurrency verification.
 *
 * Error strategy tests are in ParallelWorkflowExecutorErrorTest.
 * Callback tests are in ParallelWorkflowExecutorCallbackTest.
 */
class ParallelWorkflowExecutorTest extends ParallelWorkflowExecutorTestBase {

    // ========================
    // Basic execution
    // ========================

    @Test
    void testSingleTask_completesSuccessfully() {
        var agent = agentWithResponse("Worker", "Single task result");
        var t1 = task("Task 1", agent);

        var output = executor().execute(List.of(t1), ExecutionContext.disabled());

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

        var output = executor().execute(List.of(t1, t2, t3), ExecutionContext.disabled());

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

        var output = executor().execute(List.of(t1, t2, t3), ExecutionContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(3);
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

        var output = executor().execute(List.of(ta, tb, tc, td), ExecutionContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(4);
        assertThat(output.getTaskOutputs())
                .extracting(TaskOutput::getAgentRole)
                .containsExactlyInAnyOrder("A", "B", "C", "D");
    }

    @Test
    void testOutputOrdering_topologicalOrder() {
        var a = agentWithResponse("A", "A output");
        var b = agentWithResponse("B", "B output");
        var c = agentWithResponse("C", "C output");
        var d = agentWithResponse("D", "D output");
        var ta = task("Task A", a);
        var tb = taskWithContext("Task B", b, List.of(ta));
        var tc = taskWithContext("Task C", c, List.of(ta));
        var td = taskWithContext("Task D", d, List.of(tb, tc));

        var output = executor().execute(List.of(ta, tb, tc, td), ExecutionContext.disabled());

        List<String> roles =
                output.getTaskOutputs().stream().map(TaskOutput::getAgentRole).toList();
        assertThat(roles.indexOf("A")).isLessThan(roles.indexOf("B"));
        assertThat(roles.indexOf("A")).isLessThan(roles.indexOf("C"));
        assertThat(roles.indexOf("B")).isLessThan(roles.indexOf("D"));
        assertThat(roles.indexOf("C")).isLessThan(roles.indexOf("D"));
    }

    @Test
    void testRawOutput_isFinalTaskOutput() {
        var a = agentWithResponse("A", "A result");
        var b = agentWithResponse("B", "B result");
        var ta = task("Task A", a);
        var tb = taskWithContext("Task B", b, List.of(ta));

        var output = executor().execute(List.of(ta, tb), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("B result");
    }

    @Test
    void testTotalToolCalls_summedAcrossAllTasks() {
        var a = agentWithResponse("A", "result");
        var b = agentWithResponse("B", "result");
        var ta = task("Task A", a);
        var tb = task("Task B", b);

        var output = executor().execute(List.of(ta, tb), ExecutionContext.disabled());

        assertThat(output.getTotalToolCalls()).isZero();
    }

    // ========================
    // Context output passing
    // ========================

    @Test
    void testContextOutputsPassedToAgent_capturedViaTaskOutput() {
        var a = agentWithResponse("A", "A produced output");
        var b = agentWithResponse("B", "B used context");
        var ta = task("Task A", a);
        var tb = taskWithContext("Task B", b, List.of(ta));

        var output = executor().execute(List.of(ta, tb), ExecutionContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs()).extracting(TaskOutput::getAgentRole).containsExactlyInAnyOrder("A", "B");
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

        var memoryContext = mock(MemoryContext.class);
        when(memoryContext.isActive()).thenReturn(false);
        when(memoryContext.hasShortTerm()).thenReturn(false);
        when(memoryContext.hasLongTerm()).thenReturn(false);
        when(memoryContext.hasEntityMemory()).thenReturn(false);
        when(memoryContext.getShortTermEntries()).thenReturn(List.of());
        when(memoryContext.queryLongTerm(any())).thenReturn(List.of());
        when(memoryContext.getEntityFacts()).thenReturn(Map.of());

        var output = executor().execute(List.of(ta, tb), ExecutionContext.of(memoryContext, false));
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    // ========================
    // Concurrent execution verification
    // ========================

    @Test
    void testConcurrentExecution_trackingViaSharedState() {
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

        var output = executor().execute(List.of(t1, t2), ExecutionContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(startTimes).containsKeys("t1", "t2");
    }
}
