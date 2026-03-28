package net.agentensemble.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.nio.file.Path;
import java.util.List;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.CustomSchemaAgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpToolFactoryTest {

    private McpClient mockClient;
    private ToolSpecification specA;
    private ToolSpecification specB;
    private ToolSpecification specC;

    @BeforeEach
    void setUp() {
        mockClient = mock(McpClient.class);

        JsonObjectSchema schemaA = JsonObjectSchema.builder()
                .addStringProperty("path", "File path")
                .required(List.of("path"))
                .build();
        JsonObjectSchema schemaB = JsonObjectSchema.builder()
                .addStringProperty("pattern", "Search pattern")
                .build();
        JsonObjectSchema schemaC = JsonObjectSchema.builder()
                .addStringProperty("command", "Git command")
                .build();

        specA = ToolSpecification.builder()
                .name("read_file")
                .description("Read a file")
                .parameters(schemaA)
                .build();
        specB = ToolSpecification.builder()
                .name("search_files")
                .description("Search files")
                .parameters(schemaB)
                .build();
        specC = ToolSpecification.builder()
                .name("git_status")
                .description("Git status")
                .parameters(schemaC)
                .build();

        when(mockClient.listTools()).thenReturn(List.of(specA, specB, specC));
    }

    // ========================
    // fromClient (all tools)
    // ========================

    @Test
    void fromClient_returnsAllTools() {
        List<AgentTool> tools = McpToolFactory.fromClient(mockClient);

        assertThat(tools).hasSize(3);
    }

    @Test
    void fromClient_toolsHaveCorrectNames() {
        List<AgentTool> tools = McpToolFactory.fromClient(mockClient);

        assertThat(tools).extracting(AgentTool::name).containsExactly("read_file", "search_files", "git_status");
    }

    @Test
    void fromClient_toolsHaveCorrectDescriptions() {
        List<AgentTool> tools = McpToolFactory.fromClient(mockClient);

        assertThat(tools)
                .extracting(AgentTool::description)
                .containsExactly("Read a file", "Search files", "Git status");
    }

    @Test
    void fromClient_toolsHaveCorrectSchemas() {
        List<AgentTool> tools = McpToolFactory.fromClient(mockClient);

        // Each tool should be a CustomSchemaAgentTool with the correct schema
        for (AgentTool tool : tools) {
            assertThat(tool).isInstanceOf(CustomSchemaAgentTool.class);
        }

        CustomSchemaAgentTool readFile = (CustomSchemaAgentTool) tools.get(0);
        assertThat(readFile.parameterSchema().properties()).containsKey("path");
        assertThat(readFile.parameterSchema().required()).contains("path");
    }

    @Test
    void fromClient_emptyToolList_returnsEmpty() {
        when(mockClient.listTools()).thenReturn(List.of());

        List<AgentTool> tools = McpToolFactory.fromClient(mockClient);

        assertThat(tools).isEmpty();
    }

    // ========================
    // fromClient (filtered)
    // ========================

    @Test
    void fromClient_withFilter_returnsMatchingTools() {
        List<AgentTool> tools = McpToolFactory.fromClient(mockClient, "read_file", "git_status");

        assertThat(tools).hasSize(2);
        assertThat(tools).extracting(AgentTool::name).containsExactlyInAnyOrder("read_file", "git_status");
    }

    @Test
    void fromClient_withSingleFilter_returnsSingleTool() {
        List<AgentTool> tools = McpToolFactory.fromClient(mockClient, "search_files");

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("search_files");
    }

    @Test
    void fromClient_withUnknownName_throws() {
        assertThatThrownBy(() -> McpToolFactory.fromClient(mockClient, "read_file", "nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void fromClient_withAllUnknownNames_throws() {
        assertThatThrownBy(() -> McpToolFactory.fromClient(mockClient, "foo", "bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("foo");
    }

    // ========================
    // Convenience factory methods
    // ========================

    @Test
    void filesystem_nullPath_throws() {
        assertThatThrownBy(() -> McpToolFactory.filesystem(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedDir");
    }

    @Test
    void git_nullPath_throws() {
        assertThatThrownBy(() -> McpToolFactory.git(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repoPath");
    }

    @SuppressWarnings("PMD.CloseResource") // lifecycle is not started, no resources to close
    @Test
    void filesystem_returnsLifecycleWithCorrectCommand() {
        McpServerLifecycle lifecycle = McpToolFactory.filesystem(Path.of("/tmp/workspace"));

        assertThat(lifecycle).isNotNull();
        assertThat(lifecycle.command()).contains("npx", "@modelcontextprotocol/server-filesystem");
        assertThat(lifecycle.command()).anyMatch(s -> s.contains("workspace"));
    }

    @SuppressWarnings("PMD.CloseResource") // lifecycle is not started, no resources to close
    @Test
    void git_returnsLifecycleWithCorrectCommand() {
        McpServerLifecycle lifecycle = McpToolFactory.git(Path.of("/tmp/repo"));

        assertThat(lifecycle).isNotNull();
        assertThat(lifecycle.command()).contains("npx", "@modelcontextprotocol/server-git", "--repository");
        assertThat(lifecycle.command()).anyMatch(s -> s.contains("repo"));
    }
}
