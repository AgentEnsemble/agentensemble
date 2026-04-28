package net.agentensemble.workflow.graph;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.GraphNoEdgeMatchedException;
import net.agentensemble.exception.MaxGraphStepsExceededException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.SequentialWorkflowExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks a {@link Graph} state machine one state at a time, evaluating outgoing edges in
 * declaration order to decide the next state. Reuses {@link SequentialWorkflowExecutor}
 * to run each visited state's Task so state Tasks go through the full ensemble pipeline
 * (memory scopes, review gates, deterministic handlers, AgentExecutor for LLM tasks).
 *
 * <p>Termination:
 * <ul>
 *   <li>An edge routes to {@link Graph#END}: graph terminates normally;
 *       {@code terminationReason = "terminal"}.</li>
 *   <li>{@code maxSteps} reached without terminal: applies the configured
 *       {@link MaxStepsAction} ({@code RETURN_LAST}, {@code THROW}, or
 *       {@code RETURN_WITH_FLAG}).</li>
 *   <li>No outgoing edge matches: throws
 *       {@link GraphNoEdgeMatchedException}. Validation guarantees every non-END state has
 *       at least one outgoing edge, so this only fires when all edges are conditional and
 *       none match -- the user forgot a fallback.</li>
 * </ul>
 *
 * <p>State revisits: when {@code injectFeedbackOnRevisit} is true (default) and a state is
 * visited more than once, the state Task is rebuilt via
 * {@link Task#withRevisionFeedback(String, String, int)} on every visit after the first
 * with an auto-feedback message. States listed in {@link Graph#getNoFeedbackStates()}
 * always see the unmodified Task.
 *
 * <p>Stateless -- a single {@code GraphExecutor} instance can be reused across runs and
 * across graphs.
 */
public class GraphExecutor {

    private static final Logger log = LoggerFactory.getLogger(GraphExecutor.class);

    private static final String TERMINATION_TERMINAL = "terminal";
    private static final String TERMINATION_MAX_STEPS = "maxSteps";

    private final SequentialWorkflowExecutor sequentialExecutor;

    public GraphExecutor(SequentialWorkflowExecutor sequentialExecutor) {
        this.sequentialExecutor = sequentialExecutor;
    }

    /**
     * Execute the graph against the given execution context.
     *
     * @param graph   the graph to run; must have state Tasks already resolved (template
     *                vars substituted, agents synthesized, or deterministic handlers set)
     * @param context the ensemble execution context shared with the outer run
     * @return the graph's execution result
     * @throws MaxGraphStepsExceededException if max steps hit AND
     *         {@link MaxStepsAction#THROW} configured
     * @throws GraphNoEdgeMatchedException if a state has no matching outgoing edge for its output
     */
    public GraphExecutionResult execute(Graph graph, ExecutionContext context) {
        Instant graphStart = Instant.now();
        List<GraphStep> history = new ArrayList<>();
        Map<String, List<TaskOutput>> stateOutputsByName = new LinkedHashMap<>();
        // visit count per state name (1-based on first visit) -- drives feedback injection
        Map<String, Integer> visitCounts = new LinkedHashMap<>();

        String currentState = graph.getStartState();
        int step = 0;
        TaskOutput priorOutput = null;
        String terminationReason = null;

        while (!Graph.END.equals(currentState) && step < graph.getMaxSteps()) {
            step++;
            int visitNumber = visitCounts.merge(currentState, 1, Integer::sum);

            Instant stepStart = Instant.now();
            log.debug(
                    "Graph '{}' step {}/{} visiting state '{}' (visit #{})",
                    graph.getName(),
                    step,
                    graph.getMaxSteps(),
                    currentState,
                    visitNumber);

            Task originalTask = graph.getStates().get(currentState);
            Task taskToRun = maybeInjectFeedback(graph, currentState, originalTask, visitNumber, priorOutput);

            // Run the state Task via SequentialWorkflowExecutor for full pipeline support.
            EnsembleOutput stepOutput =
                    sequentialExecutor.executeSeeded(List.of(taskToRun), context, new IdentityHashMap<>());
            if (stepOutput.getTaskOutputs().isEmpty()) {
                throw new IllegalStateException("Graph '" + graph.getName() + "' state '" + currentState
                        + "' produced no TaskOutput on visit " + visitNumber);
            }
            TaskOutput out = stepOutput.getTaskOutputs().get(0);
            stateOutputsByName
                    .computeIfAbsent(currentState, k -> new ArrayList<>())
                    .add(out);

            // Decide next state via edge routing. Determined before fire so the event
            // payload can include the routed-to state for live dashboards.
            String nextState = routeNext(graph, currentState, out, step, stateOutputsByName);

            history.add(new GraphStep(currentState, step, out, nextState));
            priorOutput = out;

            // Fire GraphStateCompletedEvent so listeners (live dashboard, metrics) can react
            // before the next step begins.
            context.fireGraphStateCompleted(new net.agentensemble.callback.GraphStateCompletedEvent(
                    graph.getName(),
                    currentState,
                    step,
                    graph.getMaxSteps(),
                    out,
                    nextState,
                    Duration.between(stepStart, Instant.now())));

            currentState = nextState;
        }

        // Termination handling
        if (Graph.END.equals(currentState)) {
            terminationReason = TERMINATION_TERMINAL;
            log.info("Graph '{}' reached END after {} step(s)", graph.getName(), step);
        } else {
            // Hit maxSteps without terminating
            terminationReason = TERMINATION_MAX_STEPS;
            log.info(
                    "Graph '{}' hit maxSteps cap ({}); onMaxSteps={}",
                    graph.getName(),
                    graph.getMaxSteps(),
                    graph.getOnMaxSteps());
            if (graph.getOnMaxSteps() == MaxStepsAction.THROW) {
                throw new MaxGraphStepsExceededException(
                        graph.getName(),
                        graph.getMaxSteps(),
                        history.isEmpty()
                                ? graph.getStartState()
                                : history.get(history.size() - 1).getStateName());
            }
        }

        IdentityHashMap<Task, TaskOutput> projected = projectOutputs(graph, stateOutputsByName);

        log.debug(
                "Graph '{}' finished: stepsRun={}, reason={}, projectedKeys={}, totalDurationMs={}",
                graph.getName(),
                step,
                terminationReason,
                projected.size(),
                Duration.between(graphStart, Instant.now()).toMillis());

        // Make state outputs map immutable for callers
        Map<String, List<TaskOutput>> immutableStateOutputs = new LinkedHashMap<>();
        for (Map.Entry<String, List<TaskOutput>> e : stateOutputsByName.entrySet()) {
            immutableStateOutputs.put(e.getKey(), List.copyOf(e.getValue()));
        }

        return new GraphExecutionResult(
                graph,
                step,
                terminationReason,
                List.copyOf(history),
                Collections.unmodifiableMap(immutableStateOutputs),
                projected);
    }

    // ========================
    // Feedback injection on revisit
    // ========================

    private static Task maybeInjectFeedback(
            Graph graph, String stateName, Task original, int visitNumber, TaskOutput priorOutput) {
        if (visitNumber == 1) {
            // First visit -- no feedback to inject
            return original;
        }
        if (!graph.isInjectFeedbackOnRevisit()) {
            return original;
        }
        if (graph.getNoFeedbackStates() != null && graph.getNoFeedbackStates().contains(stateName)) {
            return original;
        }
        String autoFeedback = "Graph state '" + stateName + "' visit #" + visitNumber
                + ". Prior visit's output is provided above; refine your response based on it.";
        String priorRaw = priorOutput != null ? priorOutput.getRaw() : null;
        // attemptNumber: 0 = first visit, 1 = first revision, ... so visitNumber - 1 matches.
        return original.withRevisionFeedback(autoFeedback, priorRaw, visitNumber - 1);
    }

    // ========================
    // Edge routing
    // ========================

    private static String routeNext(
            Graph graph,
            String currentState,
            TaskOutput justCompletedOutput,
            int stepNumber,
            Map<String, List<TaskOutput>> stateOutputsByName) {

        GraphRoutingContext routingCtx =
                new SimpleGraphRoutingContext(currentState, justCompletedOutput, stepNumber, stateOutputsByName);

        StringBuilder candidatesDescription = new StringBuilder();
        int candidateCount = 0;
        for (GraphEdge edge : graph.getEdges()) {
            if (!edge.getFrom().equals(currentState)) continue;
            candidateCount++;
            if (candidatesDescription.length() > 0) candidatesDescription.append("; ");
            candidatesDescription.append("→ ").append(edge.getTo());
            if (edge.getCondition() == null) {
                candidatesDescription.append(" (unconditional)");
            } else if (edge.getConditionDescription() != null) {
                candidatesDescription
                        .append(" (")
                        .append(edge.getConditionDescription())
                        .append(")");
            } else {
                candidatesDescription.append(" (predicate)");
            }
            if (edge.getCondition() == null) {
                return edge.getTo();
            }
            try {
                if (edge.getCondition().matches(routingCtx)) {
                    return edge.getTo();
                }
            } catch (RuntimeException e) {
                log.error(
                        "Graph '{}' edge predicate from '{}' to '{}' threw on step {}: {}",
                        graph.getName(),
                        currentState,
                        edge.getTo(),
                        stepNumber,
                        e.toString());
                throw e;
            }
        }
        // No edge matched. Validation guarantees there IS at least one outgoing edge, so
        // this means all are conditional and none matched -- caller forgot a fallback.
        String details = candidateCount == 0
                ? "(no outgoing edges declared -- unreachable; validation should have caught this)"
                : "Candidate edges: " + candidatesDescription;
        throw new GraphNoEdgeMatchedException(graph.getName(), currentState, justCompletedOutput.getRaw(), details);
    }

    // ========================
    // Output projection
    // ========================

    /**
     * Build the identity-keyed projection of state-Task → last-visited output. Keyed by
     * the <strong>original</strong> Task instance from {@code graph.getStates()} so the
     * outer scheduler can resolve via the same machinery used for regular tasks.
     */
    private static IdentityHashMap<Task, TaskOutput> projectOutputs(
            Graph graph, Map<String, List<TaskOutput>> stateOutputsByName) {
        IdentityHashMap<Task, TaskOutput> projected = new IdentityHashMap<>();
        for (Map.Entry<String, Task> entry : graph.getStates().entrySet()) {
            List<TaskOutput> visits = stateOutputsByName.get(entry.getKey());
            if (visits != null && !visits.isEmpty()) {
                projected.put(entry.getValue(), visits.get(visits.size() - 1));
            }
        }
        return projected;
    }

    // ========================
    // GraphRoutingContext implementation
    // ========================

    private record SimpleGraphRoutingContext(
            String currentState, TaskOutput lastOutput, int stepNumber, Map<String, List<TaskOutput>> stateHistory)
            implements GraphRoutingContext {

        @Override
        public Map<String, List<TaskOutput>> stateHistory() {
            return Collections.unmodifiableMap(stateHistory);
        }
    }
}
