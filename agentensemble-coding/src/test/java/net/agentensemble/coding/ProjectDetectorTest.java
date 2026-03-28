package net.agentensemble.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectDetectorTest {

    @TempDir
    Path tempDir;

    // ---- Java / Gradle ----

    @Test
    void analyze_gradleKts_returnsJavaGradle() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle.kts"));
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/test/java"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.language()).isEqualTo("java");
        assertThat(ctx.buildSystem()).isEqualTo("gradle");
        assertThat(ctx.buildCommand()).isEqualTo("./gradlew build");
        assertThat(ctx.testCommand()).isEqualTo("./gradlew test");
        assertThat(ctx.sourceRoots()).contains("src/main/java", "src/test/java");
    }

    @Test
    void analyze_gradleGroovy_returnsJavaGradle() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.language()).isEqualTo("java");
        assertThat(ctx.buildSystem()).isEqualTo("gradle");
    }

    @Test
    void analyze_gradleNoSourceDirs_defaultsToSrc() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle.kts"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.sourceRoots()).containsExactly("src");
    }

    @Test
    void analyze_gradleWithKotlin_includesKotlinRoots() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle.kts"));
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/main/kotlin"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.sourceRoots()).contains("src/main/java", "src/main/kotlin");
    }

    // ---- Java / Maven ----

    @Test
    void analyze_maven_returnsJavaMaven() throws IOException {
        Files.createFile(tempDir.resolve("pom.xml"));
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/test/java"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.language()).isEqualTo("java");
        assertThat(ctx.buildSystem()).isEqualTo("maven");
        assertThat(ctx.buildCommand()).isEqualTo("mvn compile");
        assertThat(ctx.testCommand()).isEqualTo("mvn test");
        assertThat(ctx.sourceRoots()).contains("src/main/java", "src/test/java");
    }

    // ---- TypeScript / npm ----

    @Test
    void analyze_typescript_returnsTypescriptNpm() throws IOException {
        Files.createFile(tempDir.resolve("package.json"));
        Files.createFile(tempDir.resolve("tsconfig.json"));
        Files.createDirectories(tempDir.resolve("src"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.language()).isEqualTo("typescript");
        assertThat(ctx.buildSystem()).isEqualTo("npm");
        assertThat(ctx.buildCommand()).isEqualTo("npm run build");
        assertThat(ctx.testCommand()).isEqualTo("npm test");
        assertThat(ctx.sourceRoots()).contains("src");
    }

    // ---- JavaScript / npm ----

    @Test
    void analyze_javascript_returnsJavascriptNpm() throws IOException {
        Files.createFile(tempDir.resolve("package.json"));
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("test"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.language()).isEqualTo("javascript");
        assertThat(ctx.buildSystem()).isEqualTo("npm");
        assertThat(ctx.sourceRoots()).contains("src", "test");
    }

    // ---- Python ----

    @Test
    void analyze_pythonPyproject_returnsPython() throws IOException {
        Files.createFile(tempDir.resolve("pyproject.toml"));
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("tests"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.language()).isEqualTo("python");
        assertThat(ctx.buildSystem()).isEqualTo("pip");
        assertThat(ctx.buildCommand()).isEqualTo("python -m build");
        assertThat(ctx.testCommand()).isEqualTo("python -m pytest");
        assertThat(ctx.sourceRoots()).contains("src", "tests");
    }

    @Test
    void analyze_pythonRequirements_returnsPython() throws IOException {
        Files.createFile(tempDir.resolve("requirements.txt"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.language()).isEqualTo("python");
        assertThat(ctx.buildSystem()).isEqualTo("pip");
    }

    // ---- Go ----

    @Test
    void analyze_go_returnsGo() throws IOException {
        Files.createFile(tempDir.resolve("go.mod"));
        Files.createDirectories(tempDir.resolve("cmd"));
        Files.createDirectories(tempDir.resolve("internal"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.language()).isEqualTo("go");
        assertThat(ctx.buildSystem()).isEqualTo("go");
        assertThat(ctx.buildCommand()).isEqualTo("go build ./...");
        assertThat(ctx.testCommand()).isEqualTo("go test ./...");
        assertThat(ctx.sourceRoots()).contains("cmd", "internal");
    }

    @Test
    void analyze_goWithPkg_includesPkgRoot() throws IOException {
        Files.createFile(tempDir.resolve("go.mod"));
        Files.createDirectories(tempDir.resolve("pkg"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.sourceRoots()).contains("pkg");
    }

    // ---- Rust ----

    @Test
    void analyze_rust_returnsRust() throws IOException {
        Files.createFile(tempDir.resolve("Cargo.toml"));
        Files.createDirectories(tempDir.resolve("src"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.language()).isEqualTo("rust");
        assertThat(ctx.buildSystem()).isEqualTo("cargo");
        assertThat(ctx.buildCommand()).isEqualTo("cargo build");
        assertThat(ctx.testCommand()).isEqualTo("cargo test");
        assertThat(ctx.sourceRoots()).contains("src");
    }

    @Test
    void analyze_rustWithTests_includesTestsRoot() throws IOException {
        Files.createFile(tempDir.resolve("Cargo.toml"));
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("tests"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.sourceRoots()).contains("src", "tests");
    }

    // ---- Unknown ----

    @Test
    void analyze_emptyDirectory_returnsUnknown() {
        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx).isEqualTo(ProjectContext.UNKNOWN);
        assertThat(ctx.language()).isEqualTo("unknown");
        assertThat(ctx.buildSystem()).isEqualTo("unknown");
        assertThat(ctx.buildCommand()).isEmpty();
        assertThat(ctx.testCommand()).isEmpty();
        assertThat(ctx.sourceRoots()).isEmpty();
    }

    // ---- Priority ----

    @Test
    void analyze_gradleTakesPriorityOverMaven() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle.kts"));
        Files.createFile(tempDir.resolve("pom.xml"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.buildSystem()).isEqualTo("gradle");
    }

    @Test
    void analyze_mavenTakesPriorityOverNpm() throws IOException {
        Files.createFile(tempDir.resolve("pom.xml"));
        Files.createFile(tempDir.resolve("package.json"));

        ProjectContext ctx = ProjectDetector.analyze(tempDir);

        assertThat(ctx.buildSystem()).isEqualTo("maven");
    }

    // ---- Null safety ----

    @Test
    void analyze_nullDirectory_throwsNpe() {
        assertThatThrownBy(() -> ProjectDetector.analyze(null)).isInstanceOf(NullPointerException.class);
    }
}
