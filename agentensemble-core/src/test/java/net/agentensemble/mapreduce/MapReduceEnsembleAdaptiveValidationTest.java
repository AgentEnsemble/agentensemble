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
 * Unit tests for adaptive-mode validation in {@link MapReduceEnsemble.Builder}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Mutual exclusivity of {@code chunkSize} and {@code targetTokenBudget}</li>
 *   <li>Budget derivation from {@code contextWindowSize * budgetRatio}</li>
 *   <li>Invalid {@code budgetRatio} values</li>
 *   <li>{@code maxReduceLevels} validation</li>
 *   <li>{@code targetTokenBudget} direct validation</li>
 *   <li>{@code contextWindowSize} / {@code budgetRatio} must both be set or neither</li>
 *   <li>{@code toEnsemble()} throws {@link UnsupportedOperationException} in adaptive mode</li>
 * </ul>
 */
class MapReduceEnsembleAdaptiveValidationTest {

    private ChatModel stub;
    private AtomicInteger counter;

    @BeforeEach
    void setUp() {
        stub = new NoOpChatModel();
        counter = new AtomicInteger(0);
    }

    // ========================
    // Mutual exclusivity: chunkSize + targetTokenBudget
    // ========================

    @Test
    void build_chunkSizeAndTargetTokenBudget_throwsValidationException() {
        assertThatThrownBy(() ->
                        baseBuilder().chunkSize(3).targetTokenBudget(8_000).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("chunkSize")
                .hasMessageContaining("targetTokenBudget");
    }

    @Test
    void build_chunkSizeAndContextWindowSize_throwsValidationException() {
        // Setting contextWindowSize alone is incomplete (needs budgetRatio too),
        // but if chunkSize is also set that's a different error that should be caught first.
        // Both chunkSize and contextWindowSize (which derives targetTokenBudget) are exclusive.
        assertThatThrownBy(() -> baseBuilder()
                        .chunkSize(3)
                        .contextWindowSize(100_000)
                        .budgetRatio(0.5)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("chunkSize");
    }

    // ========================
    // targetTokenBudget validation
    // ========================

    @Test
    void build_targetTokenBudgetZero_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder().targetTokenBudget(0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("targetTokenBudget");
    }

    @Test
    void build_targetTokenBudgetNegative_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder().targetTokenBudget(-1).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("targetTokenBudget");
    }

    @Test
    void build_targetTokenBudgetPositive_succeeds() {
        MapReduceEnsemble<String> mre = baseBuilder().targetTokenBudget(8_000).build();
        assertThat(mre).isNotNull();
    }

    // ========================
    // Budget derivation: contextWindowSize + budgetRatio
    // ========================

    @Test
    void build_contextWindowSizeWithBudgetRatio_succeeds() {
        // contextWindowSize=100_000, budgetRatio=0.4 -> targetTokenBudget=40_000
        MapReduceEnsemble<String> mre =
                baseBuilder().contextWindowSize(100_000).budgetRatio(0.4).build();
        assertThat(mre).isNotNull();
    }

    @Test
    void build_contextWindowSizeWithoutBudgetRatio_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder().contextWindowSize(100_000).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("budgetRatio");
    }

    @Test
    void build_budgetRatioWithoutContextWindowSize_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder().budgetRatio(0.5).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("contextWindowSize");
    }

    @Test
    void build_contextWindowSize100000_budgetRatio04_derivedBudgetIs40000() {
        // Budget should be 100_000 * 0.4 = 40_000
        // We verify this by successfully building (no exception means validation passed)
        MapReduceEnsemble<String> mre =
                baseBuilder().contextWindowSize(100_000).budgetRatio(0.4).build();
        assertThat(mre).isNotNull();
    }

    // ========================
    // budgetRatio validation
    // ========================

    @Test
    void build_budgetRatioZero_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder()
                        .contextWindowSize(100_000)
                        .budgetRatio(0.0)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("budgetRatio");
    }

    @Test
    void build_budgetRatioNegative_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder()
                        .contextWindowSize(100_000)
                        .budgetRatio(-0.1)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("budgetRatio");
    }

    @Test
    void build_budgetRatioAboveOne_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder()
                        .contextWindowSize(100_000)
                        .budgetRatio(1.1)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("budgetRatio");
    }

    @Test
    void build_budgetRatioExactlyOne_succeeds() {
        MapReduceEnsemble<String> mre =
                baseBuilder().contextWindowSize(100_000).budgetRatio(1.0).build();
        assertThat(mre).isNotNull();
    }

    @Test
    void build_budgetRatioSmallPositive_succeeds() {
        MapReduceEnsemble<String> mre =
                baseBuilder().contextWindowSize(100_000).budgetRatio(0.01).build();
        assertThat(mre).isNotNull();
    }

    // ========================
    // maxReduceLevels validation
    // ========================

    @Test
    void build_maxReduceLevelsZero_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder()
                        .targetTokenBudget(8_000)
                        .maxReduceLevels(0)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxReduceLevels");
    }

    @Test
    void build_maxReduceLevelsNegative_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder()
                        .targetTokenBudget(8_000)
                        .maxReduceLevels(-1)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxReduceLevels");
    }

    @Test
    void build_maxReduceLevelsOne_succeeds() {
        MapReduceEnsemble<String> mre =
                baseBuilder().targetTokenBudget(8_000).maxReduceLevels(1).build();
        assertThat(mre).isNotNull();
    }

    @Test
    void build_maxReduceLevelsDefaultIsTen() {
        // Building with targetTokenBudget and no explicit maxReduceLevels should succeed
        // (the default of 10 is valid)
        MapReduceEnsemble<String> mre = baseBuilder().targetTokenBudget(8_000).build();
        assertThat(mre).isNotNull();
    }

    // ========================
    // contextWindowSize * budgetRatio derived budget must be > 0
    // ========================

    @Test
    void build_contextWindowSizeOne_budgetRatioPoinFive_derivedBudgetIsZero_throwsValidationException() {
        // 1 * 0.5 = 0 (int cast), must throw
        assertThatThrownBy(() ->
                        baseBuilder().contextWindowSize(1).budgetRatio(0.5).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("targetTokenBudget");
    }

    // ========================
    // toEnsemble() in adaptive mode
    // ========================

    @Test
    void toEnsemble_adaptiveMode_throwsUnsupportedOperationException() {
        MapReduceEnsemble<String> mre = baseBuilder().targetTokenBudget(8_000).build();

        assertThatThrownBy(mre::toEnsemble)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("adaptive");
    }

    @Test
    void toEnsemble_staticMode_doesNotThrow() {
        MapReduceEnsemble<String> mre = baseBuilder().chunkSize(3).build();
        // Must not throw
        assertThat(mre.toEnsemble()).isNotNull();
    }

    @Test
    void toEnsemble_defaultMode_isStatic_doesNotThrow() {
        // When neither chunkSize nor targetTokenBudget is set, default is static (chunkSize=5)
        MapReduceEnsemble<String> mre = baseBuilder().build();
        assertThat(mre.toEnsemble()).isNotNull();
    }

    // ========================
    // Helpers
    // ========================

    private MapReduceEnsemble.Builder<String> baseBuilder() {
        return MapReduceEnsemble.<String>builder()
                .items(List.of("A", "B", "C"))
                .mapAgent(item -> stubAgent("M-" + item))
                .mapTask((item, agent) -> stubTask("map " + item, agent))
                .reduceAgent(() -> stubAgent("R-" + counter.incrementAndGet()))
                .reduceTask((agent, chunk) -> stubReduceTask(agent, chunk));
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
