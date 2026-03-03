package net.agentensemble.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.List;
import java.util.Map;
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
     * The generated specification has a single required string parameter named "input",
     * which the LLM uses to pass input text to the tool.
     *
     * @param tool the AgentTool to adapt
     * @return a ToolSpecification for use with ChatModel.chat(ChatRequest)
     */
    public static ToolSpecification toSpecification(AgentTool tool) {
        JsonObjectSchema parameters = JsonObjectSchema.builder()
                .addStringProperty(INPUT_PARAM_NAME, INPUT_PARAM_DESCRIPTION)
                .required(List.of(INPUT_PARAM_NAME))
                .build();

        var spec = ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(parameters)
                .build();

        log.debug("Adapted AgentTool '{}' to LangChain4j ToolSpecification", tool.name());

        return spec;
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
     * @return the tool's output, or an "Error: ..." string on failure
     */
    public static String execute(AgentTool tool, String argumentsJson) {
        String input = extractInput(argumentsJson);
        try {
            ToolResult result = tool.execute(input);
            if (result == null) {
                return "";
            }
            if (result.isSuccess()) {
                return result.getOutput();
            } else {
                return "Error: " + result.getErrorMessage();
            }
        } catch (Exception e) {
            log.warn("AgentTool '{}' threw exception during execution: {}", tool.name(), e.getMessage(), e);
            return "Error: " + e.getMessage();
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
            log.warn("Annotated tool '{}' threw exception: {}", methodName, e.getMessage());
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
