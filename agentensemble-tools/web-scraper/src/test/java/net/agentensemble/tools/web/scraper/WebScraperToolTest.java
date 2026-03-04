package net.agentensemble.tools.web.scraper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebScraperToolTest {

    private UrlFetcher mockFetcher;
    private WebScraperTool tool;

    @BeforeEach
    void setUp() {
        mockFetcher = mock(UrlFetcher.class);
        tool = new WebScraperTool(5000, 10, mockFetcher);
    }

    // --- metadata ---

    @Test
    void name_returnsWebScrape() {
        assertThat(tool.name()).isEqualTo("web_scrape");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- successful scrape ---

    @Test
    void execute_extractsTextFromHtml() throws Exception {
        String html = "<html><body><h1>Hello World</h1><p>This is content.</p></body></html>";
        when(mockFetcher.fetch(anyString())).thenReturn(html);

        var result = tool.execute("https://example.com");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Hello World");
        assertThat(result.getOutput()).contains("This is content.");
    }

    @Test
    void execute_stripsHtmlTags() throws Exception {
        String html = "<html><body><p>Text with <b>bold</b> and <i>italic</i>.</p></body></html>";
        when(mockFetcher.fetch(anyString())).thenReturn(html);

        var result = tool.execute("https://example.com");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).doesNotContain("<b>");
        assertThat(result.getOutput()).doesNotContain("<p>");
        assertThat(result.getOutput()).contains("Text with");
        assertThat(result.getOutput()).contains("bold");
    }

    @Test
    void execute_passesUrlToFetcher() throws Exception {
        when(mockFetcher.fetch("https://example.com/page")).thenReturn("<html><body>content</body></html>");

        tool.execute("https://example.com/page");

        verify(mockFetcher).fetch("https://example.com/page");
    }

    @Test
    void execute_trimsInputUrl() throws Exception {
        when(mockFetcher.fetch("https://example.com")).thenReturn("<html><body>content</body></html>");

        var result = tool.execute("  https://example.com  ");

        assertThat(result.isSuccess()).isTrue();
        verify(mockFetcher).fetch("https://example.com");
    }

    @Test
    void execute_truncatesAtMaxContentLength() throws Exception {
        String longContent = "x".repeat(10000);
        String html = "<html><body><p>" + longContent + "</p></body></html>";
        when(mockFetcher.fetch(anyString())).thenReturn(html);

        WebScraperTool smallTool = new WebScraperTool(100, 10, mockFetcher);
        var result = smallTool.execute("https://example.com");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput().length()).isLessThanOrEqualTo(150); // includes truncation notice
        assertThat(result.getOutput()).contains("[truncated");
    }

    @Test
    void execute_handlesEmptyBody() throws Exception {
        when(mockFetcher.fetch(anyString())).thenReturn("<html><body></body></html>");

        var result = tool.execute("https://example.com");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEmpty();
    }

    // --- failure cases ---

    @Test
    void execute_fetcherThrowsIOException_returnsFailure() throws Exception {
        when(mockFetcher.fetch(anyString())).thenThrow(new IOException("Connection refused"));

        var result = tool.execute("https://example.com");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("failed to fetch");
    }

    @Test
    void execute_fetcherThrowsInterruptedException_returnsFailure() throws Exception {
        when(mockFetcher.fetch(anyString())).thenThrow(new InterruptedException());

        var result = tool.execute("https://example.com");

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_nullInput_returnsFailure() {
        var result = tool.execute(null);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_blankInput_returnsFailure() {
        var result = tool.execute("   ");
        assertThat(result.isSuccess()).isFalse();
    }

    // --- default constructor ---

    @Test
    void defaultConstructor_createsWorkingTool() {
        var defaultTool = new WebScraperTool();
        assertThat(defaultTool.name()).isEqualTo("web_scrape");
    }
}
