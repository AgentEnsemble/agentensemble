package net.agentensemble.exception;

import java.util.List;

/**
 * Thrown when template variable substitution fails because one or more
 * variables referenced in a task description or expected output are not
 * provided in the inputs map.
 *
 * All missing variables are reported at once (not just the first one).
 *
 * Recovery: provide the missing variables in ensemble.run(inputs), or
 * remove the {variable} placeholders from the task definition.
 */
public class PromptTemplateException extends AgentEnsembleException {

    private static final long serialVersionUID = 1L;

    private final List<String> missingVariables;
    private final String template;

    public PromptTemplateException(String message, List<String> missingVariables, String template) {
        super(message);
        this.missingVariables = List.copyOf(missingVariables);
        this.template = template;
    }

    /**
     * The variable names that were present in the template but not supplied
     * in the inputs map.
     */
    public List<String> getMissingVariables() {
        return missingVariables;
    }

    /**
     * The original template string containing the unresolved variables.
     */
    public String getTemplate() {
        return template;
    }
}
