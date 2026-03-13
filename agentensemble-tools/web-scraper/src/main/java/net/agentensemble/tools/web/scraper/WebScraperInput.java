package net.agentensemble.tools.web.scraper;

import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;

/**
 * Typed input record for {@link WebScraperTool}.
 *
 * <p>Example LLM tool call:
 * <pre>
 * { "url": "https://example.com/article" }
 * </pre>
 */
@ToolInput(description = "Parameters for scraping a web page")
public record WebScraperInput(
        @ToolParam(
                        description = "Full URL of the web page to fetch and extract text from, "
                                + "e.g. 'https://example.com/article'")
                String url) {}
