package net.agentensemble.workspace;

import java.nio.file.Path;
import lombok.Builder;
import lombok.Value;

/**
 * Configuration for workspace creation.
 *
 * <p>All fields have sensible defaults. Use the builder to override:
 * <pre>
 * WorkspaceConfig config = WorkspaceConfig.builder()
 *     .namePrefix("refactor")
 *     .baseRef("main")
 *     .autoCleanup(true)
 *     .build();
 * </pre>
 */
@Value
@Builder
public class WorkspaceConfig {

    /**
     * Prefix for generated branch/directory names.
     *
     * <p>When {@code null}, the provider uses a default prefix (typically {@code "agent"}).
     */
    String namePrefix;

    /**
     * Git ref to create the worktree from. Default: {@code HEAD}.
     *
     * <p>Ignored by {@link DirectoryWorkspace}.
     */
    @Builder.Default
    String baseRef = "HEAD";

    /**
     * Whether to automatically clean up the workspace on {@link Workspace#close()}.
     * Default: {@code true}.
     *
     * <p>When {@code false}, {@link Workspace#close()} marks the workspace as inactive but
     * does not remove the worktree or temporary directory, leaving the user responsible for
     * manual cleanup.
     */
    @Builder.Default
    boolean autoCleanup = true;

    /**
     * Base directory for workspaces.
     *
     * <p>When {@code null}, the provider resolves a default (typically
     * {@code <repoRoot>/.agentensemble/workspaces/}).
     */
    Path workspacesDir;
}
