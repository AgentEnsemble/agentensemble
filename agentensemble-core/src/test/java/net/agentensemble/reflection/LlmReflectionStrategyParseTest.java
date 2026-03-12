package net.agentensemble.reflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import net.agentensemble.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the structured response parsing logic in {@link LlmReflectionStrategy}.
 *
 * <p>Uses a mock LLM to test the full strategy round-trip, and exercises the internal
 * {@code parse()} method directly for detailed parsing validation.
 */
class LlmReflectionStrategyParseTest {

    private static final Task TASK = Task.builder()
            .description("Write a quarterly report")
            .expectedOutput("A structured PDF-ready report")
            .build();

    private LlmReflectionStrategy strategy;
    private ChatModel mockModel;

    @BeforeEach
    void setUp() {
        mockModel = mock(ChatModel.class);
        strategy = new LlmReflectionStrategy(mockModel);
    }

    @Test
    void parse_extractsRefinedDescription() {
        String response =
                """
                REFINED_DESCRIPTION:
                Write a detailed quarterly report with executive summary and KPI tables

                REFINED_EXPECTED_OUTPUT:
                A structured PDF-ready report with executive summary

                OBSERVATIONS:
                - Description lacked specificity

                SUGGESTIONS:
                - Add KPI requirements
                """;

        ReflectionInput input = ReflectionInput.firstRun(TASK, "Some output");
        TaskReflection result = strategy.parse(response, input);

        assertThat(result.refinedDescription()).contains("Write a detailed quarterly report with executive summary");
    }

    @Test
    void parse_extractsRefinedExpectedOutput() {
        String response =
                """
                REFINED_DESCRIPTION:
                Improved description

                REFINED_EXPECTED_OUTPUT:
                A well-structured PDF with charts and executive summary

                OBSERVATIONS:
                - Output format unclear

                SUGGESTIONS:
                - Specify chart types
                """;

        ReflectionInput input = ReflectionInput.firstRun(TASK, "Some output");
        TaskReflection result = strategy.parse(response, input);

        assertThat(result.refinedExpectedOutput()).contains("charts and executive summary");
    }

    @Test
    void parse_extractsBulletObservations() {
        String response =
                """
                REFINED_DESCRIPTION:
                Improved description

                REFINED_EXPECTED_OUTPUT:
                Improved output spec

                OBSERVATIONS:
                - First observation
                - Second observation

                SUGGESTIONS:
                - A suggestion
                """;

        ReflectionInput input = ReflectionInput.firstRun(TASK, "Some output");
        TaskReflection result = strategy.parse(response, input);

        assertThat(result.observations()).containsExactly("First observation", "Second observation");
    }

    @Test
    void parse_extractsBulletSuggestions() {
        String response =
                """
                REFINED_DESCRIPTION:
                Improved description

                REFINED_EXPECTED_OUTPUT:
                Improved output spec

                OBSERVATIONS:
                - An observation

                SUGGESTIONS:
                - Add section headers
                - Include page numbers
                """;

        ReflectionInput input = ReflectionInput.firstRun(TASK, "Some output");
        TaskReflection result = strategy.parse(response, input);

        assertThat(result.suggestions()).containsExactly("Add section headers", "Include page numbers");
    }

    @Test
    void parse_withMissingRefinedDescription_fallsBackToOriginal() {
        String response =
                """
                REFINED_EXPECTED_OUTPUT:
                Improved output

                OBSERVATIONS:
                - Something

                SUGGESTIONS:
                - Something
                """;

        ReflectionInput input = ReflectionInput.firstRun(TASK, "Some output");
        TaskReflection result = strategy.parse(response, input);

        // Falls back to the original task description
        assertThat(result.refinedDescription()).isEqualTo(TASK.getDescription());
    }

    @Test
    void parse_setsRunCountToOneForFirstRun() {
        String response =
                """
                REFINED_DESCRIPTION:
                Improved description

                REFINED_EXPECTED_OUTPUT:
                Improved output spec

                OBSERVATIONS:
                - Observation

                SUGGESTIONS:
                - Suggestion
                """;

        ReflectionInput input = ReflectionInput.firstRun(TASK, "Some output");
        TaskReflection result = strategy.parse(response, input);

        assertThat(result.runCount()).isEqualTo(1);
    }

    @Test
    void parse_withPriorReflection_incrementsRunCount() {
        TaskReflection prior = TaskReflection.ofFirstRun("old desc", "old output", List.of(), List.of());

        String response =
                """
                REFINED_DESCRIPTION:
                Further improved description

                REFINED_EXPECTED_OUTPUT:
                Further improved output spec

                OBSERVATIONS:
                - New observation

                SUGGESTIONS:
                - New suggestion
                """;

        ReflectionInput input = ReflectionInput.withPrior(TASK, "Some output", prior);
        TaskReflection result = strategy.parse(response, input);

        assertThat(result.runCount()).isEqualTo(2);
    }

    @Test
    void reflect_withLlmFailure_returnsFallbackWithObservation() {
        when(mockModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM unavailable"));

        ReflectionInput input = ReflectionInput.firstRun(TASK, "Some output");
        TaskReflection result = strategy.reflect(input);

        // Falls back gracefully -- does not throw
        assertThat(result).isNotNull();
        assertThat(result.runCount()).isEqualTo(1);
        assertThat(result.observations()).anyMatch(obs -> obs.contains("LLM call failed"));
        // Original task definition preserved in fallback
        assertThat(result.refinedDescription()).isEqualTo(TASK.getDescription());
    }

    @Test
    void reflect_withWellFormedResponse_producesValidReflection() {
        String wellFormedResponse =
                """
                REFINED_DESCRIPTION:
                Write a comprehensive quarterly report focusing on KPIs and trends

                REFINED_EXPECTED_OUTPUT:
                A PDF-ready report with executive summary, KPI dashboard, and actionable insights

                OBSERVATIONS:
                - Original description lacked KPI focus
                - Expected output format was underspecified

                SUGGESTIONS:
                - Specify at least 3 KPI categories to analyze
                - Add word count guidance for each section
                """;

        AiMessage aiMessage = AiMessage.from(wellFormedResponse);
        ChatResponse chatResponse = mock(ChatResponse.class);
        when(chatResponse.aiMessage()).thenReturn(aiMessage);
        when(mockModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        ReflectionInput input = ReflectionInput.firstRun(TASK, "A quarterly report with some data");
        TaskReflection result = strategy.reflect(input);

        assertThat(result.refinedDescription()).contains("KPIs and trends");
        assertThat(result.refinedExpectedOutput()).contains("executive summary");
        assertThat(result.observations()).hasSize(2);
        assertThat(result.suggestions()).hasSize(2);
        assertThat(result.runCount()).isEqualTo(1);
    }

    @Test
    void parse_withStarBullets_extractsThemToo() {
        String response =
                """
                REFINED_DESCRIPTION:
                Improved description

                REFINED_EXPECTED_OUTPUT:
                Improved output spec

                OBSERVATIONS:
                * Star observation 1
                * Star observation 2

                SUGGESTIONS:
                * Star suggestion
                """;

        ReflectionInput input = ReflectionInput.firstRun(TASK, "Some output");
        TaskReflection result = strategy.parse(response, input);

        assertThat(result.observations()).containsExactly("Star observation 1", "Star observation 2");
        assertThat(result.suggestions()).containsExactly("Star suggestion");
    }
}
