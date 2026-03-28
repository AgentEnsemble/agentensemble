package net.agentensemble.workspace;

/**
 * Factory for creating isolated {@link Workspace} instances.
 *
 * <p>Implementations include {@link GitWorktreeProvider} for git-based projects and
 * {@link DirectoryWorkspace} static factory methods for non-git projects.
 *
 * @see GitWorktreeProvider
 */
public interface WorkspaceProvider {

    /**
     * Create a new workspace with the given configuration.
     *
     * @param config workspace configuration
     * @return a new active workspace
     * @throws WorkspaceException if workspace creation fails
     */
    Workspace create(WorkspaceConfig config);

    /**
     * Create a new workspace with default configuration.
     *
     * @return a new active workspace
     * @throws WorkspaceException if workspace creation fails
     */
    default Workspace create() {
        return create(WorkspaceConfig.builder().build());
    }
}
