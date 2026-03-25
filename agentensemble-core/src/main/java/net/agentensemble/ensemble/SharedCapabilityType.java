package net.agentensemble.ensemble;

/**
 * Classifies a shared capability as either a task or a tool.
 *
 * <p>Shared capabilities are registered via
 * {@link net.agentensemble.Ensemble.EnsembleBuilder#shareTask(String, net.agentensemble.Task)}
 * and
 * {@link net.agentensemble.Ensemble.EnsembleBuilder#shareTool(String, dev.langchain4j.agent.tool.Tool)}.
 *
 * @see SharedCapability
 */
public enum SharedCapabilityType {

    /** A full task that can be delegated to this ensemble for execution. */
    TASK,

    /** A single tool that can be invoked remotely by another ensemble's agents. */
    TOOL
}
