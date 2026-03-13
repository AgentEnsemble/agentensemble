package net.agentensemble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSchemaGeneratorTest {

    // --- test record types ---

    @ToolInput(description = "Single string")
    record SingleStringInput(@ToolParam(description = "A text value") String text) {}

    record IntegerInput(@ToolParam(description = "An int") int count) {}

    record LongInput(@ToolParam(description = "A long") Long big) {}

    record DoubleInput(@ToolParam(description = "A double") double rate) {}

    record BooleanInput(@ToolParam(description = "A flag") boolean enabled) {}

    enum Color {
        RED,
        GREEN,
        BLUE
    }

    record EnumInput(@ToolParam(description = "A color") Color color) {}

    /** Enum whose {@code toString()} returns lowercase -- should not affect schema values. */
    enum StatusWithCustomToString {
        ACTIVE,
        INACTIVE;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    record CustomToStringEnumInput(@ToolParam(description = "Status") StatusWithCustomToString status) {}

    record ListStringInput(@ToolParam(description = "Tags") List<String> tags) {}

    record ArrayInput(@ToolParam(description = "Bytes") byte[] data) {}

    record MapInput(@ToolParam(description = "Headers") Map<String, String> headers) {}

    record MultiInput(
            @ToolParam(description = "Required field") String required,
            @ToolParam(description = "Optional field", required = false) String optional) {}

    record NoAnnotationInput(String field) {}

    record NoDescriptionInput(@ToolParam String value) {}

    // ========================
    // Null / non-record rejection
    // ========================

    @Test
    void generateSchema_nullInputType_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ToolSchemaGenerator.generateSchema(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generateSchema_nonRecordClass_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ToolSchemaGenerator.generateSchema(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("record");
    }

    // ========================
    // String property
    // ========================

    @Test
    void generateSchema_stringField_producesStringProperty() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(SingleStringInput.class);
        assertThat(schema.properties()).containsKey("text");
        assertThat(schema.properties().get("text")).isInstanceOf(JsonStringSchema.class);
    }

    @Test
    void generateSchema_stringField_descriptionPresent() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(SingleStringInput.class);
        JsonStringSchema prop = (JsonStringSchema) schema.properties().get("text");
        assertThat(prop.description()).isEqualTo("A text value");
    }

    @Test
    void generateSchema_noDescription_nullDescriptionInSchema() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(NoDescriptionInput.class);
        JsonStringSchema prop = (JsonStringSchema) schema.properties().get("value");
        assertThat(prop).isNotNull();
        // Empty @ToolParam description means null description in schema
        assertThat(prop.description()).isNull();
    }

    // ========================
    // Integer property
    // ========================

    @Test
    void generateSchema_intField_producesIntegerProperty() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(IntegerInput.class);
        assertThat(schema.properties().get("count")).isInstanceOf(JsonIntegerSchema.class);
    }

    @Test
    void generateSchema_longField_producesIntegerProperty() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(LongInput.class);
        assertThat(schema.properties().get("big")).isInstanceOf(JsonIntegerSchema.class);
    }

    // ========================
    // Number property
    // ========================

    @Test
    void generateSchema_doubleField_producesNumberProperty() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(DoubleInput.class);
        assertThat(schema.properties().get("rate")).isInstanceOf(JsonNumberSchema.class);
    }

    // ========================
    // Boolean property
    // ========================

    @Test
    void generateSchema_booleanField_producesBooleanProperty() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(BooleanInput.class);
        assertThat(schema.properties().get("enabled")).isInstanceOf(JsonBooleanSchema.class);
    }

    // ========================
    // Enum property
    // ========================

    @Test
    void generateSchema_enumField_producesEnumProperty() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(EnumInput.class);
        assertThat(schema.properties().get("color")).isInstanceOf(JsonEnumSchema.class);
    }

    @Test
    void generateSchema_enumField_containsAllValues() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(EnumInput.class);
        JsonEnumSchema enumProp = (JsonEnumSchema) schema.properties().get("color");
        assertThat(enumProp.enumValues()).containsExactlyInAnyOrder("RED", "GREEN", "BLUE");
    }

    @Test
    void generateSchema_enumField_descriptionPresent() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(EnumInput.class);
        JsonEnumSchema enumProp = (JsonEnumSchema) schema.properties().get("color");
        assertThat(enumProp.description()).isEqualTo("A color");
    }

    @Test
    void generateSchema_enumWithOverriddenToString_usesEnumName() {
        // StatusWithCustomToString.toString() returns lowercase, but the schema must use
        // Enum.name() to stay aligned with Jackson's default deserialization behaviour.
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(CustomToStringEnumInput.class);
        JsonEnumSchema enumProp = (JsonEnumSchema) schema.properties().get("status");
        assertThat(enumProp).isNotNull();
        assertThat(enumProp.enumValues()).containsExactlyInAnyOrder("ACTIVE", "INACTIVE");
        // Confirm that the lowercase toString() values are NOT present
        assertThat(enumProp.enumValues()).doesNotContain("active", "inactive");
    }

    // ========================
    // Array / List property
    // ========================

    @Test
    void generateSchema_listField_producesArrayProperty() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(ListStringInput.class);
        assertThat(schema.properties().get("tags")).isInstanceOf(JsonArraySchema.class);
    }

    @Test
    void generateSchema_listStringField_itemsAreStringSchema() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(ListStringInput.class);
        JsonArraySchema arrayProp = (JsonArraySchema) schema.properties().get("tags");
        assertThat(arrayProp.items()).isInstanceOf(JsonStringSchema.class);
    }

    @Test
    void generateSchema_arrayField_producesArrayProperty() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(ArrayInput.class);
        assertThat(schema.properties().get("data")).isInstanceOf(JsonArraySchema.class);
    }

    // ========================
    // Map / Object property
    // ========================

    @Test
    void generateSchema_mapField_producesObjectProperty() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(MapInput.class);
        assertThat(schema.properties().get("headers")).isInstanceOf(JsonObjectSchema.class);
    }

    // ========================
    // Required / optional fields
    // ========================

    @Test
    void generateSchema_requiredField_appearsInRequired() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(MultiInput.class);
        assertThat(schema.required()).contains("required");
    }

    @Test
    void generateSchema_optionalField_notInRequired() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(MultiInput.class);
        assertThat(schema.required()).doesNotContain("optional");
    }

    @Test
    void generateSchema_unannotatedField_treatedAsRequired() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(NoAnnotationInput.class);
        assertThat(schema.required()).contains("field");
    }

    @Test
    void generateSchema_singleRequiredField_requiredContainsFieldName() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(SingleStringInput.class);
        assertThat(schema.required()).containsExactly("text");
    }

    // ========================
    // Property count
    // ========================

    @Test
    void generateSchema_multipleFields_allPropertiesPresent() {
        JsonObjectSchema schema = ToolSchemaGenerator.generateSchema(MultiInput.class);
        assertThat(schema.properties()).hasSize(2);
        assertThat(schema.properties()).containsKeys("required", "optional");
    }
}
