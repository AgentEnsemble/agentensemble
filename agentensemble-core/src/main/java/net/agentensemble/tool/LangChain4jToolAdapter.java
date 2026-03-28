package net.agentensemble.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.List;
import java.util.Map;
import net.agentensemble.exception.ExitEarlyException;
import net.agentensemble.exception.ToolConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts {@link AgentTool} instances to LangChain4j's tool specification model,
 * and provides execution of AgentTool instances from JSON arguments.
 *
 * Also exposes a utility to extract specifications from objects annotated with
 * {@code @dev.langchain4j.agent.tool.Tool}.
 */
public final class LangChain4jToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jToolAdapter.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String INPUT_PARAM_NAME = "input";
    private static final String INPUT_PARAM_DESCRIPTION = "The input to pass to the tool";

    private LangChain4jToolAdapter() {
        // Utility class -- not instantiable
    }

    /**
     * Convert an {@link AgentTool} to a LangChain4j {@link ToolSpecification}.
     *
     * <p>For {@link TypedAgentTool} instances, the specification is generated from the
     * tool's input record type via {@link ToolSchemaGenerator}, producing a multi-parameter
     * typed schema. The LLM receives individual named parameters with types and descriptions,
     * rather than a single opaque string.
     *
     * <p>For legacy {@link AgentTool} instances that do not implement {@code TypedAgentTool},
     * the specification has a single required string parameter named {@code "input"}, which
     * is the original behavior and is fully backward compatible.
     *
     * @param tool the AgentTool to adapt
     * @return a ToolSpecification for use with ChatModel.chat(ChatRequest)
     */
    public static ToolSpecification toSpecification(AgentTool tool) {
        JsonObjectSchema parameters;

        if (tool instanceof CustomSchemaAgentTool custom) {
            parameters = custom.parameterSchema();
            if (log.isDebugEnabled()) {
                log.debug("Using custom parameter schema for AgentTool '{}'", tool.name());
            }
        } else if (tool instanceof TypedAgentTool<?> typed) {
            parameters = ToolSchemaGenerator.generateSchema(typed.inputType());
            if (log.isDebugEnabled()) {
                log.debug(
                        "Generated typed schema for AgentTool '{}' from input type '{}'",
                        tool.name(),
                        typed.inputType().getSimpleName());
            }
        } else {
            parameters = JsonObjectSchema.builder()
                    .addStringProperty(INPUT_PARAM_NAME, INPUT_PARAM_DESCRIPTION)
                    .required(List.of(INPUT_PARAM_NAME))
                    .build();
            if (log.isDebugEnabled()) {
                log.debug("Adapted AgentTool '{}' to LangChain4j ToolSpecification", tool.name());
            }
        }

        return ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(parameters)
                .build();
    }

    /**
     * Execute an {@link AgentTool} with arguments provided as a JSON string.
     *
     * The arguments JSON is expected to contain an "input" key with the string value
     * to pass to {@link AgentTool#execute(String)}. If the JSON is malformed or the
     * "input" key is absent, the raw arguments string is passed directly to execute().
     *
     * @param tool the AgentTool to execute
     * @param argumentsJson the JSON arguments from the LLM tool call
     * @return the tool's output string, or an "Error: ..." string on failure
     */
    public static String execute(AgentTool tool, String argumentsJson) {
        ToolResult result = executeForResult(tool, argumentsJson);
        if (result.isSuccess()) {
            return result.getOutput();
        } else {
            return "Error: " + result.getErrorMessage();
        }
    }

    /**
     * Execute an {@link AgentTool} with arguments provided as a JSON string, returning
     * the full {@link ToolResult} including any structured output.
     *
     * <p>For {@link TypedAgentTool} instances, the full {@code argumentsJson} is passed
     * directly to {@link AgentTool#execute(String)} so that
     * {@link AbstractTypedAgentTool#doExecute(String)} can deserialize all parameters.
     *
     * <p>For legacy {@link AgentTool} instances, the {@code "input"} key is extracted
     * from {@code argumentsJson} (original behavior, fully backward compatible).
     *
     * @param tool          the AgentTool to execute
     * @param argumentsJson the JSON arguments from the LLM tool call
     * @return the ToolResult; never null
     */
    public static ToolResult executeForResult(AgentTool tool, String argumentsJson) {
        // Typed tools receive the full JSON arguments so AbstractTypedAgentTool can
        // deserialize all parameters. Legacy tools receive only the "input" key value.
        String input = (tool instanceof TypedAgentTool<?> || tool instanceof CustomSchemaAgentTool)
                ? argumentsJson
                : extractInput(argumentsJson);
        try {
            ToolResult result = tool.execute(input);
            if (result == null) {
                return ToolResult.success("");
            }
            return result;
        } catch (ExitEarlyException e) {
            // Re-throw exit-early signals without converting to a tool failure.
            // The workflow executor catches this and assembles partial results.
            throw e;
        } catch (ToolConfigurationException e) {
            // Re-throw tool configuration errors (e.g., requireApproval=true with no ReviewHandler,
            // or missing agentensemble-review module). These are programmer errors that must surface
            // clearly. Ordinary IllegalStateException from tool code is NOT re-thrown here.
            if (log.isErrorEnabled()) {
                log.error("AgentTool '{}' configuration error: {}", tool.name(), e.getMessage());
            }
            throw e;
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("AgentTool '{}' threw exception during execution: {}", tool.name(), e.getMessage(), e);
            }
            return ToolResult.failure(e.getMessage());
        }
    }

    /**
     * Extract tool specifications from an object with {@code @Tool}-annotated methods.
     *
     * @param annotatedObject an object with one or more @Tool-annotated methods
     * @return list of ToolSpecification extracted via reflection
     */
    public static List<ToolSpecification> specificationsFrom(Object annotatedObject) {
        return ToolSpecifications.toolSpecificationsFrom(annotatedObject);
    }

    /**
     * Execute a @Tool-annotated method on an object via reflection.
     *
     * @param toolObject the object containing the @Tool-annotated method
     * @param methodName the name of the method to call
     * @param argumentsJson the JSON arguments from the LLM tool call
     * @return the method's return value as a string, or "Error: ..." on failure
     */
    public static String executeAnnotatedTool(Object toolObject, String methodName, String argumentsJson) {
        try {
            // Parse arguments as a map
            Map<String, Object> args = parseArgs(argumentsJson);

            // Find the method by name
            java.lang.reflect.Method method = findMethod(toolObject, methodName);
            if (method == null) {
                return "Error: Tool method '" + methodName + "' not found";
            }

            // Build argument array in parameter order
            java.lang.reflect.Parameter[] params = method.getParameters();
            Object[] argValues = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                String paramName = params[i].getName();
                Object value = args.get(paramName);
                argValues[i] = convertToType(value, params[i].getType());
            }

            method.setAccessible(true);
            Object result = method.invoke(toolObject, argValues);
            return result != null ? result.toString() : "";

        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Annotated tool '{}' threw exception: {}", methodName, e.getMessage());
            }
            return "Error: " + e.getMessage();
        }
    }

    // ========================
    // Private helpers
    // ========================

    private static String extractInput(String argumentsJson) {
        try {
            Map<String, Object> args = OBJECT_MAPPER.readValue(argumentsJson, MAP_TYPE);
            Object input = args.get(INPUT_PARAM_NAME);
            return input != null ? input.toString() : argumentsJson;
        } catch (Exception e) {
            // Not valid JSON or no "input" key -- pass the raw string
            return argumentsJson;
        }
    }

    private static Map<String, Object> parseArgs(String argumentsJson) {
        try {
            return OBJECT_MAPPER.readValue(argumentsJson, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static java.lang.reflect.Method findMethod(Object obj, String methodName) {
        for (java.lang.reflect.Method m : obj.getClass().getMethods()) {
            if (m.getName().equals(methodName) && m.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                return m;
            }
        }
        return null;
    }

    private static Object convertToType(Object value, Class<?> targetType) {
        if (value == null) {
            // Return primitive defaults to avoid NPE from Method.invoke auto-unboxing
            if (targetType == int.class) return 0;
            if (targetType == long.class) return 0L;
            if (targetType == double.class) return 0.0;
            if (targetType == boolean.class) return false;
            return null;
        }
        if (targetType == String.class) {
            return value.toString();
        }
        if (targetType == int.class || targetType == Integer.class) {
            return ((Number) value).intValue();
        }
        if (targetType == long.class || targetType == Long.class) {
            return ((Number) value).longValue();
        }
        if (targetType == double.class || targetType == Double.class) {
            return ((Number) value).doubleValue();
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.valueOf(value.toString());
        }
        return value;
    }
}
