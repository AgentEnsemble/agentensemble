package net.agentensemble.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Executes a list of {@link Phase} objects according to their dependency DAG.
 *
 * <p>Phases with no predecessors ({@link Phase#getAfter()} is empty) start immediately in
 * parallel. When a phase completes, any successor phase whose remaining predecessor count
 * drops to zero is submitted for execution. The executor blocks until all phases have
 * reached a terminal state: COMPLETED, FAILED, or SKIPPED.
 *
 * <h2>Parallelism</h2>
 * Each phase executes on its own Java 21 virtual thread. Multiple independent phases
 * therefore run concurrently with zero OS-thread overhead.
 *
 * <h2>Error handling</h2>
 * When a phase fails:
 * <ul>
 *   <li>The failure is recorded.</li>
 *   <li>All phases that (transitively) depend on the failed phase are marked SKIPPED.</li>
 *   <li>Phases that do not depend on the failed phase continue running normally.</li>
 *   <li>Once all phases have reached a terminal state, the first recorded failure is
 *       re-thrown wrapped in a {@link TaskExecutionException}.</li>
 * </ul>
 *
 * <h2>Execution contract</h2>
 * Each phase is executed by calling the supplied {@code phaseRunner}
 * ({@code BiFunction<Phase, Map<Task, TaskOutput>, EnsembleOutput>}). The second argument
 * is a snapshot of all task outputs completed by prior phases; the runner must seed its
 * internal executor with these outputs to enable cross-phase {@code context()} references.
 * The runner must be thread-safe because multiple phases may be submitted concurrently.
 *
 * <p>Package-private. Instantiated by {@link net.agentensemble.Ensemble}.
 */
public class PhaseDagExecutor {

    private static final Logger log = LoggerFactory.getLogger(PhaseDagExecutor.class);

    /**
     * Execute the given phases according to their dependency DAG.
     *
     * @param phases      the phases to execute; identity-based relationships used for DAG
     * @param phaseRunner function that executes a single phase and returns its output;
     *                    receives (phase, priorTaskOutputs) -- priorTaskOutputs is a snapshot
     *                    of all task outputs from completed prior phases, for cross-phase context;
     *                    must be thread-safe for concurrent invocation
     * @return combined EnsembleOutput aggregating all task outputs from all phases in
     *         execution-completion order
     * @throws TaskExecutionException if any phase fails (after all other phases settle)
     */
    public EnsembleOutput execute(
            List<Phase> phases, BiFunction<Phase, Map<Task, TaskOutput>, EnsembleOutput> phaseRunner) {

        if (phases.isEmpty()) {
            return EnsembleOutput.builder()
                    .raw("")
                    .taskOutputs(List.of())
                    .totalDuration(Duration.ZERO)
                    .totalToolCalls(0)
                    .build();
        }

        Instant startTime = Instant.now();
        int totalPhases = phases.size();

        log.info("Phase DAG starting | Phases: {}", totalPhases);

        // --- Build adjacency maps ---

        // successorMap: phase P -> list of phases that depend on P (where P is in .after())
        // Uses identity comparison via IdentityHashMap.
        Map<Phase, List<Phase>> successorMap = new IdentityHashMap<>();
        for (Phase phase : phases) {
            successorMap.putIfAbsent(phase, new ArrayList<>());
        }
        for (Phase phase : phases) {
            for (Phase predecessor : phase.getAfter()) {
                successorMap
                        .computeIfAbsent(predecessor, k -> new ArrayList<>())
                        .add(phase);
            }
        }

        // remainingPredecessors: count of predecessors not yet completed for each phase.
        // When this reaches 0, the phase can be submitted.
        Map<Phase, AtomicInteger> remainingPredecessors = new IdentityHashMap<>();
        for (Phase phase : phases) {
            remainingPredecessors.put(phase, new AtomicInteger(phase.getAfter().size()));
        }

        // --- Shared state for in-flight execution ---

        // Stores outputs produced by each completed phase (identity-keyed, ConcurrentHashMap
        // for safe concurrent writes from different phase threads).
        Map<Phase, EnsembleOutput> phaseOutputMap = new ConcurrentHashMap<>();

        // Flat map of all completed task outputs keyed by identity -- passed to the phaseRunner
        // as "prior outputs" so cross-phase context() references resolve correctly.
        // Synchronized identity map for concurrent writes.
        Map<Task, TaskOutput> globalTaskOutputs = Collections.synchronizedMap(new IdentityHashMap<>());

        // All task outputs in completion order (across all phases), for building the final output.
        List<TaskOutput> allTaskOutputs = Collections.synchronizedList(new ArrayList<>());

        // First failure seen across all phases
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        // Phases that were skipped due to a predecessor failure (identity-based set)
        Set<Phase> skippedPhases = Collections.newSetFromMap(new ConcurrentHashMap<>());

        // Each phase decrements this latch exactly once when it reaches a terminal state.
        CountDownLatch latch = new CountDownLatch(totalPhases);

        // Capture caller's MDC for propagation into virtual threads
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        if (callerMdc == null) {
            callerMdc = Map.of();
        }
        final Map<String, String> frozenMdc = callerMdc;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // Submit all root phases (those with no predecessor) to seed the execution
            for (Phase phase : phases) {
                if (remainingPredecessors.get(phase).get() == 0) {
                    submitOnePhase(
                            phase,
                            executor,
                            frozenMdc,
                            phaseRunner,
                            successorMap,
                            remainingPredecessors,
                            phaseOutputMap,
                            globalTaskOutputs,
                            allTaskOutputs,
                            firstFailure,
                            skippedPhases,
                            latch);
                }
            }

            // Block until all phases have reached a terminal state
            latch.await();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException(
                    "Phase DAG interrupted", "phase-dag", "multiple", List.copyOf(allTaskOutputs), e);
        }

        // Re-throw first recorded failure
        Throwable failure = firstFailure.get();
        if (failure != null) {
            if (failure instanceof TaskExecutionException tee) {
                throw tee;
            }
            throw new TaskExecutionException(
                    "Phase failed: " + failure.getMessage(),
                    "phase-dag",
                    "multiple",
                    List.copyOf(allTaskOutputs),
                    failure);
        }

        // All phases completed successfully -- assemble final output
        List<TaskOutput> outputs = List.copyOf(allTaskOutputs);
        Duration totalDuration = Duration.between(startTime, Instant.now());
        String finalOutput = outputs.isEmpty() ? "" : outputs.getLast().getRaw();
        int totalToolCalls =
                outputs.stream().mapToInt(TaskOutput::getToolCallCount).sum();

        log.info(
                "Phase DAG completed | Phases: {} | Tasks: {} | Duration: {} | Tool calls: {}",
                totalPhases,
                outputs.size(),
                totalDuration,
                totalToolCalls);

        return EnsembleOutput.builder()
                .raw(finalOutput)
                .taskOutputs(outputs)
                .totalDuration(totalDuration)
                .totalToolCalls(totalToolCalls)
                .build();
    }

    /**
     * Submit a single phase to the virtual-thread pool.
     *
     * <p>The submitted task captures a snapshot of {@code globalTaskOutputs} at the moment
     * of submission. This snapshot represents the outputs from all phases that completed
     * before this phase started, ensuring cross-phase context references resolve correctly.
     */
    private void submitOnePhase(
            Phase phase,
            ExecutorService threadPool,
            Map<String, String> frozenMdc,
            BiFunction<Phase, Map<Task, TaskOutput>, EnsembleOutput> phaseRunner,
            Map<Phase, List<Phase>> successorMap,
            Map<Phase, AtomicInteger> remainingPredecessors,
            Map<Phase, EnsembleOutput> phaseOutputMap,
            Map<Task, TaskOutput> globalTaskOutputs,
            List<TaskOutput> allTaskOutputs,
            AtomicReference<Throwable> firstFailure,
            Set<Phase> skippedPhases,
            CountDownLatch latch) {

        // Snapshot prior outputs at the time this phase is submitted.
        // The snapshot is an immutable copy so the running phase is not affected by
        // concurrent writes from other phases running in parallel.
        final Map<Task, TaskOutput> priorOutputsSnapshot;
        synchronized (globalTaskOutputs) {
            priorOutputsSnapshot = new IdentityHashMap<>(globalTaskOutputs);
        }

        var unused = threadPool.submit(() -> {
            if (!frozenMdc.isEmpty()) {
                MDC.setContextMap(frozenMdc);
            }

            log.info("Phase '{}' starting", phase.getName());
            try {
                EnsembleOutput phaseOutput = phaseRunner.apply(phase, priorOutputsSnapshot);
                phaseOutputMap.put(phase, phaseOutput);

                // Record this phase's outputs into the global lists/maps
                allTaskOutputs.addAll(phaseOutput.getTaskOutputs());
                // Seed global task outputs from the phase output's task-output index so that
                // successor phases can resolve cross-phase context() references.
                if (phaseOutput.getTaskOutputIndex() != null) {
                    globalTaskOutputs.putAll(phaseOutput.getTaskOutputIndex());
                }

                log.info(
                        "Phase '{}' completed | Tasks: {} | Duration: {}",
                        phase.getName(),
                        phaseOutput.getTaskOutputs().size(),
                        phaseOutput.getTotalDuration());

                // Unblock successors
                onPhaseCompleted(
                        phase,
                        threadPool,
                        frozenMdc,
                        phaseRunner,
                        successorMap,
                        remainingPredecessors,
                        phaseOutputMap,
                        globalTaskOutputs,
                        allTaskOutputs,
                        firstFailure,
                        skippedPhases,
                        latch);

            } catch (Exception e) {
                log.error("Phase '{}' failed: {}", phase.getName(), e.getMessage(), e);
                firstFailure.compareAndSet(null, e);
                skipTransitiveDependents(phase, successorMap, skippedPhases, latch);

            } finally {
                latch.countDown();
                MDC.clear();
            }
        });
    }

    /**
     * Called after a phase completes successfully. Decrements the remaining-predecessor
     * count for each successor; submits any successor whose count reaches zero.
     */
    private void onPhaseCompleted(
            Phase completedPhase,
            ExecutorService threadPool,
            Map<String, String> frozenMdc,
            BiFunction<Phase, Map<Task, TaskOutput>, EnsembleOutput> phaseRunner,
            Map<Phase, List<Phase>> successorMap,
            Map<Phase, AtomicInteger> remainingPredecessors,
            Map<Phase, EnsembleOutput> phaseOutputMap,
            Map<Task, TaskOutput> globalTaskOutputs,
            List<TaskOutput> allTaskOutputs,
            AtomicReference<Throwable> firstFailure,
            Set<Phase> skippedPhases,
            CountDownLatch latch) {

        List<Phase> successors = successorMap.getOrDefault(completedPhase, List.of());
        for (Phase successor : successors) {
            int remaining = remainingPredecessors.get(successor).decrementAndGet();
            if (remaining == 0 && !skippedPhases.contains(successor)) {
                submitOnePhase(
                        successor,
                        threadPool,
                        frozenMdc,
                        phaseRunner,
                        successorMap,
                        remainingPredecessors,
                        phaseOutputMap,
                        globalTaskOutputs,
                        allTaskOutputs,
                        firstFailure,
                        skippedPhases,
                        latch);
            }
        }
    }

    /**
     * Recursively mark all transitive dependents of a failed (or skipped) phase as
     * SKIPPED and decrement the latch for each.
     */
    private void skipTransitiveDependents(
            Phase failedPhase, Map<Phase, List<Phase>> successorMap, Set<Phase> skippedPhases, CountDownLatch latch) {

        List<Phase> successors = successorMap.getOrDefault(failedPhase, List.of());
        for (Phase successor : successors) {
            if (skippedPhases.add(successor)) {
                log.warn(
                        "Phase '{}' skipped (predecessor '{}' failed or was skipped)",
                        successor.getName(),
                        failedPhase.getName());
                latch.countDown();
                skipTransitiveDependents(successor, successorMap, skippedPhases, latch);
            }
        }
    }
}
