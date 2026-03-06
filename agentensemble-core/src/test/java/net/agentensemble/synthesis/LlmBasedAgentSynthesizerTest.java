package net.agentensemble.synthesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LlmBasedAgentSynthesizer}.
 *
 * Covers happy path (valid JSON response), fallback on malformed JSON,
 * fallback on LLM exception, and missing required fields.
 */
class LlmBasedAgentSynthesizerTest {

    private final AgentSynthesizer synthesizer = AgentSynthesizer.llmBased();

    private Task taskWithDescription(String description) {
        return Task.builder()
                .description(description)
                .expectedOutput("Some output")
                .build();
    }

    private ChatResponse jsonResponse(String json) {
        return ChatResponse.builder().aiMessage(AiMessage.from(json)).build();
    }

    // ========================
    // Happy path: valid JSON response
    // ========================

    @Test
    void synthesize_validJsonResponse_returnsCorrectAgent() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(jsonResponse("{\"role\": \"AI Analyst\", \"goal\": \"Analyse AI trends\","
                        + " \"backstory\": \"Expert in AI research\"}"));
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Analyse AI market trends");

        Agent agent = synthesizer.synthesize(task, ctx);

        assertThat(agent.getRole()).isEqualTo("AI Analyst");
        assertThat(agent.getGoal()).isEqualTo("Analyse AI trends");
        assertThat(agent.getBackground()).isEqualTo("Expert in AI research");
        assertThat(agent.getLlm()).isSameAs(model);
    }

    @Test
    void synthesize_validJsonResponse_emptyBackstory_backgroundIsNull() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(jsonResponse("{\"role\": \"Analyst\", \"goal\": \"Analyse data\", \"backstory\": \"\"}"));
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Analyse data");

        Agent agent = synthesizer.synthesize(task, ctx);

        // Empty backstory is treated as null/absent
        assertThat(agent.getBackground()).isNull();
    }

    // ========================
    // Fallback: malformed JSON
    // ========================

    @Test
    void synthesize_malformedJson_fallsBackToTemplate() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(jsonResponse("not valid json"));
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Research AI trends");

        Agent agent = synthesizer.synthesize(task, ctx);

        // Falls back to template: first word "Research" -> "Researcher"
        assertThat(agent.getRole()).isEqualTo("Researcher");
        assertThat(agent.getLlm()).isSameAs(model);
    }

    // ========================
    // Fallback: missing required fields in JSON
    // ========================

    @Test
    void synthesize_missingRoleInJson_fallsBackToTemplate() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(jsonResponse("{\"goal\": \"Analyse data\", \"backstory\": \"Expert\"}"));
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Analyse data");

        // Missing "role" -> falls back to template
        Agent agent = synthesizer.synthesize(task, ctx);

        // Template synthesizer produces "Analyst" for "Analyse..."
        assertThat(agent.getRole()).isEqualTo("Analyst");
    }

    @Test
    void synthesize_missingGoalInJson_fallsBackToTemplate() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(jsonResponse("{\"role\": \"Analyst\", \"backstory\": \"Expert\"}"));
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Analyse data");

        // Missing "goal" -> falls back to template
        Agent agent = synthesizer.synthesize(task, ctx);

        // Template synthesizer produces "Analyst" for "Analyse..."
        assertThat(agent.getRole()).isEqualTo("Analyst");
    }

    // ========================
    // Model execution failures propagate (not silently swallowed)
    // ========================

    @Test
    void synthesize_modelThrowsRuntimeException_propagatesException() {
        // A RuntimeException from the ChatModel indicates a model-level failure (connectivity,
        // broken implementation, etc.). The synthesizer must NOT silently swallow this and fall
        // back to a template agent -- that would mask the real configuration error. The exception
        // must propagate so the caller gets a clear signal that the model is broken.
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM unavailable"));
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Write a report");

        assertThatThrownBy(() -> synthesizer.synthesize(task, ctx))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM unavailable");
    }

    @Test
    void synthesize_modelThrowsNotImplemented_propagatesException() {
        // RuntimeException("Not implemented") is the LangChain4j 1.11.0 ChatModel.doChat()
        // default implementation. This signals the ChatModel has no runnable implementation.
        // The synthesizer must propagate this so the user sees the real problem, not a
        // misleading downstream runtime failure from a degraded template-synthesized agent.
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("Not implemented"));
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Analyse data trends");

        assertThatThrownBy(() -> synthesizer.synthesize(task, ctx))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Not implemented");
    }

    // ========================
    // LLM returns null response
    // ========================

    @Test
    void synthesize_llmReturnsNull_fallsBackToTemplate() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(null);
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Design a UI");

        Agent agent = synthesizer.synthesize(task, ctx);

        // Falls back to template: "Design" -> "Designer"
        assertThat(agent.getRole()).isEqualTo("Designer");
    }

    // ========================
    // AgentSynthesizer.llmBased() factory
    // ========================

    @Test
    void llmBasedFactory_returnsCorrectInstance() {
        AgentSynthesizer s = AgentSynthesizer.llmBased();
        assertThat(s).isInstanceOf(LlmBasedAgentSynthesizer.class);
    }

    @Test
    void llmBasedFactory_eachCallReturnsNewInstance() {
        AgentSynthesizer s1 = AgentSynthesizer.llmBased();
        AgentSynthesizer s2 = AgentSynthesizer.llmBased();
        assertThat(s1).isNotSameAs(s2);
    }
}
