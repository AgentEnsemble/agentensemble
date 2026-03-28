package net.agentensemble.tools.coding;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.agentensemble.tool.AbstractTypedAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that finds files by glob pattern within a sandboxed base directory.
 *
 * <p>Uses {@link java.nio.file.PathMatcher} with {@link Files#walkFileTree} for
 * pure-Java file discovery. Results are sorted alphabetically and capped at
 * {@value #MAX_RESULTS} entries.
 *
 * <p>Create an instance using the factory method:
 *
 * <pre>
 * GlobTool tool = GlobTool.of(Path.of("/workspace/project"));
 * </pre>
 */
public final class GlobTool extends AbstractTypedAgentTool<GlobInput> {

    static final int MAX_RESULTS = 200;

    private final SandboxValidator sandbox;

    private GlobTool(SandboxValidator sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * Creates a GlobTool sandboxed to the given base directory.
     *
     * @param baseDir the directory to sandbox searches within; must be an existing directory
     * @return a new GlobTool
     * @throws NullPointerException     if baseDir is null
     * @throws IllegalArgumentException if baseDir does not exist or is not a directory
     */
    public static GlobTool of(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        return new GlobTool(new SandboxValidator(baseDir));
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Finds files matching a glob pattern within the workspace. "
                + "Use '**/*.java' for recursive search or '*.txt' for current directory only. "
                + "Returns up to " + MAX_RESULTS + " matching file paths sorted alphabetically.";
    }

    @Override
    public Class<GlobInput> inputType() {
        return GlobInput.class;
    }

    @Override
    public ToolResult execute(GlobInput input) {
        String pattern = input.pattern();
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.failure("Pattern must not be blank");
        }

        Path searchRoot = sandbox.baseDir();
        if (input.path() != null && !input.path().isBlank()) {
            Path resolved = sandbox.resolveAndValidate(input.path().trim());
            if (resolved == null) {
                return ToolResult.failure("Access denied: path is outside the workspace directory");
            }
            if (!Files.isDirectory(resolved)) {
                return ToolResult.failure("Path is not a directory: " + input.path());
            }
            searchRoot = resolved;
        }

        try {
            FileSystem fs = searchRoot.getFileSystem();
            PathMatcher matcher = fs.getPathMatcher("glob:" + pattern);
            List<String> matches = walkAndMatch(searchRoot, matcher);
            Collections.sort(matches);

            if (matches.isEmpty()) {
                return ToolResult.success("No files found matching pattern: " + pattern);
            }
            return ToolResult.success(String.join("\n", matches));
        } catch (java.util.regex.PatternSyntaxException e) {
            return ToolResult.failure("Invalid glob pattern: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.failure("Error searching files: " + e.getMessage());
        }
    }

    private List<String> walkAndMatch(Path searchRoot, PathMatcher matcher) throws IOException {
        List<String> matches = new ArrayList<>();
        Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relative = sandbox.baseDir().relativize(file);
                if (matcher.matches(relative)) {
                    matches.add(relative.toString());
                }
                return matches.size() >= MAX_RESULTS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // Skip unreadable files
                return FileVisitResult.CONTINUE;
            }
        });
        return matches;
    }
}
