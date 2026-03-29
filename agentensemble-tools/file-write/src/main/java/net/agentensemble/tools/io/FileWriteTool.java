package net.agentensemble.tools.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.agentensemble.exception.ToolConfigurationException;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.tool.AbstractTypedAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that writes content to a file within a sandboxed base directory.
 *
 * <p>The tool is constructed with a base directory. All file writes are restricted to paths within
 * that directory. Any attempt to write to a path outside the sandbox (including path traversal
 * via {@code ../}) is rejected with an access-denied failure.
 *
 * <p>Parent directories are created automatically if they do not exist.
 *
 * <p>Create an instance using the factory method or builder:
 *
 * <pre>
 * // Simple factory
 * FileWriteTool tool = FileWriteTool.of(Path.of("/workspace/output"));
 *
 * // Builder with approval gate
 * FileWriteTool tool = FileWriteTool.builder(Path.of("/workspace/output"))
 *     .requireApproval(true)
 *     .build();
 * </pre>
 *
 * <h2>Approval Gate</h2>
 *
 * <p>When {@link Builder#requireApproval(boolean) requireApproval(true)} is set, a human
 * reviewer must approve the file write before execution. The reviewer sees the target path and
 * a preview of the content, and may:
 * <ul>
 *   <li>Continue -- write the original content</li>
 *   <li>Edit -- write the reviewer's revised content instead</li>
 *   <li>Exit early -- return failure without writing</li>
 * </ul>
 *
 * <p>A {@link net.agentensemble.review.ReviewHandler} must be configured on the ensemble
 * when {@code requireApproval(true)} is set; otherwise an {@link IllegalStateException}
 * is thrown at execution time.
 *
 * <p>Input: a {@link FileWriteInput} record with {@code path} and {@code content} fields.
 */
public final class FileWriteTool extends AbstractTypedAgentTool<FileWriteInput> {

    private static final int APPROVAL_CONTENT_PREVIEW_LENGTH = 200;

    private final Path baseDir;
    private final boolean requireApproval;

    private FileWriteTool(Path baseDir, boolean requireApproval) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.requireApproval = requireApproval;
    }

    /**
     * Creates a FileWriteTool sandboxed to the given base directory, without an approval gate.
     *
     * @param baseDir the directory to sandbox file writes within; must be an existing directory
     * @return a new FileWriteTool
     * @throws NullPointerException     if baseDir is null
     * @throws IllegalArgumentException if baseDir does not exist or is not a directory
     */
    public static FileWriteTool of(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        if (!Files.isDirectory(baseDir)) {
            throw new IllegalArgumentException("baseDir must be an existing directory: " + baseDir);
        }
        return new FileWriteTool(baseDir, false);
    }

    /**
     * Returns a new builder for configuring a {@code FileWriteTool} sandboxed to the given
     * base directory.
     *
     * @param baseDir the directory to sandbox file writes within; must be an existing directory
     * @return a new Builder
     * @throws NullPointerException     if baseDir is null
     * @throws IllegalArgumentException if baseDir does not exist or is not a directory
     */
    public static Builder builder(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        if (!Files.isDirectory(baseDir)) {
            throw new IllegalArgumentException("baseDir must be an existing directory: " + baseDir);
        }
        return new Builder(baseDir);
    }

    @Override
    public String name() {
        return "file_write";
    }

    @Override
    public String description() {
        return "Writes content to a file within a sandboxed directory. "
                + "Parent directories are created automatically. "
                + "Path traversal (../) is not permitted.";
    }

    @Override
    public Class<FileWriteInput> inputType() {
        return FileWriteInput.class;
    }

    @Override
    public ToolResult execute(FileWriteInput input) {
        String relativePath = input.path().trim();
        if (relativePath.isBlank()) {
            return ToolResult.failure("Path must not be blank");
        }

        String content = input.content();

        Path resolved = resolveAndValidate(relativePath);
        if (resolved == null) {
            return ToolResult.failure("Access denied: path is outside the sandbox directory");
        }

        if (requireApproval) {
            if (rawReviewHandler() == null) {
                throw new ToolConfigurationException("Tool '"
                        + name()
                        + "' requires approval but no ReviewHandler is configured on the ensemble. "
                        + "Add .reviewHandler(ReviewHandler.console()) to the ensemble builder.");
            }
            String preview = content.length() > APPROVAL_CONTENT_PREVIEW_LENGTH
                    ? content.substring(0, APPROVAL_CONTENT_PREVIEW_LENGTH) + "..."
                    : content;
            ReviewDecision decision =
                    requestApproval("Write to file: " + relativePath + "\nContent preview: " + preview);
            if (decision instanceof ReviewDecision.ExitEarly) {
                return ToolResult.failure("File write rejected by reviewer: " + relativePath);
            }
            if (decision instanceof ReviewDecision.Edit edit) {
                log().debug("Reviewer edited content for file '{}': replacing with revised content", relativePath);
                content = edit.revisedOutput();
            }
        }

        try {
            boolean existed = Files.exists(resolved);
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            // Reject symlinks that point outside the sandbox.
            // normalize()+startsWith() blocks path traversal via "../" sequences,
            // but does not prevent a symlink inside baseDir that resolves to a path
            // outside the sandbox. Check the real path of the parent directory after
            // it has been created so that toRealPath() can resolve it.
            Path checkDir = resolved.getParent() != null ? resolved.getParent() : baseDir;
            if (!checkDir.toRealPath().startsWith(baseDir.toRealPath())) {
                return ToolResult.failure("Access denied: path resolves outside the sandbox directory");
            }
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            int lineCount = content.split("\n", -1).length;
            fireFileChanged(relativePath, existed ? "MODIFIED" : "CREATED", lineCount, 0);
            return ToolResult.success("Successfully wrote to: " + relativePath);
        } catch (IOException e) {
            return ToolResult.failure("Failed to write file: " + e.getMessage());
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

    // ========================
    // Builder
    // ========================

    /**
     * Builder for {@link FileWriteTool}.
     */
    public static final class Builder {

        private final Path baseDir;
        private boolean requireApproval = false;

        private Builder(Path baseDir) {
            this.baseDir = baseDir;
        }

        /**
         * Require human approval before writing the file.
         *
         * <p>When {@code true}, the ensemble's configured
         * {@link net.agentensemble.review.ReviewHandler} is invoked before
         * {@code Files.writeString()} is called. The reviewer sees the target path and a
         * content preview, and may approve, edit the content, or reject the write.
         *
         * <p>If {@code requireApproval(true)} is set but no {@code ReviewHandler} is
         * configured on the ensemble, an {@link IllegalStateException} is thrown at
         * execution time (fail-fast).
         *
         * <p>Default: {@code false}.
         *
         * @param requireApproval {@code true} to require approval before writing
         * @return this builder
         */
        public Builder requireApproval(boolean requireApproval) {
            this.requireApproval = requireApproval;
            return this;
        }

        /**
         * Build the {@link FileWriteTool}.
         *
         * @return a new FileWriteTool
         */
        public FileWriteTool build() {
            return new FileWriteTool(baseDir, requireApproval);
        }
    }
}
