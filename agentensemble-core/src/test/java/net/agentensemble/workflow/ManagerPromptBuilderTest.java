package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import org.junit.jupiter.api.Test;

class ManagerPromptBuilderTest {

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

    // ========================
    // buildBackground tests
    // ========================

    @Test
    void testBuildBackground_withSingleAgent_containsRole() {
        Agent agent = agentWithRole("Researcher", "Find information");
        String result = ManagerPromptBuilder.buildBackground(List.of(agent));
        assertThat(result).contains("Researcher");
    }

    @Test
    void testBuildBackground_containsGoal() {
        Agent agent = agentWithRole("Researcher", "Find information");
        String result = ManagerPromptBuilder.buildBackground(List.of(agent));
        assertThat(result).contains("Find information");
    }

    @Test
    void testBuildBackground_withBackground_includesBackground() {
        Agent agent = agentWithBackground("Researcher", "Find information", "Expert analyst");
        String result = ManagerPromptBuilder.buildBackground(List.of(agent));
        assertThat(result).contains("Expert analyst");
    }

    @Test
    void testBuildBackground_withoutBackground_omitsBackgroundLine() {
        Agent agent = agentWithRole("Researcher", "Find information");
        String result = ManagerPromptBuilder.buildBackground(List.of(agent));
        assertThat(result).doesNotContain("Background:");
    }

    @Test
    void testBuildBackground_withMultipleAgents_listsAll() {
        Agent a1 = agentWithRole("Researcher", "Research topics");
        Agent a2 = agentWithRole("Writer", "Write content");
        String result = ManagerPromptBuilder.buildBackground(List.of(a1, a2));
        assertThat(result).contains("Researcher").contains("Writer");
    }

    @Test
    void testBuildBackground_numbersAgentsSequentially() {
        Agent a1 = agentWithRole("Researcher", "Research");
        Agent a2 = agentWithRole("Writer", "Write");
        String result = ManagerPromptBuilder.buildBackground(List.of(a1, a2));
        assertThat(result).contains("1.").contains("2.");
    }

    @Test
    void testBuildBackground_containsDelegateTip() {
        Agent agent = agentWithRole("Researcher", "Research");
        String result = ManagerPromptBuilder.buildBackground(List.of(agent));
        assertThat(result).containsIgnoringCase("delegate");
    }

    @Test
    void testBuildBackground_containsSynthesisInstruction() {
        Agent agent = agentWithRole("Researcher", "Research");
        String result = ManagerPromptBuilder.buildBackground(List.of(agent));
        assertThat(result).containsIgnoringCase("synthesiz");
    }

    // ========================
    // buildTaskDescription tests
    // ========================

    @Test
    void testBuildTaskDescription_withSingleTask_containsDescription() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task task = taskFor(agent, "Research AI trends", "Summary of AI trends");
        String result = ManagerPromptBuilder.buildTaskDescription(List.of(task));
        assertThat(result).contains("Research AI trends");
    }

    @Test
    void testBuildTaskDescription_containsExpectedOutput() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task task = taskFor(agent, "Research AI trends", "Summary of AI trends");
        String result = ManagerPromptBuilder.buildTaskDescription(List.of(task));
        assertThat(result).contains("Summary of AI trends");
    }

    @Test
    void testBuildTaskDescription_withMultipleTasks_listsAll() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task t1 = taskFor(agent, "Task one description", "Expected one");
        Task t2 = taskFor(agent, "Task two description", "Expected two");
        String result = ManagerPromptBuilder.buildTaskDescription(List.of(t1, t2));
        assertThat(result).contains("Task one description").contains("Task two description");
    }

    @Test
    void testBuildTaskDescription_numbersTasksSequentially() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task t1 = taskFor(agent, "First task", "First output");
        Task t2 = taskFor(agent, "Second task", "Second output");
        String result = ManagerPromptBuilder.buildTaskDescription(List.of(t1, t2));
        assertThat(result).contains("Task 1").contains("Task 2");
    }

    @Test
    void testBuildTaskDescription_containsSynthesisInstructions() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task task = taskFor(agent, "Do something", "Expected result");
        String result = ManagerPromptBuilder.buildTaskDescription(List.of(task));
        assertThat(result).containsIgnoringCase("synthesiz");
    }

    @Test
    void testBuildTaskDescription_containsDelegateTip() {
        Agent agent = agentWithRole("Researcher", "Research");
        Task task = taskFor(agent, "Do something", "Expected result");
        String result = ManagerPromptBuilder.buildTaskDescription(List.of(task));
        assertThat(result).containsIgnoringCase("delegate");
    }
}
