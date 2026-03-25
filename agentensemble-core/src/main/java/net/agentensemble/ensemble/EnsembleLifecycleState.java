package net.agentensemble.ensemble;

/**
 * Lifecycle states for a long-running ensemble.
 *
 * <p>State transitions follow a strict ordering:
 * <pre>
 *     STARTING -> READY -> DRAINING -> STOPPED
 * </pre>
 *
 * <ul>
 *   <li>{@link #STARTING} -- ensemble is initializing, binding its server port,
 *       and registering capabilities on the network. Not yet accepting work.</li>
 *   <li>{@link #READY} -- ensemble is accepting and processing work requests.</li>
 *   <li>{@link #DRAINING} -- ensemble has been asked to stop. It finishes in-flight
 *       work but does not accept new requests. Transitions to {@link #STOPPED}
 *       after the drain timeout expires or all in-flight work completes.</li>
 *   <li>{@link #STOPPED} -- ensemble has completed shutdown. All connections are
 *       closed and capabilities are deregistered.</li>
 * </ul>
 *
 * @see net.agentensemble.Ensemble#start(int)
 * @see net.agentensemble.Ensemble#stop()
 */
public enum EnsembleLifecycleState {

    /** Ensemble is initializing; not yet accepting work. */
    STARTING,

    /** Ensemble is running and accepting work requests. */
    READY,

    /** Ensemble is shutting down; finishing in-flight work, rejecting new requests. */
    DRAINING,

    /** Ensemble has completed shutdown. */
    STOPPED
}
