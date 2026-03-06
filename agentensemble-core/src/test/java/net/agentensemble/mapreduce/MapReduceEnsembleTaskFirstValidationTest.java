package net.agentensemble.mapreduce;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.exception.ValidationException;
import org.junit.jupiter.api.Test;

/**
 * Validation tests for the task-first API of {@link MapReduceEnsemble}.
 *
 * <p>Tests cover mutual exclusivity between task-first and agent-first factory styles,
 * zero-ceremony factory parameter validation, and error cases for short-circuit
 * in task-first mode.
 */
class MapReduceEnsembleTaskFirstValidationTest {

    private static final ChatModel STUB = new NoOpChatModel();

    // ========================
    // No map factory at all
    // ========================

    @Test
    void noMapFactory_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .chatLanguageModel(STUB)
                        .items(List.of("A"))
                        .reduceTask(chunkTasks -> Task.builder()
                                .description("Combine")
                                .expectedOutput("Combined")
                                .context(chunkTasks)
                                .build())
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("mapTask");
    }

    // ========================
    // No reduce factory at all
    // ========================

    @Test
    void noReduceFactory_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .chatLanguageModel(STUB)
                        .items(List.of("A"))
                        .mapTask(item -> Task.of("Analyse " + item))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reduceTask");
    }

    // ========================
    // Mixing task-first and agent-first (map phase)
    // ========================

    @Test
    void taskFirstMap_combinedWithAgentFirstMapTask_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .chatLanguageModel(STUB)
                        .items(List.of("A"))
                        // Task-first AND agent-first map task -- ambiguous
                        .mapTask(item -> Task.of("Analyse " + item))
                        .mapTask((item, agent) -> Task.builder()
                                .description("Also map " + item)
                                .expectedOutput("Out")
                                .agent(agent)
                                .build())
                        .reduceTask(chunkTasks -> Task.builder()
                                .description("Combine")
                                .expectedOutput("Combined")
                                .context(chunkTasks)
                                .build())
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Ambiguous map");
    }

    @Test
    void taskFirstMap_combinedWithAgentFactory_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .chatLanguageModel(STUB)
                        .items(List.of("A"))
                        // Task-first map AND explicit agent factory -- ambiguous
                        .mapTask(item -> Task.of("Analyse " + item))
                        .mapAgent(item -> Agent.builder()
                                .role("Agent")
                                .goal("Do")
                                .llm(STUB)
                                .build())
                        .reduceTask(chunkTasks -> Task.builder()
                                .description("Combine")
                                .expectedOutput("Combined")
                                .context(chunkTasks)
                                .build())
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Ambiguous map");
    }

    @Test
    void agentFirstMap_withoutMapAgent_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .items(List.of("A"))
                        // BiFunction mapTask but no mapAgent
                        .mapTask((item, agent) -> Task.builder()
                                .description("Map " + item)
                                .expectedOutput("Out")
                                .agent(agent)
                                .build())
                        .reduceTask(chunkTasks -> Task.builder()
                                .description("Combine")
                                .expectedOutput("Combined")
                                .context(chunkTasks)
                                .build())
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("mapAgent");
    }

    @Test
    void agentFirstMap_withoutMapTask_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .items(List.of("A"))
                        // mapAgent but no BiFunction mapTask
                        .mapAgent(item ->
                                Agent.builder().role("M").goal("Do").llm(STUB).build())
                        .reduceTask(chunkTasks -> Task.builder()
                                .description("Combine")
                                .expectedOutput("Combined")
                                .context(chunkTasks)
                                .build())
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("mapTask");
    }

    // ========================
    // Mixing task-first and agent-first (reduce phase)
    // ========================

    @Test
    void taskFirstReduce_combinedWithAgentFirstReduceTask_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .chatLanguageModel(STUB)
                        .items(List.of("A"))
                        .mapTask(item -> Task.of("Analyse " + item))
                        // Task-first AND agent-first reduce task -- ambiguous
                        .reduceTask(chunkTasks -> Task.builder()
                                .description("Combine")
                                .expectedOutput("Combined")
                                .context(chunkTasks)
                                .build())
                        .reduceTask((agent, chunkTasks) -> Task.builder()
                                .description("Also combine")
                                .expectedOutput("Also combined")
                                .agent(agent)
                                .context(chunkTasks)
                                .build())
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Ambiguous reduce");
    }

    @Test
    void taskFirstReduce_combinedWithReduceAgent_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .chatLanguageModel(STUB)
                        .items(List.of("A"))
                        .mapTask(item -> Task.of("Analyse " + item))
                        // Task-first reduce AND explicit agent factory -- ambiguous
                        .reduceTask(chunkTasks -> Task.builder()
                                .description("Combine")
                                .expectedOutput("Combined")
                                .context(chunkTasks)
                                .build())
                        .reduceAgent(() -> Agent.builder()
                                .role("R")
                                .goal("Reduce")
                                .llm(STUB)
                                .build())
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Ambiguous reduce");
    }

    @Test
    void agentFirstReduce_withoutReduceAgent_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .items(List.of("A"))
                        .mapTask(item -> Task.of("Analyse " + item))
                        // BiFunction reduceTask but no reduceAgent
                        .reduceTask((agent, chunkTasks) -> Task.builder()
                                .description("Combine")
                                .expectedOutput("Combined")
                                .agent(agent)
                                .context(chunkTasks)
                                .build())
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reduceAgent");
    }

    @Test
    void agentFirstReduce_withoutReduceTask_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .items(List.of("A"))
                        .mapTask(item -> Task.of("Analyse " + item))
                        // reduceAgent but no BiFunction reduceTask
                        .reduceAgent(() -> Agent.builder()
                                .role("R")
                                .goal("Reduce")
                                .llm(STUB)
                                .build())
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reduceTask");
    }

    // ========================
    // Task-first direct (short-circuit)
    // ========================

    @Test
    void taskFirstDirect_staticMode_throwsValidationException() {
        // directTask(Function) is only valid in adaptive mode, not static
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .chatLanguageModel(STUB)
                        .items(List.of("A"))
                        .mapTask(item -> Task.of("Analyse " + item))
                        .reduceTask(chunkTasks -> Task.builder()
                                .description("Combine")
                                .expectedOutput("Combined")
                                .context(chunkTasks)
                                .build())
                        .directTask(allItems -> Task.of("Handle all at once"))
                        .chunkSize(3) // static mode
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("static");
    }

    @Test
    void taskFirstDirect_combinedWithAgentFirstDirect_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .chatLanguageModel(STUB)
                        .items(List.of("A"))
                        .mapTask(item -> Task.of("Analyse " + item))
                        .reduceTask(chunkTasks -> Task.builder()
                                .description("Combine")
                                .expectedOutput("Combined")
                                .context(chunkTasks)
                                .build())
                        // Both direct styles -- ambiguous
                        .directTask(allItems -> Task.of("Handle all"))
                        .directAgent(() -> Agent.builder()
                                .role("D")
                                .goal("Direct")
                                .llm(STUB)
                                .build())
                        .targetTokenBudget(1000)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Ambiguous direct");
    }

    @Test
    void agentFirstDirect_onlyAgentNoTask_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .chatLanguageModel(STUB)
                        .items(List.of("A"))
                        .mapTask(item -> Task.of("Analyse " + item))
                        .reduceTask(chunkTasks -> Task.builder()
                                .description("Combine")
                                .expectedOutput("Combined")
                                .context(chunkTasks)
                                .build())
                        .directAgent(() -> Agent.builder()
                                .role("D")
                                .goal("Direct")
                                .llm(STUB)
                                .build())
                        // No directTask -- incomplete agent-first short-circuit
                        .targetTokenBudget(1000)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("directAgent");
    }

    // ========================
    // Zero-ceremony factory validation
    // ========================

    @Test
    void zeroCeremony_nullModel_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.of(null, List.of("A"), "Analyse", "Combine"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("model");
    }

    @Test
    void zeroCeremony_nullItems_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.of(STUB, null, "Analyse", "Combine"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("items");
    }

    @Test
    void zeroCeremony_emptyItems_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.of(STUB, List.of(), "Analyse", "Combine"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("items");
    }

    @Test
    void zeroCeremony_blankMapDescription_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.of(STUB, List.of("A"), "  ", "Combine"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("mapDescription");
    }

    @Test
    void zeroCeremony_blankReduceDescription_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.of(STUB, List.of("A"), "Analyse", "  "))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reduceDescription");
    }

    @Test
    void zeroCeremony_nullMapDescription_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.of(STUB, List.of("A"), null, "Combine"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("mapDescription");
    }

    @Test
    void zeroCeremony_nullReduceDescription_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.of(STUB, List.of("A"), "Analyse", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reduceDescription");
    }

    // ========================
    // Helpers
    // ========================

    static class NoOpChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new UnsupportedOperationException("NoOpChatModel must not be called in validation tests");
        }
    }
}
