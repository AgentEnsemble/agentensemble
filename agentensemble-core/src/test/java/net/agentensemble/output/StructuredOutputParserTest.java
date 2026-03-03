package net.agentensemble.output;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredOutputParserTest {

    record Report(String title, String summary) {}
    record CountRecord(String name, int count) {}
    record ListRecord(String topic, List<String> items) {}

    // ========================
    // extractJson -- plain JSON
    // ========================

    @Test
    void testExtractJson_plainJsonObject_returnsAsIs() {
        String input = "{\"title\": \"AI Trends\"}";
        assertThat(StructuredOutputParser.extractJson(input)).isEqualTo(input);
    }

    @Test
    void testExtractJson_plainJsonWithWhitespace_returnsStripped() {
        String input = "  { \"title\": \"Test\" }  ";
        String result = StructuredOutputParser.extractJson(input);
        assertThat(result).isNotNull();
        assertThat(result.strip()).startsWith("{").endsWith("}");
    }

    @Test
    void testExtractJson_jsonArray_returnsAsIs() {
        String input = "[\"a\", \"b\", \"c\"]";
        assertThat(StructuredOutputParser.extractJson(input)).isEqualTo(input);
    }

    // ========================
    // extractJson -- markdown code fences
    // ========================

    @Test
    void testExtractJson_markdownJsonFence_extractsContent() {
        String input = "```json\n{\"title\": \"AI Report\", \"summary\": \"Good\"}\n```";
        String result = StructuredOutputParser.extractJson(input);
        assertThat(result).isEqualTo("{\"title\": \"AI Report\", \"summary\": \"Good\"}");
    }

    @Test
    void testExtractJson_markdownPlainFence_extractsContent() {
        String input = "```\n{\"title\": \"Test\"}\n```";
        String result = StructuredOutputParser.extractJson(input);
        assertThat(result).isEqualTo("{\"title\": \"Test\"}");
    }

    @Test
    void testExtractJson_markdownFenceWithProse_extractsJson() {
        String input = "Here is the result:\n```json\n{\"title\": \"AI\"}\n```\nHope this helps!";
        String result = StructuredOutputParser.extractJson(input);
        assertThat(result).isEqualTo("{\"title\": \"AI\"}");
    }

    // ========================
    // extractJson -- prose with embedded JSON
    // ========================

    @Test
    void testExtractJson_leadingProse_extractsEmbeddedJson() {
        String input = "Here is the structured output:\n{\"title\": \"Test\", \"summary\": \"Summary\"}";
        String result = StructuredOutputParser.extractJson(input);
        assertThat(result).isNotNull();
        assertThat(result).contains("\"title\"");
    }

    @Test
    void testExtractJson_trailingProse_extractsEmbeddedJson() {
        String input = "{\"title\": \"Test\"}\nI hope this is helpful.";
        String result = StructuredOutputParser.extractJson(input);
        assertThat(result).isNotNull();
        assertThat(result).contains("\"title\"");
    }

    // ========================
    // extractJson -- null/empty
    // ========================

    @Test
    void testExtractJson_null_returnsNull() {
        assertThat(StructuredOutputParser.extractJson(null)).isNull();
    }

    @Test
    void testExtractJson_blank_returnsNull() {
        assertThat(StructuredOutputParser.extractJson("   ")).isNull();
    }

    @Test
    void testExtractJson_noJson_returnsNull() {
        assertThat(StructuredOutputParser.extractJson("This is just plain text with no JSON.")).isNull();
    }

    // ========================
    // parse -- success cases
    // ========================

    @Test
    void testParse_validJsonMatchingRecord_returnsSuccess() {
        String raw = "{\"title\": \"AI Trends\", \"summary\": \"AI is growing\"}";
        ParseResult<Report> result = StructuredOutputParser.parse(raw, Report.class);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValue().title()).isEqualTo("AI Trends");
        assertThat(result.getValue().summary()).isEqualTo("AI is growing");
    }

    @Test
    void testParse_jsonInMarkdownFence_returnsSuccess() {
        String raw = "```json\n{\"title\": \"Test\", \"summary\": \"Summary\"}\n```";
        ParseResult<Report> result = StructuredOutputParser.parse(raw, Report.class);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValue().title()).isEqualTo("Test");
    }

    @Test
    void testParse_extraFieldsInJson_ignoredSuccessfully() {
        // FAIL_ON_UNKNOWN_PROPERTIES = false
        String raw = "{\"title\": \"T\", \"summary\": \"S\", \"extra\": \"ignored\"}";
        ParseResult<Report> result = StructuredOutputParser.parse(raw, Report.class);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValue().title()).isEqualTo("T");
    }

    @Test
    void testParse_integerField_parsedCorrectly() {
        String raw = "{\"name\": \"Alice\", \"count\": 42}";
        ParseResult<CountRecord> result = StructuredOutputParser.parse(raw, CountRecord.class);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValue().name()).isEqualTo("Alice");
        assertThat(result.getValue().count()).isEqualTo(42);
    }

    // ========================
    // parse -- scalar JSON types (comment 3 fix)
    // ========================

    @Test
    void testParse_booleanTrue_parsedAsBoolean() {
        ParseResult<Boolean> result = StructuredOutputParser.parse("true", Boolean.class);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValue()).isTrue();
    }

    @Test
    void testParse_booleanFalse_parsedAsBoolean() {
        ParseResult<Boolean> result = StructuredOutputParser.parse("false", Boolean.class);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValue()).isFalse();
    }

    @Test
    void testParse_integerValue_parsedAsInteger() {
        ParseResult<Integer> result = StructuredOutputParser.parse("42", Integer.class);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValue()).isEqualTo(42);
    }

    @Test
    void testParse_quotedString_parsedAsString() {
        // JSON-quoted string
        ParseResult<String> result = StructuredOutputParser.parse("\"hello world\"", String.class);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValue()).isEqualTo("hello world");
    }

    // ========================
    // extractJson -- non-greedy pattern (comment 4 fix)
    // ========================

    @Test
    void testExtractJson_multipleJsonBlocks_findsFirst() {
        // With greedy pattern, this matched from first { to last } (the whole thing)
        // With non-greedy pattern, it finds just the first block
        String input = "Here is data: {\"x\": 1} and more: {\"y\": 2}";
        String result = StructuredOutputParser.extractJson(input);
        assertThat(result).isNotNull();
        // Should find the first block, not the entire span
        assertThat(result).isEqualTo("{\"x\": 1}");
    }

    // ========================
    // parse -- failure cases
    // ========================

    @Test
    void testParse_null_returnsFailure() {
        ParseResult<Report> result = StructuredOutputParser.parse(null, Report.class);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
    }

    @Test
    void testParse_blank_returnsFailure() {
        ParseResult<Report> result = StructuredOutputParser.parse("   ", Report.class);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
    }

    @Test
    void testParse_noJson_returnsFailure() {
        ParseResult<Report> result = StructuredOutputParser.parse("This is not JSON", Report.class);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
    }

    @Test
    void testParse_malformedJson_returnsFailure() {
        ParseResult<Report> result = StructuredOutputParser.parse("{\"title\": broken}", Report.class);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
    }

    @Test
    void testParse_wrongType_returnsFailure() {
        // Providing a string "title" for an int field
        String raw = "{\"name\": \"test\", \"count\": \"not-a-number\"}";
        ParseResult<CountRecord> result = StructuredOutputParser.parse(raw, CountRecord.class);
        assertThat(result.isSuccess()).isFalse();
    }
}
