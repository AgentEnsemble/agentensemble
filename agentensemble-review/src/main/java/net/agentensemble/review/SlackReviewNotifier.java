package net.agentensemble.review;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ReviewNotifier} implementation that posts review notifications to a Slack
 * incoming webhook.
 *
 * <p>Uses the JDK {@link HttpClient} (no external Slack SDK required). The webhook
 * URL must be a valid Slack incoming webhook endpoint.
 *
 * <p>Notifications are sent synchronously with a 10-second timeout. Failures are
 * logged but do not throw -- the review gate should not be blocked by a notification
 * failure.
 */
final class SlackReviewNotifier implements ReviewNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackReviewNotifier.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final String webhookUrl;
    private final HttpClient httpClient;

    SlackReviewNotifier(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /** Package-private constructor for testing with a mock HttpClient. */
    SlackReviewNotifier(String webhookUrl, HttpClient httpClient) {
        this.webhookUrl = webhookUrl;
        this.httpClient = httpClient;
    }

    @Override
    public void notifyReviewPending(ReviewRequest request) {
        try {
            String message = buildMessage(request);
            String payload = "{\"text\":" + jsonEscape(message) + "}";

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(HTTP_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Slack webhook returned status {}: {}", response.statusCode(), truncate(response.body(), 200));
            } else {
                log.debug("Slack notification sent for review of task: {}", truncate(request.taskDescription(), 80));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Slack notification interrupted for task: {}", truncate(request.taskDescription(), 80));
        } catch (Exception e) {
            log.warn(
                    "Failed to send Slack notification for task '{}': {}",
                    truncate(request.taskDescription(), 80),
                    e.getMessage());
        }
    }

    private String buildMessage(ReviewRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(":rotating_light: *Review Required*\n");
        sb.append("*Task:* ").append(truncate(request.taskDescription(), 200)).append("\n");

        if (request.requiredRole() != null) {
            sb.append("*Required Role:* ").append(request.requiredRole()).append("\n");
        }

        if (request.prompt() != null) {
            sb.append("*Prompt:* ").append(truncate(request.prompt(), 200)).append("\n");
        }

        if (request.timeout() != null && !request.timeout().isZero()) {
            long mins = request.timeout().toMinutes();
            if (mins > 0) {
                sb.append("*Timeout:* ").append(mins).append(" minute(s)\n");
            } else {
                sb.append("*Timeout:* ").append(request.timeout().toSeconds()).append(" second(s)\n");
            }
        } else {
            sb.append("*Timeout:* No timeout (waiting indefinitely)\n");
        }

        return sb.toString();
    }

    private static String jsonEscape(String value) {
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                + "\"";
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
