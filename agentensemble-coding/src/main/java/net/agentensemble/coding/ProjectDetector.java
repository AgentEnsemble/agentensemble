package net.agentensemble.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Auto-detects project type from filesystem markers.
 *
 * <p>Scans the given directory for well-known build files ({@code build.gradle.kts},
 * {@code pom.xml}, {@code package.json}, etc.) and returns a {@link ProjectContext}
 * describing the language, build system, build/test commands, and source roots.
 *
 * <p>Detection order (first match wins):
 * <ol>
 *   <li>{@code build.gradle.kts} or {@code build.gradle} &rarr; Java / Gradle</li>
 *   <li>{@code pom.xml} &rarr; Java / Maven</li>
 *   <li>{@code package.json} + {@code tsconfig.json} &rarr; TypeScript / npm</li>
 *   <li>{@code package.json} &rarr; JavaScript / npm</li>
 *   <li>{@code pyproject.toml} or {@code requirements.txt} &rarr; Python / pip</li>
 *   <li>{@code go.mod} &rarr; Go</li>
 *   <li>{@code Cargo.toml} &rarr; Rust / Cargo</li>
 *   <li>Fallback: {@link ProjectContext#UNKNOWN}</li>
 * </ol>
 */
public final class ProjectDetector {

    private ProjectDetector() {}

    /**
     * Analyze the given directory and return a {@link ProjectContext}.
     *
     * @param directory the project root directory to analyze
     * @return detected project context, never {@code null}
     * @throws NullPointerException if {@code directory} is null
     */
    public static ProjectContext analyze(Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");

        if (exists(directory, "build.gradle.kts") || exists(directory, "build.gradle")) {
            return javaGradle(directory);
        }
        if (exists(directory, "pom.xml")) {
            return javaMaven(directory);
        }
        if (exists(directory, "package.json")) {
            boolean typescript = exists(directory, "tsconfig.json");
            return npm(directory, typescript);
        }
        if (exists(directory, "pyproject.toml") || exists(directory, "requirements.txt")) {
            return python(directory);
        }
        if (exists(directory, "go.mod")) {
            return golang(directory);
        }
        if (exists(directory, "Cargo.toml")) {
            return rust(directory);
        }

        return ProjectContext.UNKNOWN;
    }

    private static ProjectContext javaGradle(Path dir) {
        List<String> roots = new ArrayList<>();
        addIfExists(roots, dir, "src/main/java");
        addIfExists(roots, dir, "src/test/java");
        addIfExists(roots, dir, "src/main/kotlin");
        addIfExists(roots, dir, "src/test/kotlin");
        if (roots.isEmpty()) {
            roots.add("src");
        }
        return new ProjectContext("java", "gradle", "./gradlew build", "./gradlew test", List.copyOf(roots));
    }

    private static ProjectContext javaMaven(Path dir) {
        List<String> roots = new ArrayList<>();
        addIfExists(roots, dir, "src/main/java");
        addIfExists(roots, dir, "src/test/java");
        if (roots.isEmpty()) {
            roots.add("src");
        }
        return new ProjectContext("java", "maven", "mvn compile", "mvn test", List.copyOf(roots));
    }

    private static ProjectContext npm(Path dir, boolean typescript) {
        String language = typescript ? "typescript" : "javascript";
        List<String> roots = new ArrayList<>();
        addIfExists(roots, dir, "src");
        addIfExists(roots, dir, "lib");
        addIfExists(roots, dir, "test");
        addIfExists(roots, dir, "tests");
        if (roots.isEmpty()) {
            roots.add(".");
        }
        return new ProjectContext(language, "npm", "npm run build", "npm test", List.copyOf(roots));
    }

    private static ProjectContext python(Path dir) {
        List<String> roots = new ArrayList<>();
        addIfExists(roots, dir, "src");
        addIfExists(roots, dir, "tests");
        addIfExists(roots, dir, "test");
        if (roots.isEmpty()) {
            roots.add(".");
        }
        return new ProjectContext("python", "pip", "python -m build", "python -m pytest", List.copyOf(roots));
    }

    private static ProjectContext golang(Path dir) {
        List<String> roots = new ArrayList<>();
        addIfExists(roots, dir, "cmd");
        addIfExists(roots, dir, "internal");
        addIfExists(roots, dir, "pkg");
        if (roots.isEmpty()) {
            roots.add(".");
        }
        return new ProjectContext("go", "go", "go build ./...", "go test ./...", List.copyOf(roots));
    }

    private static ProjectContext rust(Path dir) {
        List<String> roots = new ArrayList<>();
        addIfExists(roots, dir, "src");
        addIfExists(roots, dir, "tests");
        if (roots.isEmpty()) {
            roots.add("src");
        }
        return new ProjectContext("rust", "cargo", "cargo build", "cargo test", List.copyOf(roots));
    }

    private static boolean exists(Path dir, String child) {
        return Files.exists(dir.resolve(child));
    }

    private static void addIfExists(List<String> roots, Path dir, String child) {
        if (Files.isDirectory(dir.resolve(child))) {
            roots.add(child);
        }
    }
}
