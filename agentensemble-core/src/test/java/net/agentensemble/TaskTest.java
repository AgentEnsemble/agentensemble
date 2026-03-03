package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for Task builder, defaults, immutability, toBuilder, and outputType happy paths.
 *
 * Validation error cases are covered by TaskValidationTest.
 */
class TaskTest {

    private final Agent testAgent = Agent.builder()
            .role("Researcher")
            .goal("Find information")
            .llm(mock(ChatModel.class))
            .build();

    record SimpleReport(String title, String summary) {}

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
        var ctx1 = Task.builder()
                .description("Task 1")
                .expectedOutput("Out 1")
                .agent(testAgent)
                .build();
        var ctx2 = Task.builder()
                .description("Task 2")
                .expectedOutput("Out 2")
                .agent(testAgent)
                .build();

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
        // Template variables in description/expectedOutput are valid -- resolved at ensemble.run() time
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
    void testBuild_withoutMaxOutputRetries_defaultsToThree() {
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .build();

        assertThat(task.getMaxOutputRetries()).isEqualTo(3);
    }

    // ========================
    // Immutability
    // ========================

    @Test
    void testContextList_isImmutable() {
        var ctx = Task.builder()
                .description("Ctx")
                .expectedOutput("Out")
                .agent(testAgent)
                .build();
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

        var ctx = Task.builder()
                .description("Ctx")
                .expectedOutput("Out")
                .agent(testAgent)
                .build();
        var modified = original.toBuilder().context(List.of(ctx)).build();

        assertThat(modified.getDescription()).isEqualTo("Research AI trends");
        assertThat(modified.getContext()).hasSize(1);
        assertThat(original.getContext()).isEmpty();
    }

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

    // ========================
    // outputType happy paths
    // ========================

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
    // maxOutputRetries happy paths
    // ========================

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
}
