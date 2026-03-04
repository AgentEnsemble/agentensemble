package net.agentensemble.tools;

import java.io.IOException;

/**
 * Provider interface for web search functionality used by {@link WebSearchTool}.
 *
 * <p>Implement this interface to integrate a custom search backend. Two built-in
 * implementations are provided via factory methods on {@link WebSearchTool}:
 * Tavily ({@link WebSearchTool#ofTavily(String)}) and SerpAPI
 * ({@link WebSearchTool#ofSerpApi(String)}).
 *
 * <p>Example custom provider:
 *
 * <pre>
 * WebSearchTool tool = WebSearchTool.of(query -> {
 *     // your HTTP logic here
 *     return formattedResults;
 * });
 * </pre>
 */
@FunctionalInterface
public interface WebSearchProvider {

    /**
     * Performs a web search for the given query and returns formatted results as a string.
     *
     * @param query the search query; non-null and non-blank
     * @return formatted search results as a plain-text string
     * @throws IOException if a network or I/O error occurs
     * @throws InterruptedException if the operation is interrupted
     */
    String search(String query) throws IOException, InterruptedException;
}
