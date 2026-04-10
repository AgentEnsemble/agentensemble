package net.agentensemble.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SimpleToolProvider}. */
class SimpleToolProviderTest {

    private static final Object TOOL_A = new Object();
    private static final Object TOOL_B = new Object();

    // ========================
    // empty()
    // ========================

    @Test
    void empty_getAll_returnsEmptyList() {
        assertThat(SimpleToolProvider.empty().getAll()).isEmpty();
    }

    @Test
    void empty_getWithEmptyList_returnsEmptyList() {
        assertThat(SimpleToolProvider.empty().get(List.of())).isEmpty();
    }

    @Test
    void empty_getWithNullList_returnsEmptyList() {
        assertThat(SimpleToolProvider.empty().get(null)).isEmpty();
    }

    // ========================
    // of(String, Object) factory
    // ========================

    @Test
    void of_singleTool_isAccessibleByName() {
        var provider = SimpleToolProvider.of("calculator", TOOL_A);

        assertThat(provider.get(List.of("calculator"))).containsExactly(TOOL_A);
    }

    @Test
    void of_singleTool_getAll_containsTool() {
        var provider = SimpleToolProvider.of("calculator", TOOL_A);

        assertThat(provider.getAll()).containsExactly(TOOL_A);
    }

    @Test
    void of_nullName_throwsNullPointer() {
        assertThatThrownBy(() -> SimpleToolProvider.of(null, TOOL_A)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void of_nullTool_throwsNullPointer() {
        assertThatThrownBy(() -> SimpleToolProvider.of("tool", null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // builder
    // ========================

    @Test
    void builder_multipleTools_resolveByName() {
        var provider =
                SimpleToolProvider.builder().tool("a", TOOL_A).tool("b", TOOL_B).build();

        assertThat(provider.get(List.of("a", "b"))).containsExactly(TOOL_A, TOOL_B);
    }

    @Test
    void builder_multipleTools_getAll_containsAll() {
        var provider =
                SimpleToolProvider.builder().tool("a", TOOL_A).tool("b", TOOL_B).build();

        assertThat(provider.getAll()).containsExactlyInAnyOrder(TOOL_A, TOOL_B);
    }

    @Test
    void builder_nullName_throwsNullPointer() {
        assertThatThrownBy(() -> SimpleToolProvider.builder().tool(null, TOOL_A))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_nullTool_throwsNullPointer() {
        assertThatThrownBy(() -> SimpleToolProvider.builder().tool("tool", null))
                .isInstanceOf(NullPointerException.class);
    }

    // ========================
    // get() error cases
    // ========================

    @Test
    void get_unknownName_throwsIllegalArgument() {
        var provider = SimpleToolProvider.of("calculator", TOOL_A);

        assertThatThrownBy(() -> provider.get(List.of("unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown")
                .hasMessageContaining("calculator");
    }

    @Test
    void get_mixedKnownAndUnknown_throwsIllegalArgument() {
        var provider = SimpleToolProvider.of("calculator", TOOL_A);

        assertThatThrownBy(() -> provider.get(List.of("calculator", "missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void get_emptyNameList_returnsEmptyList() {
        var provider = SimpleToolProvider.of("calculator", TOOL_A);

        assertThat(provider.get(List.of())).isEmpty();
    }
}
