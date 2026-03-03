package net.agentensemble.execution;

import java.util.List;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.memory.MemoryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable runtime context for a single ensemble execution.
 *
 * <p>Bundles the parameters that must be threaded through the entire execution stack:
 *
 * <ul>
 *   <li>{@link MemoryContext} -- runtime memory state for this run</li>
 *   <li>{@code verbose} -- whether execution logging is elevated to INFO level</li>
 *   <li>{@link EnsembleListener} list -- observers notified of execution lifecycle events</li>
 * </ul>
 *
 * <p>Use {@link #of(boolean, MemoryContext)} to create a context without listeners, and
 * {@link #of(boolean, MemoryContext, List)} to include listeners.
 *
 * <p>Event-firing methods ({@link #fireTaskStart}, {@link #fireTaskComplete},
 * {@link #fireToolCall}) invoke each listener synchronously. Any exception thrown by a
 * listener is caught and logged; it never aborts execution or affects other listeners.
 */
public final class ExecutionContext {

    private static final Logger log = LoggerFactory.getLogger(ExecutionContext.class);

    private final MemoryContext memoryContext;
    private final boolean verbose;
    private final List<EnsembleListener> listeners;

    private ExecutionContext(MemoryContext memoryContext, boolean verbose, List<EnsembleListener> listeners) {
        this.memoryContext = memoryContext;
        this.verbose = verbose;
        this.listeners = List.copyOf(listeners);
    }

    /**
     * Create an {@code ExecutionContext} with no listeners.
     *
     * @param verbose       whether verbose logging is enabled
     * @param memoryContext runtime memory state; {@code null} is normalized to
     *                      {@link MemoryContext#disabled()}
     * @return a new {@code ExecutionContext}
     */
    public static ExecutionContext of(boolean verbose, MemoryContext memoryContext) {
        return new ExecutionContext(
                memoryContext != null ? memoryContext : MemoryContext.disabled(), verbose, List.of());
    }

    /**
     * Create an {@code ExecutionContext} with the given listeners.
     *
     * @param verbose       whether verbose logging is enabled
     * @param memoryContext runtime memory state; {@code null} is normalized to
     *                      {@link MemoryContext#disabled()}
     * @param listeners     observers to notify; {@code null} is treated as empty
     * @return a new {@code ExecutionContext}
     */
    public static ExecutionContext of(boolean verbose, MemoryContext memoryContext, List<EnsembleListener> listeners) {
        return new ExecutionContext(
                memoryContext != null ? memoryContext : MemoryContext.disabled(),
                verbose,
                listeners != null ? listeners : List.of());
    }

    /** @return the runtime memory state for this run */
    public MemoryContext getMemoryContext() {
        return memoryContext;
    }

    /** @return true if verbose logging is enabled */
    public boolean isVerbose() {
        return verbose;
    }

    /** @return the immutable list of registered listeners */
    public List<EnsembleListener> getListeners() {
        return listeners;
    }

    /**
     * Fire a {@link TaskStartEvent} to all registered listeners.
     *
     * <p>Each listener is invoked synchronously. Any exception thrown is caught and logged;
     * it does not prevent subsequent listeners from being notified.
     *
     * @param event the event to fire
     */
    public void fireTaskStart(TaskStartEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onTaskStart(event);
            } catch (Exception e) {
                log.warn("EnsembleListener.onTaskStart threw an exception (ignored)", e);
            }
        }
    }

    /**
     * Fire a {@link TaskCompleteEvent} to all registered listeners.
     *
     * <p>Each listener is invoked synchronously. Any exception thrown is caught and logged;
     * it does not prevent subsequent listeners from being notified.
     *
     * @param event the event to fire
     */
    public void fireTaskComplete(TaskCompleteEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onTaskComplete(event);
            } catch (Exception e) {
                log.warn("EnsembleListener.onTaskComplete threw an exception (ignored)", e);
            }
        }
    }

    /**
     * Fire a {@link ToolCallEvent} to all registered listeners.
     *
     * <p>Each listener is invoked synchronously. Any exception thrown is caught and logged;
     * it does not prevent subsequent listeners from being notified.
     *
     * @param event the event to fire
     */
    public void fireToolCall(ToolCallEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onToolCall(event);
            } catch (Exception e) {
                log.warn("EnsembleListener.onToolCall threw an exception (ignored)", e);
            }
        }
    }
}
