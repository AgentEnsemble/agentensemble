package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Map;
import net.agentensemble.Ensemble;
import net.agentensemble.execution.RunOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RunRequestParser}: Level 1 configuration building.
 */
class RunRequestParserTest {

    private RunRequestParser parser;
    private Ensemble template;

    @BeforeEach
    void setUp() {
        parser = new RunRequestParser(null, null);
        template = mock(Ensemble.class);
    }

    // ========================
    // buildFromTemplate -- null guards
    // ========================

    @Test
    void buildFromTemplate_nullTemplate_throws() {
        assertThatThrownBy(() -> parser.buildFromTemplate(null, Map.of(), RunOptions.DEFAULT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("template ensemble must not be null");
    }

    // ========================
    // buildFromTemplate -- happy paths
    // ========================

    @Test
    void buildFromTemplate_withInputs_returnsConfigurationWithCopiedInputs() {
        Map<String, String> inputs = Map.of("topic", "AI safety", "year", "2025");
        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, inputs, null);

        assertThat(config.template()).isSameAs(template);
        assertThat(config.inputs()).containsEntry("topic", "AI safety").containsEntry("year", "2025");
        assertThat(config.options()).isSameAs(RunOptions.DEFAULT);
    }

    @Test
    void buildFromTemplate_nullInputs_defaultsToEmpty() {
        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, null, null);
        assertThat(config.inputs()).isEmpty();
    }

    @Test
    void buildFromTemplate_emptyInputs_returnsEmptyInputs() {
        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, Map.of(), null);
        assertThat(config.inputs()).isEmpty();
    }

    @Test
    void buildFromTemplate_withOptions_preservesOptions() {
        RunOptions options = RunOptions.builder().maxToolOutputLength(5000).build();
        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, Map.of(), options);
        assertThat(config.options()).isSameAs(options);
    }

    @Test
    void buildFromTemplate_nullOptions_defaultsToRunOptionsDefault() {
        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, Map.of(), null);
        assertThat(config.options()).isSameAs(RunOptions.DEFAULT);
    }

    @Test
    void buildFromTemplate_inputsAreCopied_mutatingOriginalDoesNotAffectConfig() {
        Map<String, String> mutableInputs = new java.util.HashMap<>();
        mutableInputs.put("topic", "AI");

        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, mutableInputs, null);
        mutableInputs.put("year", "2025"); // mutate after building

        assertThat(config.inputs()).doesNotContainKey("year");
    }

    // ========================
    // Catalog accessors
    // ========================

    @Test
    void getToolCatalog_returnsNullWhenNotConfigured() {
        assertThat(parser.getToolCatalog()).isNull();
    }

    @Test
    void getModelCatalog_returnsNullWhenNotConfigured() {
        assertThat(parser.getModelCatalog()).isNull();
    }

    @Test
    void withCatalogs_returnsConfiguredCatalogs() {
        ToolCatalog tc = ToolCatalog.builder().build();
        ModelCatalog mc = ModelCatalog.builder().build();
        RunRequestParser parserWithCatalogs = new RunRequestParser(tc, mc);

        assertThat(parserWithCatalogs.getToolCatalog()).isSameAs(tc);
        assertThat(parserWithCatalogs.getModelCatalog()).isSameAs(mc);
    }
}
