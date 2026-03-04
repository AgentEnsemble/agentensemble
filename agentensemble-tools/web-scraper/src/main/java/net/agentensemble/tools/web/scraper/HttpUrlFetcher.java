package net.agentensemble.tools.web.scraper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@link UrlFetcher} implementation using Java's built-in {@link HttpClient}.
 */
final class HttpUrlFetcher implements UrlFetcher {

    private final HttpClient httpClient;

    HttpUrlFetcher(int timeoutSeconds) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Package-private constructor for testing with a pre-built HttpClient. */
    HttpUrlFetcher(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "AgentEnsemble-WebScraper/1.0")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }
}
