package net.agentensemble.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link SlackReviewNotifier}.
 */
class SlackReviewNotifierTest {

    @Test
    void slack_nullWebhookUrl_throws() {
        assertThatThrownBy(() -> ReviewNotifier.slack(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void slack_blankWebhookUrl_throws() {
        assertThatThrownBy(() -> ReviewNotifier.slack("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void notifyReviewPending_sendsHttpPost() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        SlackReviewNotifier notifier = new SlackReviewNotifier("https://hooks.slack.com/test", mockClient);

        ReviewRequest request = ReviewRequest.of(
                "Open the hotel safe",
                "",
                ReviewTiming.BEFORE_EXECUTION,
                Duration.ZERO,
                OnTimeoutAction.EXIT_EARLY,
                "Manager authorization required",
                "manager");

        notifier.notifyReviewPending(request);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(mockClient).send(captor.capture(), any());

        HttpRequest sent = captor.getValue();
        assertThat(sent.uri().toString()).isEqualTo("https://hooks.slack.com/test");
        assertThat(sent.method()).isEqualTo("POST");
    }

    @SuppressWarnings("unchecked")
    @Test
    void notifyReviewPending_nonSuccessStatus_logsWarning() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(500);
        when(mockResponse.body()).thenReturn("Internal Server Error");
        when(mockClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(mockResponse);

        SlackReviewNotifier notifier = new SlackReviewNotifier("https://hooks.slack.com/test", mockClient);

        ReviewRequest request = ReviewRequest.of("Task", "Out", ReviewTiming.AFTER_EXECUTION, Duration.ofMinutes(5));

        // Should not throw -- failure is logged
        notifier.notifyReviewPending(request);
    }

    @SuppressWarnings("unchecked")
    @Test
    void notifyReviewPending_exceptionDuringHttp_doesNotThrow() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenThrow(new RuntimeException("Connection refused"));

        SlackReviewNotifier notifier = new SlackReviewNotifier("https://hooks.slack.com/test", mockClient);

        ReviewRequest request = ReviewRequest.of("Task", "Out", ReviewTiming.AFTER_EXECUTION, Duration.ofMinutes(5));

        // Should not throw -- failure is logged
        notifier.notifyReviewPending(request);
    }
}
