package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Integration tests for the task-first sequential workflow with context dependencies.
 *
 * <p>These tests cover issues #148 (stale context identity in resolveAgents causing NPE)
 * and #149 (synthesized agent tool loop compatibility).
 *
 * <p>All tasks in the context-dependency tests are agentless -- agents are synthesized
 * automatically from task descriptions by the framework.
 */
class SequentialTaskFirstContextIntegrationTest {

    // ========================
    // Utilities
    // ========================

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private ChatResponse toolCallResponse(String toolName, String id, String arguments) {
        var request = ToolExecutionRequest.builder()
                .id(id)
                .name(toolName)
                .arguments(arguments)
                .build();
        return ChatResponse.builder().aiMessage(new AiMessage(List.of(request))).build();
    }

    /**
     * Minimal tool used to verify the synthesized agent routes through the tool loop path.
     */
    static class LookupTool {
        @Tool("Look up information by key")
        public String lookup(String key) {
            return "looked-up: " + key;
        }
    }

    // ========================
    // Bug #148: agentless tasks with explicit context dependency
    // ========================

    @Test
    void testTwoAgentlessTasks_withContextDependency_executesSuccessfully() {
        // Bug #148: before the fix, resolveAgents() created new Task instances for agentless
        // tasks but did not remap context references. The downstream task's context list still
        // pointed to the old (pre-synthesis) task instance, causing gatherContextOutputs to
        // fail to find the completed output and then NPE on contextTask.getAgent().getRole().

        var llm1 = mock(ChatModel.class);
        when(llm1.chat(any(ChatRequest.class))).thenReturn(textResponse("Research complete."));

        var llm2 = mock(ChatModel.class);
        when(llm2.chat(any(ChatRequest.class))).thenReturn(textResponse("Summary written."));

        var task1 = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A report")
                .chatLanguageModel(llm1)
                .build();

        var task2 = Task.builder()
                .description("Write a summary")
                .expectedOutput("A summary")
                .chatLanguageModel(llm2)
                .context(List.of(task1))
                .build();

        EnsembleOutput output =
                Ensemble.builder().task(task1).task(task2).build().run();

        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs().get(0).getRaw()).isEqualTo("Research complete.");
        assertThat(output.getTaskOutputs().get(1).getRaw()).isEqualTo("Summary written.");
        assertThat(output.getRaw()).isEqualTo("Summary written.");
    }

    @Test
    void testTwoAgentlessTasks_withContextDependency_ensembleLlm_executesSuccessfully() {
        // Same scenario but using a single ensemble-level LLM for both tasks.
        var sharedLlm = mock(ChatModel.class);
        when(sharedLlm.chat(any(ChatRequest.class)))
                .thenReturn(textResponse("Task 1 output."))
                .thenReturn(textResponse("Task 2 output."));

        var task1 = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A report")
                .build();

        var task2 = Task.builder()
                .description("Write a summary")
                .expectedOutput("A summary")
                .context(List.of(task1))
                .build();

        EnsembleOutput output = Ensemble.builder()
                .chatLanguageModel(sharedLlm)
                .task(task1)
                .task(task2)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs().get(0).getRaw()).isEqualTo("Task 1 output.");
        assertThat(output.getTaskOutputs().get(1).getRaw()).isEqualTo("Task 2 output.");
    }

    @Test
    void testThreeAgentlessTasks_chainedContextDependencies_allTasksExecute() {
        // A chain: task1 -> task2 (context on task1) -> task3 (context on task2)
        var llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class)))
                .thenReturn(textResponse("Step 1 done."))
                .thenReturn(textResponse("Step 2 done."))
                .thenReturn(textResponse("Step 3 done."));

        var task1 = Task.builder()
                .description("Research trends")
                .expectedOutput("Report")
                .build();

        var task2 = Task.builder()
                .description("Summarise the research")
                .expectedOutput("Summary")
                .context(List.of(task1))
                .build();

        var task3 = Task.builder()
                .description("Write the final report")
                .expectedOutput("Final report")
                .context(List.of(task2))
                .build();

        EnsembleOutput output = Ensemble.builder()
                .chatLanguageModel(llm)
                .task(task1)
                .task(task2)
                .task(task3)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).hasSize(3);
        assertThat(output.getTaskOutputs().get(0).getRaw()).isEqualTo("Step 1 done.");
        assertThat(output.getTaskOutputs().get(1).getRaw()).isEqualTo("Step 2 done.");
        assertThat(output.getTaskOutputs().get(2).getRaw()).isEqualTo("Step 3 done.");
        assertThat(output.getRaw()).isEqualTo("Step 3 done.");
    }

    @Test
    void testAgentlessDownstream_withExplicitAgentUpstream_contextDependencyWorks() {
        // Mixed: explicit-agent task as upstream; agentless task as downstream.
        // The explicit-agent task creates a new Task instance during agent resolution
        // (no change to identity since agent != null), but the agentless downstream
        // task does create a new instance -- verify context still resolves correctly.

        var upstreamLlm = mock(ChatModel.class);
        when(upstreamLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Expert output."));

        var downstreamLlm = mock(ChatModel.class);
        when(downstreamLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Synthesized output."));

        var explicitAgent = Agent.builder()
                .role("Expert")
                .goal("Produce data")
                .llm(upstreamLlm)
                .build();

        var task1 = Task.builder()
                .description("Produce expert analysis")
                .expectedOutput("Analysis")
                .agent(explicitAgent)
                .build();

        var task2 = Task.builder()
                .description("Write a summary")
                .expectedOutput("Summary")
                .chatLanguageModel(downstreamLlm)
                .context(List.of(task1))
                .build();

        EnsembleOutput output =
                Ensemble.builder().task(task1).task(task2).build().run();

        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs().get(0).getRaw()).isEqualTo("Expert output.");
        assertThat(output.getTaskOutputs().get(1).getRaw()).isEqualTo("Synthesized output.");
    }

    @Test
    void testAgentlessContextTask_upstreamOutputIsIncludedInDownstreamPrompt() {
        // Verify that after the identity-remap fix, the upstream task's output is actually
        // present in the prompt sent to the downstream task's LLM.
        var upstreamLlm = mock(ChatModel.class);
        when(upstreamLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("upstream-output-marker"));

        var downstreamLlm = mock(ChatModel.class);
        ArgumentCaptor<ChatRequest> downstreamRequest = ArgumentCaptor.forClass(ChatRequest.class);
        when(downstreamLlm.chat(downstreamRequest.capture())).thenReturn(textResponse("downstream result"));

        var task1 = Task.builder()
                .description("Research AI trends")
                .expectedOutput("Report")
                .chatLanguageModel(upstreamLlm)
                .build();

        var task2 = Task.builder()
                .description("Summarise the research")
                .expectedOutput("Summary")
                .chatLanguageModel(downstreamLlm)
                .context(List.of(task1))
                .build();

        Ensemble.builder().task(task1).task(task2).build().run();

        // The user prompt of the downstream LLM call must contain the upstream output
        String downstreamUserPrompt = downstreamRequest.getValue().messages().stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).singleText())
                .findFirst()
                .orElse("");

        assertThat(downstreamUserPrompt).contains("upstream-output-marker");
    }

    // ========================
    // Bug #149: synthesized agents with tools run through the correct execution path
    // ========================

    @Test
    void testAgentlessTask_withTaskLevelTools_synthesizedAgentRunsToolLoop() {
        // Bug #149: a task with tools but no explicit agent should synthesize an agent
        // that runs through the executeWithTools path, exercising the tool call and
        // producing the final answer after the tool result is fed back to the LLM.
        //
        // Mock LLM call sequence:
        //   1st call: request the "lookup" tool
        //   2nd call: final answer after receiving the tool result

        var llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse("lookup", "call-1", "{\"key\":\"AI\"}"))
                .thenReturn(textResponse("Final answer after tool."));

        var task = Task.builder()
                .description("Research AI trends using lookup tool")
                .expectedOutput("A report")
                .chatLanguageModel(llm)
                .tools(List.of(new LookupTool()))
                .build();

        EnsembleOutput output = Ensemble.builder().task(task).build().run();

        assertThat(output.getRaw()).isEqualTo("Final answer after tool.");
        assertThat(output.getTotalToolCalls()).isEqualTo(1);
    }

    @Test
    void testAgentlessTask_withToolsAndContext_toolLoopAndContextBothWork() {
        // Combines bugs #148 and #149: two agentless tasks where the downstream task
        // has both a context dependency and task-level tools.
        var upstreamLlm = mock(ChatModel.class);
        when(upstreamLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Context data."));

        var downstreamLlm = mock(ChatModel.class);
        when(downstreamLlm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse("lookup", "call-1", "{\"key\":\"trends\"}"))
                .thenReturn(textResponse("Report with context and tool result."));

        var task1 = Task.builder()
                .description("Research background")
                .expectedOutput("Background data")
                .chatLanguageModel(upstreamLlm)
                .build();

        var task2 = Task.builder()
                .description("Compile the report using research data")
                .expectedOutput("Full report")
                .chatLanguageModel(downstreamLlm)
                .tools(List.of(new LookupTool()))
                .context(List.of(task1))
                .build();

        EnsembleOutput output =
                Ensemble.builder().task(task1).task(task2).build().run();

        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs().get(0).getRaw()).isEqualTo("Context data.");
        assertThat(output.getTaskOutputs().get(1).getRaw()).isEqualTo("Report with context and tool result.");
        assertThat(output.getTotalToolCalls()).isEqualTo(1);
    }

    @Test
    void testSynthesizedAgent_taskLevelLlmTakesPrecedenceOverEnsembleLlm() {
        // When a task has its own chatLanguageModel, that model must be used for the
        // synthesized agent even when an ensemble-level LLM is also present.
        var taskLlm = mock(ChatModel.class);
        when(taskLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("task-level-model-response"));

        var ensembleLlm = mock(ChatModel.class);
        when(ensembleLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("ensemble-level-model-response"));

        var task = Task.builder()
                .description("Summarise findings")
                .expectedOutput("Summary")
                .chatLanguageModel(taskLlm)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .chatLanguageModel(ensembleLlm)
                .task(task)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("task-level-model-response");
    }

    @Test
    void testAgentlessTask_withToolsAndContext_contextOutputIsInPromptToToolEnabledAgent() {
        // Regression: verify that after the fix, context output flows into the user prompt
        // even when the downstream synthesized agent has tools (i.e., goes through executeWithTools).
        var upstreamLlm = mock(ChatModel.class);
        when(upstreamLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("upstream-context-marker"));

        var downstreamLlm = mock(ChatModel.class);
        ArgumentCaptor<ChatRequest> firstCallCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        // First call: tool request; second: final answer
        when(downstreamLlm.chat(firstCallCaptor.capture()))
                .thenReturn(toolCallResponse("lookup", "call-1", "{\"key\":\"x\"}"))
                .thenReturn(textResponse("Final."));

        var task1 = Task.builder()
                .description("Research background")
                .expectedOutput("Background")
                .chatLanguageModel(upstreamLlm)
                .build();

        var task2 = Task.builder()
                .description("Compile the final report")
                .expectedOutput("Report")
                .chatLanguageModel(downstreamLlm)
                .tools(List.of(new LookupTool()))
                .context(List.of(task1))
                .build();

        Ensemble.builder().task(task1).task(task2).build().run();

        // The first LLM call for task2 (the tool-request call) should contain the context marker
        // in the user message.
        List<ChatRequest> allCalls = firstCallCaptor.getAllValues();
        assertThat(allCalls).isNotEmpty();
        ChatRequest firstCall = allCalls.get(0);
        String userPrompt = firstCall.messages().stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).singleText())
                .findFirst()
                .orElse("");
        assertThat(userPrompt).contains("upstream-context-marker");
    }
}
