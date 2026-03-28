package net.agentensemble.workspace;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link EnsembleListener} that manages workspace lifecycle automatically.
 *
 * <p>Creates a new workspace when a task starts, and cleans it up when the task completes or
 * fails. This listener is opt-in -- register it on the ensemble builder:
 * <pre>
 * GitWorktreeProvider provider = GitWorktreeProvider.of(repoRoot);
 * WorkspaceLifecycleListener listener = WorkspaceLifecycleListener.of(provider);
 *
 * Ensemble.builder()
 *     .listener(listener)
 *     .task(codingTask)
 *     .build()
 *     .run();
 *
 * // During task execution, tools can look up their workspace:
 * Optional&lt;Workspace&gt; ws = listener.getWorkspace(taskDescription);
 * </pre>
 *
 * <p>This class is thread-safe. Multiple tasks running in parallel each get their own
 * independent workspace.
 */
public final class WorkspaceLifecycleListener implements EnsembleListener {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceLifecycleListener.class);

    private final WorkspaceProvider provider;
    private final WorkspaceConfig config;
    private final Map<String, Workspace> active = new ConcurrentHashMap<>();

    private WorkspaceLifecycleListener(WorkspaceProvider provider, WorkspaceConfig config) {
        this.provider = provider;
        this.config = config;
    }

    /**
     * Create a listener with default workspace configuration.
     *
     * @param provider the workspace provider to use
     * @return a new listener
     */
    public static WorkspaceLifecycleListener of(WorkspaceProvider provider) {
        return new WorkspaceLifecycleListener(
                provider, WorkspaceConfig.builder().build());
    }

    /**
     * Create a listener with custom workspace configuration.
     *
     * @param provider the workspace provider to use
     * @param config the workspace configuration
     * @return a new listener
     */
    public static WorkspaceLifecycleListener of(WorkspaceProvider provider, WorkspaceConfig config) {
        return new WorkspaceLifecycleListener(provider, config);
    }

    /**
     * Look up the workspace for a task by its description.
     *
     * @param taskDescription the task description used as the lookup key
     * @return the workspace if the task is active, or empty if not found
     */
    public Optional<Workspace> getWorkspace(String taskDescription) {
        return Optional.ofNullable(active.get(taskDescription));
    }

    /**
     * Return an unmodifiable view of all currently active workspaces.
     *
     * <p>Useful for monitoring and debugging.
     *
     * @return unmodifiable map of task description to workspace
     */
    public Map<String, Workspace> activeWorkspaces() {
        return Collections.unmodifiableMap(active);
    }

    @Override
    public void onTaskStart(TaskStartEvent event) {
        try {
            Workspace ws = provider.create(config);
            active.put(event.taskDescription(), ws);
            LOG.info("Created workspace {} for task '{}'", ws.id(), event.taskDescription());
        } catch (WorkspaceException e) {
            LOG.error("Failed to create workspace for task '{}': {}", event.taskDescription(), e.getMessage(), e);
        }
    }

    @Override
    public void onTaskComplete(TaskCompleteEvent event) {
        closeWorkspace(event.taskDescription());
    }

    @Override
    public void onTaskFailed(TaskFailedEvent event) {
        closeWorkspace(event.taskDescription());
    }

    private void closeWorkspace(String taskDescription) {
        Workspace ws = active.remove(taskDescription);
        if (ws == null) {
            return;
        }
        try {
            ws.close();
            LOG.info("Closed workspace {} for task '{}'", ws.id(), taskDescription);
        } catch (Exception e) {
            LOG.warn("Exception closing workspace {} for task '{}': {}", ws.id(), taskDescription, e.getMessage(), e);
        }
    }
}
