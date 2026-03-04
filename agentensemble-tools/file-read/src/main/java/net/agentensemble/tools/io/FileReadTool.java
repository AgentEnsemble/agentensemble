package net.agentensemble.tools.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that reads the contents of a file within a sandboxed base directory.
 *
 * <p>The tool is constructed with a base directory. All file access is restricted to paths within
 * that directory. Any attempt to access a path outside the sandbox (including path traversal
 * via {@code ../}) is rejected with an access-denied failure.
 *
 * <p>Create an instance using the factory method:
 *
 * <pre>
 * FileReadTool tool = FileReadTool.of(Path.of("/workspace/data"));
 * </pre>
 *
 * <p>Input: a file path relative to the sandbox directory, e.g. {@code "report.txt"} or
 * {@code "subdir/notes.txt"}.
 */
public final class FileReadTool extends AbstractAgentTool {

    private final Path baseDir;

    private FileReadTool(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    /**
     * Creates a FileReadTool sandboxed to the given base directory.
     *
     * @param baseDir the directory to sandbox file reads within; must be an existing directory
     * @return a new FileReadTool
     * @throws NullPointerException if baseDir is null
     * @throws IllegalArgumentException if baseDir does not exist or is not a directory
     */
    public static FileReadTool of(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        if (!Files.isDirectory(baseDir)) {
            throw new IllegalArgumentException("baseDir must be an existing directory: " + baseDir);
        }
        return new FileReadTool(baseDir);
    }

    @Override
    public String name() {
        return "file_read";
    }

    @Override
    public String description() {
        return "Reads the contents of a file. Input: a file path relative to the sandbox directory "
                + "(e.g. 'report.txt' or 'data/notes.txt'). Path traversal (../) is not permitted.";
    }

    @Override
    protected ToolResult doExecute(String input) {
        if (input == null || input.isBlank()) {
            return ToolResult.failure("File path must not be blank");
        }
        String relativePath = input.trim();
        Path resolved = resolveAndValidate(relativePath);
        if (resolved == null) {
            return ToolResult.failure("Access denied: path is outside the sandbox directory");
        }
        if (!Files.exists(resolved)) {
            return ToolResult.failure("File not found: " + relativePath);
        }
        if (!Files.isRegularFile(resolved)) {
            return ToolResult.failure("Not a regular file: " + relativePath);
        }
        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            return ToolResult.success(content);
        } catch (IOException e) {
            return ToolResult.failure("Failed to read file: " + e.getMessage());
        }
    }

    /**
     * Resolves a relative path against the base directory and validates it remains within the
     * sandbox. Returns {@code null} if the resolved path is outside the sandbox.
     */
    private Path resolveAndValidate(String relativePath) {
        try {
            Path resolved = baseDir.resolve(relativePath).normalize();
            if (!resolved.startsWith(baseDir)) {
                return null;
            }
            return resolved;
        } catch (Exception e) {
            return null;
        }
    }
}
