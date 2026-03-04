package net.agentensemble.tools.web.search;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TavilySearchProviderTest {

    private TavilySearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new TavilySearchProvider("test-api-key");
    }

    // --- parseResults ---

    @Test
    void parseResults_formatsMultipleResults() throws IOException {
        String json = "{"
                + "\"results\": ["
                + "  {\"title\": \"Java Agents\", \"url\": \"https://example.com/java\", \"content\": \"About Java agents.\"},"
                + "  {\"title\": \"Python Agents\", \"url\": \"https://example.com/python\", \"content\": \"About Python agents.\"}"
                + "]}";

        String result = provider.parseResults(json);

        assertThat(result).contains("1. Java Agents");
        assertThat(result).contains("URL: https://example.com/java");
        assertThat(result).contains("About Java agents.");
        assertThat(result).contains("2. Python Agents");
        assertThat(result).contains("URL: https://example.com/python");
    }

    @Test
    void parseResults_singleResult() throws IOException {
        String json = "{"
                + "\"results\": ["
                + "  {\"title\": \"Single Result\", \"url\": \"https://example.com\", \"content\": \"Content here.\"}"
                + "]}";

        String result = provider.parseResults(json);

        assertThat(result).startsWith("1. Single Result");
        assertThat(result).contains("URL: https://example.com");
        assertThat(result).contains("Content here.");
    }

    @Test
    void parseResults_emptyResultsArray_returnsNoResultsMessage() throws IOException {
        String json = "{\"results\": []}";

        String result = provider.parseResults(json);

        assertThat(result).isEqualTo("No results found.");
    }

    @Test
    void parseResults_missingResultsField_returnsNoResultsMessage() throws IOException {
        String json = "{\"status\": \"ok\"}";

        String result = provider.parseResults(json);

        assertThat(result).isEqualTo("No results found.");
    }

    @Test
    void parseResults_resultMissingTitle_stillFormats() throws IOException {
        String json =
                "{" + "\"results\": [" + "  {\"url\": \"https://example.com\", \"content\": \"Some content\"}" + "]}";

        String result = provider.parseResults(json);

        assertThat(result).contains("URL: https://example.com");
        assertThat(result).contains("Some content");
    }

    @Test
    void parseResults_invalidJson_throwsIOException() {
        assertThatThrownBy(() -> provider.parseResults("not valid json")).isInstanceOf(IOException.class);
    }

    // --- search() with injectable HttpClient ---

    @Test
    @SuppressWarnings("unchecked")
    void search_successfulResponse_returnsFormattedResults() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body())
                .thenReturn(
                        "{\"results\": [{\"title\": \"AI News\", \"url\": \"https://ai.example.com\", \"content\": \"Latest AI developments.\"}]}");
        doReturn(mockResponse).when(mockClient).send(any(), any());

        TavilySearchProvider searchProvider = new TavilySearchProvider("my-key", mockClient);
        String result = searchProvider.search("AI developments");

        assertThat(result).contains("AI News");
        assertThat(result).contains("https://ai.example.com");
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_non200Response_throwsIOException() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(401);
        when(mockResponse.body()).thenReturn("Unauthorized");
        doReturn(mockResponse).when(mockClient).send(any(), any());

        TavilySearchProvider searchProvider = new TavilySearchProvider("bad-key", mockClient);

        assertThatThrownBy(() -> searchProvider.search("query"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("401");
    }

    @Test
    void search_networkError_throwsIOException() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        doThrow(new IOException("Timeout")).when(mockClient).send(any(), any());

        TavilySearchProvider searchProvider = new TavilySearchProvider("my-key", mockClient);

        assertThatThrownBy(() -> searchProvider.search("query"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Timeout");
    }
}
