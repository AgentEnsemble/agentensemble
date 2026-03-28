package net.agentensemble.tools.coding;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.agentensemble.tool.AbstractTypedAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that searches code content using regex patterns within a sandboxed directory.
 *
 * <p>Detects available search backends at construction time ({@code rg} &gt; {@code grep}
 * &gt; pure-Java fallback). The pure-Java fallback ensures the tool works in any JVM
 * environment.
 *
 * <p>Results are formatted as {@code file:line:content} and capped at {@value #MAX_MATCHES}
 * matches.
 *
 * <pre>
 * CodeSearchTool tool = CodeSearchTool.of(Path.of("/workspace/project"));
 * </pre>
 */
public final class CodeSearchTool extends AbstractTypedAgentTool<CodeSearchInput> {

    static final int MAX_MATCHES = 100;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final SandboxValidator sandbox;
    private final SearchBackend backend;

    enum SearchBackend {
        RG,
        GREP,
        JAVA_FALLBACK
    }

    private CodeSearchTool(SandboxValidator sandbox, SearchBackend backend) {
        this.sandbox = sandbox;
        this.backend = backend;
    }

    /**
     * Creates a CodeSearchTool sandboxed to the given base directory.
     *
     * @param baseDir the directory to sandbox searches within; must be an existing directory
     * @return a new CodeSearchTool
     * @throws NullPointerException     if baseDir is null
     * @throws IllegalArgumentException if baseDir does not exist or is not a directory
     */
    public static CodeSearchTool of(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        SearchBackend backend = detectBackend();
        return new CodeSearchTool(new SandboxValidator(baseDir), backend);
    }

    /** Package-private constructor for testing with a specific backend. */
    static CodeSearchTool withBackend(Path baseDir, SearchBackend backend) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        return new CodeSearchTool(new SandboxValidator(baseDir), backend);
    }

    private static SearchBackend detectBackend() {
        if (isCommandAvailable("rg", "--version")) {
            return SearchBackend.RG;
        }
        if (isCommandAvailable("grep", "--version")) {
            return SearchBackend.GREP;
        }
        return SearchBackend.JAVA_FALLBACK;
    }

    private static boolean isCommandAvailable(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String name() {
        return "code_search";
    }

    @Override
    public String description() {
        return "Searches code content using regex patterns. "
                + "Returns matching lines formatted as file:line:content, "
                + "capped at " + MAX_MATCHES + " matches.";
    }

    @Override
    public Class<CodeSearchInput> inputType() {
        return CodeSearchInput.class;
    }

    @Override
    public ToolResult execute(CodeSearchInput input) {
        if (input.pattern() == null || input.pattern().isBlank()) {
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
            if (backend == SearchBackend.JAVA_FALLBACK) {
                return executeJavaSearch(input, searchRoot);
            }
            return executeSubprocessSearch(input, searchRoot);
        } catch (PatternSyntaxException e) {
            return ToolResult.failure("Invalid regex pattern: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Search was interrupted");
        } catch (IOException e) {
            // Fall back to Java search if subprocess fails
            try {
                return executeJavaSearch(input, searchRoot);
            } catch (IOException | PatternSyntaxException fallbackEx) {
                return ToolResult.failure("Search failed: " + fallbackEx.getMessage());
            }
        }
    }

    private ToolResult executeSubprocessSearch(CodeSearchInput input, Path searchRoot)
            throws IOException, InterruptedException {
        List<String> command = buildSubprocessCommand(input, searchRoot);

        SubprocessRunner.SubprocessResult result = SubprocessRunner.run(command, searchRoot, DEFAULT_TIMEOUT, log());

        // grep/rg return exit code 1 when no matches found -- that's not an error
        if (result.exitCode() != 0 && result.exitCode() != 1) {
            String errorMsg =
                    !result.stderr().isBlank() ? result.stderr() : "Search failed with exit code " + result.exitCode();
            return ToolResult.failure(errorMsg);
        }

        String output = result.stdout();
        if (output.isBlank()) {
            return ToolResult.success("No matches found for pattern: " + input.pattern());
        }

        // Relativize paths and cap results
        String relativized = relativizePaths(output, searchRoot);
        return ToolResult.success(capMatches(relativized));
    }

    /** Package-private for testing. */
    List<String> buildSubprocessCommand(CodeSearchInput input, Path searchRoot) {
        boolean isRg = backend == SearchBackend.RG;
        List<String> command = new ArrayList<>();
        command.add(isRg ? "rg" : "grep");
        if (!isRg) {
            command.add("-r");
        }
        command.add("-n");
        command.add("--color=never");
        if (input.contextLines() != null && input.contextLines() > 0) {
            command.add("-C");
            command.add(String.valueOf(input.contextLines()));
        }
        if (Boolean.TRUE.equals(input.ignoreCase())) {
            command.add("-i");
        }
        if (input.glob() != null && !input.glob().isBlank()) {
            command.add(isRg ? "-g" : "--include=" + input.glob());
            if (isRg) {
                command.add(input.glob());
            }
        }
        if (isRg) {
            command.add("--max-count=" + MAX_MATCHES);
        }
        command.add(input.pattern());
        command.add(searchRoot.toString());
        return command;
    }

    /** Package-private for testing. */
    String relativizePaths(String output, Path searchRoot) {
        String basePath = sandbox.baseDir().toString();
        String unixPrefix = basePath + "/";
        String windowsPrefix = basePath + "\\";

        // Strip base-path prefix from the path segment of each line only,
        // to avoid rewriting base-path occurrences within matched line content.
        String[] lines = output.split("\n", -1);
        StringBuilder sb = new StringBuilder(output.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith(unixPrefix)) {
                line = line.substring(unixPrefix.length());
            } else if (line.startsWith(windowsPrefix)) {
                line = line.substring(windowsPrefix.length());
            }
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /** Package-private for testing. */
    String capMatches(String output) {
        String[] lines = output.split("\n");
        if (lines.length <= MAX_MATCHES) {
            return output;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_MATCHES; i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(lines[i]);
        }
        sb.append("\n... (results capped at ").append(MAX_MATCHES).append(" matches)");
        return sb.toString();
    }

    private ToolResult executeJavaSearch(CodeSearchInput input, Path searchRoot) throws IOException {
        int flags = Boolean.TRUE.equals(input.ignoreCase()) ? Pattern.CASE_INSENSITIVE : 0;
        Pattern regex = Pattern.compile(input.pattern(), flags);
        int contextSize = input.contextLines() != null ? input.contextLines() : 0;

        PathMatcher globMatcher = null;
        if (input.glob() != null && !input.glob().isBlank()) {
            globMatcher = searchRoot.getFileSystem().getPathMatcher("glob:" + input.glob());
        }

        List<String> results = new ArrayList<>();
        PathMatcher finalGlobMatcher = globMatcher;
        Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (results.size() >= MAX_MATCHES) {
                    return FileVisitResult.TERMINATE;
                }
                if (finalGlobMatcher != null && !finalGlobMatcher.matches(file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }
                // Skip binary files (simple heuristic: check for null bytes in first 512 bytes)
                try {
                    if (!isLikelyTextFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    searchFile(file, regex, contextSize, results);
                } catch (IOException e) {
                    // Skip unreadable files
                }
                return results.size() >= MAX_MATCHES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        if (results.isEmpty()) {
            return ToolResult.success("No matches found for pattern: " + input.pattern());
        }

        Collections.sort(results);
        return ToolResult.success(String.join("\n", results));
    }

    private void searchFile(Path file, Pattern regex, int contextSize, List<String> results) throws IOException {
        List<String> lines = Files.readAllLines(file);
        Path relative = sandbox.baseDir().relativize(file);
        for (int i = 0; i < lines.size() && results.size() < MAX_MATCHES; i++) {
            Matcher m = regex.matcher(lines.get(i));
            if (m.find()) {
                if (contextSize > 0) {
                    int start = Math.max(0, i - contextSize);
                    int end = Math.min(lines.size() - 1, i + contextSize);
                    for (int j = start; j <= end && results.size() < MAX_MATCHES; j++) {
                        String prefix = (j == i) ? ":" : "-";
                        results.add(relative + prefix + (j + 1) + prefix + lines.get(j));
                    }
                } else {
                    results.add(relative + ":" + (i + 1) + ":" + lines.get(i));
                }
            }
        }
    }

    private static boolean isLikelyTextFile(Path file) throws IOException {
        byte[] header = new byte[512];
        try (var is = Files.newInputStream(file)) {
            int read = is.read(header);
            if (read <= 0) {
                return true; // Empty file is text
            }
            for (int i = 0; i < read; i++) {
                if (header[i] == 0) {
                    return false;
                }
            }
        }
        return true;
    }
}
