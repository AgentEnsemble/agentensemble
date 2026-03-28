package net.agentensemble.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.CloseResource") // Workspaces are mocks; lifecycle managed by listener under test
class WorkspaceLifecycleListenerTest {

    private WorkspaceProvider provider;
    private Workspace workspace;
    private WorkspaceLifecycleListener listener;

    @BeforeEach
    void setUp() {
        provider = mock(WorkspaceProvider.class);
        workspace = mock(Workspace.class);
        when(workspace.id()).thenReturn("agent-12345678");
        when(workspace.path()).thenReturn(Path.of("/tmp/workspace"));
        when(provider.create(any(WorkspaceConfig.class))).thenReturn(workspace);
        listener = WorkspaceLifecycleListener.of(provider);
    }

    @Test
    void onTaskStart_createsWorkspace() {
        TaskStartEvent event = new TaskStartEvent("Fix the bug", "Developer", 1, 1);

        listener.onTaskStart(event);

        verify(provider).create(any(WorkspaceConfig.class));
        assertThat(listener.getWorkspace(1, "Fix the bug")).isPresent().contains(workspace);
    }

    @Test
    void onTaskComplete_closesWorkspace() {
        TaskStartEvent startEvent = new TaskStartEvent("Fix the bug", "Developer", 1, 1);
        listener.onTaskStart(startEvent);

        TaskCompleteEvent completeEvent =
                new TaskCompleteEvent("Fix the bug", "Developer", taskOutput("done"), Duration.ofSeconds(1), 1, 1);
        listener.onTaskComplete(completeEvent);

        verify(workspace).close();
        assertThat(listener.getWorkspace(1, "Fix the bug")).isEmpty();
    }

    @Test
    void onTaskFailed_closesWorkspace() {
        TaskStartEvent startEvent = new TaskStartEvent("Fix the bug", "Developer", 1, 1);
        listener.onTaskStart(startEvent);

        TaskFailedEvent failedEvent = new TaskFailedEvent(
                "Fix the bug", "Developer", new RuntimeException("oops"), Duration.ofSeconds(1), 1, 1);
        listener.onTaskFailed(failedEvent);

        verify(workspace).close();
        assertThat(listener.getWorkspace(1, "Fix the bug")).isEmpty();
    }

    @Test
    void onTaskComplete_unknownTask_doesNotThrow() {
        TaskCompleteEvent completeEvent =
                new TaskCompleteEvent("Unknown task", "Developer", taskOutput("done"), Duration.ofSeconds(1), 1, 1);

        listener.onTaskComplete(completeEvent); // Should not throw

        verify(workspace, never()).close();
    }

    @Test
    void onTaskFailed_unknownTask_doesNotThrow() {
        TaskFailedEvent failedEvent = new TaskFailedEvent(
                "Unknown task", "Developer", new RuntimeException("oops"), Duration.ofSeconds(1), 1, 1);

        listener.onTaskFailed(failedEvent); // Should not throw

        verify(workspace, never()).close();
    }

    @Test
    void getWorkspace_empty_whenNoTaskStarted() {
        assertThat(listener.getWorkspace(1, "nonexistent")).isEmpty();
    }

    @Test
    void activeWorkspaces_returnsUnmodifiableView() {
        TaskStartEvent event = new TaskStartEvent("Task A", "Developer", 1, 2);
        listener.onTaskStart(event);

        assertThat(listener.activeWorkspaces()).hasSize(1);
        assertThat(listener.activeWorkspaces()).containsKey("1:Task A");
    }

    @Test
    void activeWorkspaces_emptyAfterAllComplete() {
        listener.onTaskStart(new TaskStartEvent("Task A", "Developer", 1, 1));
        listener.onTaskComplete(
                new TaskCompleteEvent("Task A", "Developer", taskOutput("done"), Duration.ofSeconds(1), 1, 1));

        assertThat(listener.activeWorkspaces()).isEmpty();
    }

    @Test
    void concurrentTasks_independentWorkspaces() {
        Workspace ws1 = mock(Workspace.class);
        Workspace ws2 = mock(Workspace.class);
        when(ws1.id()).thenReturn("ws-1");
        when(ws2.id()).thenReturn("ws-2");
        when(provider.create(any(WorkspaceConfig.class))).thenReturn(ws1, ws2);

        listener.onTaskStart(new TaskStartEvent("Task A", "Agent1", 1, 2));
        listener.onTaskStart(new TaskStartEvent("Task B", "Agent2", 2, 2));

        assertThat(listener.getWorkspace(1, "Task A")).isPresent().contains(ws1);
        assertThat(listener.getWorkspace(2, "Task B")).isPresent().contains(ws2);
        assertThat(listener.activeWorkspaces()).hasSize(2);

        // Complete Task A -- Task B should remain active
        listener.onTaskComplete(
                new TaskCompleteEvent("Task A", "Agent1", taskOutput("done"), Duration.ofSeconds(1), 1, 2));

        verify(ws1).close();
        verify(ws2, never()).close();
        assertThat(listener.getWorkspace(1, "Task A")).isEmpty();
        assertThat(listener.getWorkspace(2, "Task B")).isPresent().contains(ws2);
    }

    @Test
    void duplicateDescriptions_differentIndices_areIndependent() {
        Workspace ws1 = mock(Workspace.class);
        Workspace ws2 = mock(Workspace.class);
        when(ws1.id()).thenReturn("ws-1");
        when(ws2.id()).thenReturn("ws-2");
        when(provider.create(any(WorkspaceConfig.class))).thenReturn(ws1, ws2);

        // Same description, different task indices (e.g., retries)
        listener.onTaskStart(new TaskStartEvent("Fix the bug", "Developer", 1, 2));
        listener.onTaskStart(new TaskStartEvent("Fix the bug", "Developer", 2, 2));

        assertThat(listener.getWorkspace(1, "Fix the bug")).isPresent().contains(ws1);
        assertThat(listener.getWorkspace(2, "Fix the bug")).isPresent().contains(ws2);
        assertThat(listener.activeWorkspaces()).hasSize(2);
    }

    @Test
    void of_withCustomConfig_usesProvidedConfig() {
        WorkspaceConfig config = WorkspaceConfig.builder().namePrefix("custom").build();
        WorkspaceLifecycleListener customListener = WorkspaceLifecycleListener.of(provider, config);

        customListener.onTaskStart(new TaskStartEvent("Task", "Developer", 1, 1));

        verify(provider).create(config);
    }

    @Test
    void closeException_doesNotPropagate() {
        Workspace failingWorkspace = mock(Workspace.class);
        when(failingWorkspace.id()).thenReturn("failing-ws");
        when(provider.create(any(WorkspaceConfig.class))).thenReturn(failingWorkspace);
        org.mockito.Mockito.doThrow(new RuntimeException("close failed"))
                .when(failingWorkspace)
                .close();

        listener.onTaskStart(new TaskStartEvent("Task", "Developer", 1, 1));
        listener.onTaskComplete(
                new TaskCompleteEvent("Task", "Developer", taskOutput("done"), Duration.ofSeconds(1), 1, 1));

        // Should not propagate, and workspace should be removed from active map
        verify(failingWorkspace).close();
        assertThat(listener.activeWorkspaces()).isEmpty();
    }

    @Test
    void providerFailure_doesNotPropagateOrCorruptState() {
        when(provider.create(any(WorkspaceConfig.class))).thenThrow(new WorkspaceException("git not found"));

        listener.onTaskStart(new TaskStartEvent("Task", "Developer", 1, 1));

        // Should not propagate, and workspace should not be in the active map
        assertThat(listener.getWorkspace(1, "Task")).isEmpty();
        assertThat(listener.activeWorkspaces()).isEmpty();
    }

    @Test
    void providerThrowsRuntimeException_doesNotPropagate() {
        when(provider.create(any(WorkspaceConfig.class))).thenThrow(new IllegalArgumentException("unexpected error"));

        listener.onTaskStart(new TaskStartEvent("Task", "Developer", 1, 1));

        // Catches all exceptions, not just WorkspaceException
        assertThat(listener.getWorkspace(1, "Task")).isEmpty();
        assertThat(listener.activeWorkspaces()).isEmpty();
    }

    private static TaskOutput taskOutput(String raw) {
        return TaskOutput.builder()
                .raw(raw)
                .taskDescription("test")
                .agentRole("Developer")
                .completedAt(Instant.now())
                .duration(Duration.ofSeconds(1))
                .build();
    }
}
