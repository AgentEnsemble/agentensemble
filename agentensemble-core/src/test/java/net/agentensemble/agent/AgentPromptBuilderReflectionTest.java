package net.agentensemble.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.agentensemble.Task;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.reflection.TaskReflection;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link AgentPromptBuilder#buildUserPrompt} correctly injects
 * {@link TaskReflection} notes into the user prompt when provided.
 */
class AgentPromptBuilderReflectionTest {

    private static final Task TASK = Task.builder()
            .description("Analyse market trends for Q4")
            .expectedOutput("A structured analysis report")
            .build();

    @Test
    void buildUserPrompt_withoutReflection_omitsReflectionSection() {
        String prompt = AgentPromptBuilder.buildUserPrompt(TASK, List.of(), MemoryContext.disabled(), null, null);

        assertThat(prompt).doesNotContain("Task Improvement Notes");
        assertThat(prompt).contains("## Task");
        assertThat(prompt).contains("Analyse market trends for Q4");
    }

    @Test
    void buildUserPrompt_withReflection_injectsRefinedInstructions() {
        TaskReflection reflection = TaskReflection.ofFirstRun(
                "Analyse Q4 market trends focusing on tech sector with quantitative data",
                "A structured report with executive summary, data tables, and recommendations",
                List.of("Original description lacked sector specificity"),
                List.of("Add sector focus to improve output quality"));

        String prompt = AgentPromptBuilder.buildUserPrompt(TASK, List.of(), MemoryContext.disabled(), null, reflection);

        assertThat(prompt).contains("## Task Improvement Notes (from prior executions)");
        assertThat(prompt).contains("### Refined Instructions");
        assertThat(prompt).contains("Analyse Q4 market trends focusing on tech sector");
        assertThat(prompt).contains("### Output Guidance");
        assertThat(prompt).contains("executive summary, data tables, and recommendations");
    }

    @Test
    void buildUserPrompt_withReflection_includesObservations() {
        TaskReflection reflection = TaskReflection.ofFirstRun(
                "Refined description",
                "Refined output",
                List.of("Output was too verbose", "Missing quantitative data"),
                List.of());

        String prompt = AgentPromptBuilder.buildUserPrompt(TASK, List.of(), MemoryContext.disabled(), null, reflection);

        assertThat(prompt).contains("### Observations");
        assertThat(prompt).contains("Output was too verbose");
        assertThat(prompt).contains("Missing quantitative data");
    }

    @Test
    void buildUserPrompt_withReflection_includesSuggestions() {
        TaskReflection reflection = TaskReflection.ofFirstRun(
                "Refined description",
                "Refined output",
                List.of(),
                List.of("Include specific dates", "Use bullet points for clarity"));

        String prompt = AgentPromptBuilder.buildUserPrompt(TASK, List.of(), MemoryContext.disabled(), null, reflection);

        assertThat(prompt).contains("### Suggestions");
        assertThat(prompt).contains("Include specific dates");
        assertThat(prompt).contains("Use bullet points for clarity");
    }

    @Test
    void buildUserPrompt_withReflection_stillIncludesOriginalTask() {
        TaskReflection reflection =
                TaskReflection.ofFirstRun("Refined description", "Refined output", List.of(), List.of());

        String prompt = AgentPromptBuilder.buildUserPrompt(TASK, List.of(), MemoryContext.disabled(), null, reflection);

        // Reflection augments the original task, doesn't replace it
        assertThat(prompt).contains("## Task");
        assertThat(prompt).contains("Analyse market trends for Q4");
        assertThat(prompt).contains("## Expected Output");
        assertThat(prompt).contains("A structured analysis report");
    }

    @Test
    void buildUserPrompt_withReflection_reflectionAppearsBeforeTask() {
        TaskReflection reflection =
                TaskReflection.ofFirstRun("Refined description", "Refined output", List.of(), List.of());

        String prompt = AgentPromptBuilder.buildUserPrompt(TASK, List.of(), MemoryContext.disabled(), null, reflection);

        int reflectionIdx = prompt.indexOf("Task Improvement Notes");
        int taskIdx = prompt.indexOf("## Task\n");

        assertThat(reflectionIdx).isLessThan(taskIdx);
    }

    @Test
    void buildUserPrompt_withReflectionAndContextOutputs_includesBoth() {
        TaskReflection reflection =
                TaskReflection.ofFirstRun("Refined description", "Refined output", List.of(), List.of());

        TaskOutput contextOutput = TaskOutput.builder()
                .raw("Prior task result")
                .taskDescription("Prior research task")
                .agentRole("Researcher")
                .completedAt(Instant.now())
                .duration(Duration.ZERO)
                .toolCallCount(0)
                .build();

        String prompt = AgentPromptBuilder.buildUserPrompt(
                TASK, List.of(contextOutput), MemoryContext.disabled(), null, reflection);

        assertThat(prompt).contains("## Context from Previous Tasks");
        assertThat(prompt).contains("Prior task result");
        assertThat(prompt).contains("Task Improvement Notes");
    }

    @Test
    void buildUserPrompt_withEmptyObservationsAndSuggestions_omitsThoseSections() {
        TaskReflection reflection =
                TaskReflection.ofFirstRun("Refined description", "Refined output", List.of(), List.of());

        String prompt = AgentPromptBuilder.buildUserPrompt(TASK, List.of(), MemoryContext.disabled(), null, reflection);

        // Sections with empty lists should not appear
        assertThat(prompt).doesNotContain("### Observations");
        assertThat(prompt).doesNotContain("### Suggestions");
    }

    @Test
    void buildUserPrompt_fourArgOverload_doesNotInjectReflection() {
        // Backward-compatibility: 4-arg overload never injects reflection
        String prompt = AgentPromptBuilder.buildUserPrompt(TASK, List.of(), MemoryContext.disabled(), null);

        assertThat(prompt).doesNotContain("Task Improvement Notes");
    }
}
