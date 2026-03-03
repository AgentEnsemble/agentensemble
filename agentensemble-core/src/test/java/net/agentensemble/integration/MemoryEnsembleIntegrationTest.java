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
import java.time.Instant;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.memory.EnsembleMemory;
import net.agentensemble.memory.InMemoryEntityMemory;
import net.agentensemble.memory.LongTermMemory;
import net.agentensemble.memory.MemoryEntry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * End-to-end integration tests for ensemble execution with memory enabled.
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
    // Short-term memory
    // ========================

    @Test
    void testShortTermMemory_singleTask_outputRecorded() {
        var agent = agentWithResponse("Researcher", "AI is growing fast.");
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A report")
                .agent(agent)
                .build();

        EnsembleMemory memory = EnsembleMemory.builder().shortTerm(true).build();

        EnsembleOutput output = Ensemble.builder()
                .agent(agent)
                .task(task)
                .memory(memory)
                .build()
                .run();

        // Output is correct regardless of memory
        assertThat(output.getRaw()).isEqualTo("AI is growing fast.");
    }

    @Test
    void testShortTermMemory_secondTaskPrompt_containsFirstTaskOutput() {
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
                .build();
        var writeTask = Task.builder()
                .description("Write a blog post about the research")
                .expectedOutput("A blog post")
                .agent(writerAgent)
                .build();

        EnsembleMemory memory = EnsembleMemory.builder().shortTerm(true).build();

        EnsembleOutput output = Ensemble.builder()
                .agent(researcherAgent)
                .agent(writerAgent)
                .task(researchTask)
                .task(writeTask)
                .memory(memory)
                .build()
                .run();

        // Capture the request sent to the writer's LLM
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(writer, atLeast(1)).chat(captor.capture());

        // The writer's prompt should contain the research result (from STM)
        String writerPrompt =
                captor.getValue().messages().stream().map(Object::toString).reduce("", String::concat);
        assertThat(writerPrompt).contains("AI is revolutionizing healthcare");

        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    @Test
    void testShortTermMemory_promptContainsShortTermMemorySection() {
        var researcher = mock(ChatModel.class);
        var writer = mock(ChatModel.class);

        when(researcher.chat(any(ChatRequest.class))).thenReturn(textResponse("Research output here."));
        when(writer.chat(any(ChatRequest.class))).thenReturn(textResponse("Written."));

        var researcherAgent = Agent.builder()
                .role("Researcher")
                .goal("Research")
                .llm(researcher)
                .build();
        var writerAgent =
                Agent.builder().role("Writer").goal("Write").llm(writer).build();

        var researchTask = Task.builder()
                .description("Research task")
                .expectedOutput("Report")
                .agent(researcherAgent)
                .build();
        var writeTask = Task.builder()
                .description("Write task")
                .expectedOutput("Article")
                .agent(writerAgent)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .agent(researcherAgent)
                .agent(writerAgent)
                .task(researchTask)
                .task(writeTask)
                .memory(EnsembleMemory.builder().shortTerm(true).build())
                .build()
                .run();

        // Capture writer's prompt and verify STM section heading
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(writer, atLeast(1)).chat(captor.capture());
        String writerPrompt =
                captor.getValue().messages().stream().map(Object::toString).reduce("", String::concat);

        assertThat(writerPrompt).contains("Short-Term Memory");
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    // ========================
    // Long-term memory
    // ========================

    @Test
    void testLongTermMemory_outputStoredAfterExecution() {
        LongTermMemory ltm = mock(LongTermMemory.class);
        when(ltm.retrieve(any(), any(Integer.class))).thenReturn(List.of());

        var agent = agentWithResponse("Researcher", "AI findings here.");
        var task = Task.builder()
                .description("Research AI")
                .expectedOutput("A report")
                .agent(agent)
                .build();

        EnsembleMemory memory = EnsembleMemory.builder().longTerm(ltm).build();

        Ensemble.builder().agent(agent).task(task).memory(memory).build().run();

        // The task output should be stored into long-term memory
        ArgumentCaptor<MemoryEntry> captor = ArgumentCaptor.forClass(MemoryEntry.class);
        verify(ltm).store(captor.capture());

        MemoryEntry stored = captor.getValue();
        assertThat(stored.getContent()).isEqualTo("AI findings here.");
        assertThat(stored.getAgentRole()).isEqualTo("Researcher");
        assertThat(stored.getTaskDescription()).isEqualTo("Research AI");
        assertThat(stored.getTimestamp()).isNotNull();
    }

    @Test
    void testLongTermMemory_retrievedBeforeTask_injectedIntoPrompt() {
        LongTermMemory ltm = mock(LongTermMemory.class);
        // Return a relevant past memory
        MemoryEntry pastMemory = MemoryEntry.builder()
                .content("Previous AI research: neural networks are trending.")
                .agentRole("OldResearcher")
                .taskDescription("Past research task")
                .timestamp(Instant.now())
                .build();
        when(ltm.retrieve(any(), any(Integer.class))).thenReturn(List.of(pastMemory));

        var llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class))).thenReturn(textResponse("New research done."));
        var agent = Agent.builder().role("Researcher").goal("Research").llm(llm).build();
        var task = Task.builder()
                .description("Research AI trends 2026")
                .expectedOutput("A report")
                .agent(agent)
                .build();

        EnsembleMemory memory = EnsembleMemory.builder().longTerm(ltm).build();

        Ensemble.builder().agent(agent).task(task).memory(memory).build().run();

        // Capture the prompt sent to the LLM
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llm).chat(captor.capture());

        String prompt =
                captor.getValue().messages().stream().map(Object::toString).reduce("", String::concat);

        // The LTM section should be present with the past memory content
        assertThat(prompt).contains("Long-Term Memory");
        assertThat(prompt).contains("neural networks are trending");
    }

    // ========================
    // Entity memory
    // ========================

    @Test
    void testEntityMemory_factsInjectedIntoPrompt() {
        InMemoryEntityMemory em = new InMemoryEntityMemory();
        em.put("Acme Corp", "A SaaS startup founded in 2015");
        em.put("Alice", "The lead researcher on this project");

        var llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class))).thenReturn(textResponse("Report done."));
        var agent = Agent.builder().role("Analyst").goal("Analyse").llm(llm).build();
        var task = Task.builder()
                .description("Analyse Acme Corp performance")
                .expectedOutput("Analysis report")
                .agent(agent)
                .build();

        EnsembleMemory memory = EnsembleMemory.builder().entityMemory(em).build();

        Ensemble.builder().agent(agent).task(task).memory(memory).build().run();

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llm).chat(captor.capture());

        String prompt =
                captor.getValue().messages().stream().map(Object::toString).reduce("", String::concat);

        assertThat(prompt).contains("Entity Knowledge");
        assertThat(prompt).contains("Acme Corp");
        assertThat(prompt).contains("SaaS startup");
        assertThat(prompt).contains("Alice");
        assertThat(prompt).contains("lead researcher");
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
                .build() // No .memory() call
                .run();

        assertThat(output.getRaw()).isEqualTo("Research complete.");
    }

    // ========================
    // Multiple tasks with all memory types
    // ========================

    @Test
    void testAllMemoryTypes_threeTasks_executesSuccessfully() {
        LongTermMemory ltm = mock(LongTermMemory.class);
        when(ltm.retrieve(any(), any(Integer.class))).thenReturn(List.of());

        InMemoryEntityMemory em = new InMemoryEntityMemory();
        em.put("AI", "Artificial Intelligence -- a broad field of computer science");

        var researcher = agentWithResponse("Researcher", "AI research findings.");
        var analyst = agentWithResponse("Analyst", "Analysis complete.");
        var writer = agentWithResponse("Writer", "Article written.");

        var t1 = Task.builder()
                .description("Research AI")
                .expectedOutput("Report")
                .agent(researcher)
                .build();
        var t2 = Task.builder()
                .description("Analyse findings")
                .expectedOutput("Analysis")
                .agent(analyst)
                .build();
        var t3 = Task.builder()
                .description("Write article")
                .expectedOutput("Article")
                .agent(writer)
                .build();

        EnsembleMemory memory = EnsembleMemory.builder()
                .shortTerm(true)
                .longTerm(ltm)
                .entityMemory(em)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .agent(researcher)
                .agent(analyst)
                .agent(writer)
                .task(t1)
                .task(t2)
                .task(t3)
                .memory(memory)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).hasSize(3);
        assertThat(output.getRaw()).isEqualTo("Article written.");
        // Three tasks were stored in LTM
        verify(ltm, atLeast(3)).store(any(MemoryEntry.class));
    }
}
