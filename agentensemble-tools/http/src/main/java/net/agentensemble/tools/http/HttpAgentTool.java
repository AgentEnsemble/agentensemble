package net.agentensemble.tools.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.agentensemble.exception.ToolConfigurationException;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that wraps an HTTP endpoint, enabling remote services (in any language) to be
 * used as agent tools without any subprocess overhead.
 *
 * <p>The tool sends the agent's input string to a configured HTTP endpoint and returns
 * the response body as the tool output.
 *
 * <h2>Request format</h2>
 *
 * <ul>
 *   <li><strong>GET</strong>: the input is appended as a query parameter:
 *       {@code GET /endpoint?input=...}</li>
 *   <li><strong>POST</strong>: the input is sent as the request body. When the input is
 *       valid JSON, {@code Content-Type: application/json} is used; otherwise
 *       {@code Content-Type: text/plain}.</li>
 * </ul>
 *
 * <h2>Response format</h2>
 *
 * <p>The response body is returned as the tool's plain-text output to the agent.
 * HTTP 4xx/5xx responses are treated as failures.
 *
 * <h2>Approval Gate</h2>
 *
 * <p>When {@link Builder#requireApproval(boolean) requireApproval(true)} is set, a human
 * reviewer must approve the HTTP request before it is sent:
 *
 * <pre>
 * var tool = HttpAgentTool.builder()
 *     .name("delete_api")
 *     .description("Deletes a resource via the management API")
 *     .url("https://api.example.com/resources")
 *     .method("DELETE")
 *     .requireApproval(true)
 *     .build();
 * </pre>
 *
 * <p>The reviewer sees the method, URL, and a body preview, and may:
 * <ul>
 *   <li>Continue -- send the original request</li>
 *   <li>Edit -- send the revised body instead</li>
 *   <li>Exit early -- return failure without sending</li>
 * </ul>
 *
 * <p>A {@link net.agentensemble.review.ReviewHandler} must be configured on the ensemble
 * when {@code requireApproval(true)} is set; otherwise an {@link IllegalStateException}
 * is thrown at execution time.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * // Simple GET endpoint
 * var tool = HttpAgentTool.get(
 *     "search_api",
 *     "Searches the internal knowledge base",
 *     "https://api.example.com/search");
 *
 * // POST with custom headers and timeout
 * var tool = HttpAgentTool.builder()
 *     .name("classifier")
 *     .description("Classifies text using the ML service")
 *     .url("https://ml.example.com/classify")
 *     .method("POST")
 *     .header("Authorization", "Bearer " + apiKey)
 *     .timeout(Duration.ofSeconds(60))
 *     .build();
 * </pre>
 */
public final class HttpAgentTool extends AbstractAgentTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final String INPUT_QUERY_PARAM = "input";
    private static final int APPROVAL_BODY_PREVIEW_LENGTH = 200;

    private final String toolName;
    private final String toolDescription;
    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final boolean requireApproval;

    private HttpAgentTool(Builder builder, HttpClient httpClient) {
        this.toolName = builder.name;
        this.toolDescription = builder.description;
        this.url = builder.url;
        this.method = builder.method.toUpperCase(Locale.ROOT);
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
        this.timeout = builder.timeout;
        this.httpClient = httpClient;
        this.requireApproval = builder.requireApproval;
    }

    // ========================
    // Factory methods
    // ========================

    /**
     * Create an HttpAgentTool that sends GET requests to the given URL.
     *
     * <p>The input string is appended as a query parameter: {@code ?input=<encoded-input>}.
     *
     * @param name        tool name for the LLM
     * @param description tool description for the LLM
     * @param url         the endpoint URL
     * @return a new HttpAgentTool
     */
    public static HttpAgentTool get(String name, String description, String url) {
        return builder()
                .name(name)
                .description(description)
                .url(url)
                .method("GET")
                .build();
    }

    /**
     * Create an HttpAgentTool that sends POST requests to the given URL.
     *
     * <p>The input string is sent as the request body.
     *
     * @param name        tool name for the LLM
     * @param description tool description for the LLM
     * @param url         the endpoint URL
     * @return a new HttpAgentTool
     */
    public static HttpAgentTool post(String name, String description, String url) {
        return builder()
                .name(name)
                .description(description)
                .url(url)
                .method("POST")
                .build();
    }

    /** Returns a new builder for configuring an {@code HttpAgentTool}. */
    public static Builder builder() {
        return new Builder();
    }

    // ========================
    // AgentTool implementation
    // ========================

    @Override
    public String name() {
        return toolName;
    }

    @Override
    public String description() {
        return toolDescription;
    }

    @Override
    protected ToolResult doExecute(String input) {
        String effectiveInput = input != null ? input : "";

        if (requireApproval) {
            if (rawReviewHandler() == null) {
                throw new ToolConfigurationException("Tool '"
                        + name()
                        + "' requires approval but no ReviewHandler is configured on the ensemble. "
                        + "Add .reviewHandler(ReviewHandler.console()) to the ensemble builder.");
            }
            String preview = effectiveInput.length() > APPROVAL_BODY_PREVIEW_LENGTH
                    ? effectiveInput.substring(0, APPROVAL_BODY_PREVIEW_LENGTH) + "..."
                    : effectiveInput;
            ReviewDecision decision = requestApproval("HTTP " + method + " " + url + "\nBody: " + preview);
            if (decision instanceof ReviewDecision.ExitEarly) {
                return ToolResult.failure("HTTP request rejected by reviewer: " + method + " " + url);
            }
            if (decision instanceof ReviewDecision.Edit edit) {
                log().debug("Reviewer edited body for HTTP {} {}: replacing with revised content", method, url);
                effectiveInput = edit.revisedOutput();
            }
        }

        try {
            return executeRequest(effectiveInput);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("HTTP request was interrupted");
        } catch (IOException e) {
            return ToolResult.failure("HTTP request failed: " + e.getMessage());
        }
    }

    private ToolResult executeRequest(String input) throws IOException, InterruptedException {
        HttpRequest request = buildRequest(input);
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        int statusCode = response.statusCode();
        String body = response.body();

        if (statusCode >= 400) {
            String errorMsg = "HTTP " + statusCode;
            if (body != null && !body.isBlank()) {
                errorMsg += ": " + body.substring(0, Math.min(body.length(), 200));
            }
            log().warn("HTTP tool '{}' received error response {}", toolName, statusCode);
            return ToolResult.failure(errorMsg);
        }

        return ToolResult.success(body != null ? body : "");
    }

    private HttpRequest buildRequest(String input) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().timeout(timeout);

        // Add custom headers
        for (Map.Entry<String, String> header : headers.entrySet()) {
            requestBuilder.header(header.getKey(), header.getValue());
        }

        if ("GET".equals(method)) {
            String encodedInput = URLEncoder.encode(input, StandardCharsets.UTF_8);
            String fullUrl = url + (url.contains("?") ? "&" : "?") + INPUT_QUERY_PARAM + "=" + encodedInput;
            requestBuilder.uri(URI.create(fullUrl)).GET();
        } else {
            // POST or other methods with body
            String contentType = isJsonInput(input) ? "application/json" : "text/plain; charset=UTF-8";
            if (!headers.containsKey("Content-Type")) {
                requestBuilder.header("Content-Type", contentType);
            }
            requestBuilder
                    .uri(URI.create(url))
                    .method(method, HttpRequest.BodyPublishers.ofString(input, StandardCharsets.UTF_8));
        }

        return requestBuilder.build();
    }

    private static boolean isJsonInput(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        try {
            OBJECT_MAPPER.readTree(input);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ========================
    // Package-private for testing
    // ========================

    /** Package-private constructor for testing with an injectable HttpClient. */
    static HttpAgentTool withHttpClient(Builder builder, HttpClient httpClient) {
        return new HttpAgentTool(builder, httpClient);
    }

    // ========================
    // Builder
    // ========================

    /** Builder for {@link HttpAgentTool}. */
    public static final class Builder {

        private String name;
        private String description;
        private String url;
        private String method = "POST";
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Duration timeout = DEFAULT_TIMEOUT;
        private boolean requireApproval = false;

        private Builder() {}

        /**
         * Set the tool name used by the LLM to identify this tool.
         *
         * @param name tool name; must not be null or blank
         * @return this builder
         */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name must not be null");
            return this;
        }

        /**
         * Set the tool description shown to the LLM.
         *
         * @param description tool description; must not be null
         * @return this builder
         */
        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description must not be null");
            return this;
        }

        /**
         * Set the endpoint URL.
         *
         * @param url the endpoint URL; must not be null
         * @return this builder
         */
        public Builder url(String url) {
            this.url = Objects.requireNonNull(url, "url must not be null");
            return this;
        }

        /**
         * Set the HTTP method. Default: {@code "POST"}.
         *
         * @param method the HTTP method (e.g., "GET", "POST", "PUT"); must not be null
         * @return this builder
         */
        public Builder method(String method) {
            this.method = Objects.requireNonNull(method, "method must not be null");
            return this;
        }

        /**
         * Add a request header.
         *
         * @param name  header name; must not be null
         * @param value header value; must not be null
         * @return this builder
         */
        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "header name must not be null");
            Objects.requireNonNull(value, "header value must not be null");
            this.headers.put(name, value);
            return this;
        }

        /**
         * Set the request timeout. Default: 30 seconds.
         *
         * @param timeout the maximum time to wait; must be positive
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout must not be null");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Require human approval before sending the HTTP request.
         *
         * <p>When {@code true}, the ensemble's configured
         * {@link net.agentensemble.review.ReviewHandler} is invoked before
         * {@code httpClient.send()} is called. The reviewer sees the method, URL, and a
         * body preview, and may approve, edit the request body, or reject the request.
         *
         * <p>If {@code requireApproval(true)} is set but no {@code ReviewHandler} is
         * configured on the ensemble, an {@link IllegalStateException} is thrown at
         * execution time (fail-fast).
         *
         * <p>Default: {@code false}.
         *
         * @param requireApproval {@code true} to require approval before sending
         * @return this builder
         */
        public Builder requireApproval(boolean requireApproval) {
            this.requireApproval = requireApproval;
            return this;
        }

        /**
         * Build the {@link HttpAgentTool} using the default Java HttpClient.
         *
         * @return a new HttpAgentTool
         * @throws IllegalStateException if name, description, or url are not set
         */
        public HttpAgentTool build() {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("name must be set and non-blank");
            }
            if (description == null) {
                throw new IllegalStateException("description must be set");
            }
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("url must be set and non-blank");
            }
            HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
            return new HttpAgentTool(this, client);
        }
    }
}
