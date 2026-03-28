package net.agentensemble.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple {@link Workspace} backed by a temporary directory.
 *
 * <p>This is the fallback for non-git projects. Use the static factory methods to create
 * instances:
 * <pre>
 * // Empty workspace
 * try (Workspace ws = DirectoryWorkspace.createTemp()) {
 *     // work in ws.path()
 * }
 *
 * // Copy files from a source directory
 * try (Workspace ws = DirectoryWorkspace.createTemp(sourceDir)) {
 *     // work in ws.path() -- contains a copy of sourceDir contents
 * }
 * </pre>
 *
 * <p>{@link #close()} recursively deletes the temporary directory when {@code autoCleanup} is
 * enabled (the default). This class is thread-safe.
 */
public final class DirectoryWorkspace implements Workspace {

    private static final Logger LOG = LoggerFactory.getLogger(DirectoryWorkspace.class);
    private static final String TEMP_PREFIX = "agentensemble-workspace-";

    private final Path directory;
    private final String workspaceId;
    private final boolean autoCleanup;
    private final AtomicBoolean active = new AtomicBoolean(true);

    private DirectoryWorkspace(Path directory, String workspaceId, boolean autoCleanup) {
        this.directory = directory;
        this.workspaceId = workspaceId;
        this.autoCleanup = autoCleanup;
    }

    /**
     * Create an empty temporary workspace with auto-cleanup enabled.
     *
     * @return a new active workspace
     * @throws WorkspaceException if the temporary directory cannot be created
     */
    public static DirectoryWorkspace createTemp() {
        return createTemp(null, true);
    }

    /**
     * Create a temporary workspace by copying files from a source directory.
     *
     * <p>The {@code .git} directory in the source is skipped during the copy.
     *
     * @param sourceDir the directory whose contents to copy into the workspace
     * @return a new active workspace with a copy of the source files
     * @throws WorkspaceException if the directory cannot be created or files cannot be copied
     */
    public static DirectoryWorkspace createTemp(Path sourceDir) {
        return createTemp(sourceDir, true);
    }

    /**
     * Create a temporary workspace with explicit auto-cleanup control.
     *
     * @param sourceDir the directory whose contents to copy, or {@code null} for an empty workspace
     * @param autoCleanup whether {@link #close()} should delete the directory
     * @return a new active workspace
     * @throws WorkspaceException if the directory cannot be created or files cannot be copied
     */
    public static DirectoryWorkspace createTemp(Path sourceDir, boolean autoCleanup) {
        try {
            Path dir = Files.createTempDirectory(TEMP_PREFIX);
            String id = dir.getFileName().toString();

            if (sourceDir != null) {
                copyRecursive(sourceDir, dir);
            }

            LOG.info("Created directory workspace {} at {}", id, dir);
            return new DirectoryWorkspace(dir, id, autoCleanup);
        } catch (IOException e) {
            throw new WorkspaceException("Failed to create temporary workspace", e);
        }
    }

    @Override
    public Path path() {
        return directory;
    }

    @Override
    public String id() {
        return workspaceId;
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }

        if (!autoCleanup) {
            LOG.debug("Workspace {} marked inactive (autoCleanup disabled, skipping deletion)", workspaceId);
            return;
        }

        deleteRecursive(directory);
    }

    private static void copyRecursive(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName() != null && ".git".equals(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path targetDir = target.resolve(source.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursive(Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            LOG.debug("Deleted workspace directory {}", directory);
        } catch (IOException e) {
            LOG.warn("Failed to delete workspace directory {}: {}", directory, e.getMessage());
        }
    }
}
