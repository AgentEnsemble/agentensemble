package net.agentensemble.trace;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;

/**
 * Complete record of a single tool invocation within an agent's ReAct loop.
 *
 * <p>Captures the tool name, arguments, result text, timing, and outcome for one
 * tool call. Contained in {@link LlmInteraction}.
 *
 * <p>When the tool is a delegation tool (e.g., {@code delegate} or {@code delegateTask}),
 * the result is the worker agent's text output. The full worker trace is available via the
 * parent {@link TaskTrace} delegations list.
 */
@Value
@Builder(toBuilder = true)
public class ToolCallTrace {

    /** Name of the tool that was invoked. */
    @NonNull
    String toolName;

    /**
     * Arguments passed to the tool as a JSON string.
     * May be an empty object ({@code "{}"}) if the tool takes no arguments.
     */
    @NonNull
    String arguments;

    /**
     * The text result returned to the LLM after tool execution.
     * For failed tools this begins with {@code "Error: "}.
     * For skipped calls (max iterations) this contains a stop message.
     * {@code null} for {@link ToolCallOutcome#ERROR} where an exception was thrown
     * before a result could be produced.
     */
    String result;

    /**
     * Typed structured output from the tool, if the tool returned one via
     * {@link net.agentensemble.tool.ToolResult#getStructuredOutput()}.
     * {@code null} when not applicable or when the tool does not produce structured output.
     */
    Object structuredOutput;

    /** Wall-clock instant when the tool invocation began. */
    @NonNull
    Instant startedAt;

    /** Wall-clock instant when the tool invocation completed. */
    @NonNull
    Instant completedAt;

    /** Elapsed time for the tool invocation ({@code completedAt - startedAt}). */
    @NonNull
    Duration duration;

    /** Outcome of the invocation. */
    @NonNull
    ToolCallOutcome outcome;

    /**
     * Optional caller-supplied metadata attached to this tool call trace.
     * Empty by default; populated by framework extensions or custom tool implementations.
     */
    @Singular("metadataEntry")
    Map<String, Object> metadata;
}
