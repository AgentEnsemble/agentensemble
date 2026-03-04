package net.agentensemble.tools.web.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link WebSearchProvider} implementation backed by the Tavily Search API.
 *
 * <p>Tavily is a search API designed for AI agents. See <a href="https://tavily.com">tavily.com</a>.
 */
final class TavilySearchProvider implements WebSearchProvider {

    private static final String TAVILY_API_URL = "https://api.tavily.com/search";
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String apiKey;
    private final HttpClient httpClient;

    TavilySearchProvider(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Package-private constructor for testing with a controllable HttpClient. */
    TavilySearchProvider(String apiKey, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    @Override
    public String search(String query) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_key", apiKey);
        body.put("query", query);
        body.put("search_depth", "basic");
        body.put("max_results", DEFAULT_MAX_RESULTS);
        String requestBody = OBJECT_MAPPER.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TAVILY_API_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Tavily API returned HTTP " + response.statusCode() + ": " + response.body());
        }

        return parseResults(response.body());
    }

    /** Package-private for testing. */
    String parseResults(String responseBody) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        JsonNode results = root.get("results");
        if (results == null || results.isEmpty()) {
            return "No results found.";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (JsonNode result : results) {
            sb.append(index++).append(". ");
            JsonNode title = result.get("title");
            JsonNode url = result.get("url");
            JsonNode content = result.get("content");
            if (title != null) {
                sb.append(title.asText()).append("\n");
            }
            if (url != null) {
                sb.append("   URL: ").append(url.asText()).append("\n");
            }
            if (content != null) {
                sb.append("   ").append(content.asText()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
