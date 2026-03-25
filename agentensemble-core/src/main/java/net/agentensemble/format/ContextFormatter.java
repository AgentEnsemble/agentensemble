package net.agentensemble.format;

/**
 * Serializes structured data for inclusion in LLM prompts.
 *
 * <p>Implementations are obtained via {@link ContextFormatters#forFormat(ContextFormat)}.
 * The framework uses the configured formatter to encode context data (prior task outputs,
 * memory entries, tool results) before injecting them into agent prompts.
 *
 * @see ContextFormat
 * @see ContextFormatters
 */
public interface ContextFormatter {

    /**
     * Serialize a Java object to the target format.
     *
     * @param value any Java object (Map, List, record, POJO, primitive, etc.)
     * @return formatted string; never null
     */
    String format(Object value);

    /**
     * Convert a JSON string to the target format.
     *
     * <p>For the JSON formatter this is a no-op (returns the input unchanged).
     * For TOON this parses the JSON and re-encodes it as TOON.
     *
     * @param json a valid JSON string
     * @return formatted string; never null
     */
    String formatJson(String json);
}
