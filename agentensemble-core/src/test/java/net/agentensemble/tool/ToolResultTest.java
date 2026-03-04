package net.agentensemble.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolResultTest {

    @Test
    void success_withOutput() {
        var result = ToolResult.success("search results here");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("search results here");
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getStructuredOutput()).isNull();
    }

    @Test
    void success_withNullOutput_treatedAsEmpty() {
        var result = ToolResult.success(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEmpty();
    }

    @Test
    void success_withEmptyOutput() {
        var result = ToolResult.success("");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEmpty();
    }

    @Test
    void failure_withMessage() {
        var result = ToolResult.failure("Connection refused");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getOutput()).isEmpty();
        assertThat(result.getErrorMessage()).isEqualTo("Connection refused");
        assertThat(result.getStructuredOutput()).isNull();
    }

    @Test
    void failure_withNullMessage_usesDefault() {
        var result = ToolResult.failure(null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotNull().isNotEmpty();
    }

    // ========================
    // Structured output
    // ========================

    @Test
    void success_withStructuredOutput_storesPayload() {
        record SearchResult(String url, String title) {}
        var structured = new SearchResult("https://example.com", "Example");

        var result = ToolResult.success("Example - https://example.com", structured);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Example - https://example.com");
        assertThat(result.getStructuredOutput()).isSameAs(structured);
    }

    @Test
    void success_withNullStructuredOutput_getStructuredOutputReturnsNull() {
        var result = ToolResult.success("some output", null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("some output");
        assertThat(result.getStructuredOutput()).isNull();
    }

    @Test
    void getStructuredOutput_withType_castsProperly() {
        record MyPayload(int value) {}
        var payload = new MyPayload(42);
        var result = ToolResult.success("text", payload);

        MyPayload cast = result.getStructuredOutput(MyPayload.class);
        assertThat(cast).isNotNull();
        assertThat(cast.value()).isEqualTo(42);
    }

    @Test
    void getStructuredOutput_withType_returnsNullWhenAbsent() {
        var result = ToolResult.success("text");

        assertThat(result.getStructuredOutput(String.class)).isNull();
    }
}
