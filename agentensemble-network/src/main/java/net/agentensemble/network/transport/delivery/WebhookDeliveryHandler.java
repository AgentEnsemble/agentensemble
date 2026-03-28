package net.agentensemble.network.transport.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import net.agentensemble.network.transport.DeliveryHandler;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivery handler that POSTs responses as JSON to a webhook URL.
 *
 * <p>The webhook URL is taken from {@link DeliverySpec#address()}. The response body is
 * serialized to JSON and sent as an HTTP POST with {@code Content-Type: application/json}.
 *
 * <p>Non-2xx responses are logged as warnings but do not throw exceptions.
 *
 * <p>Thread-safe: {@link HttpClient} is thread-safe.
 *
 * @see DeliveryHandler
 * @see DeliveryMethod#WEBHOOK
 */
public final class WebhookDeliveryHandler implements DeliveryHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryHandler.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    /**
     * Create a webhook delivery handler with a default {@link HttpClient}.
     */
    public WebhookDeliveryHandler() {
        this(HttpClient.newHttpClient());
    }

    /**
     * Create a webhook delivery handler with a custom {@link HttpClient}.
     *
     * @param httpClient the HTTP client to use; must not be null
     */
    public WebhookDeliveryHandler(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    /**
     * Create a webhook delivery handler with a custom {@link HttpClient} and {@link ObjectMapper}.
     *
     * @param httpClient the HTTP client to use; must not be null
     * @param mapper     the Jackson ObjectMapper to use for serialization; must not be null
     */
    public WebhookDeliveryHandler(HttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public DeliveryMethod method() {
        return DeliveryMethod.WEBHOOK;
    }

    @Override
    public void deliver(DeliverySpec spec, WorkResponse response) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(response, "response must not be null");
        String url = spec.address();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("WEBHOOK delivery requires a non-blank address (URL)");
        }
        try {
            String json = mapper.writeValueAsString(response);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = httpResponse.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                log.warn(
                        "Webhook delivery to {} returned non-2xx status: {} for request {}",
                        url,
                        statusCode,
                        response.requestId());
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize WorkResponse to JSON for request " + response.requestId(), e);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to deliver webhook to " + url + " for request " + response.requestId(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Webhook delivery interrupted for request " + response.requestId(), e);
        }
    }
}
