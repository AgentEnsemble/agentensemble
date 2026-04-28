package net.agentensemble.workflow.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Value;
import net.agentensemble.Task;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.workflow.WorkflowNode;

/**
 * State-machine workflow construct: named states (Tasks) connected by directed edges with
 * optional conditional predicates. Unlike {@link net.agentensemble.workflow.loop.Loop}
 * which iterates a fixed body, a Graph chooses the next state per step from the just-
 * completed state's output.
 *
 * <p>Routes the LangGraph-style state-machine patterns AE couldn't express before:
 * <ul>
 *   <li><strong>Tool router</strong> — an {@code analyze} state inspects input and routes
 *       to one of several tool states, each of which loops back to {@code analyze}.</li>
 *   <li><strong>Selective feedback</strong> — a {@code critique} state can either continue
 *       to {@code publish} or feed back to a specific upstream state, without re-running
 *       all upstream work.</li>
 *   <li><strong>Multi-turn negotiation</strong> — two agents take turns until they agree.</li>
 * </ul>
 *
 * <p>Graphs are exclusive at the ensemble level: combining a graph with {@code task},
 * {@code loop}, or {@code phase} on the same {@code Ensemble} is rejected at validation.
 *
 * <h2>Routing semantics</h2>
 *
 * <p>After a state's Task runs, the executor walks that state's outgoing edges in
 * declaration order. The first edge whose {@link GraphEdge#getCondition()} predicate
 * returns {@code true} is taken. An edge with a {@code null} predicate (added via
 * {@link GraphBuilder#edge(String, String)}) is unconditional and always matches.
 * If no edge matches, the executor throws
 * {@link net.agentensemble.exception.GraphNoEdgeMatchedException}.
 *
 * <p>Targeting {@link #END} on an edge terminates the graph normally.
 *
 * <h2>State revisits</h2>
 *
 * <p>The same state can be visited multiple times. On each visit after the first, the
 * state's Task is rebuilt via {@link Task#withRevisionFeedback(String, String, int)} so
 * the LLM sees an auto-generated revision-instructions section in its prompt with the
 * prior visit's output. Disable per-state via {@link GraphBuilder#stateNoFeedback(String, Task)}.
 *
 * <h2>Defaults</h2>
 *
 * <p>{@code maxSteps=50}, {@code onMaxSteps=RETURN_LAST}, {@code injectFeedbackOnRevisit=true}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * Graph router = Graph.builder()
 *     .name("agent")
 *     .state("analyze", analyzeTask)
 *     .state("toolA", toolATask)
 *     .state("toolB", toolBTask)
 *     .start("analyze")
 *     .edge("analyze", "toolA", ctx -> ctx.lastOutput().getRaw().contains("USE_A"))
 *     .edge("analyze", "toolB", ctx -> ctx.lastOutput().getRaw().contains("USE_B"))
 *     .edge("analyze", Graph.END)
 *     .edge("toolA",   "analyze")
 *     .edge("toolB",   "analyze")
 *     .maxSteps(20)
 *     .build();
 *
 * Ensemble.builder().graph(router).build().run();
 * }</pre>
 */
@Builder(toBuilder = true)
@Value
public class Graph implements WorkflowNode {

    /** Sentinel state name representing graph termination. Reserved; cannot be a state name. */
    public static final String END = "__END__";

    /** Default {@link #maxSteps} when not explicitly set. */
    public static final int DEFAULT_MAX_STEPS = 50;

    /**
     * Logical name for this graph. Used in trace, viz, error messages, event payloads, and
     * as the lookup key on {@link net.agentensemble.ensemble.EnsembleOutput} accessors.
     *
     * <p>Required and must be non-blank.
     */
    String name;

    /**
     * State name → Task mapping. Insertion order is preserved; visualisation tools render
     * states in declaration order. Required and must contain at least one state. State
     * names must be non-blank, distinct, and not equal to {@link #END}.
     *
     * <p>Stored as an immutable {@code LinkedHashMap}.
     */
    Map<String, Task> states;

    /**
     * State names where feedback injection should be SUPPRESSED on revisits. Tasks for
     * these states are not rebuilt with {@code withRevisionFeedback} on revisit; they see
     * the same prompt every time. Useful for stateless router states whose decision should
     * not be biased by prior visits.
     */
    Set<String> noFeedbackStates;

    /**
     * Outgoing edges in declaration order. The executor walks this list when routing out of
     * a state, scanning entries with matching {@code from}; the first matching edge wins.
     *
     * <p>Stored as an immutable {@code List}.
     */
    List<GraphEdge> edges;

    /**
     * Name of the state where execution starts. Required and must reference a known state.
     */
    String startState;

    /** Hard cap on graph steps. Default {@value #DEFAULT_MAX_STEPS}. Must be {@code >= 1}. */
    int maxSteps;

    /** Behaviour when {@link #maxSteps} is hit without reaching {@link #END}. */
    MaxStepsAction onMaxSteps;

    /**
     * If {@code true} (default), the per-state Task is rebuilt via
     * {@link Task#withRevisionFeedback(String, String, int)} on every visit after the first
     * so the LLM is told the visit number and shown the prior visit's output. Per-state
     * suppression is available via {@link #getNoFeedbackStates()}.
     */
    boolean injectFeedbackOnRevisit;

    @Override
    public String toString() {
        return "Graph{name='" + name + "', states=" + states.size() + ", edges=" + edges.size() + ", start='"
                + startState + "', maxSteps=" + maxSteps + "}";
    }

    /**
     * Custom builder. {@code states}, {@code edges}, and {@code noFeedbackStates} are managed
     * as plain fields (not Lombok @Singular) so the convenience methods
     * {@link #state(String, Task)}, {@link #edge(String, String)}, and friends can coexist.
     */
    public static class GraphBuilder {

        private String name = null;
        // Manually-managed collections -- the Lombok-generated builder fields with these
        // names would conflict with our convenience methods below.
        private Map<String, Task> states = new LinkedHashMap<>();
        private Set<String> noFeedbackStates = new HashSet<>();
        private List<GraphEdge> edges = new ArrayList<>();
        private String startState = null;
        private int maxSteps = DEFAULT_MAX_STEPS;
        private MaxStepsAction onMaxSteps = MaxStepsAction.RETURN_LAST;
        private boolean injectFeedbackOnRevisit = true;

        // ========================
        // States
        // ========================

        /** Register a state's Task. State names must be unique and non-blank. */
        public GraphBuilder state(String stateName, Task task) {
            if (stateName == null || stateName.isBlank()) {
                throw new ValidationException("State name must be non-blank");
            }
            if (states.containsKey(stateName)) {
                throw new ValidationException("Duplicate state name '" + stateName + "'");
            }
            states.put(stateName, task);
            return this;
        }

        /**
         * Register a state and mark it as no-feedback-on-revisit. Equivalent to calling
         * {@code .state(name, task)} followed by adding {@code name} to the no-feedback set.
         */
        public GraphBuilder stateNoFeedback(String stateName, Task task) {
            state(stateName, task);
            noFeedbackStates.add(stateName);
            return this;
        }

        // ========================
        // Edges
        // ========================

        /** Unconditional edge: always matches. Place last among a state's outgoing edges to use as fallback. */
        public GraphBuilder edge(String from, String to) {
            edges.add(new GraphEdge(from, to, null, null));
            return this;
        }

        /** Conditional edge with no human-readable description. */
        public GraphBuilder edge(String from, String to, GraphPredicate condition) {
            edges.add(new GraphEdge(from, to, condition, null));
            return this;
        }

        /** Conditional edge with a human-readable label for visualisation. */
        public GraphBuilder edge(String from, String to, GraphPredicate condition, String label) {
            edges.add(new GraphEdge(from, to, condition, label));
            return this;
        }

        // ========================
        // Misc
        // ========================

        /** Convenience alias for {@link #startState(String)}. */
        public GraphBuilder start(String stateName) {
            this.startState = stateName;
            return this;
        }

        public GraphBuilder startState(String stateName) {
            this.startState = stateName;
            return this;
        }

        public GraphBuilder maxSteps(int n) {
            this.maxSteps = n;
            return this;
        }

        public GraphBuilder onMaxSteps(MaxStepsAction action) {
            this.onMaxSteps = action;
            return this;
        }

        public GraphBuilder injectFeedbackOnRevisit(boolean inject) {
            this.injectFeedbackOnRevisit = inject;
            return this;
        }

        public GraphBuilder name(String n) {
            this.name = n;
            return this;
        }

        // ========================
        // Build + validation
        // ========================

        public Graph build() {
            Map<String, Task> stateMap = new LinkedHashMap<>(states);
            List<GraphEdge> edgeList = List.copyOf(edges);
            Set<String> noFeedback = Set.copyOf(noFeedbackStates);

            validateName();
            validateStates(stateMap);
            validateMaxSteps();
            validateStart(stateMap);
            validateEdges(stateMap, edgeList);
            validateEveryStateHasOutgoingEdge(stateMap, edgeList);
            validateNoFeedbackStatesExist(stateMap, noFeedback);

            return new Graph(
                    name,
                    java.util.Collections.unmodifiableMap(stateMap),
                    noFeedback,
                    edgeList,
                    startState,
                    maxSteps,
                    onMaxSteps,
                    injectFeedbackOnRevisit);
        }

        private void validateName() {
            if (name == null || name.isBlank()) {
                throw new ValidationException("Graph name must be non-blank");
            }
        }

        private void validateStates(Map<String, Task> stateMap) {
            if (stateMap.isEmpty()) {
                throw new ValidationException("Graph '" + name + "' must declare at least one state");
            }
            for (Map.Entry<String, Task> e : stateMap.entrySet()) {
                String stateName = e.getKey();
                if (stateName == null || stateName.isBlank()) {
                    throw new ValidationException("Graph '" + name + "' has a blank state name");
                }
                if (END.equals(stateName)) {
                    throw new ValidationException("Graph '" + name + "' cannot use the reserved state name '" + END
                            + "'. END is implicit; declare an edge targeting Graph.END instead.");
                }
                if (e.getValue() == null) {
                    throw new ValidationException("Graph '" + name + "' state '" + stateName + "' has a null Task");
                }
            }
        }

        private void validateMaxSteps() {
            if (maxSteps < 1) {
                throw new ValidationException("Graph '" + name + "' maxSteps must be >= 1, got: " + maxSteps);
            }
        }

        private void validateStart(Map<String, Task> stateMap) {
            if (startState == null || startState.isBlank()) {
                throw new ValidationException("Graph '" + name + "' must declare a start state via .start(name)");
            }
            if (END.equals(startState)) {
                throw new ValidationException(
                        "Graph '" + name + "' start state cannot be Graph.END (the graph would never run)");
            }
            if (!stateMap.containsKey(startState)) {
                throw new ValidationException("Graph '" + name + "' start state '" + startState
                        + "' is not a declared state. Known states: " + stateMap.keySet());
            }
        }

        private void validateEdges(Map<String, Task> stateMap, List<GraphEdge> edgeList) {
            for (int i = 0; i < edgeList.size(); i++) {
                GraphEdge edge = edgeList.get(i);
                if (edge.getFrom() == null || edge.getFrom().isBlank()) {
                    throw new ValidationException("Graph '" + name + "' edge[" + i + "] has a blank 'from' state");
                }
                if (edge.getTo() == null || edge.getTo().isBlank()) {
                    throw new ValidationException("Graph '" + name + "' edge[" + i + "] has a blank 'to' state");
                }
                if (END.equals(edge.getFrom())) {
                    throw new ValidationException("Graph '" + name + "' edge[" + i + "] has 'from' = " + END
                            + ". END is terminal; no edges originate from it.");
                }
                if (!stateMap.containsKey(edge.getFrom())) {
                    throw new ValidationException("Graph '" + name + "' edge[" + i + "] 'from' = '" + edge.getFrom()
                            + "' is not a declared state. Known: " + stateMap.keySet());
                }
                if (!END.equals(edge.getTo()) && !stateMap.containsKey(edge.getTo())) {
                    throw new ValidationException("Graph '" + name + "' edge[" + i + "] 'to' = '"
                            + edge.getTo() + "' is neither Graph.END nor a declared state. Known: "
                            + stateMap.keySet());
                }
            }
        }

        /**
         * Every non-{@code END} state must have at least one outgoing edge, otherwise the
         * state machine deadlocks on entering it.
         */
        private void validateEveryStateHasOutgoingEdge(Map<String, Task> stateMap, List<GraphEdge> edgeList) {
            Set<String> statesWithEdges = new HashSet<>();
            for (GraphEdge edge : edgeList) {
                statesWithEdges.add(edge.getFrom());
            }
            List<String> orphans = new ArrayList<>();
            for (String state : stateMap.keySet()) {
                if (!statesWithEdges.contains(state)) {
                    orphans.add(state);
                }
            }
            if (!orphans.isEmpty()) {
                throw new ValidationException("Graph '" + name + "' has state(s) with no outgoing edges: "
                        + orphans + ". Every non-END state must have at least one edge "
                        + "(use Graph.END as the target for terminal transitions).");
            }
        }

        private void validateNoFeedbackStatesExist(Map<String, Task> stateMap, Set<String> noFeedback) {
            for (String state : noFeedback) {
                if (!stateMap.containsKey(state)) {
                    throw new ValidationException(
                            "Graph '" + name + "' noFeedback state '" + state + "' is not a declared state");
                }
            }
        }
    }
}
