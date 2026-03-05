package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import org.junit.jupiter.api.Test;

class DefaultManagerPromptStrategyTest {

    // ========================
    // Helpers
    // ========================

    private Agent agentWithRole(String role, String goal) {
        return Agent.builder().role(role).goal(goal).llm(mock(ChatModel.class)).build();
    }

    private Agent agentWithBackground(String role, String goal, String background) {
        return Agent.builder()
                .role(role)
                .goal(goal)
                .background(background)
                .llm(mock(ChatModel.class))
                .build();
    }

    private Task taskFor(Agent agent, String description, String expectedOutput) {
        return Task.builder()
                .description(description)
                .expectedOutput(expectedOutput)
                .agent(agent)
                .build();
    }

    private ManagerPromptContext agentContext(Agent... agents) {
        return new ManagerPromptContext(List.of(agents), List.of(), List.of(), null);
    }

    private ManagerPromptContext taskContext(Agent agent, Task... tasks) {
        return new ManagerPromptContext(List.of(agent), List.of(tasks), List.of(), null);
    }

    // ========================
    // Singleton / instance
    // ========================

    @Test
    void testDefault_singletonIsNotNull() {
        assertThat(DefaultManagerPromptStrategy.DEFAULT).isNotNull();
    }

    @Test
    void testDefault_singletonIsSameInstance() {
        assertThat(DefaultManagerPromptStrategy.DEFAULT).isSameAs(DefaultManagerPromptStrategy.DEFAULT);
    }

    // ========================
    // buildSystemPrompt tests
    // ========================

    @Test
    void testBuildSystemPrompt_withSingleAgent_containsRole() {
        Agent agent = agentWithRole("Researcher", "Find information");
        String result = DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(agentContext(agent));
        assertThat(result).contains("Researcher");
    }

    @Test
    void testBuildSystemPrompt_containsGoal() {
        Agent agent = agentWithRole("Researcher", "Find information");
        String result = DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(agentContext(agent));
        assertThat(result).contains("Find information");
    }

    @Test
    void testBuildSystemPrompt_withBackground_includesBackground() {
        Agent agent = agentWithBackground("Researcher", "Find information", "Expert analyst");
        String result = DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(agentContext(agent));
        assertThat(result).contains("Expert analyst");
    }

    @Test
    void testBuildSystemPrompt_withoutBackground_omitsBackgroundLine() {
        Agent agent = agentWithRole("Researcher", "Find information");
        String result = DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(agentContext(agent));
        assertThat(result).doesNotContain("Background:");
    }

    @Test
    void testBuildSystemPrompt_withMultipleAgents_listsAll() {
        Agent a1 = agentWithRole("Researcher", "Research topics");
        Agent a2 = agentWithRole("Writer", "Write content");
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(a1, a2), List.of(), List.of(), null);
        String result = DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(ctx);
        assertThat(result).contains("Researcher").contains("Writer");
    }

    @Test
    void testBuildSystemPrompt_numbersAgentsSequentially() {
        Agent a1 = agentWithRole("Researcher", "Research");
        Agent a2 = agentWithRole("Writer", "Write");
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(a1, a2), List.of(), List.of(), null);
        String result = DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(ctx);
        assertThat(result).contains("1.").contains("2.");
    }

    @Test
    void testBuildSystemPrompt_containsDelegateTip() {
        Agent agent = agentWithRole("Researcher", "Research");
        String result = DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(agentContext(agent));
        assertThat(result).containsIgnoringCase("delegate");
    }

    @Test
    void testBuildSystemPrompt_containsSynthesisInstruction() {
        Agent agent = agentWithRole("Researcher", "Research");
        String result = DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(agentContext(agent));
        assertThat(result).containsIgnoringCase("synthesiz");
    }

    @Test
    void testBuildSystemPrompt_isNotBlank() {
        Agent agent = agentWithRole("Researcher", "Research");
        String result = DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(agentContext(agent));
        assertThat(result).isNotBlank();
    }

    // ========================
    // buildUserPrompt tests
    // ========================

    @Test
    void testBuildUserPrompt_withSingleTask_containsDescription() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task task = taskFor(agent, "Research AI trends", "Summary of AI trends");
        String result = DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(taskContext(agent, task));
        assertThat(result).contains("Research AI trends");
    }

    @Test
    void testBuildUserPrompt_containsExpectedOutput() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task task = taskFor(agent, "Research AI trends", "Summary of AI trends");
        String result = DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(taskContext(agent, task));
        assertThat(result).contains("Summary of AI trends");
    }

    @Test
    void testBuildUserPrompt_withMultipleTasks_listsAll() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task t1 = taskFor(agent, "Task one description", "Expected one");
        Task t2 = taskFor(agent, "Task two description", "Expected two");
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(agent), List.of(t1, t2), List.of(), null);
        String result = DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(ctx);
        assertThat(result).contains("Task one description").contains("Task two description");
    }

    @Test
    void testBuildUserPrompt_numbersTasksSequentially() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task t1 = taskFor(agent, "First task", "First output");
        Task t2 = taskFor(agent, "Second task", "Second output");
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(agent), List.of(t1, t2), List.of(), null);
        String result = DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(ctx);
        assertThat(result).contains("Task 1").contains("Task 2");
    }

    @Test
    void testBuildUserPrompt_containsSynthesisInstructions() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task task = taskFor(agent, "Do something", "Expected result");
        String result = DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(taskContext(agent, task));
        assertThat(result).containsIgnoringCase("synthesiz");
    }

    @Test
    void testBuildUserPrompt_containsDelegateTip() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task task = taskFor(agent, "Do something", "Expected result");
        String result = DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(taskContext(agent, task));
        assertThat(result).containsIgnoringCase("delegate");
    }

    @Test
    void testBuildUserPrompt_isNotBlank() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task task = taskFor(agent, "Do something", "Expected result");
        String result = DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(taskContext(agent, task));
        assertThat(result).isNotBlank();
    }

    // ========================
    // Parity with deprecated ManagerPromptBuilder
    // ========================

    @Test
    @SuppressWarnings("deprecation")
    void testBuildSystemPrompt_matchesDeprecatedBuildBackground() {
        Agent agent = agentWithBackground("Analyst", "Analyse data", "Expert in finance");
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(agent), List.of(), List.of(), null);
        String fromStrategy = DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(ctx);
        String fromDeprecated = ManagerPromptBuilder.buildBackground(List.of(agent));
        assertThat(fromStrategy).isEqualTo(fromDeprecated);
    }

    @Test
    @SuppressWarnings("deprecation")
    void testBuildUserPrompt_matchesDeprecatedBuildTaskDescription() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task task = taskFor(agent, "Research AI", "AI summary");
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(agent), List.of(task), List.of(), null);
        String fromStrategy = DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(ctx);
        String fromDeprecated = ManagerPromptBuilder.buildTaskDescription(List.of(task));
        assertThat(fromStrategy).isEqualTo(fromDeprecated);
    }
}
