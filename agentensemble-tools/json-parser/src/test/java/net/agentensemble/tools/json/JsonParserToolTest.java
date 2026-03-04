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
        var result = tool.execute("name\n{\"name\": \"Alice\", \"age\": 30}");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Alice");
    }

    @Test
    void execute_extractsTopLevelNumber() {
        var result = tool.execute("age\n{\"name\": \"Alice\", \"age\": 30}");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("30");
    }

    @Test
    void execute_extractsTopLevelBoolean() {
        var result = tool.execute("active\n{\"active\": true}");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("true");
    }

    // --- nested key extraction ---

    @Test
    void execute_extractsNestedField() {
        var result = tool.execute("user.name\n{\"user\": {\"name\": \"Bob\", \"age\": 25}}");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Bob");
    }

    @Test
    void execute_extractsDeeplyNestedField() {
        String json = "{\"a\": {\"b\": {\"c\": {\"d\": \"deep\"}}}}";
        var result = tool.execute("a.b.c.d\n" + json);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("deep");
    }

    @Test
    void execute_extractsFieldFromNestedObject() {
        String json = "{\"user\": {\"address\": {\"city\": \"Denver\", \"zip\": \"80203\"}}}";
        var result = tool.execute("user.address.city\n" + json);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Denver");
    }

    // --- array access ---

    @Test
    void execute_extractsFirstArrayElement() {
        var result = tool.execute("items[0]\n{\"items\": [\"alpha\", \"beta\", \"gamma\"]}");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("alpha");
    }

    @Test
    void execute_extractsNthArrayElement() {
        var result = tool.execute("items[2]\n{\"items\": [\"alpha\", \"beta\", \"gamma\"]}");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("gamma");
    }

    @Test
    void execute_extractsFieldFromArrayElement() {
        String json = "{\"users\": [{\"name\": \"Alice\"}, {\"name\": \"Bob\"}]}";
        var result = tool.execute("users[1].name\n" + json);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Bob");
    }

    // --- full object/array serialization ---

    @Test
    void execute_extractsWholeNestedObject() {
        String json = "{\"config\": {\"debug\": true, \"port\": 8080}}";
        var result = tool.execute("config\n" + json);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("debug");
        assertThat(result.getOutput()).contains("8080");
    }

    @Test
    void execute_extractsWholeArray() {
        String json = "{\"tags\": [\"java\", \"ai\"]}";
        var result = tool.execute("tags\n" + json);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("java");
        assertThat(result.getOutput()).contains("ai");
    }

    // --- null values ---

    @Test
    void execute_nullJsonValue_returnsNull() {
        var result = tool.execute("field\n{\"field\": null}");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("null");
    }

    // --- failure cases ---

    @Test
    void execute_keyNotFound_returnsFailure() {
        var result = tool.execute("missing\n{\"name\": \"Alice\"}");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not found");
    }

    @Test
    void execute_nestedKeyNotFound_returnsFailure() {
        var result = tool.execute("user.missing\n{\"user\": {\"name\": \"Alice\"}}");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not found");
    }

    @Test
    void execute_arrayIndexOutOfBounds_returnsFailure() {
        var result = tool.execute("items[5]\n{\"items\": [1, 2, 3]}");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("index");
    }

    @Test
    void execute_invalidJson_returnsFailure() {
        var result = tool.execute("key\nnot valid json");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("invalid json");
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

    @Test
    void execute_noJsonLine_returnsFailure() {
        // Only the path, no newline + JSON
        var result = tool.execute("user.name");
        assertThat(result.isSuccess()).isFalse();
    }
}
