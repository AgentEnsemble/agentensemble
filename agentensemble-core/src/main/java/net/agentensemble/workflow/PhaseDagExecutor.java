package net.agentensemble.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
import net.agentensemble.review.PhaseReviewDecision;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Executes a list of {@link Phase} objects according to their dependency DAG, with optional
 * per-phase review gates that can trigger phase retries or predecessor retries.
 *
 * <p>Phases with no predecessors ({@code Phase.getAfter()} is empty) start immediately in
 * parallel. When a phase completes, any successor phase whose remaining predecessor count
 * drops to zero is submitted for execution. The executor blocks until all phases have
 * reached a terminal state: COMPLETED, FAILED, or SKIPPED.
 *
 * <h2>Parallelism</h2>
 * Each phase executes on its own Java 21 virtual thread. Multiple independent phases
 * therefore run concurrently with zero OS-thread overhead.
 *
 * <h2>Phase review and retry</h2>
 * When a {@link Phase} has a {@link PhaseReview} attached, the review task fires after all
 * tasks in the phase complete. The review task's output is parsed into a
 * {@link PhaseReviewDecision}:
 * <ul>
 *   <li>{@code Approve} -- successors are unlocked normally.</li>
 *   <li>{@code Retry(feedback)} -- the phase's tasks are re-executed with the feedback
 *       injected as a {@code ## Revision Instructions} prompt section. Up to
 *       {@code PhaseReview.maxRetries} self-retries are allowed; when exhausted the
 *       last output is accepted.</li>
 *   <li>{@code RetryPredecessor(phaseName, feedback)} -- the named direct predecessor phase
 *       is re-executed with feedback, then the current phase is re-executed with the updated
 *       predecessor outputs. Up to {@code PhaseReview.maxPredecessorRetries} predecessor
 *       retries are allowed per predecessor.</li>
 *   <li>{@code Reject(reason)} -- the phase fails and all successors are skipped.</li>
 * </ul>
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
 * <p>Public API, typically instantiated and used by {@link net.agentensemble.Ensemble}.
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

        // Stores the final approved output for each completed phase (identity-keyed,
        // ConcurrentHashMap for safe concurrent writes).
        Map<Phase, EnsembleOutput> phaseOutputMap = new ConcurrentHashMap<>();

        // Flat map of all committed task outputs keyed by identity -- passed to the phaseRunner
        // as "prior outputs" so cross-phase context() references resolve correctly.
        // Synchronized identity map for concurrent writes.
        Map<Task, TaskOutput> globalTaskOutputs = Collections.synchronizedMap(new IdentityHashMap<>());

        // All committed task outputs in completion order (across all phases).
        List<TaskOutput> allTaskOutputs = Collections.synchronizedList(new ArrayList<>());

        // First failure seen across all phases.
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        // Settle-once map for atomic submission vs. skip decisions.
        Map<Phase, String> settledPhases = new ConcurrentHashMap<>();

        // Each phase decrements this latch exactly once when it reaches a terminal state.
        CountDownLatch latch = new CountDownLatch(totalPhases);

        // Capture caller's MDC for propagation into virtual threads.
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        if (callerMdc == null) {
            callerMdc = Map.of();
        }
        final Map<String, String> frozenMdc = callerMdc;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (Phase phase : phases) {
                if (remainingPredecessors.get(phase).get() == 0
                        && settledPhases.putIfAbsent(phase, "submitted") == null) {
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
                            settledPhases,
                            latch);
                }
            }

            // Block until all phases have reached a terminal state.
            latch.await();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException(
                    "Phase DAG interrupted", "phase-dag", "multiple", List.copyOf(allTaskOutputs), e);
        }

        // Re-throw first recorded failure.
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

        // All phases completed successfully -- assemble final output.
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
     * <p>Captures a snapshot of {@code globalTaskOutputs} at the moment of submission as the
     * prior-outputs baseline. Delegates to {@link #runPhaseWithRetry} for the actual execution
     * (which may iterate multiple times when a {@link PhaseReview} is attached), then commits
     * the final approved output to global shared state and unblocks successors.
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
            Map<Phase, String> settledPhases,
            CountDownLatch latch) {

        // Snapshot prior outputs at the time this phase is submitted.
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
                // Run phase with review/retry loop -- returns final approved output.
                EnsembleOutput finalOutput = runPhaseWithRetry(
                        phase, priorOutputsSnapshot, phaseRunner, phaseOutputMap, globalTaskOutputs, allTaskOutputs);

                // Commit final (approved) output to global shared state.
                phaseOutputMap.put(phase, finalOutput);
                allTaskOutputs.addAll(finalOutput.getTaskOutputs());
                if (finalOutput.getTaskOutputIndex() != null) {
                    globalTaskOutputs.putAll(finalOutput.getTaskOutputIndex());
                }

                log.info(
                        "Phase '{}' completed | Tasks: {} | Duration: {} | Tool calls: {}",
                        phase.getName(),
                        finalOutput.getTaskOutputs().size(),
                        finalOutput.getTotalDuration(),
                        finalOutput.getTotalToolCalls());

                // Unblock successors.
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
                        settledPhases,
                        latch);

            } catch (Exception e) {
                log.error("Phase '{}' failed: {}", phase.getName(), e.getMessage(), e);
                firstFailure.compareAndSet(null, e);
                skipTransitiveDependents(phase, successorMap, settledPhases, latch);
            } finally {
                latch.countDown();
                MDC.clear();
            }
        });
    }

    /**
     * Execute a phase, running the optional review gate after each attempt and retrying
     * as directed until the review approves (or retry limits are exhausted).
     *
     * <p>Three retry paths are supported:
     * <ul>
     *   <li><b>Self-retry</b> ({@link PhaseReviewDecision.Retry}) -- the phase's tasks are
     *       rebuilt with reviewer feedback and re-executed up to
     *       {@code PhaseReview.maxRetries} times.</li>
     *   <li><b>Predecessor retry</b> ({@link PhaseReviewDecision.RetryPredecessor}) -- the
     *       named direct predecessor phase is re-run with feedback, global state is updated,
     *       and then the current phase is re-run from scratch (attempt 0) with the refreshed
     *       predecessor outputs.</li>
     *   <li><b>Reject</b> ({@link PhaseReviewDecision.Reject}) -- a
     *       {@link TaskExecutionException} is thrown, which the calling thread records as a
     *       phase failure and uses to skip transitive dependents.</li>
     * </ul>
     *
     * @return the final approved {@link EnsembleOutput} for this phase
     */
    private EnsembleOutput runPhaseWithRetry(
            Phase phase,
            Map<Task, TaskOutput> initialPriorOutputs,
            BiFunction<Phase, Map<Task, TaskOutput>, EnsembleOutput> phaseRunner,
            Map<Phase, EnsembleOutput> phaseOutputMap,
            Map<Task, TaskOutput> globalTaskOutputs,
            List<TaskOutput> allTaskOutputs) {

        PhaseReview review = phase.getReview();

        if (review == null) {
            // No review gate: execute once and return.
            EnsembleOutput output = phaseRunner.apply(phase, initialPriorOutputs);
            log.debug(
                    "Phase '{}' executed (no review) | Tasks: {}",
                    phase.getName(),
                    output.getTaskOutputs().size());
            return output;
        }

        int maxRetries = review.getMaxRetries();
        int maxPredRetries = review.getMaxPredecessorRetries();

        Phase currentPhase = phase;
        Map<Task, TaskOutput> currentPrior = initialPriorOutputs;
        // Tracks how many times each predecessor has been retried for this phase's review.
        Map<Phase, Integer> predecessorRetryCounts = new IdentityHashMap<>();

        for (int attempt = 0; ; attempt++) {
            log.info("Phase '{}' attempt {} starting", phase.getName(), attempt + 1);
            EnsembleOutput output = phaseRunner.apply(currentPhase, currentPrior);
            log.info(
                    "Phase '{}' attempt {} completed | Tasks: {} | Duration: {}",
                    phase.getName(),
                    attempt + 1,
                    output.getTaskOutputs().size(),
                    output.getTotalDuration());

            // Run the review task and parse its decision.
            PhaseReviewDecision decision =
                    executeReviewTask(review, phase, currentPhase, output, currentPrior, phaseRunner);
            log.info(
                    "Phase '{}' review decision on attempt {}: [{}]",
                    phase.getName(),
                    attempt + 1,
                    truncate(decision.toText(), 120));

            switch (decision) {
                case PhaseReviewDecision.Approve ignored -> {
                    return output;
                }

                case PhaseReviewDecision.Retry r -> {
                    if (attempt >= maxRetries) {
                        log.warn(
                                "Phase '{}' reached max self-retries ({}), accepting last output",
                                phase.getName(),
                                maxRetries);
                        return output;
                    }
                    log.info(
                            "Phase '{}' self-retry {} with feedback: [{}]",
                            phase.getName(),
                            attempt + 1,
                            truncate(r.feedback(), 200));
                    currentPhase = rebuildPhaseWithFeedback(phase, currentPhase, r.feedback(), output, attempt + 1);
                    // currentPrior stays the same -- self-retry re-runs the same phase
                }

                case PhaseReviewDecision.RetryPredecessor rp -> {
                    Phase predPhase = findDirectPredecessorByName(rp.phaseName(), phase);
                    if (predPhase == null) {
                        log.warn(
                                "Phase '{}' review requested retry of '{}' which is not a recognized "
                                        + "direct predecessor; treating as Approve.",
                                phase.getName(),
                                rp.phaseName());
                        return output;
                    }

                    int predRetries = predecessorRetryCounts.getOrDefault(predPhase, 0);
                    if (predRetries >= maxPredRetries) {
                        log.warn(
                                "Phase '{}' predecessor '{}' reached max retries ({}), " + "accepting current outputs",
                                phase.getName(),
                                rp.phaseName(),
                                maxPredRetries);
                        return output;
                    }
                    predecessorRetryCounts.put(predPhase, predRetries + 1);
                    log.info(
                            "Phase '{}' requesting predecessor '{}' retry {} with feedback: [{}]",
                            phase.getName(),
                            rp.phaseName(),
                            predRetries + 1,
                            truncate(rp.feedback(), 200));

                    // Locate the predecessor's last committed output.
                    EnsembleOutput predOldOutput = phaseOutputMap.get(predPhase);
                    if (predOldOutput == null) {
                        log.warn(
                                "Phase '{}' could not find committed output for predecessor '{}'; "
                                        + "treating as Approve.",
                                phase.getName(),
                                rp.phaseName());
                        return output;
                    }

                    // Remove the predecessor's stale outputs from global shared state.
                    removePhaseOutputsFromGlobal(predPhase, predOldOutput, allTaskOutputs, globalTaskOutputs);

                    // Rebuild predecessor with feedback and re-execute.
                    Phase rebuiltPred = rebuildPhaseWithFeedback(
                            predPhase, predPhase, rp.feedback(), predOldOutput, predRetries + 1);
                    log.info("Re-executing predecessor phase '{}' (retry {})", predPhase.getName(), predRetries + 1);
                    EnsembleOutput newPredOutput = phaseRunner.apply(rebuiltPred, initialPriorOutputs);
                    log.info(
                            "Predecessor phase '{}' retry {} completed | Tasks: {}",
                            predPhase.getName(),
                            predRetries + 1,
                            newPredOutput.getTaskOutputs().size());

                    // Commit new predecessor outputs to global shared state.
                    phaseOutputMap.put(predPhase, newPredOutput);
                    allTaskOutputs.addAll(newPredOutput.getTaskOutputs());
                    if (newPredOutput.getTaskOutputIndex() != null) {
                        globalTaskOutputs.putAll(newPredOutput.getTaskOutputIndex());
                    }

                    // Rebuild the prior snapshot to include updated predecessor outputs.
                    synchronized (globalTaskOutputs) {
                        currentPrior = new IdentityHashMap<>(globalTaskOutputs);
                    }

                    // Reset current phase to original tasks (fresh run with updated inputs).
                    currentPhase = phase;
                    // Set attempt to -1: the for-loop increment will advance it to 0.
                    attempt = -1;
                }

                case PhaseReviewDecision.Reject r -> {
                    throw new TaskExecutionException(
                            "Phase '" + phase.getName() + "' was rejected by review: " + r.reason(),
                            "phase-review",
                            phase.getName(),
                            List.copyOf(output.getTaskOutputs()));
                }
            }
        }
    }

    /**
     * Execute the review task and parse its output into a {@link PhaseReviewDecision}.
     *
     * <p>The review task is wrapped in a single-task synthetic phase and run through the
     * same {@code phaseRunner} used for normal phases. The prior-outputs map passed to the
     * runner combines the caller's current prior with both the original and current-attempt
     * task identities mapped to their outputs, so the review task's {@code context()}
     * declarations resolve correctly on every attempt (including retries where task objects
     * differ from the original due to feedback injection).
     *
     * @param review         the review configuration (supplies the review task)
     * @param originalPhase  the phase as originally defined (original task identity mapping)
     * @param currentPhase   the phase as it was executed in this attempt (may have rebuilt tasks)
     * @param phaseOutput    outputs from the current attempt
     * @param currentPrior   outputs from all prior phases (for the current attempt's context)
     * @param phaseRunner    the runner to use for executing the review task
     * @return the parsed decision; never null
     */
    private PhaseReviewDecision executeReviewTask(
            PhaseReview review,
            Phase originalPhase,
            Phase currentPhase,
            EnsembleOutput phaseOutput,
            Map<Task, TaskOutput> currentPrior,
            BiFunction<Phase, Map<Task, TaskOutput>, EnsembleOutput> phaseRunner) {

        // Build the review prior: current prior outputs + current-attempt phase outputs.
        // Map both original and current task identities to their outputs so the review
        // task's context() references resolve on all attempts.
        Map<Task, TaskOutput> reviewPrior = new IdentityHashMap<>(currentPrior);
        if (phaseOutput.getTaskOutputIndex() != null) {
            // Map by current-attempt task identity.
            reviewPrior.putAll(phaseOutput.getTaskOutputIndex());

            // Map by original task identity as well (handles retries where task objects differ).
            List<Task> originalTasks = originalPhase.getTasks();
            List<Task> currentTasks = currentPhase.getTasks();
            Map<Task, TaskOutput> currentIndex = phaseOutput.getTaskOutputIndex();
            for (int i = 0; i < originalTasks.size() && i < currentTasks.size(); i++) {
                TaskOutput out = currentIndex.get(currentTasks.get(i));
                if (out != null) {
                    reviewPrior.put(originalTasks.get(i), out);
                }
            }
        }

        // Wrap the review task in a single-task synthetic phase and run it.
        Phase reviewPhase = Phase.builder()
                .name("__review__" + originalPhase.getName())
                .task(review.getTask())
                .build();

        EnsembleOutput reviewOutput = phaseRunner.apply(reviewPhase, reviewPrior);

        String raw = reviewOutput.getTaskOutputs().isEmpty()
                ? ""
                : reviewOutput.getTaskOutputs().get(0).getRaw();

        log.debug("Phase '{}' review task raw output: [{}]", originalPhase.getName(), truncate(raw, 200));

        return PhaseReviewDecision.parse(raw);
    }

    /**
     * Create a copy of {@code currentPhase} with reviewer feedback injected into each task.
     *
     * <p>Each task in the returned phase has the same configuration as in {@code currentPhase}
     * except that {@link Task#withRevisionFeedback(String, String, int)} has been applied,
     * injecting the feedback text and the task's prior-attempt raw output.
     *
     * <p>The returned phase has the same name, workflow, and {@code after()} dependencies as
     * {@code originalPhase} but carries no {@link PhaseReview} (the retry loop manages reviews
     * externally to prevent double-review of rebuilt phases).
     *
     * @param originalPhase the phase as originally defined (used for name and dependencies)
     * @param currentPhase  the phase as executed in the current attempt (source of tasks)
     * @param feedback      reviewer feedback to inject into each task prompt
     * @param priorOutput   outputs from the current attempt (per-task prior output text)
     * @param attempt       retry attempt number (1 = first retry, 2 = second, ...)
     * @return a new phase with feedback-enhanced tasks
     */
    private Phase rebuildPhaseWithFeedback(
            Phase originalPhase, Phase currentPhase, String feedback, EnsembleOutput priorOutput, int attempt) {

        Phase.PhaseBuilder builder =
                Phase.builder().name(originalPhase.getName()).workflow(originalPhase.getWorkflow());
        for (Phase pred : originalPhase.getAfter()) {
            builder.after(pred);
        }
        // No review on the rebuilt phase -- the retry loop manages reviews externally.

        List<Task> currentTasks = currentPhase.getTasks();
        Map<Task, TaskOutput> taskIndex = priorOutput.getTaskOutputIndex();
        for (Task task : currentTasks) {
            String priorTaskOutput = null;
            if (taskIndex != null) {
                TaskOutput to = taskIndex.get(task);
                if (to != null) {
                    priorTaskOutput = to.getRaw();
                }
            }
            builder.task(task.withRevisionFeedback(feedback, priorTaskOutput, attempt));
        }

        return builder.build();
    }

    /**
     * Remove the outputs produced by {@code phase} from the global shared collections.
     *
     * <p>Used during predecessor retry to clear stale outputs before re-running the
     * predecessor. Removes all task outputs from both {@code allTaskOutputs} (by reference
     * equality) and {@code globalTaskOutputs} (by task identity for both the phase's declared
     * tasks and the output index keys).
     */
    private void removePhaseOutputsFromGlobal(
            Phase phase,
            EnsembleOutput output,
            List<TaskOutput> allTaskOutputs,
            Map<Task, TaskOutput> globalTaskOutputs) {

        // Remove from the ordered list by reference equality.
        allTaskOutputs.removeAll(output.getTaskOutputs());

        // Remove from the identity map using the phase's declared task objects as keys.
        for (Task task : phase.getTasks()) {
            globalTaskOutputs.remove(task);
        }
        // Also remove any rebuilt-task keys in the output index (covers retry attempts
        // where the phase tasks were rebuilt with feedback).
        if (output.getTaskOutputIndex() != null) {
            for (Task task : output.getTaskOutputIndex().keySet()) {
                globalTaskOutputs.remove(task);
            }
        }
    }

    /**
     * Find a direct predecessor of {@code phase} by name.
     *
     * @param phaseName       the name to look for
     * @param requestingPhase the phase whose direct predecessors are searched
     * @return the matching predecessor phase, or {@code null} if not found
     */
    private Phase findDirectPredecessorByName(String phaseName, Phase requestingPhase) {
        for (Phase pred : requestingPhase.getAfter()) {
            if (pred.getName().equals(phaseName)) {
                return pred;
            }
        }
        return null;
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
            Map<Phase, String> settledPhases,
            CountDownLatch latch) {

        List<Phase> successors = successorMap.getOrDefault(completedPhase, List.of());
        for (Phase successor : successors) {
            int remaining = remainingPredecessors.get(successor).decrementAndGet();
            if (remaining == 0 && settledPhases.putIfAbsent(successor, "submitted") == null) {
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
                        settledPhases,
                        latch);
            }
        }
    }

    /**
     * Recursively mark all transitive dependents of a failed (or skipped) phase as
     * SKIPPED and decrement the latch for each.
     */
    private void skipTransitiveDependents(
            Phase failedPhase,
            Map<Phase, List<Phase>> successorMap,
            Map<Phase, String> settledPhases,
            CountDownLatch latch) {

        List<Phase> successors = successorMap.getOrDefault(failedPhase, List.of());
        for (Phase successor : successors) {
            if (settledPhases.putIfAbsent(successor, "skipped") == null) {
                log.warn(
                        "Phase '{}' skipped (predecessor '{}' failed or was skipped)",
                        successor.getName(),
                        failedPhase.getName());
                latch.countDown();
                skipTransitiveDependents(successor, successorMap, settledPhases, latch);
            }
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "(null)";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
