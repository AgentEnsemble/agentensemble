package net.agentensemble.tools.web.scraper;

import java.io.IOException;
import net.agentensemble.tool.AbstractTypedAgentTool;
import net.agentensemble.tool.ToolResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Tool that fetches a web page and extracts its readable text content.
 *
 * <p>Uses Jsoup to parse HTML and extract text, stripping all tags and scripts.
 * The extracted text is truncated to a configurable maximum character length to
 * avoid overwhelming the LLM context window.
 *
 * <p>Usage:
 *
 * <pre>
 * // Default settings (5000 chars, 10s timeout)
 * var scraper = new WebScraperTool();
 *
 * // Custom max content length
 * var scraper = WebScraperTool.withMaxContentLength(3000);
 * </pre>
 *
 * <p>Input: a {@link WebScraperInput} record with a {@code url} field.
 */
public final class WebScraperTool extends AbstractTypedAgentTool<WebScraperInput> {

    private static final int DEFAULT_MAX_CONTENT_LENGTH = 5000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private final int maxContentLength;
    private final UrlFetcher fetcher;

    /** Creates a WebScraperTool with default settings (5000 chars, 10s timeout). */
    public WebScraperTool() {
        this(DEFAULT_MAX_CONTENT_LENGTH, new HttpUrlFetcher(DEFAULT_TIMEOUT_SECONDS));
    }

    /**
     * Creates a WebScraperTool with a custom maximum content length.
     *
     * @param maxContentLength maximum number of characters to return
     * @return a new WebScraperTool
     */
    public static WebScraperTool withMaxContentLength(int maxContentLength) {
        return new WebScraperTool(maxContentLength, new HttpUrlFetcher(DEFAULT_TIMEOUT_SECONDS));
    }

    /** Package-private constructor for testing with a controllable URL fetcher. */
    WebScraperTool(int maxContentLength, UrlFetcher fetcher) {
        this.maxContentLength = maxContentLength;
        this.fetcher = fetcher;
    }

    @Override
    public String name() {
        return "web_scrape";
    }

    @Override
    public String description() {
        return "Fetches a web page and extracts its readable text content. "
                + "Returns the page text with HTML tags removed.";
    }

    @Override
    public Class<WebScraperInput> inputType() {
        return WebScraperInput.class;
    }

    @Override
    public ToolResult execute(WebScraperInput input) {
        String url = input.url().trim();
        if (url.isBlank()) {
            return ToolResult.failure("URL must not be blank");
        }
        try {
            String html = fetcher.fetch(url);
            String text = extractText(html);
            return ToolResult.success(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Web scraping was interrupted");
        } catch (IOException e) {
            return ToolResult.failure("Failed to fetch URL: " + e.getMessage());
        }
    }

    private String extractText(String html) {
        Document doc = Jsoup.parse(html);
        // Remove script and style elements before extracting text
        doc.select("script, style, nav, footer, header").remove();
        String text = doc.body().text();
        if (text.length() > maxContentLength) {
            return text.substring(0, maxContentLength) + " [truncated at " + maxContentLength + " chars]";
        }
        return text;
    }
}
