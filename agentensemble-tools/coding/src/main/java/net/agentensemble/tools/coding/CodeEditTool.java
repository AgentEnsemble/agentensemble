package net.agentensemble.tools.coding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.agentensemble.exception.ToolConfigurationException;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.tool.AbstractTypedAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that performs surgical code edits within a sandboxed base directory.
 *
 * <p>Three edit modes are supported:
 * <ul>
 *   <li><strong>replace_lines</strong> -- replace a line range (1-based, inclusive)</li>
 *   <li><strong>find_replace</strong> -- find text/regex and replace first occurrence</li>
 *   <li><strong>write</strong> -- full file overwrite (creates the file if it doesn't exist)</li>
 * </ul>
 *
 * <p>Create an instance using the factory method or builder:
 *
 * <pre>
 * // Simple factory (no approval gate)
 * CodeEditTool tool = CodeEditTool.of(Path.of("/workspace/project"));
 *
 * // Builder with approval gate
 * CodeEditTool tool = CodeEditTool.builder(Path.of("/workspace/project"))
 *     .requireApproval(true)
 *     .build();
 * </pre>
 */
public final class CodeEditTool extends AbstractTypedAgentTool<CodeEditInput> {

    private static final int CONTEXT_SIZE = 3;
    private static final int APPROVAL_CONTENT_PREVIEW_LENGTH = 200;

    private final SandboxValidator sandbox;
    private final boolean requireApproval;

    private CodeEditTool(SandboxValidator sandbox, boolean requireApproval) {
        this.sandbox = sandbox;
        this.requireApproval = requireApproval;
    }

    /**
     * Creates a CodeEditTool sandboxed to the given base directory, without an approval gate.
     *
     * @param baseDir the directory to sandbox edits within; must be an existing directory
     * @return a new CodeEditTool
     * @throws NullPointerException     if baseDir is null
     * @throws IllegalArgumentException if baseDir does not exist or is not a directory
     */
    public static CodeEditTool of(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        return new CodeEditTool(new SandboxValidator(baseDir), false);
    }

    /**
     * Returns a new builder for configuring a {@code CodeEditTool}.
     *
     * @param baseDir the directory to sandbox edits within; must be an existing directory
     * @return a new Builder
     * @throws NullPointerException     if baseDir is null
     * @throws IllegalArgumentException if baseDir does not exist or is not a directory
     */
    public static Builder builder(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        return new Builder(new SandboxValidator(baseDir));
    }

    @Override
    public String name() {
        return "code_edit";
    }

    @Override
    public String description() {
        return "Edits code files with surgical precision. "
                + "Modes: replace_lines (line range), find_replace (text/regex), write (full file). "
                + "Path traversal (../) is not permitted.";
    }

    @Override
    public Class<CodeEditInput> inputType() {
        return CodeEditInput.class;
    }

    @Override
    public ToolResult execute(CodeEditInput input) {
        String relativePath = input.path();
        if (relativePath == null || relativePath.isBlank()) {
            return ToolResult.failure("Path must not be blank");
        }
        relativePath = relativePath.trim();

        String command = input.command();
        if (command == null || command.isBlank()) {
            return ToolResult.failure("Command must not be blank");
        }

        Path resolved = sandbox.resolveAndValidate(relativePath);
        if (resolved == null) {
            return ToolResult.failure("Access denied: path is outside the workspace directory");
        }

        String content = input.content();
        if (content == null) {
            return ToolResult.failure("Content must not be null");
        }

        if (requireApproval) {
            ToolResult approvalResult = checkApproval(relativePath, command, content);
            if (approvalResult != null) {
                return approvalResult;
            }
        }

        return switch (command) {
            case "replace_lines" -> executeReplaceLines(input, resolved, relativePath);
            case "find_replace" -> executeFindReplace(input, resolved, relativePath, content);
            case "write" -> executeWrite(resolved, relativePath, content);
            default -> ToolResult.failure(
                    "Unknown command: " + command + ". Expected: replace_lines, find_replace, or write");
        };
    }

    private ToolResult checkApproval(String relativePath, String command, String content) {
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
                requestApproval("Edit file (" + command + "): " + relativePath + "\nContent preview: " + preview);
        if (decision instanceof ReviewDecision.ExitEarly) {
            return ToolResult.failure("Edit rejected by reviewer: " + relativePath);
        }
        // Edit decisions are not applied to the content itself (unlike FileWriteTool)
        // because the edit parameters are more complex than a simple content field
        return null;
    }

    private ToolResult executeReplaceLines(CodeEditInput input, Path resolved, String relativePath) {
        if (input.startLine() == null || input.endLine() == null) {
            return ToolResult.failure("replace_lines requires startLine and endLine");
        }
        if (!Files.exists(resolved)) {
            return ToolResult.failure("File not found: " + relativePath);
        }

        int startLine = input.startLine();
        int endLine = input.endLine();
        if (startLine < 1 || endLine < 1) {
            return ToolResult.failure("Line numbers must be >= 1");
        }
        if (startLine > endLine) {
            return ToolResult.failure("startLine must be <= endLine");
        }

        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(resolved, StandardCharsets.UTF_8));
            if (startLine > lines.size()) {
                return ToolResult.failure(
                        "startLine " + startLine + " exceeds file length (" + lines.size() + " lines)");
            }
            if (endLine > lines.size()) {
                return ToolResult.failure("endLine " + endLine + " exceeds file length (" + lines.size() + " lines)");
            }

            // Replace the line range (1-based inclusive → 0-based)
            List<String> newContentLines = List.of(input.content().split("\n", -1));
            lines.subList(startLine - 1, endLine).clear();
            lines.addAll(startLine - 1, newContentLines);

            if (sandbox.isSymlinkEscape(resolved)) {
                return ToolResult.failure("Access denied: path resolves outside the workspace directory");
            }
            Files.writeString(resolved, String.join("\n", lines), StandardCharsets.UTF_8);

            String snippet = buildContextSnippet(lines, startLine - 1, startLine - 1 + newContentLines.size() - 1);
            return ToolResult.success(
                    "Replaced lines " + startLine + "-" + endLine + " in " + relativePath + "\n" + snippet);
        } catch (IOException e) {
            return ToolResult.failure("Failed to edit file: " + e.getMessage());
        }
    }

    private ToolResult executeFindReplace(CodeEditInput input, Path resolved, String relativePath, String content) {
        if (input.find() == null || input.find().isEmpty()) {
            return ToolResult.failure("find_replace requires 'find' to be set");
        }
        if (!Files.exists(resolved)) {
            return ToolResult.failure("File not found: " + relativePath);
        }

        try {
            String fileContent = Files.readString(resolved, StandardCharsets.UTF_8);
            String newContent;
            if (Boolean.TRUE.equals(input.regex())) {
                Pattern pattern = Pattern.compile(input.find());
                Matcher matcher = pattern.matcher(fileContent);
                if (!matcher.find()) {
                    return ToolResult.failure("Pattern not found in file: " + input.find());
                }
                newContent = matcher.replaceFirst(Matcher.quoteReplacement(content));
            } else {
                int idx = fileContent.indexOf(input.find());
                if (idx < 0) {
                    return ToolResult.failure("Text not found in file: " + input.find());
                }
                newContent = fileContent.substring(0, idx)
                        + content
                        + fileContent.substring(idx + input.find().length());
            }

            if (sandbox.isSymlinkEscape(resolved)) {
                return ToolResult.failure("Access denied: path resolves outside the workspace directory");
            }
            Files.writeString(resolved, newContent, StandardCharsets.UTF_8);

            List<String> newLines = List.of(newContent.split("\n", -1));
            return ToolResult.success("Replaced in " + relativePath + "\n"
                    + buildContextSnippet(newLines, 0, Math.min(CONTEXT_SIZE, newLines.size() - 1)));
        } catch (java.util.regex.PatternSyntaxException e) {
            return ToolResult.failure("Invalid regex pattern: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.failure("Failed to edit file: " + e.getMessage());
        }
    }

    private ToolResult executeWrite(Path resolved, String relativePath, String content) {
        try {
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            if (sandbox.isSymlinkEscape(resolved)) {
                return ToolResult.failure("Access denied: path resolves outside the workspace directory");
            }
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            return ToolResult.success("Successfully wrote to: " + relativePath);
        } catch (IOException e) {
            return ToolResult.failure("Failed to write file: " + e.getMessage());
        }
    }

    private String buildContextSnippet(List<String> lines, int editStart, int editEnd) {
        int start = Math.max(0, editStart - CONTEXT_SIZE);
        int end = Math.min(lines.size() - 1, editEnd + CONTEXT_SIZE);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            sb.append(String.format("%4d | %s%n", i + 1, lines.get(i)));
        }
        return sb.toString().stripTrailing();
    }

    /** Builder for {@link CodeEditTool}. */
    public static final class Builder {

        private final SandboxValidator sandbox;
        private boolean requireApproval = false;

        private Builder(SandboxValidator sandbox) {
            this.sandbox = sandbox;
        }

        /**
         * Require human approval before editing a file.
         *
         * @param requireApproval {@code true} to require approval before editing
         * @return this builder
         */
        public Builder requireApproval(boolean requireApproval) {
            this.requireApproval = requireApproval;
            return this;
        }

        /** Build the {@link CodeEditTool}. */
        public CodeEditTool build() {
            return new CodeEditTool(sandbox, requireApproval);
        }
    }
}
