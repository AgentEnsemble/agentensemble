package net.agentensemble.tools.web.scraper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link UrlFetcher} implementation using Java's built-in {@link HttpClient}.
 *
 * <p>Response bodies are bounded to {@link #DEFAULT_MAX_RESPONSE_BYTES} to prevent
 * OOM from unexpectedly large pages.
 */
final class HttpUrlFetcher implements UrlFetcher {

    static final long DEFAULT_MAX_RESPONSE_BYTES = 5_242_880L; // 5 MB

    private final HttpClient httpClient;
    private final long maxResponseBytes;

    HttpUrlFetcher(int timeoutSeconds) {
        this(timeoutSeconds, DEFAULT_MAX_RESPONSE_BYTES);
    }

    HttpUrlFetcher(int timeoutSeconds, long maxResponseBytes) {
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.maxResponseBytes = maxResponseBytes;
    }

    /** Package-private constructor for testing with a pre-built HttpClient. */
    HttpUrlFetcher(HttpClient httpClient) {
        this.httpClient = httpClient;
        this.maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
    }

    @Override
    public String fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "AgentEnsemble-WebScraper/1.0")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return readBounded(response.body());
    }

    private String readBounded(InputStream in) throws IOException {
        try (in) {
            byte[] buf = in.readNBytes((int) Math.min(maxResponseBytes, Integer.MAX_VALUE));
            return new String(buf, StandardCharsets.UTF_8);
        }
    }
}
