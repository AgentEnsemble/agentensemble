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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SerpApiSearchProviderTest {

    private SerpApiSearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new SerpApiSearchProvider("test-api-key");
    }

    // --- parseResults ---

    @Test
    void parseResults_formatsMultipleResults() throws IOException {
        String json = "{"
                + "\"organic_results\": ["
                + "  {\"title\": \"Java Guide\", \"link\": \"https://example.com/java\", \"snippet\": \"Learn Java here.\"},"
                + "  {\"title\": \"Python Guide\", \"link\": \"https://example.com/python\", \"snippet\": \"Learn Python here.\"}"
                + "]}";

        String result = provider.parseResults(json);

        assertThat(result).contains("1. Java Guide");
        assertThat(result).contains("URL: https://example.com/java");
        assertThat(result).contains("Learn Java here.");
        assertThat(result).contains("2. Python Guide");
        assertThat(result).contains("URL: https://example.com/python");
    }

    @Test
    void parseResults_singleResult() throws IOException {
        String json = "{"
                + "\"organic_results\": ["
                + "  {\"title\": \"Only Result\", \"link\": \"https://example.com\", \"snippet\": \"The one result.\"}"
                + "]}";

        String result = provider.parseResults(json);

        assertThat(result).startsWith("1. Only Result");
        assertThat(result).contains("URL: https://example.com");
        assertThat(result).contains("The one result.");
    }

    @Test
    void parseResults_emptyResultsArray_returnsNoResultsMessage() throws IOException {
        String json = "{\"organic_results\": []}";

        String result = provider.parseResults(json);

        assertThat(result).isEqualTo("No results found.");
    }

    @Test
    void parseResults_missingOrganicResultsField_returnsNoResultsMessage() throws IOException {
        String json = "{\"search_metadata\": {\"status\": \"Success\"}}";

        String result = provider.parseResults(json);

        assertThat(result).isEqualTo("No results found.");
    }

    @Test
    void parseResults_resultMissingSnippet_stillFormats() throws IOException {
        String json = "{"
                + "\"organic_results\": ["
                + "  {\"title\": \"No Snippet\", \"link\": \"https://example.com\"}"
                + "]}";

        String result = provider.parseResults(json);

        assertThat(result).contains("No Snippet");
        assertThat(result).contains("URL: https://example.com");
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
                .thenReturn("{\"organic_results\": [{\"title\": \"Go Guide\","
                        + " \"link\": \"https://go.example.com\","
                        + " \"snippet\": \"Learn Go.\"}]}");
        doReturn(mockResponse).when(mockClient).send(any(), any());

        SerpApiSearchProvider searchProvider = new SerpApiSearchProvider("my-key", mockClient);
        String result = searchProvider.search("Go programming");

        assertThat(result).contains("Go Guide");
        assertThat(result).contains("https://go.example.com");
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_non200Response_throwsIOException() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(403);
        when(mockResponse.body()).thenReturn("Forbidden");
        doReturn(mockResponse).when(mockClient).send(any(), any());

        SerpApiSearchProvider searchProvider = new SerpApiSearchProvider("bad-key", mockClient);

        assertThatThrownBy(() -> searchProvider.search("query"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("403");
    }

    @Test
    void search_networkError_throwsIOException() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        doThrow(new IOException("Timeout")).when(mockClient).send(any(), any());

        SerpApiSearchProvider searchProvider = new SerpApiSearchProvider("my-key", mockClient);

        assertThatThrownBy(() -> searchProvider.search("query"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Timeout");
    }
}
