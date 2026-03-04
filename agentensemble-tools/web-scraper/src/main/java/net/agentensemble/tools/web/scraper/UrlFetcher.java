package net.agentensemble.tools.web.scraper;

import java.io.IOException;

/**
 * Fetches raw HTTP content from a URL. Package-private -- used internally by
 * {@link WebScraperTool} and injectable for testing.
 */
@FunctionalInterface
interface UrlFetcher {

    /**
     * Fetches the content at the given URL and returns it as a string.
     *
     * @param url the URL to fetch
     * @return the response body as a string
     * @throws IOException if a network or I/O error occurs
     * @throws InterruptedException if the operation is interrupted
     */
    String fetch(String url) throws IOException, InterruptedException;
}
