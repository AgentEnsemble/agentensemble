package net.agentensemble.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ToolBackendDetectorTest {

    @Test
    void resolve_auto_returnsMinimalWhenNoOptionalModules() {
        // Neither MCP nor Java tools are on the test classpath
        ToolBackend resolved = ToolBackendDetector.resolve(ToolBackend.AUTO);

        assertThat(resolved).isEqualTo(ToolBackend.MINIMAL);
    }

    @Test
    void resolve_minimal_returnsMinimal() {
        ToolBackend resolved = ToolBackendDetector.resolve(ToolBackend.MINIMAL);

        assertThat(resolved).isEqualTo(ToolBackend.MINIMAL);
    }

    @Test
    void resolve_java_throwsWhenNotOnClasspath() {
        assertThatThrownBy(() -> ToolBackendDetector.resolve(ToolBackend.JAVA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentensemble-tools-coding");
    }

    @Test
    void resolve_mcp_throwsWhenNotOnClasspath() {
        assertThatThrownBy(() -> ToolBackendDetector.resolve(ToolBackend.MCP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentensemble-mcp");
    }

    @Test
    void isMcpAvailable_returnsFalseWhenNotOnClasspath() {
        assertThat(ToolBackendDetector.isMcpAvailable()).isFalse();
    }

    @Test
    void isJavaToolsAvailable_returnsFalseWhenNotOnClasspath() {
        assertThat(ToolBackendDetector.isJavaToolsAvailable()).isFalse();
    }
}
