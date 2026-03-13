package net.agentensemble.tools.web.search;

import java.io.IOException;
import java.util.Objects;
import net.agentensemble.tool.AbstractTypedAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that performs a web search and returns formatted results.
 *
 * <p>Uses a {@link WebSearchProvider} to perform the actual search. Built-in providers are
 * available via factory methods:
 *
 * <ul>
 *   <li>{@link #ofTavily(String)} -- Tavily Search API
 *   <li>{@link #ofSerpApi(String)} -- SerpAPI (Google)
 *   <li>{@link #of(WebSearchProvider)} -- custom provider
 * </ul>
 *
 * <p>Input: a {@link WebSearchInput} record with a {@code query} field.
 *
 * <p>Output: formatted search results as plain text, ready for the agent to read.
 */
public final class WebSearchTool extends AbstractTypedAgentTool<WebSearchInput> {

    private final WebSearchProvider provider;

    private WebSearchTool(WebSearchProvider provider) {
        this.provider = provider;
    }

    /**
     * Creates a WebSearchTool with a custom {@link WebSearchProvider}.
     *
     * @param provider the search provider to use; must not be null
     * @return a new WebSearchTool
     * @throws NullPointerException if provider is null
     */
    public static WebSearchTool of(WebSearchProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        return new WebSearchTool(provider);
    }

    /**
     * Creates a WebSearchTool backed by the Tavily Search API.
     *
     * @param apiKey the Tavily API key; must not be null
     * @return a new WebSearchTool
     * @throws NullPointerException if apiKey is null
     */
    public static WebSearchTool ofTavily(String apiKey) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        return new WebSearchTool(new TavilySearchProvider(apiKey));
    }

    /**
     * Creates a WebSearchTool backed by SerpAPI (Google search).
     *
     * @param apiKey the SerpAPI key; must not be null
     * @return a new WebSearchTool
     * @throws NullPointerException if apiKey is null
     */
    public static WebSearchTool ofSerpApi(String apiKey) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        return new WebSearchTool(new SerpApiSearchProvider(apiKey));
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Performs a web search and returns relevant results.";
    }

    @Override
    public Class<WebSearchInput> inputType() {
        return WebSearchInput.class;
    }

    @Override
    public ToolResult execute(WebSearchInput input) {
        String query = input.query().trim();
        if (query.isBlank()) {
            return ToolResult.failure("Search query must not be blank");
        }
        try {
            String results = provider.search(query);
            return ToolResult.success(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Search was interrupted");
        } catch (IOException e) {
            return ToolResult.failure("Search failed: " + e.getMessage());
        }
    }
}
