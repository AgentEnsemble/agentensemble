package net.agentensemble.tool;

/**
 * A {@link AgentTool} that declares a structured, typed input record.
 *
 * <p>Implement this interface (or extend {@link AbstractTypedAgentTool}) instead of
 * {@link AgentTool} when the tool accepts more than one parameter or when you want
 * the LLM to receive a proper JSON Schema for the tool's inputs rather than a single
 * opaque string.
 *
 * <h2>How it works</h2>
 *
 * <p>Declare a Java record annotated with {@link ToolInput} and annotate its components
 * with {@link ToolParam}. The framework introspects the record at startup to generate a
 * typed {@code JsonObjectSchema} for LangChain4j. When the LLM calls the tool, the
 * framework deserializes the JSON arguments into an instance of {@code T} and hands it
 * to {@link #execute(Object)}.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {@literal @}ToolInput(description = "Parameters for an HTTP request")
 * public record HttpRequestInput(
 *     {@literal @}ToolParam(description = "The URL to request") String url,
 *     {@literal @}ToolParam(description = "HTTP method", required = false) String method
 * ) {}
 *
 * public final class HttpAgentTool extends AbstractTypedAgentTool{@literal <}HttpRequestInput{@literal >} {
 *
 *     {@literal @}Override public String name() { return "http_request"; }
 *     {@literal @}Override public String description() { return "Makes HTTP requests to URLs."; }
 *     {@literal @}Override public Class{@literal <}HttpRequestInput{@literal >} inputType() { return HttpRequestInput.class; }
 *
 *     {@literal @}Override
 *     public ToolResult execute(HttpRequestInput input) {
 *         // input.url() and input.method() are already typed and validated
 *     }
 * }
 * </pre>
 *
 * <h2>Backward compatibility</h2>
 *
 * <p>The legacy {@link AgentTool} interface is unchanged and fully supported.
 * Tools that still implement {@code execute(String)} directly continue to work
 * exactly as before. {@code TypedAgentTool} is an opt-in upgrade.
 *
 * @param <T> the input record type; must be a Java record
 * @see AbstractTypedAgentTool
 * @see ToolInput
 * @see ToolParam
 */
public interface TypedAgentTool<T> extends AgentTool {

    /**
     * Returns the class of the input record type {@code T}.
     *
     * <p>Used by the framework to generate the JSON Schema and to deserialize
     * the LLM's JSON arguments into an instance of {@code T}.
     *
     * @return the input type class; never null
     */
    Class<T> inputType();

    /**
     * Execute this tool with a deserialized, typed input object.
     *
     * <p>Called by the framework after deserializing the LLM's JSON arguments
     * into an instance of {@code T}. Implementations receive a fully typed object
     * and do not need to perform any input parsing.
     *
     * @param input the typed input; never null
     * @return a ToolResult indicating success or failure; must not be null
     */
    ToolResult execute(T input);
}
