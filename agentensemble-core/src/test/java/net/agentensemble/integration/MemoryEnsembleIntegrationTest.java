package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * End-to-end integration tests for ensemble execution with the v2.0.0 scoped MemoryStore API.
 * Uses mocked LLMs to avoid real network calls.
 */
class MemoryEnsembleIntegrationTest {

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private Agent agentWithResponse(String role, String response) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        return Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
    }

    // ========================
    // Single task -- output stored in scope
    // ========================

    @Test
    void testSingleTask_withScope_outputStoredInStore() {
        MemoryStore store = MemoryStore.inMemory();

        var agent = agentWithResponse("Researcher", "AI is growing fast.");
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A report")
                .agent(agent)
                .memory("research")
                .build();

        EnsembleOutput output = Ensemble.builder()
                .agent(agent)
                .task(task)
                .memoryStore(store)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("AI is growing fast.");

        // The output should now be persisted in the "research" scope
        List<MemoryEntry> stored = store.retrieve("research", "AI", 10);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getContent()).isEqualTo("AI is growing fast.");
        assertThat(stored.get(0).getMeta(MemoryEntry.META_AGENT_ROLE)).isEqualTo("Researcher");
    }

    // ========================
    // Second task sees first task output via shared scope
    // ========================

    @Test
    void testSecondTask_sameScope_receivesFirstTaskOutputInPrompt() {
        MemoryStore store = MemoryStore.inMemory();

        var researcher = mock(ChatModel.class);
        var writer = mock(ChatModel.class);

        when(researcher.chat(any(ChatRequest.class)))
                .thenReturn(textResponse("Research result: AI is revolutionizing healthcare."));
        when(writer.chat(any(ChatRequest.class))).thenReturn(textResponse("Blog post written."));

        var researcherAgent = Agent.builder()
                .role("Researcher")
                .goal("Research")
                .llm(researcher)
                .build();
        var writerAgent =
                Agent.builder().role("Writer").goal("Write").llm(writer).build();

        var researchTask = Task.builder()
                .description("Research healthcare AI")
                .expectedOutput("A detailed report")
                .agent(researcherAgent)
                .memory("ai-research")
                .build();
        var writeTask = Task.builder()
                .description("Write a blog post about the research")
                .expectedOutput("A blog post")
                .agent(writerAgent)
                .memory("ai-research") // declares same scope
                .build();

        EnsembleOutput output = Ensemble.builder()
                .agent(researcherAgent)
                .agent(writerAgent)
                .task(researchTask)
                .task(writeTask)
                .memoryStore(store)
                .build()
                .run();

        // Capture the writer's prompt
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(writer, atLeast(1)).chat(captor.capture());

        String writerPrompt =
                captor.getValue().messages().stream().map(Object::toString).reduce("", String::concat);

        // The "Memory: ai-research" section should be present because the research task
        // already stored its output before the write task runs
        assertThat(writerPrompt).contains("Memory: ai-research");
        assertThat(writerPrompt).contains("AI is revolutionizing healthcare");

        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    // ========================
    // No memory -- backward compatibility
    // ========================

    @Test
    void testNoMemory_ensembleRunsNormally() {
        var agent = agentWithResponse("Researcher", "Research complete.");
        var task = Task.builder()
                .description("Research AI")
                .expectedOutput("Report")
                .agent(agent)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .agent(agent)
                .task(task)
                .build() // No .memoryStore() call
                .run();

        assertThat(output.getRaw()).isEqualTo("Research complete.");
    }

    // ========================
    // Scope isolation -- task cannot read from undeclared scope
    // ========================

    @Test
    void testScopeIsolation_taskOnlySeesOwnScope() {
        MemoryStore store = MemoryStore.inMemory();

        var researcher = mock(ChatModel.class);
        var writer = mock(ChatModel.class);

        when(researcher.chat(any(ChatRequest.class))).thenReturn(textResponse("Confidential research output."));
        when(writer.chat(any(ChatRequest.class))).thenReturn(textResponse("Generic article."));

        var researcherAgent = Agent.builder()
                .role("Researcher")
                .goal("Research")
                .llm(researcher)
                .build();
        var writerAgent =
                Agent.builder().role("Writer").goal("Write").llm(writer).build();

        // Research task writes to "confidential-research"
        var researchTask = Task.builder()
                .description("Research sensitive data")
                .expectedOutput("Sensitive report")
                .agent(researcherAgent)
                .memory("confidential-research")
                .build();
        // Writer task only declares "public-writing" -- should NOT see confidential-research
        var writeTask = Task.builder()
                .description("Write a public article")
                .expectedOutput("Article")
                .agent(writerAgent)
                .memory("public-writing")
                .build();

        Ensemble.builder()
                .agent(researcherAgent)
                .agent(writerAgent)
                .task(researchTask)
                .task(writeTask)
                .memoryStore(store)
                .build()
                .run();

        // Capture writer prompt
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(writer, atLeast(1)).chat(captor.capture());

        String writerPrompt =
                captor.getValue().messages().stream().map(Object::toString).reduce("", String::concat);

        // Writer should NOT see confidential-research entries
        assertThat(writerPrompt).doesNotContain("Confidential research output");
        assertThat(writerPrompt).doesNotContain("confidential-research");
    }

    // ========================
    // Empty scope on first run
    // ========================

    @Test
    void testEmptyScope_firstRun_taskRunsNormally() {
        MemoryStore store = MemoryStore.inMemory();

        var agent = agentWithResponse("Researcher", "First run output.");
        var task = Task.builder()
                .description("Research AI for the first time")
                .expectedOutput("Report")
                .agent(agent)
                .memory("fresh-scope") // scope has no prior entries
                .build();

        // Should not throw even though scope has no entries yet
        EnsembleOutput output = Ensemble.builder()
                .agent(agent)
                .task(task)
                .memoryStore(store)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("First run output.");

        // After the run, the output is stored
        List<MemoryEntry> stored = store.retrieve("fresh-scope", "AI", 10);
        assertThat(stored).hasSize(1);
    }

    // ========================
    // Cross-run persistence
    // ========================

    @Test
    void testCrossRunPersistence_secondRunSeesFirstRunOutput() {
        MemoryStore store = MemoryStore.inMemory(); // shared across runs

        var researcher = mock(ChatModel.class);
        var researcherAgent = Agent.builder()
                .role("Researcher")
                .goal("Research")
                .llm(researcher)
                .build();

        // First run
        when(researcher.chat(any(ChatRequest.class))).thenReturn(textResponse("Run 1: AI trends identified."));

        var task1 = Task.builder()
                .description("Research AI trends")
                .expectedOutput("Report")
                .agent(researcherAgent)
                .memory("research-history")
                .build();

        Ensemble.builder()
                .agent(researcherAgent)
                .task(task1)
                .memoryStore(store)
                .build()
                .run();

        // Second run -- should see run 1 output in prompt
        when(researcher.chat(any(ChatRequest.class))).thenReturn(textResponse("Run 2: Building on prior research."));

        var task2 = Task.builder()
                .description("Continue AI research")
                .expectedOutput("Updated report")
                .agent(researcherAgent)
                .memory("research-history")
                .build();

        Ensemble.builder()
                .agent(researcherAgent)
                .task(task2)
                .memoryStore(store)
                .build()
                .run();

        // Capture second run's prompt
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(researcher, atLeast(2)).chat(captor.capture());

        // The last call (second run) should see prior research in the prompt
        List<ChatRequest> allRequests = captor.getAllValues();
        ChatRequest secondRunRequest = allRequests.get(allRequests.size() - 1);
        String secondRunPrompt =
                secondRunRequest.messages().stream().map(Object::toString).reduce("", String::concat);

        assertThat(secondRunPrompt).contains("Memory: research-history");
        assertThat(secondRunPrompt).contains("Run 1: AI trends identified");
    }

    // ========================
    // Multiple tasks sharing a scope
    // ========================

    @Test
    void testMultipleTasksSharingScope_allOutputsStoredAndRetrievable() {
        MemoryStore store = MemoryStore.inMemory();

        var researcher = agentWithResponse("Researcher", "AI research findings.");
        var analyst = agentWithResponse("Analyst", "Analysis complete.");

        var t1 = Task.builder()
                .description("Research AI")
                .expectedOutput("Report")
                .agent(researcher)
                .memory("shared-scope")
                .build();
        var t2 = Task.builder()
                .description("Analyse findings")
                .expectedOutput("Analysis")
                .agent(analyst)
                .memory("shared-scope")
                .build();

        EnsembleOutput output = Ensemble.builder()
                .agent(researcher)
                .agent(analyst)
                .task(t1)
                .task(t2)
                .memoryStore(store)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).hasSize(2);

        // Both outputs should be in the shared scope
        List<MemoryEntry> stored = store.retrieve("shared-scope", "query", 10);
        assertThat(stored).hasSize(2);
    }
}
