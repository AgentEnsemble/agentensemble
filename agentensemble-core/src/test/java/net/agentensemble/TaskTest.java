package net.agentensemble;

import dev.langchain4j.model.chat.ChatModel;
import net.agentensemble.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TaskTest {

    private final Agent testAgent = Agent.builder()
            .role("Researcher")
            .goal("Find information")
            .llm(mock(ChatModel.class))
            .build();

    // ========================
    // Build success cases
    // ========================

    @Test
    void testBuild_withMinimalFields_succeeds() {
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A detailed report")
                .agent(testAgent)
                .build();

        assertThat(task.getDescription()).isEqualTo("Research AI trends");
        assertThat(task.getExpectedOutput()).isEqualTo("A detailed report");
        assertThat(task.getAgent()).isSameAs(testAgent);
        assertThat(task.getContext()).isEmpty();
    }

    @Test
    void testBuild_withAllFields_succeeds() {
        var contextTask = Task.builder()
                .description("Gather data")
                .expectedOutput("Raw data")
                .agent(testAgent)
                .build();

        var task = Task.builder()
                .description("Analyze AI trends")
                .expectedOutput("An analysis report")
                .agent(testAgent)
                .context(List.of(contextTask))
                .build();

        assertThat(task.getDescription()).isEqualTo("Analyze AI trends");
        assertThat(task.getExpectedOutput()).isEqualTo("An analysis report");
        assertThat(task.getContext()).hasSize(1);
        assertThat(task.getContext().get(0)).isSameAs(contextTask);
    }

    @Test
    void testBuild_withMultipleContextTasks_succeeds() {
        var ctx1 = Task.builder().description("Task 1").expectedOutput("Out 1").agent(testAgent).build();
        var ctx2 = Task.builder().description("Task 2").expectedOutput("Out 2").agent(testAgent).build();

        var task = Task.builder()
                .description("Main task")
                .expectedOutput("Final output")
                .agent(testAgent)
                .context(List.of(ctx1, ctx2))
                .build();

        assertThat(task.getContext()).hasSize(2);
    }

    @Test
    void testBuild_withTemplateVariables_succeeds() {
        // Template variables in description/expectedOutput are valid - resolved at ensemble.run() time
        var task = Task.builder()
                .description("Research {topic} developments in {year}")
                .expectedOutput("A report on {topic}")
                .agent(testAgent)
                .build();

        assertThat(task.getDescription()).contains("{topic}");
        assertThat(task.getExpectedOutput()).contains("{topic}");
    }

    // ========================
    // Default values
    // ========================

    @Test
    void testDefaultValues_contextIsEmpty() {
        var task = Task.builder()
                .description("Research task")
                .expectedOutput("A report")
                .agent(testAgent)
                .build();

        assertThat(task.getContext()).isNotNull().isEmpty();
    }

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
        // List.of() doesn't allow nulls but we can use Arrays.asList for testing
        var ctx = Task.builder().description("Ctx").expectedOutput("Out").agent(testAgent).build();
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
        // Create a task that we will reuse in its own context via toBuilder
        var baseTask = Task.builder()
                .description("Self-referencing task")
                .expectedOutput("Self output")
                .agent(testAgent)
                .build();

        // toBuilder produces a task with identical field values -- context.contains() checks value equality
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
    // Immutability
    // ========================

    @Test
    void testContextList_isImmutable() {
        var ctx = Task.builder().description("Ctx").expectedOutput("Out").agent(testAgent).build();
        var task = Task.builder()
                .description("Main")
                .expectedOutput("Result")
                .agent(testAgent)
                .context(List.of(ctx))
                .build();

        assertThat(task.getContext()).isUnmodifiable();
    }

    @Test
    void testContextList_defaultIsImmutable() {
        var task = Task.builder()
                .description("Main")
                .expectedOutput("Result")
                .agent(testAgent)
                .build();

        assertThat(task.getContext()).isUnmodifiable();
    }

    // ========================
    // toBuilder
    // ========================

    @Test
    void testToBuilder_createsModifiedCopy() {
        var original = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A detailed report")
                .agent(testAgent)
                .build();

        var ctx = Task.builder().description("Ctx").expectedOutput("Out").agent(testAgent).build();
        var modified = original.toBuilder()
                .context(List.of(ctx))
                .build();

        assertThat(modified.getDescription()).isEqualTo("Research AI trends");
        assertThat(modified.getContext()).hasSize(1);
        assertThat(original.getContext()).isEmpty();
    }

    // ========================
    // outputType defaults and happy paths
    // ========================

    record SimpleReport(String title, String summary) {}

    @Test
    void testBuild_withoutOutputType_defaultsToNull() {
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A report")
                .agent(testAgent)
                .build();

        assertThat(task.getOutputType()).isNull();
    }

    @Test
    void testBuild_withOutputType_record_succeeds() {
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A structured report")
                .agent(testAgent)
                .outputType(SimpleReport.class)
                .build();

        assertThat(task.getOutputType()).isEqualTo(SimpleReport.class);
    }

    @Test
    void testBuild_withOutputType_string_succeeds() {
        var task = Task.builder()
                .description("Summarize this")
                .expectedOutput("A summary")
                .agent(testAgent)
                .outputType(String.class)
                .build();

        assertThat(task.getOutputType()).isEqualTo(String.class);
    }

    // ========================
    // outputType validation
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
    // maxOutputRetries defaults and validation
    // ========================

    @Test
    void testBuild_withoutMaxOutputRetries_defaultsToThree() {
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .build();

        assertThat(task.getMaxOutputRetries()).isEqualTo(3);
    }

    @Test
    void testBuild_withMaxOutputRetries_zero_succeeds() {
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .maxOutputRetries(0)
                .build();

        assertThat(task.getMaxOutputRetries()).isZero();
    }

    @Test
    void testBuild_withMaxOutputRetries_positive_succeeds() {
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .maxOutputRetries(5)
                .build();

        assertThat(task.getMaxOutputRetries()).isEqualTo(5);
    }

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
    // outputType with toBuilder
    // ========================

    @Test
    void testToBuilder_preservesOutputType() {
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .outputType(SimpleReport.class)
                .maxOutputRetries(2)
                .build();

        var copy = task.toBuilder().description("Updated task").build();

        assertThat(copy.getOutputType()).isEqualTo(SimpleReport.class);
        assertThat(copy.getMaxOutputRetries()).isEqualTo(2);
    }
}
