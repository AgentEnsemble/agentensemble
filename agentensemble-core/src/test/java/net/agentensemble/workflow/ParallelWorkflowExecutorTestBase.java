package net.agentensemble.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared test helpers for ParallelWorkflowExecutor test classes.
 */
abstract class ParallelWorkflowExecutorTestBase {

    protected List<Agent> agents;

    @BeforeEach
    void setUp() {
        agents = new ArrayList<>();
    }

    protected ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    protected Agent agentWithResponse(String role, String response) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        var agent = Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
        agents.add(agent);
        return agent;
    }

    protected Agent agentThatFails(String role) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM error for " + role));
        var agent = Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
        agents.add(agent);
        return agent;
    }

    protected Task task(String description, Agent agent) {
        return Task.builder()
                .description(description)
                .expectedOutput("Output for " + description)
                .agent(agent)
                .build();
    }

    protected Task taskWithContext(String description, Agent agent, List<Task> context) {
        return Task.builder()
                .description(description)
                .expectedOutput("Output for " + description)
                .agent(agent)
                .context(context)
                .build();
    }

    protected ParallelWorkflowExecutor executor(ParallelErrorStrategy strategy) {
        return new ParallelWorkflowExecutor(agents, 3, strategy);
    }

    protected ParallelWorkflowExecutor executor() {
        return executor(ParallelErrorStrategy.FAIL_FAST);
    }
}
