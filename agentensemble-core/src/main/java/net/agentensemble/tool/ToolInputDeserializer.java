package net.agentensemble.tool;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Deserializes JSON arguments from an LLM tool call into a typed Java record.
 *
 * <p>For {@link TypedAgentTool} implementations, the LLM sends a JSON object whose
 * top-level keys are the record component names. This class:
 *
 * <ol>
 *   <li>Parses the JSON using Jackson
 *   <li>Validates that all required fields (those with {@code @ToolParam(required=true)}
 *       or with no {@code @ToolParam} annotation, since required is the default) are
 *       present and non-null
 *   <li>Deserializes the JSON into an instance of the record type
 * </ol>
 *
 * <p>Missing required fields produce an {@link IllegalArgumentException} with a clear
 * message naming all absent parameters. Malformed JSON or type mismatches similarly
 * produce an {@link IllegalArgumentException}. Both are caught by
 * {@link AbstractAgentTool#execute(String)} and returned to the LLM as a
 * {@link ToolResult#failure(String)}.
 *
 * @see TypedAgentTool
 * @see AbstractTypedAgentTool
 */
public final class ToolInputDeserializer {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ToolInputDeserializer() {
        // Utility class -- not instantiable
    }

    /**
     * Deserialize the given JSON string into an instance of the specified record type.
     *
     * @param <T>       the record type
     * @param json      the JSON string from the LLM tool call; must be a JSON object
     * @param inputType the record class to deserialize into; must be a Java record
     * @return the deserialized record instance; never null
     * @throws IllegalArgumentException if the JSON is malformed, required fields are
     *                                  missing, or the JSON cannot be mapped to the type
     */
    public static <T> T deserialize(String json, Class<T> inputType) {
        if (inputType == null) {
            throw new IllegalArgumentException("inputType must not be null");
        }
        if (!inputType.isRecord()) {
            throw new IllegalArgumentException("Input type '" + inputType.getName() + "' must be a Java record");
        }

        // Parse the JSON
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid JSON input for '" + inputType.getSimpleName() + "': " + e.getMessage(), e);
        }

        if (!root.isObject()) {
            throw new IllegalArgumentException("Expected a JSON object for '"
                    + inputType.getSimpleName()
                    + "' but received: "
                    + root.getNodeType());
        }

        // Validate required fields
        RecordComponent[] components = inputType.getRecordComponents();
        List<String> missing = new ArrayList<>();
        for (RecordComponent comp : components) {
            ToolParam param = comp.getAnnotation(ToolParam.class);
            boolean required = (param == null) || param.required();
            if (required) {
                JsonNode field = root.get(comp.getName());
                if (field == null || field.isNull()) {
                    missing.add(comp.getName());
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing required parameter(s) for '"
                    + inputType.getSimpleName()
                    + "': "
                    + String.join(", ", missing));
        }

        // Deserialize into the record type
        try {
            return OBJECT_MAPPER.treeToValue(root, inputType);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to deserialize input for '" + inputType.getSimpleName() + "': " + e.getMessage(), e);
        }
    }
}
