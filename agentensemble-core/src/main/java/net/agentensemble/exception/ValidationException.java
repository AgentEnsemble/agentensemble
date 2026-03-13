package net.agentensemble.exception;

/**
 * Thrown when ensemble, agent, or task configuration is invalid.
 *
 * This is always a user error (incorrect configuration). It is thrown at
 * build time (in builder methods) or at the start of ensemble.run() before
 * any execution begins.
 */
public class ValidationException extends AgentEnsembleException {

    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }
}
