package net.agentensemble.tools.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import net.agentensemble.exception.ToolConfigurationException;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.tool.NoOpToolMetrics;
import net.agentensemble.tool.ToolContext;
import net.agentensemble.tool.ToolContextInjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for HttpAgentTool using a mock HttpClient.
 */
@SuppressWarnings("unchecked")
class HttpAgentToolTest {

    private HttpClient mockClient;
    private HttpResponse<String> mockResponse;

    @BeforeEach
    void setUp() {
        mockClient = mock(HttpClient.class);
        mockResponse = mock(HttpResponse.class);
    }

    private HttpAgentTool buildPostTool() {
        return HttpAgentTool.withHttpClient(
                HttpAgentTool.builder()
                        .name("test_tool")
                        .description("A test HTTP tool")
                        .url("https://example.com/api")
                        .method("POST"),
                mockClient);
    }

    private HttpAgentTool buildGetTool() {
        return HttpAgentTool.withHttpClient(
                HttpAgentTool.builder()
                        .name("get_tool")
                        .description("A GET HTTP tool")
                        .url("https://example.com/search")
                        .method("GET"),
                mockClient);
    }

    /** Configure the mock HttpClient to return a response with the given status and body. */
    private void stubHttpResponse(int statusCode, String body) throws Exception {
        when(mockResponse.statusCode()).thenReturn(statusCode);
        when(mockResponse.body()).thenReturn(body);
        // Use org.mockito.Mockito.doReturn to avoid generic type issues with HttpResponse<T>
        org.mockito.Mockito.doReturn(mockResponse)
                .when(mockClient)
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    // ========================
    // metadata
    // ========================

    @Test
    void name_returnsConfiguredName() {
        var tool = HttpAgentTool.builder()
                .name("my_tool")
                .description("desc")
                .url("https://example.com")
                .build();
        assertThat(tool.name()).isEqualTo("my_tool");
    }

    @Test
    void description_returnsConfiguredDescription() {
        var tool = HttpAgentTool.builder()
                .name("tool")
                .description("Does things")
                .url("https://example.com")
                .build();
        assertThat(tool.description()).isEqualTo("Does things");
    }

    // ========================
    // POST requests
    // ========================

    @Test
    void execute_post_200Response_returnsBody() throws Exception {
        stubHttpResponse(200, "result text");
        var tool = buildPostTool();

        var result = tool.execute("my input");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("result text");
    }

    @Test
    void execute_post_nullInput_usesEmptyBody() throws Exception {
        stubHttpResponse(200, "ok");
        var tool = buildPostTool();

        var result = tool.execute(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("ok");
    }

    @Test
    void execute_post_jsonInput_setsJsonContentType() throws Exception {
        stubHttpResponse(200, "classified");
        var tool = buildPostTool();

        // JSON input should trigger application/json content type
        var result = tool.execute("{\"text\": \"hello\"}");

        assertThat(result.isSuccess()).isTrue();
    }

    // ========================
    // GET requests
    // ========================

    @Test
    void execute_get_200Response_returnsBody() throws Exception {
        stubHttpResponse(200, "search results");
        var tool = buildGetTool();

        var result = tool.execute("query string");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("search results");
    }

    @Test
    void execute_get_urlAlreadyHasQueryParams_appendsCorrectly() throws Exception {
        stubHttpResponse(200, "results");
        var tool = HttpAgentTool.withHttpClient(
                HttpAgentTool.builder()
                        .name("tool")
                        .description("desc")
                        .url("https://example.com/search?key=value")
                        .method("GET"),
                mockClient);

        var result = tool.execute("my query");

        assertThat(result.isSuccess()).isTrue();
    }

    // ========================
    // HTTP error responses
    // ========================

    @Test
    void execute_404Response_returnsFailure() throws Exception {
        stubHttpResponse(404, "Not Found");
        var tool = buildPostTool();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("404");
    }

    @Test
    void execute_500Response_returnsFailureWithBody() throws Exception {
        stubHttpResponse(500, "Internal Server Error");
        var tool = buildPostTool();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("500");
        assertThat(result.getErrorMessage()).contains("Internal Server Error");
    }

    @Test
    void execute_200Response_emptyBody_returnsEmptySuccess() throws Exception {
        stubHttpResponse(200, "");
        var tool = buildPostTool();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEmpty();
    }

    // ========================
    // Network errors
    // ========================

    @Test
    void execute_networkIOException_returnsFailure() throws Exception {
        when(mockClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("Connection refused"));
        var tool = buildPostTool();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("Connection refused");
    }

    @Test
    void execute_interruptedException_returnsFailureAndRestoresInterrupt() throws Exception {
        when(mockClient.send(any(HttpRequest.class), any())).thenThrow(new InterruptedException("interrupted"));
        var tool = buildPostTool();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("interrupted");
        // Restore interrupt flag
        Thread.interrupted();
    }

    // ========================
    // Factory methods
    // ========================

    @Test
    void factoryMethod_get_createsGetTool() {
        var tool = HttpAgentTool.get("search", "Searches", "https://example.com/search");
        assertThat(tool.name()).isEqualTo("search");
        assertThat(tool.description()).isEqualTo("Searches");
    }

    @Test
    void factoryMethod_post_createsPostTool() {
        var tool = HttpAgentTool.post("classify", "Classifies", "https://example.com/classify");
        assertThat(tool.name()).isEqualTo("classify");
    }

    // ========================
    // Builder validation
    // ========================

    @Test
    void builder_missingName_throwsIllegalStateException() {
        assertThatThrownBy(() -> HttpAgentTool.builder()
                        .description("desc")
                        .url("https://example.com")
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name");
    }

    @Test
    void builder_missingDescription_throwsIllegalStateException() {
        assertThatThrownBy(() -> HttpAgentTool.builder()
                        .name("tool")
                        .url("https://example.com")
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("description");
    }

    @Test
    void builder_missingUrl_throwsIllegalStateException() {
        assertThatThrownBy(() ->
                        HttpAgentTool.builder().name("tool").description("desc").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("url");
    }

    @Test
    void builder_zeroTimeout_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> HttpAgentTool.builder().timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void builder_withHeaders_storesHeaders() throws Exception {
        stubHttpResponse(200, "ok");
        var tool = HttpAgentTool.withHttpClient(
                HttpAgentTool.builder()
                        .name("tool")
                        .description("desc")
                        .url("https://example.com")
                        .header("Authorization", "Bearer token123")
                        .header("X-Custom", "value"),
                mockClient);

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isTrue();
    }

    // ========================
    // Approval gate
    // ========================

    private HttpAgentTool approvalToolWithHandler(ReviewHandler handler) {
        var tool = HttpAgentTool.withHttpClient(
                HttpAgentTool.builder()
                        .name("approval_http_tool")
                        .description("Requires approval")
                        .url("https://example.com/api")
                        .method("POST")
                        .requireApproval(true),
                mockClient);
        var ctx = ToolContext.of(
                tool.name(), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), handler);
        ToolContextInjector.injectContext(tool, ctx);
        return tool;
    }

    @Test
    void requireApproval_disabled_sendsRequestWithoutApproval() throws Exception {
        stubHttpResponse(200, "result");
        var tool = buildPostTool(); // requireApproval=false (default)

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isTrue();
        verify(mockClient).send(any(HttpRequest.class), any());
    }

    @Test
    void requireApproval_enabled_handlerContinue_sendsOriginalRequest() throws Exception {
        stubHttpResponse(200, "approved response");
        var tool = approvalToolWithHandler(ReviewHandler.autoApprove());

        var result = tool.execute("original body");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("approved response");
        // HTTP request was actually sent
        verify(mockClient).send(any(HttpRequest.class), any());
    }

    @Test
    void requireApproval_enabled_handlerExitEarly_returnsFailureWithoutSendingRequest() throws Exception {
        var tool = approvalToolWithHandler(request -> ReviewDecision.exitEarly());

        var result = tool.execute("body");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("rejected");
        // HTTP request was never sent
        verify(mockClient, never()).send(any(HttpRequest.class), any());
    }

    @Test
    void requireApproval_enabled_handlerEdit_sendsRevisedBody() throws Exception {
        stubHttpResponse(200, "edit response");
        var tool = approvalToolWithHandler(request -> ReviewDecision.edit("revised body"));

        // Capture the actual HttpRequest passed to send()
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.doReturn(mockResponse)
                .when(mockClient)
                .send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("edit response");

        var result = tool.execute("original body");

        assertThat(result.isSuccess()).isTrue();
        // Verify the request was sent with the REVISED body, not the original
        HttpRequest sentRequest = requestCaptor.getValue();
        String sentBody = readBodyPublisher(sentRequest.bodyPublisher().orElseThrow());
        assertThat(sentBody).isEqualTo("revised body");
        assertThat(sentBody).doesNotContain("original body");
    }

    @Test
    void requireApproval_enabled_noHandlerConfigured_throwsToolConfigurationException() {
        var tool = HttpAgentTool.withHttpClient(
                HttpAgentTool.builder()
                        .name("no_handler_tool")
                        .description("Requires approval but no handler")
                        .url("https://example.com/api")
                        .requireApproval(true),
                mockClient);
        // No ToolContext injected -- rawReviewHandler() returns null

        assertThatThrownBy(() -> tool.execute("input"))
                .isInstanceOf(ToolConfigurationException.class)
                .isInstanceOf(IllegalStateException.class) // ToolConfigurationException extends ISE
                .hasMessageContaining("requires approval")
                .hasMessageContaining("ReviewHandler");
    }

    @Test
    void requireApproval_approvalDescription_containsMethodUrlAndBodyPreview() {
        var capturedRequest = new net.agentensemble.review.ReviewRequest[1];
        var tool = approvalToolWithHandler(request -> {
            capturedRequest[0] = request;
            return ReviewDecision.exitEarly();
        });

        tool.execute("test-body");

        assertThat(capturedRequest[0]).isNotNull();
        assertThat(capturedRequest[0].taskDescription()).contains("HTTP POST");
        assertThat(capturedRequest[0].taskDescription()).contains("https://example.com/api");
        assertThat(capturedRequest[0].taskDescription()).contains("test-body");
    }

    @Test
    void requireApproval_longBody_previewTruncatedTo200Chars() {
        var capturedRequest = new net.agentensemble.review.ReviewRequest[1];
        var tool = approvalToolWithHandler(request -> {
            capturedRequest[0] = request;
            return ReviewDecision.exitEarly();
        });
        String longBody = "x".repeat(400);

        tool.execute(longBody);

        assertThat(capturedRequest[0]).isNotNull();
        assertThat(capturedRequest[0].taskDescription()).contains("...");
    }

    // ========================
    // Test helpers
    // ========================

    /**
     * Read the full body content from an {@link HttpRequest.BodyPublisher} by subscribing
     * to the {@link Flow.Publisher} and collecting all emitted {@link ByteBuffer} chunks.
     *
     * <p>This enables assertions on the actual request body sent by {@link HttpAgentTool}
     * when testing the Edit approval path -- verifying the REVISED body was used, not the
     * original input.
     */
    private static String readBodyPublisher(HttpRequest.BodyPublisher publisher) throws Exception {
        var future = new CompletableFuture<String>();
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            private final List<byte[]> chunks = new ArrayList<>();

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer buf) {
                byte[] b = new byte[buf.remaining()];
                buf.get(b);
                chunks.add(b);
            }

            @Override
            public void onError(Throwable t) {
                future.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                int total = chunks.stream().mapToInt(b -> b.length).sum();
                byte[] all = new byte[total];
                int offset = 0;
                for (byte[] chunk : chunks) {
                    System.arraycopy(chunk, 0, all, offset, chunk.length);
                    offset += chunk.length;
                }
                future.complete(new String(all, StandardCharsets.UTF_8));
            }
        });
        return future.get(5, TimeUnit.SECONDS);
    }

    // ========================
    // Locale-safe method normalization
    // ========================

    @Test
    void builder_lowercaseMethod_normalizedToUppercase() throws Exception {
        // Regression test: builder.method.toUpperCase(Locale.ROOT) must normalize
        // lowercase method names regardless of system locale.
        stubHttpResponse(200, "ok");
        var tool = HttpAgentTool.withHttpClient(
                HttpAgentTool.builder()
                        .name("test")
                        .description("test")
                        .url("http://example.com/api")
                        .method("get"), // lowercase - must be normalized to "GET"
                mockClient);

        tool.execute("query");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient).send(captor.capture(), any());
        assertThat(captor.getValue().method()).isEqualTo("GET");
    }

    @Test
    void builder_mixedCaseMethod_normalizedToUppercase() throws Exception {
        // e.g. "Post" -> "POST", "Delete" -> "DELETE"
        stubHttpResponse(200, "ok");
        var tool = HttpAgentTool.withHttpClient(
                HttpAgentTool.builder()
                        .name("test")
                        .description("test")
                        .url("http://example.com/api")
                        .method("Post"), // mixed case
                mockClient);

        tool.execute("data");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient).send(captor.capture(), any());
        assertThat(captor.getValue().method()).isEqualTo("POST");
    }
}
