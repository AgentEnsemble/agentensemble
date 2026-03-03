package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import net.agentensemble.exception.ValidationException;
import org.junit.jupiter.api.Test;

/**
 * Validation tests for Task builder.
 *
 * Builder defaults, immutability, and toBuilder are covered by TaskTest.
 */
class TaskValidationTest {

    private final Agent testAgent = Agent.builder()
            .role("Researcher")
            .goal("Find information")
            .llm(mock(ChatModel.class))
            .build();

    record SimpleReport(String title, String summary) {}

    // ========================
    // Validation: description
    // ========================

    @Test
    void testBuild_withNullDescription_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description(null)
                        .expectedOutput("A report")
                        .agent(testAgent)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("description");
    }

    @Test
    void testBuild_withBlankDescription_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description("   ")
                        .expectedOutput("A report")
                        .agent(testAgent)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("description");
    }

    @Test
    void testBuild_withEmptyDescription_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description("")
                        .expectedOutput("A report")
                        .agent(testAgent)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("description");
    }

    // ========================
    // Validation: expectedOutput
    // ========================

    @Test
    void testBuild_withNullExpectedOutput_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description("Research task")
                        .expectedOutput(null)
                        .agent(testAgent)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("expectedOutput");
    }

    @Test
    void testBuild_withBlankExpectedOutput_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description("Research task")
                        .expectedOutput("  ")
                        .agent(testAgent)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("expectedOutput");
    }

    // ========================
    // Validation: agent
    // ========================

    @Test
    void testBuild_withNullAgent_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description("Research task")
                        .expectedOutput("A report")
                        .agent(null)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("agent");
    }

    // ========================
    // Validation: context
    // ========================

    @Test
    void testBuild_withNullContextElement_throwsValidation() {
        var ctx = Task.builder()
                .description("Ctx")
                .expectedOutput("Out")
                .agent(testAgent)
                .build();
        assertThatThrownBy(() -> Task.builder()
                        .description("Main task")
                        .expectedOutput("Output")
                        .agent(testAgent)
                        .context(java.util.Arrays.asList(ctx, null))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("context")
                .hasMessageContaining("null");
    }

    @Test
    void testBuild_withSelfReference_throwsValidation() {
        var baseTask = Task.builder()
                .description("Self-referencing task")
                .expectedOutput("Self output")
                .agent(testAgent)
                .build();

        assertThatThrownBy(() -> Task.builder()
                        .description(baseTask.getDescription())
                        .expectedOutput(baseTask.getExpectedOutput())
                        .agent(baseTask.getAgent())
                        .context(List.of(baseTask))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot reference itself");
    }

    // ========================
    // Validation: outputType
    // ========================

    @Test
    void testBuild_withOutputType_primitive_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description("Task")
                        .expectedOutput("Output")
                        .agent(testAgent)
                        .outputType(int.class)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("primitive");
    }

    @Test
    void testBuild_withOutputType_void_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description("Task")
                        .expectedOutput("Output")
                        .agent(testAgent)
                        .outputType(Void.class)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Void");
    }

    @Test
    void testBuild_withOutputType_array_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description("Task")
                        .expectedOutput("Output")
                        .agent(testAgent)
                        .outputType(String[].class)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("array");
    }

    // ========================
    // Validation: maxOutputRetries
    // ========================

    @Test
    void testBuild_withMaxOutputRetries_negative_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description("Task")
                        .expectedOutput("Output")
                        .agent(testAgent)
                        .maxOutputRetries(-1)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxOutputRetries");
    }
}
