package net.agentensemble.tool;

/**
 * Base class for {@link TypedAgentTool} implementations that provides automatic JSON
 * deserialization, built-in metrics, structured logging, and exception handling.
 *
 * <p>Extend this class (rather than implementing {@link TypedAgentTool} directly) to get
 * the full framework treatment: timing, success/failure/error counters, exception safety,
 * and optional human approval gates from {@link AbstractAgentTool}.
 *
 * <h2>Usage</h2>
 *
 * <p>Declare a record for the tool's input, then extend this class:
 *
 * <pre>
 * {@literal @}ToolInput(description = "Parameters for an HTTP request")
 * public record HttpRequestInput(
 *     {@literal @}ToolParam(description = "The URL to request") String url,
 *     {@literal @}ToolParam(description = "HTTP method (GET, POST, PUT, DELETE)", required = false) String method
 * ) {}
 *
 * public final class HttpAgentTool extends AbstractTypedAgentTool{@literal <}HttpRequestInput{@literal >} {
 *
 *     {@literal @}Override public String name()        { return "http_request"; }
 *     {@literal @}Override public String description() { return "Makes HTTP requests to URLs."; }
 *
 *     {@literal @}Override
 *     public Class{@literal <}HttpRequestInput{@literal >} inputType() {
 *         return HttpRequestInput.class;
 *     }
 *
 *     {@literal @}Override
 *     public ToolResult execute(HttpRequestInput input) {
 *         // input.url() and input.method() are already typed -- no parsing needed
 *         return ToolResult.success("...");
 *     }
 * }
 * </pre>
 *
 * <h2>How execution flows</h2>
 *
 * <ol>
 *   <li>The framework calls {@link #execute(String)} with the full LLM JSON arguments
 *   <li>{@link AbstractAgentTool#execute(String)} (final) wraps with metrics and timing
 *   <li>{@link #doExecute(String)} deserializes the JSON into an instance of {@code T}
 *       using {@link ToolInputDeserializer}
 *   <li>Validation errors produce a {@link ToolResult#failure(String)} with a clear message
 *   <li>{@link TypedAgentTool#execute(Object)} is called with the typed input
 * </ol>
 *
 * <h2>Required fields</h2>
 *
 * <p>Record components annotated with {@code @ToolParam(required = false)} are optional.
 * All other components (annotated with {@code @ToolParam} without {@code required=false},
 * or not annotated at all) are required. Missing required fields cause a
 * {@link ToolResult#failure(String)} before {@code execute(T)} is ever called.
 *
 * <h2>Backward compatibility</h2>
 *
 * <p>The legacy {@link AgentTool} interface and {@link AbstractAgentTool} are unchanged.
 * This class is an opt-in upgrade for tools that want typed inputs.
 *
 * @param <T> the input record type; must be a Java record
 * @see TypedAgentTool
 * @see ToolInput
 * @see ToolParam
 * @see ToolSchemaGenerator
 * @see ToolInputDeserializer
 */
public abstract class AbstractTypedAgentTool<T> extends AbstractAgentTool implements TypedAgentTool<T> {

    /**
     * Deserializes the JSON arguments and delegates to {@code execute(T)}.
     *
     * <p>This method is {@code final} to preserve the deserialization contract.
     * Override {@code execute(T)} to implement the tool's business logic.
     *
     * @param argumentsJson the full JSON arguments from the LLM tool call
     * @return the ToolResult from the typed execute method
     * @throws IllegalArgumentException propagated from {@link ToolInputDeserializer} if
     *                                  the JSON is malformed or required fields are missing;
     *                                  caught by {@link AbstractAgentTool#execute(String)}
     *                                  and returned as a {@link ToolResult#failure(String)}
     */
    @Override
    protected final ToolResult doExecute(String argumentsJson) {
        T typedInput = ToolInputDeserializer.deserialize(argumentsJson, inputType());
        return execute(typedInput);
    }
}
