package net.agentensemble.executor;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * A request to run a multi-task ensemble via {@link EnsembleExecutor}.
 *
 * <p>Use {@code EnsembleExecutor} when you want AgentEnsemble's internal orchestration
 * (sequential, parallel, or hierarchical) to handle a full pipeline within a single
 * external-workflow activity. For finer-grained control -- where each AgentEnsemble task
 * maps to a separate Temporal activity -- use {@link TaskExecutor} instead.
 *
 * <p>The {@code getWorkflow()} string maps to {@code net.agentensemble.workflow.Workflow}
 * enum values: {@code "SEQUENTIAL"}, {@code "PARALLEL"}, or {@code "HIERARCHICAL"}.
 * When null, AgentEnsemble infers the workflow from task context dependencies.
 *
 * <p>Cross-task context within an ensemble run is handled internally by AgentEnsemble.
 * The per-task {@code TaskRequest.getContext()} entries are injected as additional template
 * variable inputs for that specific task.
 *
 * <h2>Example</h2>
 * <pre>
 * EnsembleRequest request = EnsembleRequest.builder()
 *     .task(TaskRequest.of("Research artificial intelligence trends", "A research summary"))
 *     .task(TaskRequest.of("Write a blog post about AI trends", "A polished blog post"))
 *     .workflow("SEQUENTIAL")
 *     .inputs(Map.of("audience", "software engineers"))
 *     .build();
 *
 * EnsembleResult result = executor.execute(request);
 * </pre>
 */
@Value
@Builder
public class EnsembleRequest {

    /** The ordered list of tasks to run. At least one task must be provided. */
    @Singular
    List<TaskRequest> tasks;

    /**
     * Workflow mode. One of {@code "SEQUENTIAL"}, {@code "PARALLEL"}, {@code "HIERARCHICAL"},
     * or {@code null} to let AgentEnsemble infer the mode from task dependencies.
     */
    String workflow;

    /**
     * Global template variable values applied to all tasks in the ensemble.
     * Per-task {@link TaskRequest#getInputs()} entries take precedence when keys collide.
     */
    Map<String, String> inputs;

    /**
     * Optional model name resolved by the {@link ModelProvider} on the executor. When null,
     * the provider's default model is used for all tasks that do not specify their own model.
     */
    String modelName;
}
