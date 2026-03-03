package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TaskDependencyGraph.
 *
 * Uses identity-based sets throughout to mirror the graph's own identity semantics.
 */
class TaskDependencyGraphTest {

    private Agent agent;

    @BeforeEach
    void setUp() {
        agent = Agent.builder()
                .role("Worker")
                .goal("Do work")
                .llm(mock(ChatModel.class))
                .build();
    }

    private Task task(String description) {
        return Task.builder()
                .description(description)
                .expectedOutput("Output for " + description)
                .agent(agent)
                .build();
    }

    private Task taskWithContext(String description, List<Task> context) {
        return Task.builder()
                .description(description)
                .expectedOutput("Output for " + description)
                .agent(agent)
                .context(context)
                .build();
    }

    private Set<Task> identitySet(Task... tasks) {
        Set<Task> set = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Task t : tasks) set.add(t);
        return set;
    }

    // ========================
    // Constructor validation
    // ========================

    @Test
    void testConstructor_nullTasks_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new TaskDependencyGraph(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tasks");
    }

    @Test
    void testConstructor_emptyList_createsEmptyGraph() {
        var graph = new TaskDependencyGraph(List.of());
        assertThat(graph.size()).isZero();
        assertThat(graph.getRoots()).isEmpty();
    }

    // ========================
    // size()
    // ========================

    @Test
    void testSize_singleTask() {
        var t1 = task("T1");
        var graph = new TaskDependencyGraph(List.of(t1));
        assertThat(graph.size()).isEqualTo(1);
    }

    @Test
    void testSize_multipleTasks() {
        var t1 = task("T1");
        var t2 = task("T2");
        var t3 = task("T3");
        var graph = new TaskDependencyGraph(List.of(t1, t2, t3));
        assertThat(graph.size()).isEqualTo(3);
    }

    // ========================
    // getRoots()
    // ========================

    @Test
    void testGetRoots_singleTask_isRoot() {
        var t1 = task("T1");
        var graph = new TaskDependencyGraph(List.of(t1));
        assertThat(graph.getRoots()).containsExactlyInAnyOrder(t1);
    }

    @Test
    void testGetRoots_allIndependent_allAreRoots() {
        var t1 = task("T1");
        var t2 = task("T2");
        var t3 = task("T3");
        var graph = new TaskDependencyGraph(List.of(t1, t2, t3));
        assertThat(graph.getRoots()).containsExactlyInAnyOrder(t1, t2, t3);
    }

    @Test
    void testGetRoots_linearChain_onlyFirstIsRoot() {
        var t1 = task("T1");
        var t2 = taskWithContext("T2", List.of(t1));
        var t3 = taskWithContext("T3", List.of(t2));
        var graph = new TaskDependencyGraph(List.of(t1, t2, t3));
        assertThat(graph.getRoots()).containsExactlyInAnyOrder(t1);
    }

    @Test
    void testGetRoots_diamondPattern_onlyApexIsRoot() {
        var a = task("A");
        var b = taskWithContext("B", List.of(a));
        var c = taskWithContext("C", List.of(a));
        var d = taskWithContext("D", List.of(b, c));
        var graph = new TaskDependencyGraph(List.of(a, b, c, d));
        assertThat(graph.getRoots()).containsExactlyInAnyOrder(a);
    }

    @Test
    void testGetRoots_multipleRoots_twoIndependentChains() {
        var t1 = task("T1");
        var t2 = task("T2");
        var t3 = taskWithContext("T3", List.of(t1));
        var t4 = taskWithContext("T4", List.of(t2));
        var graph = new TaskDependencyGraph(List.of(t1, t2, t3, t4));
        assertThat(graph.getRoots()).containsExactlyInAnyOrder(t1, t2);
    }

    // ========================
    // getReadyTasks()
    // ========================

    @Test
    void testGetReadyTasks_emptyCompleted_returnsRoots() {
        var t1 = task("T1");
        var t2 = task("T2");
        var t3 = taskWithContext("T3", List.of(t1));
        var graph = new TaskDependencyGraph(List.of(t1, t2, t3));

        Set<Task> ready = graph.getReadyTasks(identitySet());
        assertThat(ready).containsExactlyInAnyOrder(t1, t2);
    }

    @Test
    void testGetReadyTasks_afterT1Completes_unlocksT3() {
        var t1 = task("T1");
        var t2 = task("T2");
        var t3 = taskWithContext("T3", List.of(t1));
        var graph = new TaskDependencyGraph(List.of(t1, t2, t3));

        Set<Task> ready = graph.getReadyTasks(identitySet(t1));
        // t1 is completed so not returned; t2 is root and not yet completed; t3 is now ready
        assertThat(ready).containsExactlyInAnyOrder(t2, t3);
    }

    @Test
    void testGetReadyTasks_diamondAllCompleted_returnsD() {
        var a = task("A");
        var b = taskWithContext("B", List.of(a));
        var c = taskWithContext("C", List.of(a));
        var d = taskWithContext("D", List.of(b, c));
        var graph = new TaskDependencyGraph(List.of(a, b, c, d));

        // After A, B, and C complete, D becomes ready
        Set<Task> ready = graph.getReadyTasks(identitySet(a, b, c));
        assertThat(ready).containsExactlyInAnyOrder(d);
    }

    @Test
    void testGetReadyTasks_diamondOnlyBCompleted_DNotReady() {
        var a = task("A");
        var b = taskWithContext("B", List.of(a));
        var c = taskWithContext("C", List.of(a));
        var d = taskWithContext("D", List.of(b, c));
        var graph = new TaskDependencyGraph(List.of(a, b, c, d));

        // B completed but C has not -- D is not ready yet
        Set<Task> ready = graph.getReadyTasks(identitySet(a, b));
        assertThat(ready).containsExactlyInAnyOrder(c);
    }

    @Test
    void testGetReadyTasks_allCompleted_returnsEmpty() {
        var t1 = task("T1");
        var t2 = taskWithContext("T2", List.of(t1));
        var graph = new TaskDependencyGraph(List.of(t1, t2));

        Set<Task> ready = graph.getReadyTasks(identitySet(t1, t2));
        assertThat(ready).isEmpty();
    }

    @Test
    void testGetReadyTasks_nullCompleted_throwsIllegalArgumentException() {
        var t1 = task("T1");
        var graph = new TaskDependencyGraph(List.of(t1));
        assertThatThrownBy(() -> graph.getReadyTasks(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // getDependents()
    // ========================

    @Test
    void testGetDependents_taskWithNoDependents_returnsEmpty() {
        var t1 = task("T1");
        var t2 = taskWithContext("T2", List.of(t1));
        var graph = new TaskDependencyGraph(List.of(t1, t2));

        assertThat(graph.getDependents(t2)).isEmpty();
    }

    @Test
    void testGetDependents_singleDependent() {
        var t1 = task("T1");
        var t2 = taskWithContext("T2", List.of(t1));
        var graph = new TaskDependencyGraph(List.of(t1, t2));

        assertThat(graph.getDependents(t1)).containsExactlyInAnyOrder(t2);
    }

    @Test
    void testGetDependents_multipleDepend() {
        var a = task("A");
        var b = taskWithContext("B", List.of(a));
        var c = taskWithContext("C", List.of(a));
        var graph = new TaskDependencyGraph(List.of(a, b, c));

        assertThat(graph.getDependents(a)).containsExactlyInAnyOrder(b, c);
    }

    @Test
    void testGetDependents_diamondMiddle_DependsOnBoth() {
        var a = task("A");
        var b = taskWithContext("B", List.of(a));
        var c = taskWithContext("C", List.of(a));
        var d = taskWithContext("D", List.of(b, c));
        var graph = new TaskDependencyGraph(List.of(a, b, c, d));

        assertThat(graph.getDependents(b)).containsExactlyInAnyOrder(d);
        assertThat(graph.getDependents(c)).containsExactlyInAnyOrder(d);
    }

    @Test
    void testGetDependents_unknownTask_returnsEmpty() {
        var t1 = task("T1");
        var graph = new TaskDependencyGraph(List.of(t1));
        var outsideTask = task("Outside");

        assertThat(graph.getDependents(outsideTask)).isEmpty();
    }

    // ========================
    // Identity semantics
    // ========================

    @Test
    void testIdentitySemantics_equalTasksDifferentInstances_treatedDistinctly() {
        // Two tasks with identical field values but distinct identity
        var t1a = Task.builder()
                .description("Same description")
                .expectedOutput("Same output")
                .agent(agent)
                .build();
        var t1b = Task.builder()
                .description("Same description")
                .expectedOutput("Same output")
                .agent(agent)
                .build();

        // t1b depends on t1a (different instances, same field values)
        var t2 = taskWithContext("T2", List.of(t1a));

        var graph = new TaskDependencyGraph(List.of(t1a, t1b, t2));

        // Both are distinct in the graph
        assertThat(graph.size()).isEqualTo(3);

        // t1a and t1b are both roots (t1b is independent)
        assertThat(graph.getRoots()).containsExactlyInAnyOrder(t1a, t1b);

        // After completing t1a (by identity), t2 becomes ready; t1b still a root (not yet done)
        Set<Task> ready = graph.getReadyTasks(identitySet(t1a));
        assertThat(ready).containsExactlyInAnyOrder(t1b, t2);
    }
}
