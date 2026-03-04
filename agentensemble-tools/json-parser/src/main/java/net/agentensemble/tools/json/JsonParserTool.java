package net.agentensemble.tools.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that extracts values from JSON using dot-notation path expressions.
 *
 * <p>Input format: two sections separated by a newline. The first line is the path expression;
 * the remaining lines are the JSON to parse.
 *
 * <p>Path syntax:
 *
 * <ul>
 *   <li>Dot notation for object keys: {@code user.name}
 *   <li>Bracket notation for array indices: {@code items[0]}
 *   <li>Combined: {@code users[1].address.city}
 * </ul>
 *
 * <p>Examples:
 *
 * <pre>
 * // Input:
 * user.name
 * {"user": {"name": "Alice", "age": 30}}
 *
 * // Output: Alice
 * </pre>
 */
public final class JsonParserTool extends AbstractAgentTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Matches a path segment: either "key", "key[n]", or "[n]"
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^\\[\\].]+)?(\\[(\\d+)\\])?");

    @Override
    public String name() {
        return "json_parser";
    }

    @Override
    public String description() {
        return "Extracts values from JSON using a path expression. "
                + "Input format: first line is the path (e.g. 'user.name' or 'items[0].title'), "
                + "second line onwards is the JSON. "
                + "Example:\n"
                + "user.address.city\n"
                + "{\"user\": {\"address\": {\"city\": \"Denver\"}}}";
    }

    @Override
    protected ToolResult doExecute(String input) {
        if (input == null || input.isBlank()) {
            return ToolResult.failure("Input must not be blank");
        }
        int newlineIndex = input.indexOf('\n');
        if (newlineIndex < 0) {
            return ToolResult.failure("Input must have two sections: first line is the path, remaining lines are JSON");
        }

        String path = input.substring(0, newlineIndex).trim();
        String jsonStr = input.substring(newlineIndex + 1).trim();

        if (path.isBlank()) {
            return ToolResult.failure("Path expression must not be blank");
        }
        if (jsonStr.isBlank()) {
            return ToolResult.failure("JSON content must not be blank");
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(jsonStr);
        } catch (JsonProcessingException e) {
            return ToolResult.failure("Invalid JSON: " + e.getOriginalMessage());
        }

        try {
            JsonNode result = navigate(root, path);
            return ToolResult.success(nodeToString(result));
        } catch (PathNotFoundException e) {
            return ToolResult.failure("Path not found: '" + e.getMessage() + "'");
        } catch (ArrayIndexException e) {
            return ToolResult.failure("Array index out of bounds: " + e.getMessage());
        }
    }

    private JsonNode navigate(JsonNode node, String path) {
        List<PathSegment> segments = parsePath(path);
        JsonNode current = node;
        for (PathSegment segment : segments) {
            if (segment.key() != null && !segment.key().isBlank()) {
                if (!current.isObject()) {
                    throw new PathNotFoundException(path);
                }
                current = current.get(segment.key());
                if (current == null) {
                    throw new PathNotFoundException(segment.key());
                }
            }
            if (segment.hasIndex()) {
                if (!current.isArray()) {
                    throw new PathNotFoundException(path);
                }
                int index = segment.index();
                if (index >= current.size() || index < 0) {
                    throw new ArrayIndexException(
                            "index " + index + " is out of bounds for array of size " + current.size());
                }
                current = current.get(index);
            }
        }
        return current;
    }

    private List<PathSegment> parsePath(String path) {
        List<PathSegment> segments = new ArrayList<>();
        // Split on dots, but keep array notation (e.g., "items[0]" stays together).
        // Use -1 limit to preserve trailing empty strings (avoids StringSplitter warning).
        String[] dotParts = path.split("\\.", -1);
        for (String part : dotParts) {
            // Each part may contain array index: e.g. "items[0]", "items[0][1]"
            int pos = 0;
            Matcher m = SEGMENT_PATTERN.matcher(part);
            while (m.find() && m.start() <= pos && m.group().length() > 0) {
                String key = m.group(1);
                boolean hasIndex = m.group(2) != null;
                int index = hasIndex ? Integer.parseInt(m.group(3)) : -1;
                segments.add(new PathSegment(key, hasIndex, index));
                pos = m.end();
            }
        }
        return segments;
    }

    private static String nodeToString(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.isIntegralNumber() ? String.valueOf(node.longValue()) : String.valueOf(node.doubleValue());
        }
        if (node.isBoolean()) {
            return String.valueOf(node.booleanValue());
        }
        if (node.isNull()) {
            return "null";
        }
        // Object or array -- return compact JSON
        return node.toString();
    }

    private record PathSegment(String key, boolean hasIndex, int index) {}

    private static final class PathNotFoundException extends RuntimeException {
        PathNotFoundException(String message) {
            super(message);
        }
    }

    private static final class ArrayIndexException extends RuntimeException {
        ArrayIndexException(String message) {
            super(message);
        }
    }
}
