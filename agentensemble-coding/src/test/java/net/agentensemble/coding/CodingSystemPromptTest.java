package net.agentensemble.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CodingSystemPromptTest {

    @Test
    void build_javaGradle_containsLanguageAndBuildSystem() {
        ProjectContext ctx = new ProjectContext(
                "java", "gradle", "./gradlew build", "./gradlew test", List.of("src/main/java", "src/test/java"));

        String prompt = CodingSystemPrompt.build(ctx);

        assertThat(prompt).contains("java");
        assertThat(prompt).contains("gradle");
        assertThat(prompt).contains("./gradlew build");
        assertThat(prompt).contains("./gradlew test");
        assertThat(prompt).contains("src/main/java");
        assertThat(prompt).contains("src/test/java");
    }

    @Test
    void build_containsWorkflowSteps() {
        ProjectContext ctx = new ProjectContext("java", "gradle", "./gradlew build", "./gradlew test", List.of("src"));

        String prompt = CodingSystemPrompt.build(ctx);

        assertThat(prompt).contains("## Workflow");
        assertThat(prompt).contains("Read relevant code");
        assertThat(prompt).contains("Plan your approach");
        assertThat(prompt).contains("Run tests");
        assertThat(prompt).contains("Repeat until all tests pass");
    }

    @Test
    void build_containsBuildSystemSection() {
        ProjectContext ctx = new ProjectContext("typescript", "npm", "npm run build", "npm test", List.of("src"));

        String prompt = CodingSystemPrompt.build(ctx);

        assertThat(prompt).contains("## Build system");
        assertThat(prompt).contains("Build: npm run build");
        assertThat(prompt).contains("Test: npm test");
    }

    @Test
    void build_containsProjectStructure() {
        ProjectContext ctx =
                new ProjectContext("go", "go", "go build ./...", "go test ./...", List.of("cmd", "internal"));

        String prompt = CodingSystemPrompt.build(ctx);

        assertThat(prompt).contains("## Project structure");
        assertThat(prompt).contains("- cmd");
        assertThat(prompt).contains("- internal");
    }

    @Test
    void build_unknown_producesMinimalPrompt() {
        String prompt = CodingSystemPrompt.build(ProjectContext.UNKNOWN);

        assertThat(prompt).contains("expert software engineer");
        assertThat(prompt).doesNotContain("## Build system");
        assertThat(prompt).contains("## Workflow");
        // No test command -> different workflow step
        assertThat(prompt).contains("Verify your changes");
        assertThat(prompt).doesNotContain("Run tests");
    }

    @Test
    void build_unknownLanguageWithBuildSystem_omitsBuildSystemInIntro() {
        ProjectContext ctx = new ProjectContext("unknown", "unknown", "", "", List.of());

        String prompt = CodingSystemPrompt.build(ctx);

        assertThat(prompt).startsWith("You are an expert software engineer.\n");
        assertThat(prompt).doesNotContain("working on a unknown");
    }

    @Test
    void build_knownLanguageUnknownBuildSystem_omitsBuildSystemInIntro() {
        ProjectContext ctx = new ProjectContext("python", "unknown", "python -m build", "pytest", List.of("src"));

        String prompt = CodingSystemPrompt.build(ctx);

        assertThat(prompt).contains("working on a python project");
        assertThat(prompt).doesNotContain("using unknown");
    }

    @Test
    void build_null_throwsNpe() {
        assertThatThrownBy(() -> CodingSystemPrompt.build(null)).isInstanceOf(NullPointerException.class);
    }
}
