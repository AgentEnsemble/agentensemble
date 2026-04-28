package net.agentensemble.workflow.loop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.MaxLoopIterationsExceededException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryScope;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.SequentialWorkflowExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a {@link Loop} by repeating its body until the predicate fires or the
 * {@code maxIterations} cap is hit.
 *
 * <p>Delegates body execution to a {@link SequentialWorkflowExecutor} -- one
 * {@code executeSeeded(...)} call per iteration. Between iterations, the executor:
 * <ol>
 *   <li>Optionally injects revision feedback into the body's first task via
 *       {@link Task#withRevisionFeedback(String, String, int)} so the LLM is told the
 *       iteration number and shown the prior iteration's final body output.</li>
 *   <li>Remaps any subsequent body task's {@code context()} list so references to
 *       rebuilt body tasks point at the new instances (otherwise the sequential
 *       executor's "context task not yet completed" check would fire).</li>
 *   <li>Builds a {@link LoopIterationContext} from the iteration's outputs and
 *       evaluates the loop's predicate.</li>
 * </ol>
 *
 * <p>{@link LoopMemoryMode#FRESH_PER_ITERATION} is reserved in the API but not yet
 * wired up -- selecting it throws {@link UnsupportedOperationException} at execute
 * time. Default {@link LoopMemoryMode#ACCUMULATE} requires no special handling: the
 * declared {@link net.agentensemble.memory.MemoryScope} on body tasks naturally
 * carries prior outputs into the next iteration's prompt.
 *
 * <p>This executor expects body tasks to be fully resolved (template variables
 * substituted, agents synthesized or explicit, or a deterministic {@code handler}
 * configured). Resolution is the caller's responsibility -- typically
 * {@code Ensemble.runTasks} pre-resolves the loop body alongside regular tasks.
 *
 * <p>Stateless -- a single {@code LoopExecutor} instance can be reused across runs
 * and across loops.
 */
public class LoopExecutor {

    private static final Logger log = LoggerFactory.getLogger(LoopExecutor.class);

    private static final String TERMINATION_PREDICATE = "predicate";
    private static final String TERMINATION_MAX_ITERATIONS = "maxIterations";

    private final SequentialWorkflowExecutor sequentialExecutor;

    public LoopExecutor(SequentialWorkflowExecutor sequentialExecutor) {
        this.sequentialExecutor = sequentialExecutor;
    }

    /**
     * Execute the given {@link Loop} against the supplied {@link ExecutionContext}.
     *
     * @param loop    the loop to run; must have body tasks already resolved (agent or handler
     *                set; template variables substituted)
     * @param context the execution context shared with the outer ensemble run
     * @return the loop's execution result, including per-iteration history and projected
     *         outputs visible to the outer ensemble
     * @throws MaxLoopIterationsExceededException if the loop hits {@code maxIterations}
     *         without the predicate firing AND {@link MaxIterationsAction#THROW} is configured
     */
    public LoopExecutionResult execute(Loop loop, ExecutionContext context) {
        Instant loopStart = Instant.now();
        List<Task> originalBody = loop.getBody();
        Set<String> bodyScopeNames = collectBodyScopeNames(originalBody);
        List<Map<String, TaskOutput>> history = new ArrayList<>(loop.getMaxIterations());
        TaskOutput priorIterationFinalOutput = null;
        String terminationReason = null;
        int iterationsRun = 0;

        for (int iteration = 1; iteration <= loop.getMaxIterations(); iteration++) {
            Instant iterationStart = Instant.now();
            log.debug("Loop '{}' starting iteration {}/{}", loop.getName(), iteration, loop.getMaxIterations());

            // Memory mode handling between iterations (skipped on iteration 1 -- the first
            // iteration always sees scopes as configured by the outer ensemble).
            if (iteration > 1) {
                switch (loop.getMemoryMode()) {
                    case ACCUMULATE -> {
                        // No-op: prior outputs naturally remain in the scope.
                    }
                    case FRESH_PER_ITERATION -> clearBodyMemoryScopes(loop, context, bodyScopeNames);
                    case WINDOW -> evictBodyMemoryScopesToWindow(
                            loop, context, bodyScopeNames, loop.getMemoryWindowSize());
                }
            }

            List<Task> iterationBody = buildIterationBody(loop, iteration, priorIterationFinalOutput);

            // Run body once via SequentialWorkflowExecutor.
            // Loops are sealed: body tasks may not reference outer-DAG tasks (validated at
            // build time), so an empty seed map suffices.
            EnsembleOutput iterationOutput =
                    sequentialExecutor.executeSeeded(iterationBody, context, new IdentityHashMap<>());

            Map<String, TaskOutput> iterationByName = indexByName(iterationBody, iterationOutput.getTaskOutputIndex());
            history.add(Collections.unmodifiableMap(iterationByName));
            iterationsRun = iteration;

            // Fire LoopIterationCompletedEvent so live dashboards / metrics listeners can
            // record per-iteration progress before the predicate is evaluated.
            context.fireLoopIterationCompleted(new net.agentensemble.callback.LoopIterationCompletedEvent(
                    loop.getName(),
                    iteration,
                    loop.getMaxIterations(),
                    iterationByName,
                    java.time.Duration.between(iterationStart, Instant.now())));

            // Update the running tail-of-body output for the next iteration's feedback injection.
            priorIterationFinalOutput = lastByInsertion(iterationByName);

            // Evaluate predicate.
            if (loop.getUntil() != null) {
                LoopIterationContext predicateCtx =
                        new SimpleLoopIterationContext(iteration, iterationByName, history, priorIterationFinalOutput);
                boolean shouldStop;
                try {
                    shouldStop = loop.getUntil().shouldStop(predicateCtx);
                } catch (RuntimeException e) {
                    log.error("Loop '{}' predicate threw on iteration {}: {}", loop.getName(), iteration, e.toString());
                    throw e;
                }
                if (shouldStop) {
                    terminationReason = TERMINATION_PREDICATE;
                    log.info(
                            "Loop '{}' stopped by predicate after iteration {}/{}",
                            loop.getName(),
                            iteration,
                            loop.getMaxIterations());
                    break;
                }
            }
        }

        // Loop body completed all iterations without the predicate firing.
        if (terminationReason == null) {
            terminationReason = TERMINATION_MAX_ITERATIONS;
            log.info(
                    "Loop '{}' hit max-iterations cap ({}); terminationAction={}",
                    loop.getName(),
                    loop.getMaxIterations(),
                    loop.getOnMaxIterations());
            if (loop.getOnMaxIterations() == MaxIterationsAction.THROW) {
                throw new MaxLoopIterationsExceededException(loop.getName(), loop.getMaxIterations());
            }
        }

        IdentityHashMap<Task, TaskOutput> projected = projectOutputs(loop, originalBody, history);

        log.debug(
                "Loop '{}' finished: iterationsRun={}, reason={}, projectedKeys={}, totalDurationMs={}",
                loop.getName(),
                iterationsRun,
                terminationReason,
                projected.size(),
                java.time.Duration.between(loopStart, Instant.now()).toMillis());

        return new LoopExecutionResult(loop, iterationsRun, terminationReason, List.copyOf(history), projected);
    }

    // ========================
    // FRESH_PER_ITERATION memory clearing
    // ========================

    /**
     * Collect the union of memory-scope names declared by the loop body's tasks.
     * Used by {@link LoopMemoryMode#FRESH_PER_ITERATION} to wipe those scopes
     * between iterations.
     */
    private static Set<String> collectBodyScopeNames(List<Task> body) {
        Set<String> names = new LinkedHashSet<>();
        for (Task t : body) {
            List<MemoryScope> scopes = t.getMemoryScopes();
            if (scopes == null) continue;
            for (MemoryScope s : scopes) {
                if (s != null && s.getName() != null) {
                    names.add(s.getName());
                }
            }
        }
        return names;
    }

    /**
     * Clear all memory scopes declared by the loop body tasks. No-op if the
     * {@link ExecutionContext} has no {@code MemoryStore} configured (consistent with
     * the rest of the framework, which silently no-ops scope writes when no store is
     * present).
     *
     * <p>Wraps a single underlying-store {@link UnsupportedOperationException} into a more
     * actionable runtime exception explaining how to resolve it (e.g. switching to
     * {@code MemoryStore.inMemory()} for the affected scopes when using a vector backend).
     */
    private static void clearBodyMemoryScopes(Loop loop, ExecutionContext context, Set<String> scopeNames) {
        if (scopeNames.isEmpty()) {
            return;
        }
        MemoryStore store = context.memoryStore();
        if (store == null) {
            log.debug(
                    "Loop '{}' FRESH_PER_ITERATION requested but no MemoryStore configured; nothing to clear",
                    loop.getName());
            return;
        }
        for (String scope : scopeNames) {
            try {
                store.clear(scope);
            } catch (UnsupportedOperationException e) {
                throw new UnsupportedOperationException(
                        "Loop '" + loop.getName() + "' has LoopMemoryMode.FRESH_PER_ITERATION but the "
                                + "configured MemoryStore (" + store.getClass().getSimpleName()
                                + ") does not support per-scope clear for scope '" + scope + "'. "
                                + e.getMessage(),
                        e);
            }
        }
        log.debug("Loop '{}' cleared {} memory scope(s) for FRESH_PER_ITERATION", loop.getName(), scopeNames.size());
    }

    /**
     * Evict body memory scopes to retain only the {@code windowSize} most-recent entries.
     * Used by {@link LoopMemoryMode#WINDOW} to bound prompt growth across iterations.
     *
     * <p>Uses {@link net.agentensemble.memory.EvictionPolicy#keepLastEntries(int)} which
     * is supported by {@link net.agentensemble.memory.MemoryStore#inMemory()} and is a
     * no-op on the embedding-backed store. No exception is thrown when the store does
     * not support eviction; the window is effectively unbounded in that case.
     */
    private static void evictBodyMemoryScopesToWindow(
            Loop loop, ExecutionContext context, Set<String> scopeNames, int windowSize) {
        if (scopeNames.isEmpty()) {
            return;
        }
        MemoryStore store = context.memoryStore();
        if (store == null) {
            log.debug("Loop '{}' WINDOW({}) requested but no MemoryStore configured", loop.getName(), windowSize);
            return;
        }
        net.agentensemble.memory.EvictionPolicy policy =
                net.agentensemble.memory.EvictionPolicy.keepLastEntries(windowSize);
        for (String scope : scopeNames) {
            store.evict(scope, policy);
        }
        log.debug(
                "Loop '{}' evicted {} memory scope(s) to window={} for WINDOW mode",
                loop.getName(),
                scopeNames.size(),
                windowSize);
    }

    // ========================
    // Iteration body construction
    // ========================

    /**
     * For iteration 1 (or when feedback injection is disabled) returns the body unchanged.
     * For iteration N>1 with feedback enabled, rebuilds the body so the first task carries
     * revision feedback and subsequent tasks' {@code context()} references point at the
     * rebuilt instances.
     *
     * <p>Package-private to allow direct unit testing -- the handler-task path used by
     * tests cannot observe revision fields from inside a {@code TaskHandler}, so the
     * rebuild is verified via this method directly.
     */
    static List<Task> buildIterationBody(Loop loop, int iteration, TaskOutput priorIterationFinalOutput) {
        List<Task> originalBody = loop.getBody();
        if (iteration == 1 || !loop.isInjectFeedback()) {
            return originalBody;
        }

        IdentityHashMap<Task, Task> originalToResolved = new IdentityHashMap<>();

        Task originalFirst = originalBody.get(0);
        String autoFeedback = "Loop iteration " + iteration + " of " + loop.getMaxIterations()
                + ". Prior iteration's final body output is shown above; revise based on it.";
        String priorRaw = priorIterationFinalOutput != null ? priorIterationFinalOutput.getRaw() : null;
        // attemptNumber: 0 = first attempt, 1 = first retry, ... so iteration N becomes attempt N-1.
        Task resolvedFirst = originalFirst.withRevisionFeedback(autoFeedback, priorRaw, iteration - 1);
        originalToResolved.put(originalFirst, resolvedFirst);

        List<Task> result = new ArrayList<>(originalBody.size());
        result.add(resolvedFirst);
        for (int i = 1; i < originalBody.size(); i++) {
            Task original = originalBody.get(i);
            List<Task> remappedContext;
            List<Task> originalContext = original.getContext();
            if (originalContext == null || originalContext.isEmpty()) {
                remappedContext = originalContext;
            } else {
                remappedContext = new ArrayList<>(originalContext.size());
                for (Task ctx : originalContext) {
                    remappedContext.add(originalToResolved.getOrDefault(ctx, ctx));
                }
            }
            Task resolved = original.toBuilder().context(remappedContext).build();
            originalToResolved.put(original, resolved);
            result.add(resolved);
        }
        return result;
    }

    // ========================
    // Per-iteration output indexing
    // ========================

    /**
     * Index the body's outputs by task name (or description fallback when name is null).
     * Insertion order is preserved (LinkedHashMap), matching body declaration order.
     */
    private static Map<String, TaskOutput> indexByName(List<Task> iterationBody, Map<Task, TaskOutput> outputsByTask) {
        Map<String, TaskOutput> byName = new LinkedHashMap<>();
        for (Task t : iterationBody) {
            TaskOutput out = outputsByTask.get(t);
            if (out == null) continue;
            String key = nameKey(t);
            byName.put(key, out);
        }
        return byName;
    }

    private static String nameKey(Task t) {
        return t.getName() != null ? t.getName() : t.getDescription();
    }

    private static TaskOutput lastByInsertion(Map<String, TaskOutput> byName) {
        TaskOutput last = null;
        for (TaskOutput o : byName.values()) {
            last = o;
        }
        return last;
    }

    // ========================
    // Output projection
    // ========================

    /**
     * Project per-iteration history into outputs visible to the outer ensemble.
     * Keyed by the ORIGINAL body-task instances from {@code loop.getBody()} (not the
     * per-iteration rebuilt instances), so the outer scheduler can resolve them via
     * the same {@code Map<Task, TaskOutput>} machinery used for regular tasks.
     */
    private static IdentityHashMap<Task, TaskOutput> projectOutputs(
            Loop loop, List<Task> originalBody, List<Map<String, TaskOutput>> history) {

        IdentityHashMap<Task, TaskOutput> projected = new IdentityHashMap<>();
        if (history.isEmpty()) {
            return projected;
        }

        switch (loop.getOutputMode()) {
            case LAST_ITERATION -> {
                Map<String, TaskOutput> last = history.get(history.size() - 1);
                for (Task original : originalBody) {
                    TaskOutput out = last.get(nameKey(original));
                    if (out != null) {
                        projected.put(original, out);
                    }
                }
            }
            case FINAL_TASK_ONLY -> {
                Map<String, TaskOutput> last = history.get(history.size() - 1);
                Task lastBody = originalBody.get(originalBody.size() - 1);
                TaskOutput out = last.get(nameKey(lastBody));
                if (out != null) {
                    projected.put(lastBody, out);
                }
            }
            case ALL_ITERATIONS -> {
                // Concatenate raw text across iterations for each body task.
                for (Task original : originalBody) {
                    String key = nameKey(original);
                    StringBuilder concat = new StringBuilder();
                    TaskOutput template = null;
                    for (int i = 0; i < history.size(); i++) {
                        TaskOutput out = history.get(i).get(key);
                        if (out == null) continue;
                        if (template == null) template = out;
                        if (concat.length() > 0)
                            concat.append("\n\n--- iteration ").append(i + 1).append(" ---\n\n");
                        concat.append(out.getRaw());
                    }
                    if (template != null) {
                        projected.put(
                                original,
                                template.toBuilder().raw(concat.toString()).build());
                    }
                }
            }
        }
        return projected;
    }

    // ========================
    // LoopIterationContext implementation
    // ========================

    /** Trivial value-style implementation passed to the predicate. */
    private record SimpleLoopIterationContext(
            int iterationNumber,
            Map<String, TaskOutput> lastIterationOutputs,
            List<Map<String, TaskOutput>> history,
            TaskOutput lastBodyOutput)
            implements LoopIterationContext {

        @Override
        public Map<String, TaskOutput> lastIterationOutputs() {
            return Collections.unmodifiableMap(lastIterationOutputs);
        }

        @Override
        public List<Map<String, TaskOutput>> history() {
            return Collections.unmodifiableList(history);
        }
    }
}
