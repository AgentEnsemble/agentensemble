package net.agentensemble.tools.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.agentensemble.tool.AbstractTypedAgentTool;
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
 * <p>Input: a {@link FileReadInput} record with a {@code path} field -- the relative path to
 * the file within the sandbox directory.
 */
public final class FileReadTool extends AbstractTypedAgentTool<FileReadInput> {

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
        return "Reads the contents of a file within a sandboxed directory. " + "Path traversal (../) is not permitted.";
    }

    @Override
    public Class<FileReadInput> inputType() {
        return FileReadInput.class;
    }

    @Override
    public ToolResult execute(FileReadInput input) {
        String relativePath = input.path().trim();
        if (relativePath.isBlank()) {
            return ToolResult.failure("File path must not be blank");
        }

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

        // Reject symlinks that point outside the sandbox.
        // normalize()+startsWith() above blocks path traversal via "../" sequences,
        // but does not prevent a symlink inside baseDir that resolves to a path
        // outside the sandbox. Resolve to the real path and re-validate.
        try {
            if (!resolved.toRealPath().startsWith(baseDir.toRealPath())) {
                return ToolResult.failure("Access denied: path resolves outside the sandbox directory");
            }
        } catch (IOException e) {
            return ToolResult.failure("Access denied: cannot resolve file path");
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
