package net.agentensemble.delegation;

/**
 * Priority hint attached to a {@link DelegationRequest}.
 *
 * <p>Priority values are informational: the framework does not currently schedule or
 * reorder delegations based on priority. They are available to custom orchestration
 * logic and for observability (e.g., routing high-priority work to faster workers).
 */
public enum DelegationPriority {

    /** Lower urgency than normal; may be deferred or deprioritised. */
    LOW,

    /** Standard priority; the default for all delegations unless overridden. */
    NORMAL,

    /** Higher urgency than normal; should be handled before LOW and NORMAL work. */
    HIGH,

    /** Maximum urgency; treat as time-sensitive or business-critical. */
    CRITICAL
}
