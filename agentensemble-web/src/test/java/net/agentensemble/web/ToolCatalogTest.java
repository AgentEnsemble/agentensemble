package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import net.agentensemble.tool.AgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolCatalog}: builder validation, resolution, listing, and containment.
 */
class ToolCatalogTest {

    private AgentTool webSearchTool;
    private AgentTool calculatorTool;

    @BeforeEach
    void setUp() {
        webSearchTool = mock(AgentTool.class);
        when(webSearchTool.name()).thenReturn("web_search");
        when(webSearchTool.description()).thenReturn("Search the web");

        calculatorTool = mock(AgentTool.class);
        when(calculatorTool.name()).thenReturn("calculator");
        when(calculatorTool.description()).thenReturn("Evaluate math expressions");
    }

    // ========================
    // Builder validation
    // ========================

    @Test
    void builder_nullToolName_throws() {
        assertThatThrownBy(() -> ToolCatalog.builder().tool(null, webSearchTool))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool name must not be null or blank");
    }

    @Test
    void builder_blankToolName_throws() {
        assertThatThrownBy(() -> ToolCatalog.builder().tool("  ", webSearchTool))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool name must not be null or blank");
    }

    @Test
    void builder_nullTool_throws() {
        assertThatThrownBy(() -> ToolCatalog.builder().tool("web_search", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool must not be null");
    }

    @Test
    void builder_duplicateName_throws() {
        AgentTool another = mock(AgentTool.class);
        when(another.description()).thenReturn("Another tool");

        assertThatThrownBy(() ->
                        ToolCatalog.builder().tool("web_search", webSearchTool).tool("web_search", another))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate tool name: 'web_search'");
    }

    @Test
    void builder_emptyBuild_producesEmptyCatalog() {
        ToolCatalog catalog = ToolCatalog.builder().build();
        assertThat(catalog.size()).isZero();
        assertThat(catalog.list()).isEmpty();
    }

    // ========================
    // resolve
    // ========================

    @Test
    void resolve_knownName_returnsCorrectTool() {
        ToolCatalog catalog = ToolCatalog.builder()
                .tool("web_search", webSearchTool)
                .tool("calculator", calculatorTool)
                .build();

        assertThat(catalog.resolve("web_search")).isSameAs(webSearchTool);
        assertThat(catalog.resolve("calculator")).isSameAs(calculatorTool);
    }

    @Test
    void resolve_unknownName_throwsWithHelpfulMessage() {
        ToolCatalog catalog =
                ToolCatalog.builder().tool("web_search", webSearchTool).build();

        assertThatThrownBy(() -> catalog.resolve("foobar"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Unknown tool 'foobar'")
                .hasMessageContaining("web_search");
    }

    // ========================
    // find
    // ========================

    @Test
    void find_knownName_returnsNonEmpty() {
        ToolCatalog catalog =
                ToolCatalog.builder().tool("web_search", webSearchTool).build();
        Optional<AgentTool> found = catalog.find("web_search");
        assertThat(found).isPresent().contains(webSearchTool);
    }

    @Test
    void find_unknownName_returnsEmpty() {
        ToolCatalog catalog =
                ToolCatalog.builder().tool("web_search", webSearchTool).build();
        assertThat(catalog.find("unknown")).isEmpty();
    }

    // ========================
    // list
    // ========================

    @Test
    void list_returnsToolInfosInInsertionOrder() {
        ToolCatalog catalog = ToolCatalog.builder()
                .tool("web_search", webSearchTool)
                .tool("calculator", calculatorTool)
                .build();

        List<ToolCatalog.ToolInfo> infos = catalog.list();
        assertThat(infos).hasSize(2);
        assertThat(infos.get(0).name()).isEqualTo("web_search");
        assertThat(infos.get(0).description()).isEqualTo("Search the web");
        assertThat(infos.get(1).name()).isEqualTo("calculator");
        assertThat(infos.get(1).description()).isEqualTo("Evaluate math expressions");
    }

    // ========================
    // contains
    // ========================

    @Test
    void contains_registeredTool_returnsTrue() {
        ToolCatalog catalog =
                ToolCatalog.builder().tool("web_search", webSearchTool).build();
        assertThat(catalog.contains("web_search")).isTrue();
    }

    @Test
    void contains_unregisteredTool_returnsFalse() {
        ToolCatalog catalog =
                ToolCatalog.builder().tool("web_search", webSearchTool).build();
        assertThat(catalog.contains("unknown")).isFalse();
    }

    // ========================
    // size
    // ========================

    @Test
    void size_reflectsRegisteredToolCount() {
        ToolCatalog catalog = ToolCatalog.builder()
                .tool("web_search", webSearchTool)
                .tool("calculator", calculatorTool)
                .build();
        assertThat(catalog.size()).isEqualTo(2);
    }
}
