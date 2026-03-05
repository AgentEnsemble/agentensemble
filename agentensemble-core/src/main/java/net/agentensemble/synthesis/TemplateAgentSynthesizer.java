package net.agentensemble.synthesis;

import java.util.Locale;
import java.util.Map;
import net.agentensemble.Agent;
import net.agentensemble.Task;

/**
 * Template-based {@link AgentSynthesizer} that derives an agent persona deterministically
 * from the task description without making any LLM calls.
 *
 * <p>The role is derived by extracting the first verb from the task description and
 * looking it up in a built-in verb-to-role table. For example:
 * <ul>
 *   <li>"Research AI trends" -&gt; role "Researcher"</li>
 *   <li>"Write a blog post" -&gt; role "Writer"</li>
 *   <li>"Analyse the data" -&gt; role "Analyst"</li>
 * </ul>
 *
 * <p>If the first word is not found in the table, the role defaults to {@code "Agent"}.
 *
 * <p>The goal is set directly from the task description. The backstory is a minimal
 * sentence derived from the role.
 *
 * <p>This synthesizer is stateless and thread-safe.
 */
class TemplateAgentSynthesizer implements AgentSynthesizer {

    private static final Map<String, String> VERB_TO_ROLE = Map.ofEntries(
            Map.entry("research", "Researcher"),
            Map.entry("investigate", "Researcher"),
            Map.entry("find", "Researcher"),
            Map.entry("discover", "Researcher"),
            Map.entry("search", "Researcher"),
            Map.entry("write", "Writer"),
            Map.entry("draft", "Writer"),
            Map.entry("compose", "Writer"),
            Map.entry("document", "Writer"),
            Map.entry("create", "Creator"),
            Map.entry("generate", "Generator"),
            Map.entry("analyze", "Analyst"),
            Map.entry("analyse", "Analyst"),
            Map.entry("evaluate", "Analyst"),
            Map.entry("assess", "Analyst"),
            Map.entry("review", "Reviewer"),
            Map.entry("audit", "Auditor"),
            Map.entry("design", "Designer"),
            Map.entry("plan", "Planner"),
            Map.entry("build", "Developer"),
            Map.entry("implement", "Developer"),
            Map.entry("develop", "Developer"),
            Map.entry("code", "Developer"),
            Map.entry("program", "Developer"),
            Map.entry("test", "Tester"),
            Map.entry("verify", "Tester"),
            Map.entry("validate", "Validator"),
            Map.entry("summarize", "Summarizer"),
            Map.entry("summarise", "Summarizer"),
            Map.entry("translate", "Translator"),
            Map.entry("classify", "Classifier"),
            Map.entry("categorize", "Classifier"),
            Map.entry("extract", "Extractor"),
            Map.entry("compare", "Analyst"),
            Map.entry("monitor", "Monitor"),
            Map.entry("track", "Tracker"),
            Map.entry("scrape", "Collector"),
            Map.entry("parse", "Parser"),
            Map.entry("convert", "Converter"),
            Map.entry("calculate", "Calculator"),
            Map.entry("compute", "Calculator"),
            Map.entry("format", "Formatter"),
            Map.entry("clean", "Cleaner"),
            Map.entry("process", "Processor"));

    @Override
    public Agent synthesize(Task task, SynthesisContext context) {
        String description = task.getDescription();
        String role = extractRole(description);
        String goal = description;
        String backstory = buildBackstory(role);

        return Agent.builder()
                .role(role)
                .goal(goal)
                .background(backstory)
                .llm(context.model())
                .build();
    }

    /**
     * Extract a role noun from the task description by looking up the first word
     * (lowercased, letters only) in the verb-to-role table.
     *
     * @param description the task description; must not be null
     * @return the derived role noun, or {@code "Agent"} if no match is found
     */
    static String extractRole(String description) {
        if (description == null || description.isBlank()) {
            return "Agent";
        }
        String[] words = description.strip().split("\\s+");
        if (words.length == 0) {
            return "Agent";
        }
        String firstWord = words[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return VERB_TO_ROLE.getOrDefault(firstWord, "Agent");
    }

    private static String buildBackstory(String role) {
        return "An expert " + role + " with extensive domain knowledge and professional experience.";
    }
}
