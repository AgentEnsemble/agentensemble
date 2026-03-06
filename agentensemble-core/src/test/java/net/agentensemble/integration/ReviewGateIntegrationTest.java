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
import net.agentensemble.review.Review;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewPolicy;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the human-in-the-loop review gate system.
 *
 * <p>Uses Mockito-mocked LLMs (no real network calls) and programmatic
 * {@link ReviewHandler} implementations to verify end-to-end behaviour.
 */
class ReviewGateIntegrationTest {

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private Agent agentWithResponse(String role, String response) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        return Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
    }

    // ========================
    // After-execution review gate: Continue
    // ========================

    @Test
    void afterReview_autoApprove_allTasksComplete_exitReasonCompleted() {
        Agent researcher = agentWithResponse("Researcher", "Research findings");
        Agent writer = agentWithResponse("Writer", "Blog post draft");

        Task task1 = Task.builder()
                .description("Research AI trends")
                .expectedOutput("Report")
                .agent(researcher)
                .build();
        Task task2 = Task.builder()
                .description("Write blog post")
                .expectedOutput("Blog post")
                .agent(writer)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .reviewHandler(ReviewHandler.autoApprove())
                .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Blog post draft");
        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
    }

    // ========================
    // After-execution review gate: ExitEarly
    // ========================

    @Test
    void afterReview_exitEarlyAfterFirstTask_pipelineStops_firstTaskIncluded() {
        Agent researcher = agentWithResponse("Researcher", "Research complete");
        Agent writer = agentWithResponse("Writer", "This should never run");

        Task task1 = Task.builder()
                .description("Research AI")
                .expectedOutput("Report")
                .agent(researcher)
                .review(Review.required()) // always review after execution
                .build();
        Task task2 = Task.builder()
                .description("Write blog")
                .expectedOutput("Post")
                .agent(writer)
                .build();

        // Handler always returns ExitEarly
        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .reviewHandler(request -> ReviewDecision.exitEarly())
                .build()
                .run();

        // Task 1 completed and is included; task 2 never ran
        assertThat(output.getExitReason()).isEqualTo(ExitReason.USER_EXIT_EARLY);
        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getTaskOutputs().get(0).getRaw()).isEqualTo("Research complete");
        assertThat(output.getRaw()).isEqualTo("Research complete");
    }

    @Test
    void afterReview_policyAfterLastTask_exitEarly_onlyLastTaskTriggersGate() {
        Agent researcher = agentWithResponse("Researcher", "Research result");
        Agent writer = agentWithResponse("Writer", "Writer result");

        Task task1 = Task.builder()
                .description("Research")
                .expectedOutput("Report")
                .agent(researcher)
                .build();
        Task task2 = Task.builder()
                .description("Write")
                .expectedOutput("Post")
                .agent(writer)
                .build();

        // Policy: AFTER_LAST_TASK -> only fires after task2
        // Handler: ExitEarly -> pipeline stops after task2 (both tasks included)
        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .reviewHandler(request -> ReviewDecision.exitEarly())
                .reviewPolicy(ReviewPolicy.AFTER_LAST_TASK)
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.USER_EXIT_EARLY);
        // Both tasks completed; exit-early fires AFTER task2 (which is included)
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    // ========================
    // After-execution review gate: Edit
    // ========================

    @Test
    void afterReview_edit_revisedOutputPassedToNextTask() {
        Agent researcher = agentWithResponse("Researcher", "Original research output");
        Agent writer = agentWithResponse("Writer", "Post based on context");

        Task task1 = Task.builder()
                .description("Research AI")
                .expectedOutput("Report")
                .agent(researcher)
                .review(Review.required())
                .build();
        Task task2 = Task.builder()
                .description("Write post using research")
                .expectedOutput("Blog post")
                .agent(writer)
                .context(java.util.List.of(task1))
                .build();

        // Reviewer edits task1 output before it propagates to task2's context
        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .reviewHandler(request -> ReviewDecision.edit("Revised research output"))
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.getTaskOutputs()).hasSize(2);
        // Task1 output is replaced by the reviewer's edit
        assertThat(output.getTaskOutputs().get(0).getRaw()).isEqualTo("Revised research output");
    }

    // ========================
    // Before-execution review gate: Continue
    // ========================

    @Test
    void beforeReview_continue_taskExecutesNormally() {
        Agent researcher = agentWithResponse("Researcher", "Research findings");

        Task task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("Report")
                .agent(researcher)
                .beforeReview(Review.required())
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .reviewHandler(ReviewHandler.autoApprove())
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getRaw()).isEqualTo("Research findings");
    }

    // ========================
    // Before-execution review gate: ExitEarly
    // ========================

    @Test
    void beforeReview_exitEarly_taskDoesNotExecute_emptyOutput() {
        Agent researcher = agentWithResponse("Researcher", "This should never run");

        Task task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("Report")
                .agent(researcher)
                .beforeReview(Review.required())
                .build();

        // Handler returns ExitEarly -> task never executes
        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .reviewHandler(request -> ReviewDecision.exitEarly())
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.USER_EXIT_EARLY);
        // Task did NOT execute; no task outputs
        assertThat(output.getTaskOutputs()).isEmpty();
        assertThat(output.getRaw()).isEqualTo("");
    }

    @Test
    void beforeReview_exitEarlyOnSecondTask_firstTaskOutputPreserved() {
        Agent researcher = agentWithResponse("Researcher", "Research done");
        Agent writer = agentWithResponse("Writer", "This should never run");

        Task task1 = Task.builder()
                .description("Research AI")
                .expectedOutput("Report")
                .agent(researcher)
                .build();
        Task task2 = Task.builder()
                .description("Write blog")
                .expectedOutput("Post")
                .agent(writer)
                .beforeReview(Review.required())
                .build();

        // Approve task1 (no review configured), ExitEarly for task2 before-review
        int[] callCount = {0};
        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .reviewHandler(request -> {
                    callCount[0]++;
                    return ReviewDecision.exitEarly();
                })
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.USER_EXIT_EARLY);
        // Task1 completed; task2 before-review fired ExitEarly -> task2 did not run
        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getTaskOutputs().get(0).getRaw()).isEqualTo("Research done");
    }

    // ========================
    // Before-execution review gate: Edit treated as Continue
    // ========================

    @Test
    void beforeReview_edit_treatedAsContinue_taskExecutesNormally() {
        Agent researcher = agentWithResponse("Researcher", "Research result");

        Task task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("Report")
                .agent(researcher)
                .beforeReview(Review.required())
                .build();

        // Edit before execution should be treated as Continue
        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .reviewHandler(request -> ReviewDecision.edit("Ignored edit - no output before execution"))
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.getRaw()).isEqualTo("Research result");
    }

    // ========================
    // Task-level skip overrides ensemble policy
    // ========================

    @Test
    void taskLevelSkip_overridesEnsembleAfterEveryTask_gateDoesNotFire() {
        Agent agent = agentWithResponse("Agent", "Task output");

        Task task = Task.builder()
                .description("Do the work")
                .expectedOutput("Output")
                .agent(agent)
                .review(Review.skip()) // explicitly skip review for this task
                .build();

        int[] callCount = {0};
        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .reviewHandler(request -> {
                    callCount[0]++;
                    return ReviewDecision.continueExecution();
                })
                .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        // Skip review should prevent the gate from firing
        assertThat(callCount[0]).isZero();
    }

    @Test
    void taskLevelRequired_overridesEnsembleNeverPolicy_gateFires() {
        Agent agent = agentWithResponse("Agent", "Task output");

        Task task = Task.builder()
                .description("Do the work")
                .expectedOutput("Output")
                .agent(agent)
                .review(Review.required()) // explicitly require review
                .build();

        int[] callCount = {0};
        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .reviewHandler(request -> {
                    callCount[0]++;
                    return ReviewDecision.continueExecution();
                })
                .reviewPolicy(ReviewPolicy.NEVER) // default, no reviews
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        // Task-level required should override NEVER policy
        assertThat(callCount[0]).isEqualTo(1);
    }

    // ========================
    // Policy-driven review uses default timeout when task has no explicit Review
    // ========================

    @Test
    void policyDrivenReview_noTaskReview_requestHasDefaultTimeoutAndOnTimeout() {
        // When a gate fires via ReviewPolicy (no task.review() set), the ReviewRequest
        // must carry Review.DEFAULT_TIMEOUT and Review.DEFAULT_ON_TIMEOUT so that
        // ConsoleReviewHandler does not block indefinitely on an infinite-wait path.
        Agent agent = agentWithResponse("Agent", "Task output");

        Task task = Task.builder()
                .description("Do work")
                .expectedOutput("Output")
                .agent(agent)
                .build(); // no review configured on task

        net.agentensemble.review.ReviewRequest[] capturedRequest = new net.agentensemble.review.ReviewRequest[1];

        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .reviewHandler(request -> {
                    capturedRequest[0] = request;
                    return ReviewDecision.continueExecution();
                })
                .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(capturedRequest[0]).isNotNull();
        assertThat(capturedRequest[0].timeout()).isEqualTo(Review.DEFAULT_TIMEOUT);
        assertThat(capturedRequest[0].onTimeoutAction()).isEqualTo(Review.DEFAULT_ON_TIMEOUT);
    }

    // ========================
    // No review handler -> no gates fire
    // ========================

    @Test
    void noReviewHandler_tasksRunNormally_exitReasonCompleted() {
        Agent agent = agentWithResponse("Agent", "Normal output");

        Task task = Task.builder()
                .description("Do work")
                .expectedOutput("Output")
                .agent(agent)
                .review(Review.required()) // review configured but no handler
                .build();

        // No reviewHandler on the ensemble -> gates are suppressed
        EnsembleOutput output = Ensemble.builder().task(task).build().run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.getRaw()).isEqualTo("Normal output");
    }
}
