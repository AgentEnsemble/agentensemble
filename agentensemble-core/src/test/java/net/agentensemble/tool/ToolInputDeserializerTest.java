package net.agentensemble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolInputDeserializerTest {

    record SimpleInput(@ToolParam(description = "A field") String text) {}

    record MultiInput(
            @ToolParam(description = "Name") String name,
            @ToolParam(description = "Age", required = false) Integer age) {}

    record IntInput(@ToolParam(description = "Count") int count) {}

    record AllOptionalInput(
            @ToolParam(description = "Field A", required = false) String a,
            @ToolParam(description = "Field B", required = false) String b) {}

    record ListInput(@ToolParam(description = "Tags") List<String> tags) {}

    record MapInput(@ToolParam(description = "Headers") Map<String, String> headers) {}

    // ========================
    // Happy path
    // ========================

    @Test
    void deserialize_validJson_returnsRecord() {
        SimpleInput result = ToolInputDeserializer.deserialize("{\"text\": \"hello\"}", SimpleInput.class);
        assertThat(result.text()).isEqualTo("hello");
    }

    @Test
    void deserialize_multipleFields_mapsAllCorrectly() {
        MultiInput result = ToolInputDeserializer.deserialize("{\"name\": \"Alice\", \"age\": 30}", MultiInput.class);
        assertThat(result.name()).isEqualTo("Alice");
        assertThat(result.age()).isEqualTo(30);
    }

    @Test
    void deserialize_optionalFieldAbsent_returnsNullForOptional() {
        MultiInput result = ToolInputDeserializer.deserialize("{\"name\": \"Alice\"}", MultiInput.class);
        assertThat(result.name()).isEqualTo("Alice");
        assertThat(result.age()).isNull();
    }

    @Test
    void deserialize_intField_parsedCorrectly() {
        IntInput result = ToolInputDeserializer.deserialize("{\"count\": 42}", IntInput.class);
        assertThat(result.count()).isEqualTo(42);
    }

    @Test
    void deserialize_listField_parsedCorrectly() {
        ListInput result = ToolInputDeserializer.deserialize("{\"tags\": [\"a\", \"b\"]}", ListInput.class);
        assertThat(result.tags()).containsExactly("a", "b");
    }

    @Test
    void deserialize_mapField_parsedCorrectly() {
        MapInput result = ToolInputDeserializer.deserialize("{\"headers\": {\"k\": \"v\"}}", MapInput.class);
        assertThat(result.headers()).containsEntry("k", "v");
    }

    @Test
    void deserialize_allOptionalFieldsAbsent_returnsNulls() {
        AllOptionalInput result = ToolInputDeserializer.deserialize("{}", AllOptionalInput.class);
        assertThat(result.a()).isNull();
        assertThat(result.b()).isNull();
    }

    @Test
    void deserialize_extraFieldsIgnored() {
        SimpleInput result =
                ToolInputDeserializer.deserialize("{\"text\": \"hello\", \"extra\": \"ignored\"}", SimpleInput.class);
        assertThat(result.text()).isEqualTo("hello");
    }

    // ========================
    // Validation failures
    // ========================

    @Test
    void deserialize_missingRequiredField_throwsWithFieldName() {
        assertThatThrownBy(() -> ToolInputDeserializer.deserialize("{}", SimpleInput.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    @Test
    void deserialize_nullRequiredFieldValue_throwsWithFieldName() {
        assertThatThrownBy(() -> ToolInputDeserializer.deserialize("{\"text\": null}", SimpleInput.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    @Test
    void deserialize_multipleMissingRequiredFields_mentionsAllMissing() {
        assertThatThrownBy(() -> ToolInputDeserializer.deserialize("{}", MultiInput.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    // ========================
    // Bad JSON input
    // ========================

    @Test
    void deserialize_invalidJson_throwsWithHelpfulMessage() {
        assertThatThrownBy(() -> ToolInputDeserializer.deserialize("not json", SimpleInput.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSON");
    }

    @Test
    void deserialize_jsonArray_throwsObjectExpected() {
        assertThatThrownBy(() -> ToolInputDeserializer.deserialize("[1,2,3]", SimpleInput.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void deserialize_jsonScalar_throwsObjectExpected() {
        assertThatThrownBy(() -> ToolInputDeserializer.deserialize("\"just a string\"", SimpleInput.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // Contract violations
    // ========================

    @Test
    void deserialize_nullInputType_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ToolInputDeserializer.deserialize("{}", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deserialize_nonRecordClass_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ToolInputDeserializer.deserialize("{}", String.class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
