package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.Collections;
import java.util.Map;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.execution.RunOptions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Ensemble#run(RunOptions)} and {@link Ensemble#run(Map, RunOptions)},
 * covering the null-fallback branches and {@code resolveRunOption} paths.
 */
class EnsembleRunOptionsTest {

    private Agent agentWithResponse(String response) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage(response))
                        .build());
        return Agent.builder().role("Worker").goal("Do work").llm(mockLlm).build();
    }

    private Ensemble simpleEnsemble(String llmResponse) {
        var agent = agentWithResponse(llmResponse);
        var task = Task.builder()
                .description("A task")
                .expectedOutput("Output")
                .agent(agent)
                .build();
        return Ensemble.builder().task(task).build();
    }

    // ========================
    // run(RunOptions) — null fallback
    // ========================

    @Test
    void run_withNullRunOptions_delegatesToRun() {
        var output = simpleEnsemble("Done.").run((RunOptions) null);
        assertThat(output.getRaw()).isEqualTo("Done.");
    }

    // ========================
    // run(RunOptions) — resolveRunOption: non-null override path
    // ========================

    @Test
    void run_withNonNullRunOptions_appliesOverrides() {
        var opts = RunOptions.builder()
                .maxToolOutputLength(100)
                .toolLogTruncateLength(50)
                .build();
        var output = simpleEnsemble("Done.").run(opts);
        assertThat(output.getRaw()).isEqualTo("Done.");
    }

    // ========================
    // run(RunOptions) — resolveRunOption: null override path (inherits default)
    // ========================

    @Test
    void run_withDefaultRunOptions_inheritsEnsembleDefaults() {
        var output = simpleEnsemble("Done.").run(RunOptions.DEFAULT);
        assertThat(output.getRaw()).isEqualTo("Done.");
    }

    // ========================
    // run(Map, RunOptions) — null RunOptions fallback
    // ========================

    @Test
    void run_withMapAndNullRunOptions_delegatesToRunMap() {
        var output = simpleEnsemble("Done.").run(Map.of(), (RunOptions) null);
        assertThat(output.getRaw()).isEqualTo("Done.");
    }

    // ========================
    // run(Map, RunOptions) — null runtimeInputs branch
    // ========================

    @Test
    void run_withNullRuntimeInputs_usesEnsembleInputs() {
        var output = simpleEnsemble("Done.").run((Map<String, String>) null, RunOptions.DEFAULT);
        assertThat(output.getRaw()).isEqualTo("Done.");
    }

    // ========================
    // run(Map, RunOptions) — empty runtimeInputs branch
    // ========================

    @Test
    void run_withEmptyRuntimeInputs_usesEnsembleInputs() {
        var output = simpleEnsemble("Done.").run(Collections.emptyMap(), RunOptions.DEFAULT);
        assertThat(output.getRaw()).isEqualTo("Done.");
    }

    // ========================
    // run(Map, RunOptions) — non-empty runtimeInputs merge branch
    // ========================

    @Test
    void run_withNonEmptyRuntimeInputsAndRunOptions_mergesInputs() {
        var agent = agentWithResponse("Done.");
        var task = Task.builder()
                .description("Research {topic}")
                .expectedOutput("Output")
                .agent(agent)
                .build();
        var ensemble = Ensemble.builder().task(task).build();

        var opts = RunOptions.builder().maxToolOutputLength(500).build();
        var output = ensemble.run(Map.of("topic", "AI"), opts);
        assertThat(output.getRaw()).isEqualTo("Done.");
    }
}
