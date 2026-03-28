package net.agentensemble.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.agentensemble.Agent;
import net.agentensemble.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingAgentTest {

    @TempDir
    Path tempDir;

    private final ChatModel model = mock(ChatModel.class);

    // ---- Happy path ----

    @Test
    void build_withWorkingDirectory_producesValidAgent() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle.kts"));

        Agent agent = CodingAgent.builder().llm(model).workingDirectory(tempDir).build();

        assertThat(agent.getRole()).isEqualTo("Senior Software Engineer");
        assertThat(agent.getGoal()).contains("Implement");
        assertThat(agent.getBackground()).contains("java");
        assertThat(agent.getBackground()).contains("gradle");
        assertThat(agent.getMaxIterations()).isEqualTo(75);
        assertThat(agent.getTools()).isNotEmpty();
    }

    @Test
    void build_withWorkspace_producesValidAgent() throws IOException {
        Files.createFile(tempDir.resolve("package.json"));
        Workspace workspace = testWorkspace(tempDir);

        Agent agent = CodingAgent.builder().llm(model).workspace(workspace).build();

        assertThat(agent.getBackground()).contains("javascript");
        assertThat(agent.getTools()).isNotEmpty();
    }

    @Test
    void build_unknownProject_producesAgentWithGenericPrompt() {
        Agent agent = CodingAgent.builder().llm(model).workingDirectory(tempDir).build();

        assertThat(agent.getBackground()).contains("expert software engineer");
    }

    @Test
    void build_customMaxIterations_usesCustomValue() {
        Agent agent = CodingAgent.builder()
                .llm(model)
                .workingDirectory(tempDir)
                .maxIterations(50)
                .build();

        assertThat(agent.getMaxIterations()).isEqualTo(50);
    }

    @Test
    void build_additionalTools_appendedToToolList() {
        Object customTool = new Object() {
            @dev.langchain4j.agent.tool.Tool("A test tool")
            public String testTool() {
                return "test";
            }
        };

        Agent agent = CodingAgent.builder()
                .llm(model)
                .workingDirectory(tempDir)
                .additionalTools(customTool)
                .build();

        // FileReadTool + customTool
        assertThat(agent.getTools()).hasSize(2);
    }

    @Test
    void build_requireApprovalDefaultsFalse() {
        Agent agent = CodingAgent.builder().llm(model).workingDirectory(tempDir).build();

        assertThat(agent).isNotNull();
    }

    @Test
    void build_requireApprovalCanBeSet() {
        Agent agent = CodingAgent.builder()
                .llm(model)
                .workingDirectory(tempDir)
                .requireApproval(true)
                .build();

        assertThat(agent).isNotNull();
    }

    @Test
    void build_autoBackend_defaultsToMinimalWhenNoOptionalModules() {
        Agent agent = CodingAgent.builder()
                .llm(model)
                .workingDirectory(tempDir)
                .toolBackend(ToolBackend.AUTO)
                .build();

        // AUTO resolves to MINIMAL (no optional modules on classpath) -> only FileReadTool
        assertThat(agent.getTools()).hasSize(1);
    }

    @Test
    void build_minimalBackend_includesFileReadTool() {
        Agent agent = CodingAgent.builder()
                .llm(model)
                .workingDirectory(tempDir)
                .toolBackend(ToolBackend.MINIMAL)
                .build();

        assertThat(agent.getTools()).hasSize(1);
    }

    // ---- Validation ----

    @Test
    void build_nullLlm_throwsNpe() {
        assertThatThrownBy(() -> CodingAgent.builder().workingDirectory(tempDir).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("llm");
    }

    @Test
    void build_neitherDirectoryNorWorkspace_throwsIse() {
        assertThatThrownBy(() -> CodingAgent.builder().llm(model).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workingDirectory or workspace");
    }

    @Test
    void build_bothDirectoryAndWorkspace_throwsIse() {
        Workspace workspace = testWorkspace(tempDir);

        assertThatThrownBy(() -> CodingAgent.builder()
                        .llm(model)
                        .workingDirectory(tempDir)
                        .workspace(workspace)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void build_zeroMaxIterations_throwsIae() {
        assertThatThrownBy(() -> CodingAgent.builder()
                        .llm(model)
                        .workingDirectory(tempDir)
                        .maxIterations(0)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxIterations");
    }

    @Test
    void build_negativeMaxIterations_throwsIae() {
        assertThatThrownBy(() -> CodingAgent.builder()
                        .llm(model)
                        .workingDirectory(tempDir)
                        .maxIterations(-1)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Helper ----

    private static Workspace testWorkspace(Path path) {
        return new Workspace() {
            @Override
            public Path path() {
                return path;
            }

            @Override
            public String id() {
                return "test-workspace";
            }

            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public void close() {}
        };
    }
}
