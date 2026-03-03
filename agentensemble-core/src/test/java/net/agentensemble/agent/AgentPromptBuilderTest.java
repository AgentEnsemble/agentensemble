package net.agentensemble.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

class AgentPromptBuilderTest {

    private Agent minimalAgent(String role, String goal) {
        return Agent.builder().role(role).goal(goal).llm(mock(ChatModel.class)).build();
    }

    private TaskOutput taskOutput(String agentRole, String description, String raw) {
        return TaskOutput.builder()
                .agentRole(agentRole)
                .taskDescription(description)
                .raw(raw)
                .completedAt(Instant.now())
                .duration(Duration.ofSeconds(1))
                .toolCallCount(0)
                .build();
    }

    // ========================
    // System prompt
    // ========================

    @Test
    void testBuildSystemPrompt_withAllFields() {
        var agent = Agent.builder()
                .role("Senior Research Analyst")
                .goal("Find cutting-edge developments")
                .background("You are a veteran researcher with 20 years experience.")
                .responseFormat("Respond in bullet points.")
                .llm(mock(ChatModel.class))
                .build();

        String prompt = AgentPromptBuilder.buildSystemPrompt(agent);

        assertThat(prompt)
                .startsWith("You are Senior Research Analyst.")
                .contains("You are a veteran researcher with 20 years experience.")
                .contains("Your personal goal is: Find cutting-edge developments")
                .contains("Output format instructions: Respond in bullet points.");
    }

    @Test
    void testBuildSystemPrompt_withoutBackground() {
        var agent = minimalAgent("Writer", "Write articles");

        String prompt = AgentPromptBuilder.buildSystemPrompt(agent);

        assertThat(prompt)
                .startsWith("You are Writer.")
                .contains("Your personal goal is: Write articles")
                .doesNotContain("null");
    }

    @Test
    void testBuildSystemPrompt_withEmptyBackground() {
        var agent = Agent.builder()
                .role("Writer")
                .goal("Write articles")
                .background("") // empty, should be omitted
                .llm(mock(ChatModel.class))
                .build();

        String prompt = AgentPromptBuilder.buildSystemPrompt(agent);

        // No extra blank line from empty background
        assertThat(prompt).startsWith("You are Writer.").doesNotContain("background");
    }

    @Test
    void testBuildSystemPrompt_withoutResponseFormat() {
        var agent = minimalAgent("Analyst", "Analyze data");

        String prompt = AgentPromptBuilder.buildSystemPrompt(agent);

        assertThat(prompt).doesNotContain("Output format instructions");
    }

    @Test
    void testBuildSystemPrompt_minimalAgent_hasRequiredParts() {
        var agent = minimalAgent("Assistant", "Help users");

        String prompt = AgentPromptBuilder.buildSystemPrompt(agent);

        assertThat(prompt)
                .contains("You are Assistant.")
                .contains("Your personal goal is: Help users")
                .contains("You must produce a final answer");
    }

    @Test
    void testBuildSystemPrompt_withBackground_hasBlankLineSeparation() {
        var agent = Agent.builder()
                .role("Researcher")
                .goal("Find info")
                .background("Expert researcher.")
                .llm(mock(ChatModel.class))
                .build();

        String prompt = AgentPromptBuilder.buildSystemPrompt(agent);

        // Background appears after role, before goal
        int rolePos = prompt.indexOf("You are Researcher.");
        int bgPos = prompt.indexOf("Expert researcher.");
        int goalPos = prompt.indexOf("Your personal goal");
        assertThat(rolePos).isLessThan(bgPos);
        assertThat(bgPos).isLessThan(goalPos);
    }

    // ========================
    // User prompt
    // ========================

    @Test
    void testBuildUserPrompt_withoutContext() {
        var agent = minimalAgent("Researcher", "Find info");
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A detailed report")
                .agent(agent)
                .build();

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        assertThat(prompt)
                .contains("## Task")
                .contains("Research AI trends")
                .contains("## Expected Output")
                .contains("A detailed report")
                .doesNotContain("## Context");
    }

    @Test
    void testBuildUserPrompt_withSingleContext() {
        var agent = minimalAgent("Writer", "Write content");
        var task = Task.builder()
                .description("Write a blog post")
                .expectedOutput("A 1000-word blog post")
                .agent(agent)
                .build();
        var contextOutput = taskOutput("Researcher", "Research AI trends", "AI is growing fast...");

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of(contextOutput));

        assertThat(prompt)
                .contains("## Context from Previous Tasks")
                .contains("Researcher")
                .contains("Research AI trends")
                .contains("AI is growing fast...")
                .contains("## Task")
                .contains("Write a blog post")
                .contains("## Expected Output");
    }

    @Test
    void testBuildUserPrompt_withMultipleContexts() {
        var agent = minimalAgent("Editor", "Edit content");
        var task = Task.builder()
                .description("Edit the article")
                .expectedOutput("Polished article")
                .agent(agent)
                .build();
        var ctx1 = taskOutput("Researcher", "Research task", "Research findings...");
        var ctx2 = taskOutput("Writer", "Write task", "Draft article...");

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of(ctx1, ctx2));

        assertThat(prompt)
                .contains("Researcher")
                .contains("Research findings...")
                .contains("Writer")
                .contains("Draft article...");
    }

    @Test
    void testBuildUserPrompt_contextOrderPreserved() {
        var agent = minimalAgent("Editor", "Edit content");
        var task = Task.builder()
                .description("Edit the article")
                .expectedOutput("Polished article")
                .agent(agent)
                .build();
        var ctx1 = taskOutput("First", "First task", "First output");
        var ctx2 = taskOutput("Second", "Second task", "Second output");

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of(ctx1, ctx2));

        int firstPos = prompt.indexOf("First output");
        int secondPos = prompt.indexOf("Second output");
        assertThat(firstPos).isLessThan(secondPos);
    }

    @Test
    void testBuildUserPrompt_contextBeforeTask() {
        var agent = minimalAgent("Writer", "Write content");
        var task = Task.builder()
                .description("Write a blog post")
                .expectedOutput("A blog post")
                .agent(agent)
                .build();
        var contextOutput = taskOutput("Researcher", "Research", "Findings...");

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of(contextOutput));

        int contextPos = prompt.indexOf("## Context");
        int taskPos = prompt.indexOf("## Task");
        assertThat(contextPos).isLessThan(taskPos);
    }

    // ========================
    // Structured output format section
    // ========================

    record ReportRecord(String title, String summary) {}

    @Test
    void testBuildUserPrompt_withOutputType_containsOutputFormatSection() {
        var agent = minimalAgent("Researcher", "Find info");
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A structured report")
                .agent(agent)
                .outputType(ReportRecord.class)
                .build();

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        assertThat(prompt).contains("## Output Format");
        assertThat(prompt).contains("ONLY valid JSON");
        assertThat(prompt).contains("\"title\"");
        assertThat(prompt).contains("\"summary\"");
    }

    @Test
    void testBuildUserPrompt_withOutputType_outputFormatAfterExpectedOutput() {
        var agent = minimalAgent("Researcher", "Find info");
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A structured report")
                .agent(agent)
                .outputType(ReportRecord.class)
                .build();

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        int expectedOutputPos = prompt.indexOf("## Expected Output");
        int outputFormatPos = prompt.indexOf("## Output Format");
        assertThat(expectedOutputPos).isLessThan(outputFormatPos);
    }

    @Test
    void testBuildUserPrompt_withoutOutputType_noOutputFormatSection() {
        var agent = minimalAgent("Researcher", "Find info");
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A detailed report")
                .agent(agent)
                .build();

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        assertThat(prompt).doesNotContain("## Output Format");
    }

    @Test
    void testBuildUserPrompt_withOutputType_schemaIncludesAllFields() {
        var agent = minimalAgent("Researcher", "Find info");
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A structured report")
                .agent(agent)
                .outputType(ReportRecord.class)
                .build();

        String prompt = AgentPromptBuilder.buildUserPrompt(task, List.of());

        // Schema should show both fields from the record
        assertThat(prompt).contains("\"title\": \"string\"");
        assertThat(prompt).contains("\"summary\": \"string\"");
    }
}
