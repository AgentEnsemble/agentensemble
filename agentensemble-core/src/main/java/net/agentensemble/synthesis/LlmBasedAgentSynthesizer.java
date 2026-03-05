package net.agentensemble.synthesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM-based {@link AgentSynthesizer} that invokes the LLM once per task to generate
 * an optimal agent persona.
 *
 * <p>This synthesizer sends a single prompt to the LLM requesting a JSON response
 * containing the agent's role, goal, and backstory. It is non-deterministic -- the
 * same task description may produce different agents on successive runs -- but
 * typically produces higher-quality agent descriptions than the template-based approach.
 *
 * <p>On LLM or JSON parsing failure, falls back to {@link TemplateAgentSynthesizer}
 * to ensure an agent is always produced.
 *
 * <p>This synthesizer is stateless and thread-safe.
 */
class LlmBasedAgentSynthesizer implements AgentSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(LlmBasedAgentSynthesizer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TemplateAgentSynthesizer FALLBACK = new TemplateAgentSynthesizer();

    private static final String SYNTHESIS_PROMPT_TEMPLATE =
            "Generate an expert AI agent persona for the following task.\n\n"
                    + "Task: %s\n\n"
                    + "Respond ONLY with valid JSON in this exact format (no markdown, no explanation):\n"
                    + "{\n"
                    + "  \"role\": \"concise expert title (e.g. Researcher, Analyst, Developer)\",\n"
                    + "  \"goal\": \"the agent's primary objective derived from the task\",\n"
                    + "  \"backstory\": \"brief professional background (1-2 sentences)\"\n"
                    + "}";

    @Override
    public Agent synthesize(Task task, SynthesisContext context) {
        try {
            String prompt = String.format(SYNTHESIS_PROMPT_TEMPLATE, task.getDescription());
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(new UserMessage(prompt)))
                    .build();

            ChatResponse response = context.model().chat(request);
            String json = response.aiMessage().text();

            JsonNode node = MAPPER.readTree(json);
            String role = node.path("role").asText();
            String goal = node.path("goal").asText();
            String backstory = node.path("backstory").asText();

            if (role.isBlank() || goal.isBlank()) {
                throw new IllegalStateException("LLM response missing required fields 'role' or 'goal': " + json);
            }

            return Agent.builder()
                    .role(role)
                    .goal(goal)
                    .background(backstory.isBlank() ? null : backstory)
                    .llm(context.model())
                    .build();

        } catch (Exception e) {
            log.warn(
                    "LLM-based agent synthesis failed for task '{}', falling back to template synthesizer: {}",
                    truncate(task.getDescription(), 80),
                    e.getMessage());
            return FALLBACK.synthesize(task, context);
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
