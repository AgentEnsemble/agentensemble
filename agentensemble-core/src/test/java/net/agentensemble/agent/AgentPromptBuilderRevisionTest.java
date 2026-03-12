package net.agentensemble.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.agentensemble.Task;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code ## Revision Instructions} section injected by
 * {@link AgentPromptBuilder#buildUserPrompt} when a task has revision feedback set.
 */
class AgentPromptBuilderRevisionTest {

    private TaskOutput taskOutput(String raw) {
        return TaskOutput.builder()
                .agentRole("Researcher")
                .taskDescription("Prior task")
                .raw(raw)
                .completedAt(Instant.now())
                .duration(Duration.ofSeconds(1))
                .toolCallCount(0)
                .build();
    }

    // ========================
    // Revision section present
    // ========================

    @Test
    void revisionFeedback_injectsRevisionInstructionsSection() {
        Task task = Task.builder()
                .description("Research quantum computing")
                .expectedOutput("A detailed report")
                .handler(ctx -> null) // not executed in prompt building
                .build()
                .withRevisionFeedback("Need more sources", null, 1);

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        assertThat(prompt).contains("## Revision Instructions");
        assertThat(prompt).contains("Need more sources");
    }

    @Test
    void revisionFeedback_showsAttemptNumberInHeader() {
        Task task = Task.builder()
                .description("Research quantum computing")
                .expectedOutput("A report")
                .handler(ctx -> null)
                .build()
                .withRevisionFeedback("Expand section 2", null, 2);

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        assertThat(prompt).contains("## Revision Instructions (Attempt 3)");
    }

    @Test
    void revisionFeedback_includesPriorOutputWhenPresent() {
        Task task = Task.builder()
                .description("Research topic")
                .expectedOutput("Report")
                .handler(ctx -> null)
                .build()
                .withRevisionFeedback("More depth needed", "Previous attempt output text", 1);

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        assertThat(prompt).contains("### Previous Output");
        assertThat(prompt).contains("Previous attempt output text");
    }

    @Test
    void revisionFeedback_noPriorOutput_doesNotShowPreviousOutputSection() {
        Task task = Task.builder()
                .description("Research topic")
                .expectedOutput("Report")
                .handler(ctx -> null)
                .build()
                .withRevisionFeedback("More depth needed", null, 1);

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        assertThat(prompt).doesNotContain("### Previous Output");
    }

    @Test
    void revisionFeedback_appearsBeforeTaskSection() {
        Task task = Task.builder()
                .description("Research topic")
                .expectedOutput("Report")
                .handler(ctx -> null)
                .build()
                .withRevisionFeedback("Feedback here", null, 1);

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        int revisionIdx = prompt.indexOf("## Revision Instructions");
        int taskIdx = prompt.indexOf("## Task");
        assertThat(revisionIdx).isLessThan(taskIdx);
    }

    @Test
    void revisionFeedback_includesFeedbackLabel() {
        Task task = Task.builder()
                .description("Do something")
                .expectedOutput("Result")
                .handler(ctx -> null)
                .build()
                .withRevisionFeedback("Be more concise", null, 0);

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        assertThat(prompt).contains("### Feedback");
        assertThat(prompt).contains("Be more concise");
    }

    // ========================
    // Revision section absent
    // ========================

    @Test
    void noRevisionFeedback_doesNotShowRevisionSection() {
        Task task = Task.builder()
                .description("Research topic")
                .expectedOutput("Report")
                .handler(ctx -> null)
                .build();

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        assertThat(prompt).doesNotContain("## Revision Instructions");
        assertThat(prompt).doesNotContain("### Feedback");
    }

    @Test
    void emptyRevisionFeedback_doesNotShowRevisionSection() {
        // withRevisionFeedback("", ...) -- empty string is treated the same as null (blank).
        Task task = Task.builder()
                .description("Research topic")
                .expectedOutput("Report")
                .handler(ctx -> null)
                .build()
                .withRevisionFeedback("", null, 0);

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        // Empty feedback string should not trigger the section (isBlank() check).
        assertThat(prompt).doesNotContain("## Revision Instructions");
    }

    // ========================
    // Task section still present
    // ========================

    @Test
    void revisionFeedback_taskSectionStillPresent() {
        Task task = Task.builder()
                .description("Research quantum computing")
                .expectedOutput("Report")
                .handler(ctx -> null)
                .build()
                .withRevisionFeedback("More citations", null, 1);

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        assertThat(prompt).contains("## Task");
        assertThat(prompt).contains("Research quantum computing");
        assertThat(prompt).contains("## Expected Output");
    }
}
