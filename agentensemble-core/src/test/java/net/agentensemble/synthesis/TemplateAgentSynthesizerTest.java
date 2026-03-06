package net.agentensemble.synthesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link TemplateAgentSynthesizer}.
 *
 * Covers role extraction (verb-to-role mapping), goal derivation, backstory generation,
 * determinism, and edge cases.
 */
class TemplateAgentSynthesizerTest {

    private final AgentSynthesizer synthesizer = AgentSynthesizer.template();

    private Task taskWithDescription(String description) {
        return Task.builder()
                .description(description)
                .expectedOutput("Some output")
                .build();
    }

    // ========================
    // Role extraction: known verbs
    // ========================

    @ParameterizedTest
    @CsvSource({
        "Research AI trends, Researcher",
        "research the topic, Researcher",
        "investigate the issue, Researcher",
        "Write a blog post, Writer",
        "Draft a summary, Writer",
        "Analyze the data, Analyst",
        "Analyse financial results, Analyst",
        "Evaluate the proposal, Analyst",
        "Design a system, Designer",
        "Build a pipeline, Developer",
        "Implement the feature, Developer",
        "Test the output, Tester",
        "Summarize the findings, Summarizer",
        "Summarise the report, Summarizer",
        "Translate the document, Translator",
        "Review the code, Reviewer",
        "Plan the project, Planner",
        "Generate a report, Generator",
        "Extract entities, Extractor",
        "Classify the items, Classifier"
    })
    void extractRole_knownVerb_returnsCorrectRole(String description, String expectedRole) {
        ChatModel model = mock(ChatModel.class);
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription(description);

        Agent agent = synthesizer.synthesize(task, ctx);

        assertThat(agent.getRole()).isEqualTo(expectedRole);
    }

    @Test
    void extractRole_unknownVerb_returnsDefaultAgent() {
        String role = TemplateAgentSynthesizer.extractRole("Schedule a meeting for next week");
        assertThat(role).isEqualTo("Agent");
    }

    // ========================
    // Role extraction: verb appears after first word (task-first persona-in-description patterns)
    // ========================

    @Test
    void extractRole_verbAfterPreposition_scansAheadAndMatchesVerb() {
        // "Based on the analysis, write a summary" -- "write" is at position 4
        String role = TemplateAgentSynthesizer.extractRole("Based on the analysis, write a summary");
        assertThat(role).isEqualTo("Writer");
    }

    @Test
    void extractRole_rolePrefixedDescription_findsVerbBeyondFirstWord() {
        // Task-first persona-in-description: "Role: Analyst. Analyse the market data"
        // First word "Role:" strips to "role", not in table; scans ahead and finds "analyse"
        String role = TemplateAgentSynthesizer.extractRole("Role: Analyst. Analyse the market data");
        assertThat(role).isEqualTo("Analyst");
    }

    @Test
    void extractRole_articleBeforeVerb_scansAheadAndMatchesVerb() {
        // "The team must research AI trends" -- "research" at position 3
        String role = TemplateAgentSynthesizer.extractRole("The team must research AI trends");
        assertThat(role).isEqualTo("Researcher");
    }

    @Test
    void extractRole_usingPreamble_findsVerbWithinScanLimit() {
        // "Using the collected data, write a comprehensive report"
        String role = TemplateAgentSynthesizer.extractRole("Using the collected data, write a comprehensive report");
        assertThat(role).isEqualTo("Writer");
    }

    @Test
    void extractRole_verbBeyondScanLimit_returnsDefaultAgent() {
        // More than 8 words before the first recognized verb: verb is out of scan range
        // "Given all of the available context material and data, analyse the trends" -- "analyse" at position 10
        String role = TemplateAgentSynthesizer.extractRole(
                "Given all of the available context material and data, analyse the trends");
        assertThat(role).isEqualTo("Agent");
    }

    @Test
    void extractRole_secondWordIsKnownVerb_matchesImmediately() {
        // "Please research the topic" -- "research" at position 1
        String role = TemplateAgentSynthesizer.extractRole("Please research the topic");
        assertThat(role).isEqualTo("Researcher");
    }

    @Test
    void extractRole_nullDescription_returnsDefaultAgent() {
        String role = TemplateAgentSynthesizer.extractRole(null);
        assertThat(role).isEqualTo("Agent");
    }

    @Test
    void extractRole_blankDescription_returnsDefaultAgent() {
        String role = TemplateAgentSynthesizer.extractRole("   ");
        assertThat(role).isEqualTo("Agent");
    }

    @Test
    void extractRole_descriptionWithLeadingPunctuation_extractsCorrectly() {
        // "research" is the first word -- punctuation stripped by regex
        String role = TemplateAgentSynthesizer.extractRole("research: AI trends");
        assertThat(role).isEqualTo("Researcher");
    }

    // ========================
    // Synthesize: goal derived from description
    // ========================

    @Test
    void synthesize_goal_isSetFromTaskDescription() {
        ChatModel model = mock(ChatModel.class);
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Research the latest AI trends");

        Agent agent = synthesizer.synthesize(task, ctx);

        assertThat(agent.getGoal()).isEqualTo("Research the latest AI trends");
    }

    // ========================
    // Synthesize: backstory contains role
    // ========================

    @Test
    void synthesize_backstory_containsRole() {
        ChatModel model = mock(ChatModel.class);
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Analyse market data");

        Agent agent = synthesizer.synthesize(task, ctx);

        assertThat(agent.getBackground()).contains("Analyst");
    }

    // ========================
    // Synthesize: LLM is from SynthesisContext
    // ========================

    @Test
    void synthesize_llm_isFromSynthesisContext() {
        ChatModel model = mock(ChatModel.class);
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Write a summary");

        Agent agent = synthesizer.synthesize(task, ctx);

        assertThat(agent.getLlm()).isSameAs(model);
    }

    // ========================
    // Determinism: same input => same output
    // ========================

    @Test
    void synthesize_sameDescription_producesSameAgent() {
        ChatModel model = mock(ChatModel.class);
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Research AI trends");

        Agent agent1 = synthesizer.synthesize(task, ctx);
        Agent agent2 = synthesizer.synthesize(task, ctx);

        assertThat(agent1.getRole()).isEqualTo(agent2.getRole());
        assertThat(agent1.getGoal()).isEqualTo(agent2.getGoal());
        assertThat(agent1.getBackground()).isEqualTo(agent2.getBackground());
    }

    // ========================
    // Agent is fully valid
    // ========================

    @Test
    void synthesize_returnsFullyValidAgent() {
        ChatModel model = mock(ChatModel.class);
        SynthesisContext ctx = SynthesisContext.of(model);
        Task task = taskWithDescription("Write an executive summary");

        Agent agent = synthesizer.synthesize(task, ctx);

        // Agent must have all required fields (role, goal, llm)
        assertThat(agent.getRole()).isNotBlank();
        assertThat(agent.getGoal()).isNotBlank();
        assertThat(agent.getLlm()).isNotNull();
        // Background is optional but should be non-blank for template synthesizer
        assertThat(agent.getBackground()).isNotBlank();
    }

    // ========================
    // AgentSynthesizer.template() factory
    // ========================

    @Test
    void templateFactory_returnsSynthesizerInstance() {
        AgentSynthesizer s = AgentSynthesizer.template();
        assertThat(s).isInstanceOf(TemplateAgentSynthesizer.class);
    }

    @Test
    void templateFactory_eachCallReturnsNewInstance() {
        AgentSynthesizer s1 = AgentSynthesizer.template();
        AgentSynthesizer s2 = AgentSynthesizer.template();
        assertThat(s1).isNotSameAs(s2);
    }
}
