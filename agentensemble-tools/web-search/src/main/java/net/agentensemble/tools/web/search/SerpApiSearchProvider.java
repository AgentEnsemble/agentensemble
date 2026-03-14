package net.agentensemble.tools.web.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link WebSearchProvider} implementation backed by SerpAPI (Google Search).
 *
 * <p>SerpAPI provides real-time access to Google search results.
 * See <a href="https://serpapi.com">serpapi.com</a>.
 */
final class SerpApiSearchProvider implements WebSearchProvider {

    private static final String SERPAPI_URL = "https://serpapi.com/search.json";
    private static final int DEFAULT_RESULTS = 5;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String apiKey;
    private final HttpClient httpClient;

    SerpApiSearchProvider(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Package-private constructor for testing with a controllable HttpClient. */
    SerpApiSearchProvider(String apiKey, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    @Override
    public String search(String query) throws IOException, InterruptedException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = SERPAPI_URL
                + "?engine=google"
                + "&q=" + encodedQuery
                + "&api_key=" + apiKey
                + "&num=" + DEFAULT_RESULTS;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("SerpAPI returned HTTP " + response.statusCode() + ": " + response.body());
        }

        return parseResults(response.body());
    }

    /** Package-private for testing. */
    String parseResults(String responseBody) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        JsonNode results = root.get("organic_results");
        if (results == null || results.isEmpty()) {
            return "No results found.";
        }
        StringBuilder sb = new StringBuilder(512);
        int index = 1;
        for (JsonNode result : results) {
            sb.append(index++).append(". ");
            JsonNode title = result.get("title");
            JsonNode link = result.get("link");
            JsonNode snippet = result.get("snippet");
            if (title != null) {
                sb.append(title.asText()).append('\n');
            }
            if (link != null) {
                sb.append("   URL: ").append(link.asText()).append('\n');
            }
            if (snippet != null) {
                sb.append("   ").append(snippet.asText()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
