package net.agentensemble.workflow;

/**
 * Defines how tasks within an ensemble are executed.
 */
public enum Workflow {

    /**
     * Tasks execute one after another in list order.
     * Output from earlier tasks is passed as context to later tasks
     * that declare those tasks in their context list.
     */
    SEQUENTIAL,

    /**
     * A manager agent automatically delegates tasks to worker agents
     * based on their roles and goals. The manager synthesizes the final output.
     * Reserved for Phase 2.
     */
    HIERARCHICAL
}
