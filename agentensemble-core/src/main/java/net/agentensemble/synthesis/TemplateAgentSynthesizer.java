package net.agentensemble.synthesis;

import java.util.Locale;
import java.util.Map;
import net.agentensemble.Agent;
import net.agentensemble.Task;

/**
 * Template-based {@link AgentSynthesizer} that derives an agent persona deterministically
 * from the task description without making any LLM calls.
 *
 * <p>The role is derived by scanning the first {@value #VERB_SCAN_LIMIT} words of the
 * task description (lowercased, punctuation stripped) and looking each word up in a
 * built-in verb-to-role table. The first matching word wins. For example:
 * <ul>
 *   <li>"Research AI trends" -&gt; role "Researcher" (1st word match)</li>
 *   <li>"Write a blog post" -&gt; role "Writer" (1st word match)</li>
 *   <li>"Based on the analysis, write a summary" -&gt; role "Writer" (5th word match)</li>
 *   <li>"Role: Analyst. Analyse the market" -&gt; role "Analyst" (3rd word match)</li>
 *   <li>"Please research the topic" -&gt; role "Researcher" (2nd word match)</li>
 * </ul>
 *
 * <p>If no recognized verb is found within the scan window, the role defaults to
 * {@code "Agent"}.
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
     * Maximum number of words scanned from the start of the task description when
     * looking for a recognized verb. Scanning beyond the first word handles common
     * task-first patterns where the action verb appears after a preamble:
     *
     * <ul>
     *   <li>{@code "Based on the analysis, write a summary"} -- "write" at the 5th word</li>
     *   <li>{@code "Role: Analyst. Analyse the market"} -- "analyse" at the 3rd word</li>
     *   <li>{@code "Please research the topic"} -- "research" at the 2nd word</li>
     * </ul>
     *
     * <p>The limit prevents over-scanning long descriptions where the first matching
     * verb might appear in a subordinate clause with unrelated semantics.
     */
    static final int VERB_SCAN_LIMIT = 8;

    /**
     * Extract a role noun from the task description by scanning the first
     * {@value #VERB_SCAN_LIMIT} words (lowercased, letters only) for a match in the
     * verb-to-role table. The first matching word wins.
     *
     * <p>Scanning beyond the first word handles task-first patterns where the action
     * verb follows a preposition, article, or persona prefix (e.g. "Role: Analyst.").
     * If no match is found within the scan window, the role defaults to {@code "Agent"}.
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
        int limit = Math.min(words.length, VERB_SCAN_LIMIT);
        for (int i = 0; i < limit; i++) {
            String word = words[i].toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (!word.isEmpty()) {
                String role = VERB_TO_ROLE.get(word);
                if (role != null) {
                    return role;
                }
            }
        }
        return "Agent";
    }

    private static String buildBackstory(String role) {
        return "An expert " + role + " with extensive domain knowledge and professional experience.";
    }
}
