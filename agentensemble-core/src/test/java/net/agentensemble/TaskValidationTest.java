package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThat;
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
    // Validation: agent (optional in v2)
    // ========================

    @Test
    void testBuild_withNullAgent_succeeds() {
        // Agent is optional in v2 -- tasks without an explicit agent are synthesized at runtime
        var task = Task.builder()
                .description("Research task")
                .expectedOutput("A report")
                .agent(null)
                .build();

        assertThat(task.getAgent()).isNull();
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

    // ========================
    // Validation: maxIterations (v2)
    // ========================

    @Test
    void testBuild_withMaxIterations_zero_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description("Task")
                        .expectedOutput("Output")
                        .maxIterations(0)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxIterations");
    }

    @Test
    void testBuild_withMaxIterations_negative_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description("Task")
                        .expectedOutput("Output")
                        .maxIterations(-5)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxIterations");
    }

    @Test
    void testBuild_withMaxIterations_positive_succeeds() {
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .maxIterations(10)
                .build();

        assertThat(task.getMaxIterations()).isEqualTo(10);
    }

    @Test
    void testBuild_withMaxIterations_null_succeeds() {
        // null means "use synthesis default"
        var task = Task.builder().description("Task").expectedOutput("Output").build();

        assertThat(task.getMaxIterations()).isNull();
    }

    // ========================
    // Validation: tools (v2)
    // ========================

    @Test
    void testBuild_withNullToolInList_throwsValidation() {
        // Use an ArrayList that permits null entries (unlike List.of)
        java.util.List<Object> toolsWithNull = new java.util.ArrayList<>();
        toolsWithNull.add(null);
        assertThatThrownBy(() -> Task.builder()
                        .description("Task")
                        .expectedOutput("Output")
                        .tools(toolsWithNull)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("tool");
    }

    @Test
    void testBuild_withInvalidToolObject_throwsValidation() {
        assertThatThrownBy(() -> Task.builder()
                        .description("Task")
                        .expectedOutput("Output")
                        .tools(List.of("not-a-tool"))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("AgentTool");
    }
}
