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
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.exception.ParallelExecutionException;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.workflow.ParallelErrorStrategy;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for parallel ensemble error handling strategies.
 *
 * Covers FAIL_FAST and CONTINUE_ON_ERROR behaviour including skip cascading.
 * Basic execution, validation, and output structure tests are in
 * ParallelEnsembleIntegrationTest.
 */
class ParallelEnsembleErrorIntegrationTest {

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
    // FAIL_FAST error handling
    // ========================

    @Test
    void testFailFast_oneTaskFails_throwsTaskExecutionException() {
        var good = agentWithResponse("Good", "Good result");
        var bad = agentThatFails("Bad");
        var tGood = Task.builder()
                .description("Good task")
                .expectedOutput("Good out")
                .agent(good)
                .build();
        var tBad = Task.builder()
                .description("Bad task")
                .expectedOutput("Bad out")
                .agent(bad)
                .build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .agent(good)
                        .agent(bad)
                        .task(tGood)
                        .task(tBad)
                        .workflow(Workflow.PARALLEL)
                        .parallelErrorStrategy(ParallelErrorStrategy.FAIL_FAST)
                        .build()
                        .run())
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
        var tBad = Task.builder()
                .description("Failing task")
                .expectedOutput("Out")
                .agent(bad)
                .build();

        // No explicit parallelErrorStrategy set -- default is FAIL_FAST
        assertThatThrownBy(() -> Ensemble.builder()
                        .agent(bad)
                        .task(tBad)
                        .workflow(Workflow.PARALLEL)
                        .build()
                        .run())
                .isInstanceOf(TaskExecutionException.class);
    }

    // ========================
    // CONTINUE_ON_ERROR error handling
    // ========================

    @Test
    void testContinueOnError_partialSuccess_throwsParallelExecutionException() {
        var good = agentWithResponse("Good", "Good result");
        var bad = agentThatFails("Bad");
        var tGood = Task.builder()
                .description("Good task")
                .expectedOutput("Good out")
                .agent(good)
                .build();
        var tBad = Task.builder()
                .description("Bad task")
                .expectedOutput("Bad out")
                .agent(bad)
                .build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .agent(good)
                        .agent(bad)
                        .task(tGood)
                        .task(tBad)
                        .workflow(Workflow.PARALLEL)
                        .parallelErrorStrategy(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                        .build()
                        .run())
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
        var tGood = Task.builder()
                .description("Good task")
                .expectedOutput("Good out")
                .agent(good)
                .build();
        var tBad = Task.builder()
                .description("Bad task")
                .expectedOutput("Bad out")
                .agent(bad)
                .build();
        var tSkip = Task.builder()
                .description("Skip task")
                .expectedOutput("Skip out")
                .agent(skip)
                .context(List.of(tBad))
                .build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .agent(good)
                        .agent(bad)
                        .agent(skip)
                        .task(tGood)
                        .task(tBad)
                        .task(tSkip)
                        .workflow(Workflow.PARALLEL)
                        .parallelErrorStrategy(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                        .build()
                        .run())
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
        var ta = Task.builder()
                .description("Task A")
                .expectedOutput("Out A")
                .agent(a)
                .build();
        var tb = Task.builder()
                .description("Task B")
                .expectedOutput("Out B")
                .agent(b)
                .build();

        var output = Ensemble.builder()
                .agent(a)
                .agent(b)
                .task(ta)
                .task(tb)
                .workflow(Workflow.PARALLEL)
                .parallelErrorStrategy(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).hasSize(2);
    }
}
