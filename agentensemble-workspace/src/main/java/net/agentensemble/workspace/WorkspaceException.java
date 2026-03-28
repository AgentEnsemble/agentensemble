package net.agentensemble.workspace;

import net.agentensemble.exception.AgentEnsembleException;

/**
 * Exception thrown when a workspace operation fails.
 *
 * <p>This covers failures such as git worktree creation errors, invalid repository paths, and
 * directory copy failures. Like all framework exceptions, this is unchecked.
 */
public class WorkspaceException extends AgentEnsembleException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a workspace exception with the given message.
     *
     * @param message the detail message
     */
    public WorkspaceException(String message) {
        super(message);
    }

    /**
     * Create a workspace exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
