package net.agentensemble.exception;

/**
 * Base exception for all AgentEnsemble framework errors.
 *
 * All framework exceptions are unchecked (extend RuntimeException) to avoid
 * forcing users to catch-or-declare everywhere. Users who want to handle
 * specific errors can catch the relevant subtype.
 */
public class AgentEnsembleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AgentEnsembleException(String message) {
        super(message);
    }

    public AgentEnsembleException(String message, Throwable cause) {
        super(message, cause);
    }

    public AgentEnsembleException(Throwable cause) {
        super(cause);
    }
}
