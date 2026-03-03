package net.agentensemble.output;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses structured (JSON) output from raw LLM responses.
 *
 * Handles JSON extraction from:
 * <ul>
 *   <li>Plain JSON responses (trimmed text starting and ending with braces/brackets)</li>
 *   <li>Responses wrapped in markdown code fences ({@code ```json ... ```} or
 *       {@code ``` ... ```})</li>
 *   <li>Responses with leading or trailing prose (extracts the first
 *       JSON object or array using a regex scan)</li>
 * </ul>
 *
 * Deserialization is performed with Jackson {@code ObjectMapper} configured to:
 * <ul>
 *   <li>Ignore unknown JSON properties ({@code FAIL_ON_UNKNOWN_PROPERTIES = false})</li>
 *   <li>Fail on null values for primitive fields
 *       ({@code FAIL_ON_NULL_FOR_PRIMITIVES = true})</li>
 * </ul>
 */
public final class StructuredOutputParser {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputParser.class);

    /** Matches markdown code fences: ```json ... ``` or ``` ... ``` */
    private static final Pattern MARKDOWN_FENCE_PATTERN =
            Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```", Pattern.CASE_INSENSITIVE);

    /** Matches the first JSON object or array embedded in prose (non-greedy to find the first block). */
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("(?s)([\\[\\{].*?[\\]\\}])", Pattern.DOTALL);

    private static final ObjectMapper OBJECT_MAPPER = buildObjectMapper();

    private StructuredOutputParser() {
        // Utility class
    }

    /**
     * Attempt to extract and parse JSON from a raw LLM response.
     *
     * <p>Handles both structured (object/array) and scalar JSON outputs:
     * <ul>
     *   <li>Object/array types ({@code records}, {@code POJOs}, {@code Map}, {@code List}):
     *       {@link #extractJson(String)} locates the JSON block first.</li>
     *   <li>Scalar types ({@code Boolean}, numeric wrappers, {@code String} as quoted JSON):
     *       When no object/array block is found, the raw text is attempted directly via
     *       Jackson, enabling parsing of bare values like {@code true}, {@code 42}, or
     *       {@code "quoted text"}.</li>
     * </ul>
     *
     * @param raw  the raw LLM response text
     * @param type the target class to deserialize into
     * @param <T>  the target type
     * @return a {@link ParseResult} carrying the parsed value on success,
     *         or a descriptive error message on failure
     */
    public static <T> ParseResult<T> parse(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return ParseResult.failure("LLM produced an empty response");
        }

        String json = extractJson(raw);

        if (json == null) {
            // extractJson only finds object/array blocks. For scalar output types
            // (Boolean, numbers, JSON-quoted strings) the response may be a bare JSON
            // value -- attempt a direct Jackson parse of the stripped text as a fallback.
            String trimmed = raw.strip();
            try {
                T value = OBJECT_MAPPER.readValue(trimmed, type);
                log.debug("Structured scalar output parsed successfully into {} from bare value", type.getSimpleName());
                return ParseResult.success(value);
            } catch (Exception ignored) {
                // Not a parseable scalar JSON value for this type -- fall through
            }
            return ParseResult.failure("Could not find valid JSON in response: " + truncate(raw, 200));
        }

        try {
            T value = OBJECT_MAPPER.readValue(json, type);
            log.debug("Structured output parsed successfully into {}", type.getSimpleName());
            return ParseResult.success(value);
        } catch (Exception e) {
            String errorMsg = "JSON could not be deserialized as " + type.getSimpleName() + ": " + e.getMessage();
            log.debug("Structured output parse failed: {}", errorMsg);
            return ParseResult.failure(errorMsg);
        }
    }

    /**
     * Extract the first JSON object or array from a raw LLM response.
     *
     * Tries, in order:
     * <ol>
     *   <li>Markdown code fence content</li>
     *   <li>The full trimmed response (if it begins and ends with braces/brackets)</li>
     *   <li>A regex scan for the first embedded {@code { ... }} or
     *       {@code [ ... ]} block</li>
     * </ol>
     *
     * Package-private to allow direct testing.
     *
     * @param raw the raw LLM response
     * @return the extracted JSON string, or {@code null} if none was found
     */
    static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        // 1. Try markdown code fence
        Matcher fenceMatcher = MARKDOWN_FENCE_PATTERN.matcher(raw);
        if (fenceMatcher.find()) {
            String fenceContent = fenceMatcher.group(1).strip();
            if (looksLikeJson(fenceContent)) {
                return fenceContent;
            }
        }

        // 2. Try the full trimmed response as-is
        String trimmed = raw.strip();
        if (looksLikeJson(trimmed)) {
            return trimmed;
        }

        // 3. Scan for the first JSON block embedded in prose
        Matcher jsonMatcher = JSON_BLOCK_PATTERN.matcher(raw);
        if (jsonMatcher.find()) {
            String candidate = jsonMatcher.group(1).strip();
            if (looksLikeJson(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private static boolean looksLikeJson(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        return (first == '{' && last == '}') || (first == '[' && last == ']');
    }

    private static ObjectMapper buildObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
