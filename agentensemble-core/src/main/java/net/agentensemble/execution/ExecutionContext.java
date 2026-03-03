package net.agentensemble.execution;

import java.util.List;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.memory.MemoryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable execution context bundling all cross-cutting concerns for a single
 * ensemble run: memory state, verbosity, and event listeners.
 *
 * An {@code ExecutionContext} is created once per {@link net.agentensemble.Ensemble#run()}
 * invocation and threaded through the entire execution stack -- workflow executors,
 * {@link net.agentensemble.agent.AgentExecutor}, delegation tools, and delegation
 * contexts -- replacing the previously separate {@code verbose} and
 * {@link MemoryContext} parameters.
 *
 * Fire methods ({@link #fireTaskStart}, {@link #fireTaskComplete}, {@link #fireTaskFailed},
 * {@link #fireToolCall}) dispatch events to all registered listeners. Each listener is
 * called independently; an exception from one listener is caught, logged, and does not
 * prevent subsequent listeners from being notified or abort task execution.
 *
 * Thread safety: this class is immutable. Fire methods may be called concurrently from
 * parallel workflow virtual threads. Listener implementations must be thread-safe when
 * used with parallel workflows.
 */
public final class ExecutionContext {

    private static final Logger log = LoggerFactory.getLogger(ExecutionContext.class);

    private final MemoryContext memoryContext;
    private final boolean verbose;
    private final List<EnsembleListener> listeners;

    private ExecutionContext(MemoryContext memoryContext, boolean verbose, List<EnsembleListener> listeners) {
        this.memoryContext = memoryContext;
        this.verbose = verbose;
        this.listeners = listeners;
    }

    /**
     * Create an ExecutionContext with all fields specified.
     *
     * @param memoryContext runtime memory state for this run; must not be null;
     *                      use {@link MemoryContext#disabled()} when memory is not configured
     * @param verbose       when true, elevates execution logging to INFO level
     * @param listeners     event listeners to notify during execution; must not be null;
     *                      an immutable defensive copy is stored
     * @return a new ExecutionContext
     * @throws IllegalArgumentException if memoryContext or listeners is null
     */
    public static ExecutionContext of(MemoryContext memoryContext, boolean verbose, List<EnsembleListener> listeners) {
        if (memoryContext == null) {
            throw new IllegalArgumentException("memoryContext must not be null");
        }
        if (listeners == null) {
            throw new IllegalArgumentException("listeners must not be null");
        }
        return new ExecutionContext(memoryContext, verbose, List.copyOf(listeners));
    }

    /**
     * Create an ExecutionContext with no event listeners.
     *
     * @param memoryContext runtime memory state for this run; must not be null
     * @param verbose       when true, elevates execution logging to INFO level
     * @return a new ExecutionContext with an empty listeners list
     * @throws IllegalArgumentException if memoryContext is null
     */
    public static ExecutionContext of(MemoryContext memoryContext, boolean verbose) {
        return of(memoryContext, verbose, List.of());
    }

    /**
     * Create an ExecutionContext with disabled memory, verbose=false, and no listeners.
     *
     * Suitable for backward-compatible use in tests and delegation tools where the
     * full context is not available or not needed.
     *
     * @return a new disabled ExecutionContext
     */
    public static ExecutionContext disabled() {
        return new ExecutionContext(MemoryContext.disabled(), false, List.of());
    }

    // ========================
    // Accessors
    // ========================

    /**
     * @return the memory context for this run; never null
     */
    public MemoryContext memoryContext() {
        return memoryContext;
    }

    /**
     * @return true if verbose logging is enabled for this run
     */
    public boolean isVerbose() {
        return verbose;
    }

    /**
     * @return the immutable list of registered event listeners; never null
     */
    public List<EnsembleListener> listeners() {
        return listeners;
    }

    // ========================
    // Event dispatch
    // ========================

    /**
     * Fire a {@link TaskStartEvent} to all registered listeners.
     *
     * Exceptions from individual listeners are caught and logged. All listeners
     * are called regardless of whether a previous listener threw.
     *
     * @param event the event to fire; must not be null
     */
    public void fireTaskStart(TaskStartEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onTaskStart(event);
            } catch (Exception e) {
                log.warn("EnsembleListener threw exception in onTaskStart: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Fire a {@link TaskCompleteEvent} to all registered listeners.
     *
     * Exceptions from individual listeners are caught and logged.
     *
     * @param event the event to fire; must not be null
     */
    public void fireTaskComplete(TaskCompleteEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onTaskComplete(event);
            } catch (Exception e) {
                log.warn("EnsembleListener threw exception in onTaskComplete: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Fire a {@link TaskFailedEvent} to all registered listeners.
     *
     * Exceptions from individual listeners are caught and logged.
     *
     * @param event the event to fire; must not be null
     */
    public void fireTaskFailed(TaskFailedEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onTaskFailed(event);
            } catch (Exception e) {
                log.warn("EnsembleListener threw exception in onTaskFailed: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Fire a {@link ToolCallEvent} to all registered listeners.
     *
     * Exceptions from individual listeners are caught and logged.
     *
     * @param event the event to fire; must not be null
     */
    public void fireToolCall(ToolCallEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onToolCall(event);
            } catch (Exception e) {
                log.warn("EnsembleListener threw exception in onToolCall: {}", e.getMessage(), e);
            }
        }
    }
}
