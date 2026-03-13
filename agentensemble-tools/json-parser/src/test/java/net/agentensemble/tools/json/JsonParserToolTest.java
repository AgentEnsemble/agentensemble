package net.agentensemble.tools.json;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsonParserToolTest {

    private JsonParserTool tool;

    @BeforeEach
    void setUp() {
        tool = new JsonParserTool();
    }

    /**
     * Helper: build a JSON-formatted input string for JsonParserTool.
     * The jsonPath is a plain expression; json is a Java string containing the JSON to parse.
     */
    private static String input(String jsonPath, String json) {
        String escapedPath = jsonPath.replace("\\", "\\\\").replace("\"", "\\\"");
        String escapedJson = json.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"jsonPath\":\"" + escapedPath + "\",\"json\":\"" + escapedJson + "\"}";
    }

    // --- metadata ---

    @Test
    void name_returnsJsonParser() {
        assertThat(tool.name()).isEqualTo("json_parser");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- simple key extraction ---

    @Test
    void execute_extractsTopLevelString() {
        var result = tool.execute(input("name", "{\"name\": \"Alice\", \"age\": 30}"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Alice");
    }

    @Test
    void execute_extractsTopLevelNumber() {
        var result = tool.execute(input("age", "{\"name\": \"Alice\", \"age\": 30}"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("30");
    }

    @Test
    void execute_extractsTopLevelBoolean() {
        var result = tool.execute(input("active", "{\"active\": true}"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("true");
    }

    @Test
    void execute_typedInput_extractsField() {
        var input = new JsonParserInput("name", "{\"name\": \"Alice\"}");
        var result = tool.execute(input);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Alice");
    }

    // --- nested key extraction ---

    @Test
    void execute_extractsNestedField() {
        var result = tool.execute(input("user.name", "{\"user\": {\"name\": \"Bob\", \"age\": 25}}"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Bob");
    }

    @Test
    void execute_extractsDeeplyNestedField() {
        var result = tool.execute(input("a.b.c.d", "{\"a\": {\"b\": {\"c\": {\"d\": \"deep\"}}}}"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("deep");
    }

    @Test
    void execute_extractsFieldFromNestedObject() {
        String json = "{\"user\": {\"address\": {\"city\": \"Denver\", \"zip\": \"80203\"}}}";
        var result = tool.execute(input("user.address.city", json));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Denver");
    }

    // --- array access ---

    @Test
    void execute_extractsFirstArrayElement() {
        var result = tool.execute(input("items[0]", "{\"items\": [\"alpha\", \"beta\", \"gamma\"]}"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("alpha");
    }

    @Test
    void execute_extractsNthArrayElement() {
        var result = tool.execute(input("items[2]", "{\"items\": [\"alpha\", \"beta\", \"gamma\"]}"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("gamma");
    }

    @Test
    void execute_extractsFieldFromArrayElement() {
        String json = "{\"users\": [{\"name\": \"Alice\"}, {\"name\": \"Bob\"}]}";
        var result = tool.execute(input("users[1].name", json));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Bob");
    }

    // --- full object/array serialization ---

    @Test
    void execute_extractsWholeNestedObject() {
        String json = "{\"config\": {\"debug\": true, \"port\": 8080}}";
        var result = tool.execute(input("config", json));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("debug");
        assertThat(result.getOutput()).contains("8080");
    }

    @Test
    void execute_extractsWholeArray() {
        String json = "{\"tags\": [\"java\", \"ai\"]}";
        var result = tool.execute(input("tags", json));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("java");
        assertThat(result.getOutput()).contains("ai");
    }

    // --- null values ---

    @Test
    void execute_nullJsonValue_returnsNull() {
        var result = tool.execute(input("field", "{\"field\": null}"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("null");
    }

    // --- failure cases ---

    @Test
    void execute_keyNotFound_returnsFailure() {
        var result = tool.execute(input("missing", "{\"name\": \"Alice\"}"));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not found");
    }

    @Test
    void execute_nestedKeyNotFound_returnsFailure() {
        var result = tool.execute(input("user.missing", "{\"user\": {\"name\": \"Alice\"}}"));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not found");
    }

    @Test
    void execute_arrayIndexOutOfBounds_returnsFailure() {
        var result = tool.execute(input("items[5]", "{\"items\": [1, 2, 3]}"));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("index");
    }

    @Test
    void execute_invalidJson_returnsFailure() {
        var result = tool.execute(input("key", "not valid json"));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("invalid json");
    }

    // --- null/blank/invalid JSON input ---

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
    void execute_missingJsonPathField_returnsFailure() {
        // Missing required "jsonPath" field
        var result = tool.execute("{\"json\": \"{}\"}");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_missingJsonField_returnsFailure() {
        // Missing required "json" field
        var result = tool.execute("{\"jsonPath\": \"key\"}");
        assertThat(result.isSuccess()).isFalse();
    }
}
