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
}
