package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.ensemble.ExitReason;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.review.Review;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewPolicy;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for partial result preservation and graceful exit-early.
 *
 * <p>Verifies that completed task outputs are always accessible via the new
 * EnsembleOutput API: {@code isComplete()}, {@code completedTasks()},
 * {@code lastCompletedOutput()}, and {@code getOutput(Task)}.
 */
class PartialResultsIntegrationTest {

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private Agent agentWithResponse(String role, String response) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        return Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
    }

    // ========================
    // Sequential: 3-task pipeline, exit-early after task 2
    // ========================

    @Test
    void sequential_exitEarlyAfterTask2_task1And2OutputsPreserved() {
        Agent researcher = agentWithResponse("Researcher", "Research complete");
        Agent analyst = agentWithResponse("Analyst", "Analysis complete");
        Agent writer = agentWithResponse("Writer", "Should not run");

        Task task1 = Task.builder()
                .description("Research AI trends")
                .expectedOutput("Report")
                .agent(researcher)
                .build();
        Task task2 = Task.builder()
                .description("Analyse the research")
                .expectedOutput("Analysis")
                .agent(analyst)
                .review(Review.required())
                .build();
        Task task3 = Task.builder()
                .description("Write blog post")
                .expectedOutput("Blog")
                .agent(writer)
                .build();

        // Review fires after task2 and returns ExitEarly
        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .task(task3)
                .reviewHandler(request -> ReviewDecision.exitEarly())
                .build()
                .run();

        // Task 3 should never have run
        assertThat(output.getExitReason()).isEqualTo(ExitReason.USER_EXIT_EARLY);
        assertThat(output.isComplete()).isFalse();
        assertThat(output.completedTasks()).hasSize(2);
        assertThat(output.getRaw()).isEqualTo("Analysis complete");

        // lastCompletedOutput() returns task2's output
        assertThat(output.lastCompletedOutput()).isPresent();
        assertThat(output.lastCompletedOutput().get().getRaw()).isEqualTo("Analysis complete");

        // getOutput(task) returns correct outputs
        assertThat(output.getOutput(task1)).isPresent();
        assertThat(output.getOutput(task1).get().getRaw()).isEqualTo("Research complete");
        assertThat(output.getOutput(task2)).isPresent();
        assertThat(output.getOutput(task2).get().getRaw()).isEqualTo("Analysis complete");
        assertThat(output.getOutput(task3)).isEmpty(); // task3 never ran
    }

    @Test
    void sequential_allTasksComplete_isCompleteTrue() {
        Agent researcher = agentWithResponse("Researcher", "Research done");
        Agent writer = agentWithResponse("Writer", "Post done");

        Task task1 = Task.builder()
                .description("Research AI")
                .expectedOutput("Report")
                .agent(researcher)
                .build();
        Task task2 = Task.builder()
                .description("Write post")
                .expectedOutput("Post")
                .agent(writer)
                .build();

        EnsembleOutput output =
                Ensemble.builder().task(task1).task(task2).build().run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.isComplete()).isTrue();
        assertThat(output.completedTasks()).hasSize(2);

        // getOutput returns correct per-task outputs
        assertThat(output.getOutput(task1)).isPresent();
        assertThat(output.getOutput(task1).get().getRaw()).isEqualTo("Research done");
        assertThat(output.getOutput(task2)).isPresent();
        assertThat(output.getOutput(task2).get().getRaw()).isEqualTo("Post done");
    }

    @Test
    void sequential_exitEarlyBeforeFirstTask_noCompletedOutputs() {
        Agent researcher = agentWithResponse("Researcher", "Should not run");

        Task task1 = Task.builder()
                .description("Research AI")
                .expectedOutput("Report")
                .agent(researcher)
                .beforeReview(Review.required())
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .reviewHandler(request -> ReviewDecision.exitEarly())
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.USER_EXIT_EARLY);
        assertThat(output.isComplete()).isFalse();
        assertThat(output.completedTasks()).isEmpty();
        assertThat(output.lastCompletedOutput()).isEmpty();
        assertThat(output.getOutput(task1)).isEmpty();
    }

    // ========================
    // Parallel: exit-early support
    // ========================

    @Test
    void parallel_exitEarlyAfterTask_partialOutputsPreserved() {
        Agent researcher = agentWithResponse("Researcher", "Research complete");
        Agent analyst = agentWithResponse("Analyst", "Analysis complete");

        Task task1 = Task.builder()
                .description("Research AI trends")
                .expectedOutput("Report")
                .agent(researcher)
                .review(Review.required())
                .build();
        Task task2 = Task.builder()
                .description("Analyse data (independent)")
                .expectedOutput("Analysis")
                .agent(analyst)
                .review(Review.required())
                .build();

        // First ExitEarly review response wins; tasks are sequential here since
        // the parallel executor uses the same review gate mechanism.
        int[] reviewCount = {0};
        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .workflow(net.agentensemble.workflow.Workflow.PARALLEL)
                .reviewHandler(request -> {
                    reviewCount[0]++;
                    return ReviewDecision.exitEarly();
                })
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.USER_EXIT_EARLY);
        assertThat(output.isComplete()).isFalse();
        // At least one task completed (whichever triggered exit-early)
        assertThat(output.completedTasks()).isNotEmpty();
    }

    // ========================
    // Full run: isComplete() = true, getOutput works for all tasks
    // ========================

    @Test
    void sequential_getOutput_worksForAllTasksInFullRun() {
        Agent a = agentWithResponse("Agent1", "Output 1");
        Agent b = agentWithResponse("Agent2", "Output 2");
        Agent c = agentWithResponse("Agent3", "Output 3");

        Task task1 = Task.of("Task 1");
        Task task2 = Task.of("Task 2");
        Task task3 = Task.of("Task 3");

        // Use task-level LLMs (synthesized agents)
        task1 = task1.toBuilder().agent(a).build();
        task2 = task2.toBuilder().agent(b).build();
        task3 = task3.toBuilder().agent(c).build();

        EnsembleOutput output =
                Ensemble.builder().task(task1).task(task2).task(task3).build().run();

        assertThat(output.isComplete()).isTrue();
        assertThat(output.getOutput(task1)).isPresent();
        assertThat(output.getOutput(task1).get().getRaw()).isEqualTo("Output 1");
        assertThat(output.getOutput(task2)).isPresent();
        assertThat(output.getOutput(task2).get().getRaw()).isEqualTo("Output 2");
        assertThat(output.getOutput(task3)).isPresent();
        assertThat(output.getOutput(task3).get().getRaw()).isEqualTo("Output 3");
    }

    // ========================
    // Timeout exit reason
    // ========================

    @Test
    void sequential_timeoutExitReason_distinguishedFromUserExitEarly() {
        Agent researcher = agentWithResponse("Researcher", "Research complete");
        Agent writer = agentWithResponse("Writer", "Should not run");

        Task task1 = Task.builder()
                .description("Research AI")
                .expectedOutput("Report")
                .agent(researcher)
                .review(Review.required())
                .build();
        Task task2 = Task.builder()
                .description("Write post")
                .expectedOutput("Post")
                .agent(writer)
                .build();

        // Simulate a ReviewHandler that returns exitEarlyTimeout (timed-out exit)
        ReviewHandler timeoutHandler = request -> ReviewDecision.exitEarlyTimeout();

        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .reviewHandler(timeoutHandler)
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.TIMEOUT);
        assertThat(output.isComplete()).isFalse();
        assertThat(output.completedTasks()).hasSize(1);
        assertThat(output.getRaw()).isEqualTo("Research complete");
    }

    // ========================
    // Memory persistence: completed task outputs persisted even on exit-early
    // ========================

    @Test
    void sequential_exitEarly_completedTaskOutputPersistedToMemoryScope() {
        // Arrange: task1 declares a memory scope, task2 never runs (exit-early after task1)
        Agent researcher = agentWithResponse("Researcher", "Research stored to memory");
        Agent writer = agentWithResponse("Writer", "Should not run");

        MemoryStore memoryStore = MemoryStore.inMemory();

        Task task1 = Task.builder()
                .description("Research AI")
                .expectedOutput("Report")
                .agent(researcher)
                .memory("research-scope") // output will be stored in this scope
                .review(Review.required())
                .build();
        Task task2 = Task.builder()
                .description("Write post (skipped)")
                .expectedOutput("Post")
                .agent(writer)
                .build();

        // Exit-early fires after task1 (after task1 has already completed + stored its output)
        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .memoryStore(memoryStore)
                .reviewHandler(request -> ReviewDecision.exitEarly())
                .build()
                .run();

        // Pipeline stopped early
        assertThat(output.getExitReason()).isEqualTo(ExitReason.USER_EXIT_EARLY);
        assertThat(output.isComplete()).isFalse();
        assertThat(output.completedTasks()).hasSize(1);

        // Memory was persisted for the completed task BEFORE exit-early fired
        // AgentExecutor stores to declared scopes immediately after task execution,
        // before the review gate fires, so the output is always persisted.
        var storedEntries = memoryStore.retrieve("research-scope", "", 10);
        assertThat(storedEntries).isNotEmpty();
        assertThat(storedEntries.get(0).getContent()).isEqualTo("Research stored to memory");
    }

    // ========================
    // Policy-driven review: autoApprove -> COMPLETED with index
    // ========================

    @Test
    void sequential_reviewApproved_exitReasonCompletedWithIndex() {
        Agent researcher = agentWithResponse("Researcher", "Research result");

        Task task = Task.builder()
                .description("Research AI")
                .expectedOutput("Report")
                .agent(researcher)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .reviewHandler(ReviewHandler.autoApprove())
                .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.isComplete()).isTrue();
        assertThat(output.getOutput(task)).isPresent();
        assertThat(output.getOutput(task).get().getRaw()).isEqualTo("Research result");
    }
}
