package net.agentensemble.network.memory;

/**
 * Consistency model for {@link SharedMemory} scopes.
 *
 * <p>Each model defines the coordination behavior for concurrent reads and writes
 * to a shared memory scope across ensembles.
 *
 * @see SharedMemory
 */
public enum Consistency {

    /** Last-write-wins with no coordination. Suitable for context, preferences, notes. */
    EVENTUAL,

    /** Distributed lock before write. Suitable for room assignments, exclusive access. */
    LOCKED,

    /** Compare-and-swap with retry on conflict. Suitable for counters, inventory. */
    OPTIMISTIC,

    /** Framework does not manage consistency; user's tools handle it. */
    EXTERNAL
}
