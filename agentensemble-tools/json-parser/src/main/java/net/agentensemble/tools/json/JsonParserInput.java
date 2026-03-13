package net.agentensemble.tools.json;

import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;

/**
 * Typed input record for {@link JsonParserTool}.
 *
 * <p>Example LLM tool call:
 * <pre>
 * {
 *   "jsonPath": "user.address.city",
 *   "json": "{\"user\": {\"address\": {\"city\": \"Denver\"}}}"
 * }
 * </pre>
 *
 * <p>Supported path syntax:
 * <ul>
 *   <li>Dot notation for object keys: {@code user.name}
 *   <li>Bracket notation for array indices: {@code items[0]}
 *   <li>Combined: {@code users[1].address.city}
 * </ul>
 */
@ToolInput(description = "Parameters for extracting a value from JSON using a path expression")
public record JsonParserInput(
        @ToolParam(
                        description = "Dot-notation path expression to extract, "
                                + "e.g. 'user.name', 'items[0].title', or 'users[1].address.city'")
                String jsonPath,
        @ToolParam(description = "The JSON content to parse") String json) {}
