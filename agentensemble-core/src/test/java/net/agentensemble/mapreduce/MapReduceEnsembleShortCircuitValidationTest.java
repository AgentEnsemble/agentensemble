package net.agentensemble.mapreduce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for short-circuit builder validation in {@link MapReduceEnsemble.Builder}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code directAgent} without {@code directTask}: {@link ValidationException}</li>
 *   <li>{@code directTask} without {@code directAgent}: {@link ValidationException}</li>
 *   <li>{@code directAgent} or {@code directTask} in static mode ({@code chunkSize}):
 *       {@link ValidationException}</li>
 *   <li>{@code directAgent} or {@code directTask} in default static mode (no strategy set):
 *       {@link ValidationException}</li>
 *   <li>Both factories set in adaptive mode: builds successfully</li>
 *   <li>{@code inputEstimator} alone (no direct factories): builds successfully (no constraint)</li>
 *   <li>{@code inputEstimator} with both direct factories in adaptive mode: builds successfully</li>
 * </ul>
 */
class MapReduceEnsembleShortCircuitValidationTest {

    private ChatModel stub;
    private AtomicInteger counter;

    @BeforeEach
    void setUp() {
        stub = new NoOpChatModel();
        counter = new AtomicInteger(0);
    }

    // ========================
    // directAgent without directTask (and vice versa)
    // ========================

    @Test
    void build_directAgentWithoutDirectTask_throwsValidationException() {
        assertThatThrownBy(() -> adaptiveBuilder()
                        .directAgent(() -> stubAgent("Head Chef"))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("directAgent")
                .hasMessageContaining("directTask");
    }

    @Test
    void build_directTaskWithoutDirectAgent_throwsValidationException() {
        assertThatThrownBy(() -> adaptiveBuilder()
                        .directTask((agent, items) -> stubTask("direct task", agent))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("directAgent")
                .hasMessageContaining("directTask");
    }

    // ========================
    // direct factories in static mode
    // ========================

    @Test
    void build_directAgentAndDirectTask_withExplicitChunkSize_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder()
                        .chunkSize(3)
                        .directAgent(() -> stubAgent("Head Chef"))
                        .directTask((agent, items) -> stubTask("direct task", agent))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("directAgent")
                .hasMessageContaining("directTask")
                .hasMessageContaining("static");
    }

    @Test
    void build_directAgentOnly_withExplicitChunkSize_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder()
                        .chunkSize(3)
                        .directAgent(() -> stubAgent("Head Chef"))
                        .build())
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void build_directAgentAndDirectTask_withDefaultStaticMode_throwsValidationException() {
        // No strategy set = default static (chunkSize=5); direct factories must not be accepted
        assertThatThrownBy(() -> baseBuilder()
                        .directAgent(() -> stubAgent("Head Chef"))
                        .directTask((agent, items) -> stubTask("direct task", agent))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("directAgent")
                .hasMessageContaining("directTask")
                .hasMessageContaining("static");
    }

    // ========================
    // valid combinations
    // ========================

    @Test
    void build_bothDirectFactories_inAdaptiveMode_succeeds() {
        MapReduceEnsemble<String> mre = adaptiveBuilder()
                .directAgent(() -> stubAgent("Head Chef"))
                .directTask((agent, items) -> stubTask("direct: " + items, agent))
                .build();
        assertThat(mre).isNotNull();
        assertThat(mre.isAdaptiveMode()).isTrue();
    }

    @Test
    void build_inputEstimatorAlone_noDirectFactories_inAdaptiveMode_succeeds() {
        // inputEstimator has no pairing constraint; it can be set independently
        MapReduceEnsemble<String> mre = adaptiveBuilder()
                .inputEstimator(item -> item.length() + " chars: " + item)
                .build();
        assertThat(mre).isNotNull();
    }

    @Test
    void build_inputEstimatorWithBothDirectFactories_inAdaptiveMode_succeeds() {
        MapReduceEnsemble<String> mre = adaptiveBuilder()
                .directAgent(() -> stubAgent("Head Chef"))
                .directTask((agent, items) -> stubTask("direct: " + items, agent))
                .inputEstimator(item -> item)
                .build();
        assertThat(mre).isNotNull();
    }

    @Test
    void build_inputEstimatorAlone_inStaticMode_succeeds() {
        // inputEstimator has no constraint against static mode either
        MapReduceEnsemble<String> mre =
                baseBuilder().chunkSize(3).inputEstimator(item -> item).build();
        assertThat(mre).isNotNull();
    }

    // ========================
    // Helpers
    // ========================

    /**
     * Base builder with required fields only (no strategy set = default static mode).
     */
    private MapReduceEnsemble.Builder<String> baseBuilder() {
        return MapReduceEnsemble.<String>builder()
                .items(List.of("A", "B", "C"))
                .mapAgent(item -> stubAgent("M-" + item))
                .mapTask((item, agent) -> stubTask("map " + item, agent))
                .reduceAgent(() -> stubAgent("R-" + counter.incrementAndGet()))
                .reduceTask((agent, chunk) -> stubReduceTask(agent, chunk));
    }

    /**
     * Builder pre-configured for adaptive mode with {@code targetTokenBudget}.
     */
    private MapReduceEnsemble.Builder<String> adaptiveBuilder() {
        return baseBuilder().targetTokenBudget(8_000);
    }

    private Agent stubAgent(String role) {
        return Agent.builder().role(role).goal("goal").llm(stub).build();
    }

    private Task stubTask(String description, Agent agent) {
        return Task.builder()
                .description(description)
                .expectedOutput("output of " + description)
                .agent(agent)
                .build();
    }

    private Task stubReduceTask(Agent agent, List<Task> chunk) {
        return Task.builder()
                .description("reduce-" + counter.incrementAndGet())
                .expectedOutput("reduced output")
                .agent(agent)
                .context(chunk)
                .build();
    }

    static class NoOpChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new UnsupportedOperationException("NoOpChatModel must not be called in unit tests");
        }
    }
}
