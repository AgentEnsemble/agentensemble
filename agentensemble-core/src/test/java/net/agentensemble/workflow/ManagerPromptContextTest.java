package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

class ManagerPromptContextTest {

    private Agent sampleAgent() {
        return Agent.builder()
                .role("Researcher")
                .goal("Research topics")
                .llm(mock(ChatModel.class))
                .build();
    }

    private Task sampleTask(Agent agent) {
        return Task.builder()
                .description("Research AI trends")
                .expectedOutput("Summary of AI trends")
                .agent(agent)
                .build();
    }

    // ========================
    // Field accessor tests
    // ========================

    @Test
    void testAgents_returnsProvidedList() {
        Agent agent = sampleAgent();
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(agent), List.of(), List.of(), null);
        assertThat(ctx.agents()).containsExactly(agent);
    }

    @Test
    void testTasks_returnsProvidedList() {
        Agent agent = sampleAgent();
        Task task = sampleTask(agent);
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(agent), List.of(task), List.of(), null);
        assertThat(ctx.tasks()).containsExactly(task);
    }

    @Test
    void testPreviousOutputs_returnsEmptyWhenNoneProvided() {
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(), List.of(), List.of(), null);
        assertThat(ctx.previousOutputs()).isEmpty();
    }

    @Test
    void testPreviousOutputs_returnsProvidedList() {
        TaskOutput output = mock(TaskOutput.class);
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(), List.of(), List.of(output), null);
        assertThat(ctx.previousOutputs()).containsExactly(output);
    }

    @Test
    void testWorkflowDescription_returnsNullWhenNotProvided() {
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(), List.of(), List.of(), null);
        assertThat(ctx.workflowDescription()).isNull();
    }

    @Test
    void testWorkflowDescription_returnsProvidedString() {
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(), List.of(), List.of(), "Investment analysis");
        assertThat(ctx.workflowDescription()).isEqualTo("Investment analysis");
    }

    // ========================
    // Record equality tests
    // ========================

    @Test
    void testEquals_sameFields_equal() {
        Agent agent = sampleAgent();
        ManagerPromptContext ctx1 = new ManagerPromptContext(List.of(agent), List.of(), List.of(), "desc");
        ManagerPromptContext ctx2 = new ManagerPromptContext(List.of(agent), List.of(), List.of(), "desc");
        assertThat(ctx1).isEqualTo(ctx2);
    }

    @Test
    void testEquals_differentDescription_notEqual() {
        Agent agent = sampleAgent();
        ManagerPromptContext ctx1 = new ManagerPromptContext(List.of(agent), List.of(), List.of(), "desc-1");
        ManagerPromptContext ctx2 = new ManagerPromptContext(List.of(agent), List.of(), List.of(), "desc-2");
        assertThat(ctx1).isNotEqualTo(ctx2);
    }

    // ========================
    // Empty context
    // ========================

    @Test
    void testEmptyContext_fieldsReturnEmptyOrNull() {
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(), List.of(), List.of(), null);
        assertThat(ctx.agents()).isEmpty();
        assertThat(ctx.tasks()).isEmpty();
        assertThat(ctx.previousOutputs()).isEmpty();
        assertThat(ctx.workflowDescription()).isNull();
    }
}
