package net.agentensemble.tools.web.search;

import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;

/**
 * Typed input record for {@link WebSearchTool}.
 *
 * <p>Example LLM tool call:
 * <pre>
 * { "query": "Java 21 virtual threads tutorial" }
 * </pre>
 */
@ToolInput(description = "Parameters for a web search")
public record WebSearchInput(
        @ToolParam(description = "Search query string, e.g. 'Java 21 virtual threads'") String query) {}
