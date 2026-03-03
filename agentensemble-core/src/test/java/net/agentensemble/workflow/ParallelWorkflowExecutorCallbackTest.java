package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.exception.ParallelExecutionException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryContext;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ParallelWorkflowExecutor callback and task-index behaviour.
 */
class ParallelWorkflowExecutorCallbackTest extends ParallelWorkflowExecutorTestBase {

    @Test
    void testCallbacks_taskStartEvents_haveOneBasedIndices() {
        var a1 = agentWithResponse("A1", "Result 1");
        var a2 = agentWithResponse("A2", "Result 2");
        var a3 = agentWithResponse("A3", "Result 3");
        var t1 = task("Task 1", a1);
        var t2 = task("Task 2", a2);
        var t3 = task("Task 3", a3);

        List<TaskStartEvent> events = Collections.synchronizedList(new ArrayList<>());
        ExecutionContext ec = ExecutionContext.of(MemoryContext.disabled(), false, List.of(new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent event) {
                events.add(event);
            }
        }));

        executor().execute(List.of(t1, t2, t3), ec);

        assertThat(events).hasSize(3);
        assertThat(events.stream().map(TaskStartEvent::taskIndex).toList()).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(events.stream().map(TaskStartEvent::totalTasks).toList()).containsOnly(3);
    }

    @Test
    void testCallbacks_taskCompleteEvents_haveOneBasedIndices() {
        var a1 = agentWithResponse("A1", "Result 1");
        var a2 = agentWithResponse("A2", "Result 2");
        var t1 = task("Task 1", a1);
        var t2 = task("Task 2", a2);

        List<Integer> completedIndices = Collections.synchronizedList(new ArrayList<>());
        ExecutionContext ec = ExecutionContext.of(MemoryContext.disabled(), false, List.of(new EnsembleListener() {
            @Override
            public void onTaskComplete(TaskCompleteEvent event) {
                completedIndices.add(event.taskIndex());
            }
        }));

        executor().execute(List.of(t1, t2), ec);

        assertThat(completedIndices).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void testCallbacks_taskFailedEvent_hasOneBasedIndex() {
        var bad = agentThatFails("Bad");
        var good = agentWithResponse("Good", "Good result");
        var t1 = task("Task 1", bad);
        var t2 = task("Task 2", good);

        List<Integer> failedIndices = Collections.synchronizedList(new ArrayList<>());
        ExecutionContext ec = ExecutionContext.of(MemoryContext.disabled(), false, List.of(new EnsembleListener() {
            @Override
            public void onTaskFailed(TaskFailedEvent event) {
                failedIndices.add(event.taskIndex());
            }
        }));

        assertThatThrownBy(
                        () -> executor(ParallelErrorStrategy.CONTINUE_ON_ERROR).execute(List.of(t1, t2), ec))
                .isInstanceOf(ParallelExecutionException.class);

        assertThat(failedIndices).containsExactly(1);
    }
}
