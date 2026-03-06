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
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.ensemble.ExitReason;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for workflow inference from task context declarations.
 *
 * <p>Verifies the three cases described in issue #112:
 * <ul>
 *   <li>No workflow declared + no context deps -> SEQUENTIAL</li>
 *   <li>No workflow declared + context deps -> PARALLEL (DAG inferred)</li>
 *   <li>Explicit {@link Workflow} override -> always honoured</li>
 * </ul>
 */
class WorkflowInferenceIntegrationTest {

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private Agent agentWithResponse(String role, String response) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        return Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
    }

    // ========================
    // Default sequential (no workflow, no context)
    // ========================

    @Test
    void noWorkflowDeclared_noContextDeps_executesSequentially() {
        Agent a = agentWithResponse("Agent1", "Output 1");
        Agent b = agentWithResponse("Agent2", "Output 2");
        Agent c = agentWithResponse("Agent3", "Output 3");

        // Tasks have no context dependencies -> sequential inferred
        Task task1 = Task.builder()
                .description("Task 1")
                .expectedOutput("Result 1")
                .agent(a)
                .build();
        Task task2 = Task.builder()
                .description("Task 2")
                .expectedOutput("Result 2")
                .agent(b)
                .build();
        Task task3 = Task.builder()
                .description("Task 3")
                .expectedOutput("Result 3")
                .agent(c)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .task(task3)
                // no .workflow(...) call
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.completedTasks()).hasSize(3);
        // Sequential: outputs in declaration order
        assertThat(output.completedTasks().get(0).getRaw()).isEqualTo("Output 1");
        assertThat(output.completedTasks().get(1).getRaw()).isEqualTo("Output 2");
        assertThat(output.completedTasks().get(2).getRaw()).isEqualTo("Output 3");
        assertThat(output.getRaw()).isEqualTo("Output 3");
    }

    // ========================
    // Inferred parallel from context declarations
    // ========================

    @Test
    void noWorkflowDeclared_contextDepsPresent_infersDag() {
        Agent a = agentWithResponse("Agent1", "Output A");
        Agent b = agentWithResponse("Agent2", "Output B");
        Agent c = agentWithResponse("Agent3", "Output C");

        Task taskA = Task.builder()
                .description("Independent A")
                .expectedOutput("A")
                .agent(a)
                .build();
        Task taskB = Task.builder()
                .description("Independent B")
                .expectedOutput("B")
                .agent(b)
                .build();
        // C depends on both A and B
        Task taskC = Task.builder()
                .description("Combine A and B")
                .expectedOutput("C")
                .agent(c)
                .context(List.of(taskA, taskB))
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(taskA)
                .task(taskB)
                .task(taskC)
                // no .workflow(...) -> DAG inferred from context declarations
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.completedTasks()).hasSize(3);
        // C must finish last; its output is the final raw
        assertThat(output.getRaw()).isEqualTo("Output C");

        // All tasks accessible via getOutput
        assertThat(output.getOutput(taskA)).isPresent();
        assertThat(output.getOutput(taskB)).isPresent();
        assertThat(output.getOutput(taskC)).isPresent();
    }

    @Test
    void noWorkflowDeclared_fanIn_taskCWaitsForBothAandB() {
        AtomicInteger completionOrder = new AtomicInteger(0);

        // Variable names corrected: llmA/B/C (not lmmA/B/C)
        ChatModel llmA = mock(ChatModel.class);
        when(llmA.chat(any(ChatRequest.class))).thenAnswer(inv -> ChatResponse.builder()
                .aiMessage(new AiMessage("A-" + completionOrder.incrementAndGet()))
                .build());

        ChatModel llmB = mock(ChatModel.class);
        when(llmB.chat(any(ChatRequest.class))).thenAnswer(inv -> ChatResponse.builder()
                .aiMessage(new AiMessage("B-" + completionOrder.incrementAndGet()))
                .build());

        ChatModel llmC = mock(ChatModel.class);
        when(llmC.chat(any(ChatRequest.class))).thenAnswer(inv -> ChatResponse.builder()
                .aiMessage(new AiMessage("C-" + completionOrder.incrementAndGet()))
                .build());

        Agent a = Agent.builder().role("AgentA").goal("A work").llm(llmA).build();
        Agent b = Agent.builder().role("AgentB").goal("B work").llm(llmB).build();
        Agent c = Agent.builder().role("AgentC").goal("C work").llm(llmC).build();

        Task taskA = Task.builder()
                .description("A task")
                .expectedOutput("A")
                .agent(a)
                .build();
        Task taskB = Task.builder()
                .description("B task")
                .expectedOutput("B")
                .agent(b)
                .build();
        Task taskC = Task.builder()
                .description("C task (after A and B)")
                .expectedOutput("C")
                .agent(c)
                .context(List.of(taskA, taskB))
                .build();

        EnsembleOutput output =
                Ensemble.builder().task(taskA).task(taskB).task(taskC).build().run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.completedTasks()).hasSize(3);

        // C must be last (order 3)
        String cRaw = output.getOutput(taskC).get().getRaw();
        assertThat(cRaw).startsWith("C-3");
    }

    // ========================
    // Explicit SEQUENTIAL override
    // ========================

    @Test
    void explicitSequential_withContextDeps_forcesSequentialOverInference() {
        // task2 declares a context dependency on task1. Without an explicit workflow,
        // PARALLEL would be inferred. With explicit SEQUENTIAL, the pipeline runs in
        // declaration order and context ordering validation fires.
        Agent a = agentWithResponse("Agent1", "Output A");
        Agent b = agentWithResponse("Agent2", "Output B");

        Task task1 = Task.builder()
                .description("Task A")
                .expectedOutput("A")
                .agent(a)
                .build();
        Task task2 = Task.builder()
                .description("Task B (depends on Task A)")
                .expectedOutput("B")
                .agent(b)
                .context(List.of(task1)) // context dep on task1 -- would trigger PARALLEL inference
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .workflow(Workflow.SEQUENTIAL) // explicit override: SEQUENTIAL despite context dep
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.completedTasks()).hasSize(2);
        assertThat(output.completedTasks().get(0).getRaw()).isEqualTo("Output A");
        assertThat(output.completedTasks().get(1).getRaw()).isEqualTo("Output B");
    }

    @Test
    void explicitParallel_worksAsBeforeWithContextDeps() {
        Agent a = agentWithResponse("AgentA", "A result");
        Agent b = agentWithResponse("AgentB", "B result");
        Agent c = agentWithResponse("AgentC", "C result");

        Task taskA =
                Task.builder().description("A").expectedOutput("A").agent(a).build();
        Task taskB =
                Task.builder().description("B").expectedOutput("B").agent(b).build();
        Task taskC = Task.builder()
                .description("C depends on A and B")
                .expectedOutput("C")
                .agent(c)
                .context(List.of(taskA, taskB))
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(taskA)
                .task(taskB)
                .task(taskC)
                .workflow(Workflow.PARALLEL) // explicit override
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.completedTasks()).hasSize(3);
    }

    // ========================
    // Cycle detection enforced regardless of inference mode
    // ========================

    @Test
    void inferredParallel_forwardContextDependency_completesSuccessfully() {
        // Verify that inferred PARALLEL correctly handles a task that declares
        // a context dependency on a preceding task. This also demonstrates that
        // cycle detection (enforced during validation) does not interfere with
        // valid forward dependencies.
        //
        // Note: true A<->B cycles cannot be constructed with the immutable Task
        // builder (building B with context(A) requires A to already exist, so B
        // cannot be passed into A's construction). Cycle detection unit tests are
        // in EnsembleValidationTest using the cyclic graph traversal path.
        Agent a = agentWithResponse("Agent1", "First result");
        Agent b = agentWithResponse("Agent2", "Second result");

        Task task1 = Task.builder()
                .description("First task")
                .expectedOutput("First")
                .agent(a)
                .build();
        Task task2 = Task.builder()
                .description("Second task (depends on first)")
                .expectedOutput("Second")
                .agent(b)
                .context(List.of(task1)) // valid forward dep -- task1 appears first in list
                .build();

        // task2 has a context dep on task1 -> PARALLEL inferred
        EnsembleOutput output =
                Ensemble.builder().task(task1).task(task2).build().run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.completedTasks()).hasSize(2);
        assertThat(output.getOutput(task1)).isPresent();
        assertThat(output.getOutput(task2)).isPresent();
    }

    @Test
    void cycleDetection_enforcedForExplicitSequential() {
        // Cycle detection (validateNoCircularContextDependencies) fires regardless of
        // the workflow setting. Here we demonstrate that a forward reference in an
        // explicit-SEQUENTIAL ensemble is caught at run() time before any LLM calls.
        //
        // Setup: taskAWithDep depends on taskB, but taskAWithDep appears BEFORE taskB
        // in the task list. With explicit SEQUENTIAL, context ordering validation fires
        // and throws ValidationException.
        Agent agent = agentWithResponse("Agent", "result");

        Task taskA = Task.builder()
                .description("Task A")
                .expectedOutput("A")
                .agent(agent)
                .build();
        Task taskB = Task.builder()
                .description("Task B")
                .expectedOutput("B")
                .agent(agent)
                .context(List.of(taskA)) // taskB depends on taskA
                .build();
        Task taskAWithDep = taskA.toBuilder()
                .context(List.of(taskB)) // taskA now depends on taskB -> forward reference
                .build();

        // Explicit SEQUENTIAL triggers context ordering validation
        assertThatThrownBy(() -> Ensemble.builder()
                        .task(taskAWithDep) // declared first but depends on taskB (second)
                        .task(taskB)
                        .workflow(Workflow.SEQUENTIAL)
                        .build()
                        .run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("context");
    }

    // ========================
    // Single task: default sequential, no workflow needed
    // ========================

    @Test
    void singleTask_noWorkflowDeclaration_runs() {
        Agent agent = agentWithResponse("Solo", "Solo output");
        Task task = Task.builder()
                .description("Solo task")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        EnsembleOutput output = Ensemble.builder().task(task).build().run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.getRaw()).isEqualTo("Solo output");
        assertThat(output.getOutput(task)).isPresent();
    }
}
