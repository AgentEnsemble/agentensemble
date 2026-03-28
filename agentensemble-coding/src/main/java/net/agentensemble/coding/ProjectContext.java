package net.agentensemble.coding;

import java.util.List;

/**
 * Describes the detected project type, build system, and structure.
 *
 * <p>Produced by {@link ProjectDetector#analyze(java.nio.file.Path)} from filesystem markers
 * (build files, config files, source directories). Used by {@link CodingSystemPrompt} to
 * generate project-aware agent instructions and by {@link CodingAgent} to configure tools.
 *
 * @param language    detected language, e.g. {@code "java"}, {@code "typescript"}, {@code "unknown"}
 * @param buildSystem detected build system, e.g. {@code "gradle"}, {@code "maven"}, {@code "unknown"}
 * @param buildCommand shell command to build the project, e.g. {@code "./gradlew build"}
 * @param testCommand  shell command to run tests, e.g. {@code "./gradlew test"}
 * @param sourceRoots  detected source directories relative to the project root
 */
public record ProjectContext(
        String language, String buildSystem, String buildCommand, String testCommand, List<String> sourceRoots) {

    /** A context representing an unrecognized project type. */
    public static final ProjectContext UNKNOWN = new ProjectContext("unknown", "unknown", "", "", List.of());
}
