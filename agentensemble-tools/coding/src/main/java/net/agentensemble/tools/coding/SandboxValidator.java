package net.agentensemble.tools.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Validates that resolved paths remain within a sandboxed base directory.
 *
 * <p>This utility is shared across all coding tools to enforce consistent sandbox
 * containment. It mirrors the validation logic in {@code FileWriteTool}: normalize + startsWith
 * for path traversal prevention, and toRealPath for symlink escape detection.
 */
final class SandboxValidator {

    private final Path baseDir;

    SandboxValidator(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        if (!Files.isDirectory(baseDir)) {
            throw new IllegalArgumentException("baseDir must be an existing directory: " + baseDir);
        }
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    /**
     * Resolves a relative path against the base directory and validates it remains within the
     * sandbox. Returns {@code null} if the resolved path is outside the sandbox.
     */
    Path resolveAndValidate(String relativePath) {
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

    /**
     * Checks whether the given resolved path escapes the sandbox via a symlink.
     *
     * <p>Call this after directories have been created so that {@code toRealPath()} can
     * fully resolve the path. Returns {@code true} if the real path is outside the sandbox.
     */
    boolean isSymlinkEscape(Path resolved) throws IOException {
        Path checkDir = resolved.getParent() != null ? resolved.getParent() : baseDir;
        return !checkDir.toRealPath().startsWith(baseDir.toRealPath());
    }

    /** Returns the normalized, absolute base directory. */
    Path baseDir() {
        return baseDir;
    }
}
