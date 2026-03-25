package net.agentensemble.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ContextFormatters}, {@link JsonContextFormatter}, and
 * {@link ToonContextFormatter}.
 */
class ContextFormattersTest {

    // ========================
    // ContextFormatters factory
    // ========================

    @Test
    void forFormat_json_returnsJsonFormatter() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.JSON);
        assertThat(formatter).isNotNull();
    }

    @Test
    void forFormat_json_alwaysReturnsSameInstance() {
        ContextFormatter first = ContextFormatters.forFormat(ContextFormat.JSON);
        ContextFormatter second = ContextFormatters.forFormat(ContextFormat.JSON);
        assertThat(first).isSameAs(second);
    }

    @Test
    void forFormat_toon_returnsToonFormatter() {
        // JToon is on the test classpath via testImplementation(libs.jtoon)
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.TOON);
        assertThat(formatter).isNotNull();
    }

    @Test
    void forFormat_null_throwsIllegalArgument() {
        assertThatThrownBy(() -> ContextFormatters.forFormat(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void isToonAvailable_returnsTrue_whenJToonOnClasspath() {
        // JToon is on the test classpath
        assertThat(ContextFormatters.isToonAvailable()).isTrue();
    }

    // ========================
    // JsonContextFormatter
    // ========================

    @Test
    void json_format_map_producesJsonString() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.JSON);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "Ada");
        data.put("age", 30);

        String result = formatter.format(data);

        assertThat(result)
                .contains("\"name\"")
                .contains("\"Ada\"")
                .contains("\"age\"")
                .contains("30");
    }

    @Test
    void json_format_list_producesJsonArray() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.JSON);
        List<String> data = List.of("alpha", "beta", "gamma");

        String result = formatter.format(data);

        assertThat(result).startsWith("[").endsWith("]").contains("\"alpha\"");
    }

    @Test
    void json_format_null_returnsNullString() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.JSON);
        assertThat(formatter.format(null)).isEqualTo("null");
    }

    @Test
    void json_format_string_returnsStringAsIs() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.JSON);
        assertThat(formatter.format("hello world")).isEqualTo("hello world");
    }

    @Test
    void json_formatJson_returnsInputUnchanged() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.JSON);
        String json = "{\"key\":\"value\"}";
        assertThat(formatter.formatJson(json)).isEqualTo(json);
    }

    @Test
    void json_formatJson_null_returnsNullString() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.JSON);
        assertThat(formatter.formatJson(null)).isEqualTo("null");
    }

    // ========================
    // ToonContextFormatter
    // ========================

    @Test
    void toon_format_map_producesToonString() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.TOON);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "Ada");
        data.put("age", 30);

        String result = formatter.format(data);

        // TOON uses key: value format (YAML-like)
        assertThat(result).contains("name: Ada").contains("age: 30");
        // TOON should NOT contain JSON braces/quotes for keys
        assertThat(result).doesNotContain("{").doesNotContain("}");
    }

    @Test
    void toon_format_null_returnsNullString() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.TOON);
        assertThat(formatter.format(null)).isEqualTo("null");
    }

    @Test
    void toon_format_string_returnsStringAsIs() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.TOON);
        assertThat(formatter.format("plain text")).isEqualTo("plain text");
    }

    @Test
    void toon_formatJson_convertsJsonToToon() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.TOON);
        String json = "{\"name\":\"Ada\",\"age\":30}";

        String result = formatter.formatJson(json);

        assertThat(result).contains("name: Ada").contains("age: 30");
        assertThat(result).doesNotContain("{").doesNotContain("}");
    }

    @Test
    void toon_formatJson_null_returnsNullString() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.TOON);
        assertThat(formatter.formatJson(null)).isEqualTo("null");
    }

    @Test
    void toon_formatJson_blank_returnsBlank() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.TOON);
        assertThat(formatter.formatJson("")).isEqualTo("");
        assertThat(formatter.formatJson("  ")).isEqualTo("  ");
    }

    @Test
    void toon_formatJson_invalidJson_returnsInputAsIs() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.TOON);
        String notJson = "this is not json";
        assertThat(formatter.formatJson(notJson)).isEqualTo(notJson);
    }

    @Test
    void toon_format_tabularData_usesTabularArraySyntax() {
        ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.TOON);
        // Tabular array: list of maps with identical keys
        String json =
                "{\"items\":[{\"sku\":\"A1\",\"qty\":2,\"price\":9.99},{\"sku\":\"B2\",\"qty\":1,\"price\":14.5}]}";

        String result = formatter.formatJson(json);

        // TOON tabular arrays use {header} syntax
        assertThat(result).contains("items[2]");
        assertThat(result).contains("A1");
        assertThat(result).contains("B2");
    }

    // ========================
    // ContextFormat enum
    // ========================

    @Test
    void contextFormat_hasJsonAndToonValues() {
        assertThat(ContextFormat.values()).containsExactly(ContextFormat.JSON, ContextFormat.TOON);
    }

    @Test
    void contextFormat_valueOf_json() {
        assertThat(ContextFormat.valueOf("JSON")).isEqualTo(ContextFormat.JSON);
    }

    @Test
    void contextFormat_valueOf_toon() {
        assertThat(ContextFormat.valueOf("TOON")).isEqualTo(ContextFormat.TOON);
    }
}
