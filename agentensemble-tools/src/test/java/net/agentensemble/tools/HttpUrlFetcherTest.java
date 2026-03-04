package net.agentensemble.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class HttpUrlFetcherTest {

    @Test
    @SuppressWarnings("unchecked")
    void fetch_successfulResponse_returnsBody() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("<html><body>Hello</body></html>");
        // Use doReturn to avoid generic type mismatch with HttpClient.send()
        doReturn(mockResponse).when(mockClient).send(any(), any());

        HttpUrlFetcher fetcher = new HttpUrlFetcher(mockClient);
        String result = fetcher.fetch("https://example.com");

        assertThat(result).isEqualTo("<html><body>Hello</body></html>");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetch_non200Response_throwsIOException() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(404);
        when(mockResponse.body()).thenReturn("Not Found");
        doReturn(mockResponse).when(mockClient).send(any(), any());

        HttpUrlFetcher fetcher = new HttpUrlFetcher(mockClient);

        assertThatThrownBy(() -> fetcher.fetch("https://example.com/missing"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("404");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetch_500Response_throwsIOException() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(500);
        when(mockResponse.body()).thenReturn("Internal Server Error");
        doReturn(mockResponse).when(mockClient).send(any(), any());

        HttpUrlFetcher fetcher = new HttpUrlFetcher(mockClient);

        assertThatThrownBy(() -> fetcher.fetch("https://example.com"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500");
    }

    @Test
    void fetch_networkIOException_propagates() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        doThrow(new IOException("Connection refused")).when(mockClient).send(any(), any());

        HttpUrlFetcher fetcher = new HttpUrlFetcher(mockClient);

        assertThatThrownBy(() -> fetcher.fetch("https://unreachable.example.com"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Connection refused");
    }

    @Test
    void defaultConstructor_createsUsableInstance() {
        // Smoke test: verifies the default constructor doesn't throw
        HttpUrlFetcher fetcher = new HttpUrlFetcher(10);
        assertThat(fetcher).isNotNull();
    }
}
