package net.agentensemble.tools.web.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebSearchToolTest {

    private WebSearchProvider mockProvider;
    private WebSearchTool tool;

    @BeforeEach
    void setUp() {
        mockProvider = mock(WebSearchProvider.class);
        tool = WebSearchTool.of(mockProvider);
    }

    // --- metadata ---

    @Test
    void name_returnsWebSearch() {
        assertThat(tool.name()).isEqualTo("web_search");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- successful search ---

    @Test
    void execute_returnsProviderResults() throws Exception {
        when(mockProvider.search("climate change")).thenReturn("Result 1: ...\nResult 2: ...");

        var result = tool.execute("{\"query\": \"climate change\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Result 1");
    }

    @Test
    void execute_passesQueryToProvider() throws Exception {
        when(mockProvider.search(anyString())).thenReturn("some results");

        tool.execute("{\"query\": \"best Java frameworks 2024\"}");

        verify(mockProvider).search("best Java frameworks 2024");
    }

    @Test
    void execute_trimsQueryWhitespace() throws Exception {
        when(mockProvider.search("java agents")).thenReturn("results");

        var result = tool.execute("{\"query\": \"  java agents  \"}");

        assertThat(result.isSuccess()).isTrue();
        verify(mockProvider).search("java agents");
    }

    @Test
    void execute_typedInput_search() throws Exception {
        when(mockProvider.search("typed query")).thenReturn("typed results");

        var result = tool.execute(new WebSearchInput("typed query"));

        assertThat(result.isSuccess()).isTrue();
        verify(mockProvider).search("typed query");
    }

    // --- provider IOException ---

    @Test
    void execute_providerThrowsIOException_returnsFailure() throws Exception {
        when(mockProvider.search(anyString())).thenThrow(new IOException("Network error"));

        var result = tool.execute("{\"query\": \"some query\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("search failed");
    }

    @Test
    void execute_providerThrowsInterruptedException_returnsFailure() throws Exception {
        when(mockProvider.search(anyString())).thenThrow(new InterruptedException("Interrupted"));

        var result = tool.execute("{\"query\": \"some query\"}");

        assertThat(result.isSuccess()).isFalse();
    }

    // --- null/blank/invalid JSON ---

    @Test
    void execute_nullInput_returnsFailure() {
        var result = tool.execute((String) null);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_blankInput_returnsFailure() {
        var result = tool.execute("   ");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_missingQueryField_returnsFailure() {
        var result = tool.execute("{}");
        assertThat(result.isSuccess()).isFalse();
    }

    // --- factory methods ---

    @Test
    void of_nullProvider_throwsNullPointerException() {
        assertThatThrownBy(() -> WebSearchTool.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofTavily_nullApiKey_throwsNullPointerException() {
        assertThatThrownBy(() -> WebSearchTool.ofTavily(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofSerpApi_nullApiKey_throwsNullPointerException() {
        assertThatThrownBy(() -> WebSearchTool.ofSerpApi(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofTavily_createsToolWithTavilyProvider() {
        // Only validates it creates without error; no real API call
        var tavilyTool = WebSearchTool.ofTavily("test-api-key");
        assertThat(tavilyTool.name()).isEqualTo("web_search");
    }

    @Test
    void ofSerpApi_createsToolWithSerpApiProvider() {
        // Only validates it creates without error; no real API call
        var serpTool = WebSearchTool.ofSerpApi("test-api-key");
        assertThat(serpTool.name()).isEqualTo("web_search");
    }
}
