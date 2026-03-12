package net.agentensemble.reflection;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ReflectionStrategy} that uses an LLM call to produce task improvement notes.
 *
 * <p>Sends a structured prompt to the configured model asking it to analyze the task's
 * definition and output, then parses the response into a {@link TaskReflection}. The
 * prompt format is defined by {@link ReflectionPromptBuilder}.
 *
 * <p>If the LLM response cannot be parsed into the expected format, this strategy falls
 * back gracefully: the {@code refinedDescription} and {@code refinedExpectedOutput} are
 * set to the originals, and the raw response is placed in observations. This ensures
 * reflection failures are non-fatal.
 */
public final class LlmReflectionStrategy implements ReflectionStrategy {

    private static final Logger log = LoggerFactory.getLogger(LlmReflectionStrategy.class);

    private static final String SECTION_REFINED_DESCRIPTION = "REFINED_DESCRIPTION:";
    private static final String SECTION_REFINED_EXPECTED_OUTPUT = "REFINED_EXPECTED_OUTPUT:";
    private static final String SECTION_OBSERVATIONS = "OBSERVATIONS:";
    private static final String SECTION_SUGGESTIONS = "SUGGESTIONS:";

    private final ChatModel model;

    /**
     * Creates a new strategy that uses the given model for reflection calls.
     *
     * @param model the LLM to call; must not be null
     */
    public LlmReflectionStrategy(ChatModel model) {
        this.model = Objects.requireNonNull(model, "model must not be null");
    }

    @Override
    public TaskReflection reflect(ReflectionInput input) {
        String prompt = ReflectionPromptBuilder.buildPrompt(input);
        log.debug(
                "Reflecting on task '{}' (run {})",
                abbreviate(input.task().getDescription(), 60),
                input.priorReflection().map(p -> p.runCount() + 1).orElse(1));

        String rawResponse;
        try {
            ChatRequest request =
                    ChatRequest.builder().messages(UserMessage.from(prompt)).build();
            ChatResponse response = model.chat(request);
            rawResponse = response.aiMessage().text();
        } catch (Exception e) {
            log.warn(
                    "Reflection LLM call failed for task '{}': {}",
                    abbreviate(input.task().getDescription(), 60),
                    e.getMessage());
            return buildFallback(input, "LLM call failed: " + e.getMessage());
        }

        try {
            return parse(rawResponse, input);
        } catch (Exception e) {
            log.warn(
                    "Failed to parse reflection response for task '{}': {}",
                    abbreviate(input.task().getDescription(), 60),
                    e.getMessage());
            return buildFallback(input, "Response parsing failed. Raw response: " + rawResponse);
        }
    }

    /**
     * Parses the structured LLM response into a {@link TaskReflection}.
     *
     * <p>Expects the response to contain the following sections (in order):
     * <pre>
     * REFINED_DESCRIPTION:
     * ...
     * REFINED_EXPECTED_OUTPUT:
     * ...
     * OBSERVATIONS:
     * - ...
     * SUGGESTIONS:
     * - ...
     * </pre>
     */
    TaskReflection parse(String response, ReflectionInput input) {
        String refinedDescription =
                extractSection(response, SECTION_REFINED_DESCRIPTION, SECTION_REFINED_EXPECTED_OUTPUT);
        String refinedExpectedOutput = extractSection(response, SECTION_REFINED_EXPECTED_OUTPUT, SECTION_OBSERVATIONS);
        List<String> observations = extractBulletList(response, SECTION_OBSERVATIONS, SECTION_SUGGESTIONS);
        List<String> suggestions = extractBulletListToEnd(response, SECTION_SUGGESTIONS);

        // Fall back to originals if sections are empty
        if (refinedDescription.isBlank()) {
            refinedDescription = input.task().getDescription();
        }
        if (refinedExpectedOutput.isBlank()) {
            refinedExpectedOutput = input.task().getExpectedOutput();
        }

        return input.priorReflection().isPresent()
                ? TaskReflection.fromPrior(
                        refinedDescription,
                        refinedExpectedOutput,
                        observations,
                        suggestions,
                        input.priorReflection().get())
                : TaskReflection.ofFirstRun(refinedDescription, refinedExpectedOutput, observations, suggestions);
    }

    /**
     * Extracts the text content between two section headers, trimmed.
     */
    private static String extractSection(String response, String startMarker, String endMarker) {
        int startIdx = response.indexOf(startMarker);
        if (startIdx < 0) {
            return "";
        }
        startIdx += startMarker.length();
        int endIdx = response.indexOf(endMarker, startIdx);
        String content = endIdx < 0 ? response.substring(startIdx) : response.substring(startIdx, endIdx);
        return content.strip();
    }

    /**
     * Extracts bullet list items between two section headers.
     */
    private static List<String> extractBulletList(String response, String startMarker, String endMarker) {
        String section = extractSection(response, startMarker, endMarker);
        return parseBulletItems(section);
    }

    /**
     * Extracts bullet list items from a section to the end of the response.
     */
    private static List<String> extractBulletListToEnd(String response, String startMarker) {
        int startIdx = response.indexOf(startMarker);
        if (startIdx < 0) {
            return List.of();
        }
        String section = response.substring(startIdx + startMarker.length()).strip();
        return parseBulletItems(section);
    }

    private static List<String> parseBulletItems(String section) {
        List<String> items = new ArrayList<>();
        // Split with limit -1 to preserve trailing empty strings (satisfies StringSplitter check)
        for (String line : section.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("- ")) {
                String item = trimmed.substring(2).strip();
                if (!item.isBlank()) {
                    items.add(item);
                }
            } else if (trimmed.startsWith("* ")) {
                String item = trimmed.substring(2).strip();
                if (!item.isBlank()) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    private static TaskReflection buildFallback(ReflectionInput input, String reason) {
        String desc = input.task().getDescription();
        String output = input.task().getExpectedOutput();
        List<String> obs = List.of("Reflection could not be completed: " + reason);
        List<String> sugg = List.of();
        return input.priorReflection().isPresent()
                ? TaskReflection.fromPrior(
                        desc, output, obs, sugg, input.priorReflection().get())
                : TaskReflection.ofFirstRun(desc, output, obs, sugg);
    }

    private static String abbreviate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
}
