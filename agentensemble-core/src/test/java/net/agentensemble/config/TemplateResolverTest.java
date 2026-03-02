package net.agentensemble.config;

import net.agentensemble.exception.PromptTemplateException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateResolverTest {

    // ========================
    // Null / blank pass-through
    // ========================

    @Test
    void testResolve_nullTemplate_returnsNull() {
        assertThat(TemplateResolver.resolve(null, Map.of())).isNull();
    }

    @Test
    void testResolve_emptyTemplate_returnsEmpty() {
        assertThat(TemplateResolver.resolve("", Map.of())).isEmpty();
    }

    @Test
    void testResolve_whitespaceOnlyTemplate_returnsUnchanged() {
        assertThat(TemplateResolver.resolve("   ", Map.of())).isEqualTo("   ");
    }

    // ========================
    // No variables
    // ========================

    @Test
    void testResolve_noVariables_returnsUnchanged() {
        assertThat(TemplateResolver.resolve("Hello world", Map.of())).isEqualTo("Hello world");
    }

    @Test
    void testResolve_noVariables_extraInputsIgnored() {
        assertThat(TemplateResolver.resolve("Hello world", Map.of("unused", "value")))
                .isEqualTo("Hello world");
    }

    // ========================
    // Simple substitution
    // ========================

    @Test
    void testResolve_simpleVariable() {
        assertThat(TemplateResolver.resolve("Research {topic}", Map.of("topic", "AI")))
                .isEqualTo("Research AI");
    }

    @Test
    void testResolve_multipleVariables() {
        assertThat(TemplateResolver.resolve("Research {topic} in {year}",
                Map.of("topic", "AI", "year", "2026")))
                .isEqualTo("Research AI in 2026");
    }

    @Test
    void testResolve_sameVariableMultipleTimes() {
        assertThat(TemplateResolver.resolve("{a} and {a}", Map.of("a", "X")))
                .isEqualTo("X and X");
    }

    @Test
    void testResolve_underscoreInName() {
        assertThat(TemplateResolver.resolve("{under_score}", Map.of("under_score", "ok")))
                .isEqualTo("ok");
    }

    @Test
    void testResolve_emptyValue_replacesWithEmpty() {
        assertThat(TemplateResolver.resolve("Hello {name}", Map.of("name", "")))
                .isEqualTo("Hello ");
    }

    @Test
    void testResolve_nullValue_treatedAsEmpty() {
        Map<String, String> inputs = new java.util.HashMap<>();
        inputs.put("name", null);
        assertThat(TemplateResolver.resolve("Hello {name}", inputs))
                .isEqualTo("Hello ");
    }

    // ========================
    // Missing variables
    // ========================

    @Test
    void testResolve_missingVariable_throwsPromptTemplateException() {
        assertThatThrownBy(() -> TemplateResolver.resolve("{topic}", Map.of()))
                .isInstanceOf(PromptTemplateException.class)
                .hasMessageContaining("topic");
    }

    @Test
    void testResolve_multipleMissingVariables_reportsAll() {
        assertThatThrownBy(() ->
                TemplateResolver.resolve("{a} and {b}", Map.of()))
                .isInstanceOf(PromptTemplateException.class)
                .satisfies(ex -> {
                    var pte = (PromptTemplateException) ex;
                    assertThat(pte.getMissingVariables()).containsExactlyInAnyOrder("a", "b");
                });
    }

    @Test
    void testResolve_partialInputs_reportsOnlyMissing() {
        assertThatThrownBy(() ->
                TemplateResolver.resolve("{a} and {b}", Map.of("a", "X")))
                .isInstanceOf(PromptTemplateException.class)
                .satisfies(ex -> {
                    var pte = (PromptTemplateException) ex;
                    assertThat(pte.getMissingVariables()).containsExactly("b");
                });
    }

    @Test
    void testResolve_missingVariable_exceptionContainsTemplate() {
        String template = "Research {topic} in detail";
        assertThatThrownBy(() -> TemplateResolver.resolve(template, Map.of()))
                .isInstanceOf(PromptTemplateException.class)
                .satisfies(ex -> {
                    var pte = (PromptTemplateException) ex;
                    assertThat(pte.getTemplate()).isEqualTo(template);
                });
    }

    @Test
    void testResolve_nullInputs_treatedAsEmpty_throwsOnMissingVariables() {
        assertThatThrownBy(() -> TemplateResolver.resolve("{topic}", null))
                .isInstanceOf(PromptTemplateException.class)
                .hasMessageContaining("topic");
    }

    // ========================
    // Escaped variables
    // ========================

    @Test
    void testResolve_escapedBraces_returnsLiteral() {
        assertThat(TemplateResolver.resolve("{{topic}}", Map.of()))
                .isEqualTo("{topic}");
    }

    @Test
    void testResolve_escapedBraces_notSubstitutedEvenIfInputExists() {
        assertThat(TemplateResolver.resolve("{{topic}}", Map.of("topic", "AI")))
                .isEqualTo("{topic}");
    }

    @Test
    void testResolve_mixedEscapedAndNormal() {
        assertThat(TemplateResolver.resolve("{{escaped}} and {normal}",
                Map.of("normal", "substituted")))
                .isEqualTo("{escaped} and substituted");
    }
}
