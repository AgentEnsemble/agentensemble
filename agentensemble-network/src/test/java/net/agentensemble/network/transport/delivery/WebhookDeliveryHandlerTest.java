package net.agentensemble.network.transport.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link WebhookDeliveryHandler}.
 */
class WebhookDeliveryHandlerTest {

    @Test
    void method_returnsWebhook() {
        WebhookDeliveryHandler handler = new WebhookDeliveryHandler(mock(HttpClient.class));

        assertThat(handler.method()).isEqualTo(DeliveryMethod.WEBHOOK);
    }

    @SuppressWarnings("unchecked")
    @Test
    void deliver_postsToUrl() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        WebhookDeliveryHandler handler = new WebhookDeliveryHandler(httpClient);
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.WEBHOOK, "https://example.com/hook");
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);

        handler.deliver(spec, response);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

        HttpRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.uri().toString()).isEqualTo("https://example.com/hook");
        assertThat(capturedRequest.method()).isEqualTo("POST");
        assertThat(capturedRequest.headers().firstValue("Content-Type")).hasValue("application/json");
    }

    @Test
    void deliver_nullAddress_throwsIAE() {
        WebhookDeliveryHandler handler = new WebhookDeliveryHandler(mock(HttpClient.class));
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.WEBHOOK, null);
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);

        assertThatThrownBy(() -> handler.deliver(spec, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
    }
}
