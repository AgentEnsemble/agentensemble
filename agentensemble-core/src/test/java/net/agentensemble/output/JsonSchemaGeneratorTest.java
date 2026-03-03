package net.agentensemble.output;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaGeneratorTest {

    // ========================
    // Supported record types
    // ========================

    record SimpleRecord(String title, int count, boolean active) {}

    @Test
    void testGenerate_simpleRecord_containsAllFields() {
        String schema = JsonSchemaGenerator.generate(SimpleRecord.class);
        assertThat(schema)
                .contains("\"title\"")
                .contains("\"string\"")
                .contains("\"count\"")
                .contains("\"integer\"")
                .contains("\"active\"")
                .contains("\"boolean\"");
    }

    @Test
    void testGenerate_simpleRecord_isJsonLike() {
        String schema = JsonSchemaGenerator.generate(SimpleRecord.class);
        assertThat(schema.strip()).startsWith("{").endsWith("}");
    }

    record StringOnlyRecord(String name, String description) {}

    @Test
    void testGenerate_multipleStringFields() {
        String schema = JsonSchemaGenerator.generate(StringOnlyRecord.class);
        assertThat(schema)
                .contains("\"name\": \"string\"")
                .contains("\"description\": \"string\"");
    }

    record ListRecord(String title, List<String> findings) {}

    @Test
    void testGenerate_listOfString_producesArraySchema() {
        String schema = JsonSchemaGenerator.generate(ListRecord.class);
        assertThat(schema)
                .contains("\"findings\"")
                .contains("[\"string\"]");
    }

    record NumericRecord(int intVal, long longVal, double doubleVal, float floatVal) {}

    @Test
    void testGenerate_numericTypes() {
        String schema = JsonSchemaGenerator.generate(NumericRecord.class);
        assertThat(schema)
                .contains("\"intVal\": \"integer\"")
                .contains("\"longVal\": \"integer\"")
                .contains("\"doubleVal\": \"number\"")
                .contains("\"floatVal\": \"number\"");
    }

    record BoxedRecord(Integer intBox, Long longBox, Double doubleBox, Boolean boolBox) {}

    @Test
    void testGenerate_boxedTypes() {
        String schema = JsonSchemaGenerator.generate(BoxedRecord.class);
        assertThat(schema)
                .contains("\"intBox\": \"integer\"")
                .contains("\"longBox\": \"integer\"")
                .contains("\"doubleBox\": \"number\"")
                .contains("\"boolBox\": \"boolean\"");
    }

    // ========================
    // Enum support
    // ========================

    enum Status { ACTIVE, INACTIVE, PENDING }

    record RecordWithEnum(String name, Status status) {}

    @Test
    void testGenerate_enumField_producesEnumValues() {
        String schema = JsonSchemaGenerator.generate(RecordWithEnum.class);
        assertThat(schema)
                .contains("\"status\"")
                .contains("enum:")
                .contains("ACTIVE")
                .contains("INACTIVE")
                .contains("PENDING");
    }

    // ========================
    // Nested objects
    // ========================

    record Address(String street, String city) {}
    record PersonRecord(String name, Address address) {}

    @Test
    void testGenerate_nestedRecord_isInlined() {
        String schema = JsonSchemaGenerator.generate(PersonRecord.class);
        assertThat(schema)
                .contains("\"name\": \"string\"")
                .contains("\"address\"")
                .contains("\"street\"")
                .contains("\"city\"");
    }

    // ========================
    // Map type
    // ========================

    record MapRecord(String id, Map<String, String> metadata) {}

    @Test
    void testGenerate_mapField_producesMapSchema() {
        String schema = JsonSchemaGenerator.generate(MapRecord.class);
        assertThat(schema).contains("\"metadata\"");
        // Map key-value schema representation
        assertThat(schema).contains("\"string\": \"string\"");
    }

    // ========================
    // POJO (non-record class)
    // ========================

    @SuppressWarnings("unused")
    static class SimplePojo {
        private String name;
        private int score;
        public static final String IGNORED = "static";
    }

    @Test
    void testGenerate_pojo_instanceFieldsOnly() {
        String schema = JsonSchemaGenerator.generate(SimplePojo.class);
        assertThat(schema)
                .contains("\"name\": \"string\"")
                .contains("\"score\": \"integer\"")
                .doesNotContain("\"IGNORED\"");
    }

    // ========================
    // Empty class
    // ========================

    static class EmptyClass {}

    @Test
    void testGenerate_emptyClass_returnsEmptyObject() {
        String schema = JsonSchemaGenerator.generate(EmptyClass.class);
        assertThat(schema.strip()).isEqualTo("{}");
    }

    // ========================
    // Validation: unsupported types
    // ========================

    @Test
    void testGenerate_null_throwsIllegalArgument() {
        assertThatThrownBy(() -> JsonSchemaGenerator.generate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void testGenerate_primitiveInt_throwsIllegalArgument() {
        assertThatThrownBy(() -> JsonSchemaGenerator.generate(int.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primitive");
    }

    @Test
    void testGenerate_primitiveBoolean_throwsIllegalArgument() {
        assertThatThrownBy(() -> JsonSchemaGenerator.generate(boolean.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primitive");
    }

    @Test
    void testGenerate_void_throwsIllegalArgument() {
        assertThatThrownBy(() -> JsonSchemaGenerator.generate(Void.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Void");
    }

    @Test
    void testGenerate_topLevelArray_throwsIllegalArgument() {
        assertThatThrownBy(() -> JsonSchemaGenerator.generate(String[].class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("array");
    }

    // ========================
    // Indentation and structure
    // ========================

    record TwoFieldRecord(String a, String b) {}

    @Test
    void testGenerate_fieldOrder_preservedFromRecord() {
        String schema = JsonSchemaGenerator.generate(TwoFieldRecord.class);
        int aPos = schema.indexOf("\"a\"");
        int bPos = schema.indexOf("\"b\"");
        assertThat(aPos).isLessThan(bPos);
    }

    @Test
    void testGenerate_multipleFields_commasOnAllButLast() {
        String schema = JsonSchemaGenerator.generate(SimpleRecord.class);
        // "active" is the last field -- no trailing comma
        int activePos = schema.indexOf("\"active\"");
        int trailingCommaPos = schema.indexOf(",", activePos);
        // The only comma after "active" should be from "boolean\"," or "}" -- but there
        // should be no comma on the active line itself
        String activeLine = schema.lines()
                .filter(l -> l.contains("\"active\""))
                .findFirst()
                .orElse("");
        assertThat(activeLine).doesNotEndWith(",");
    }
}
