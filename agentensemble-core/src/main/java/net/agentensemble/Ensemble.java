package net.agentensemble;

import dev.langchain4j.model.chat.ChatModel;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.config.TemplateResolver;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.EnsembleMemory;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.workflow.HierarchicalWorkflowExecutor;
import net.agentensemble.workflow.ParallelErrorStrategy;
import net.agentensemble.workflow.ParallelWorkflowExecutor;
import net.agentensemble.workflow.SequentialWorkflowExecutor;
import net.agentensemble.workflow.Workflow;
import net.agentensemble.workflow.WorkflowExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * An ensemble of agents collaborating on a sequence of tasks.
 *
 * The ensemble orchestrates task execution according to the configured workflow
 * strategy, passing context between tasks as declared in each task's context list.
 *
 * Example:
 * <pre>
 * EnsembleOutput result = Ensemble.builder()
 *     .agent(researcher)
 *     .agent(writer)
 *     .task(researchTask)
 *     .task(writeTask)
 *     .workflow(Workflow.SEQUENTIAL)
 *     .build()
 *     .run();
 * </pre>
 */
@Builder
@Getter
public class Ensemble {

    private static final Logger log = LoggerFactory.getLogger(Ensemble.class);

    /** All agents participating in this ensemble. */
    @Singular
    private final List<Agent> agents;

    /** All tasks to execute. */
    @Singular
    private final List<Task> tasks;

    /** How tasks are executed. Default: SEQUENTIAL. */
    @Builder.Default
    private final Workflow workflow = Workflow.SEQUENTIAL;

    /**
     * Optional LLM for the Manager agent in hierarchical workflow.
     * If not set, defaults to the first registered agent's LLM.
     */
    private final ChatModel managerLlm;

    /**
     * Maximum number of tool call iterations for the Manager agent in hierarchical workflow.
     * Default: 20. Must be greater than zero.
     */
    @Builder.Default
    private final int managerMaxIterations = 20;

    /** When true, elevates execution logging to INFO level. */
    @Builder.Default
    private final boolean verbose = false;

    /**
     * Optional memory configuration.
     * When set, short-term, long-term, and/or entity memory are enabled.
     * Default: null (no memory).
     */
    private final EnsembleMemory memory;

    /**
     * Maximum delegation depth for agent-to-agent delegation.
     * Prevents infinite recursion when agents have {@code allowDelegation = true}.
     * Only relevant when at least one agent has {@code allowDelegation = true}.
     * Default: 3. Must be greater than zero.
     */
    @Builder.Default
    private final int maxDelegationDepth = 3;

    /**
     * Error handling strategy for parallel workflow execution.
     * Only relevant when {@code workflow = Workflow.PARALLEL}.
     * Default: {@link ParallelErrorStrategy#FAIL_FAST}.
     */
    @Builder.Default
    private final ParallelErrorStrategy parallelErrorStrategy = ParallelErrorStrategy.FAIL_FAST;

    /**
     * Event listeners that will be notified of task lifecycle events during execution.
     *
     * Use the builder's {@code EnsembleBuilder.listener(EnsembleListener)} or
     * {@code EnsembleBuilder.listeners(Collection)} methods to add full listener
     * implementations, or the lambda convenience methods:
     * {@link EnsembleBuilder#onTaskStart}, {@link EnsembleBuilder#onTaskComplete},
     * {@link EnsembleBuilder#onTaskFailed}, {@link EnsembleBuilder#onToolCall}.
     */
    @Singular
    private final List<EnsembleListener> listeners;

    /**
     * Execute the ensemble's tasks with no input variables.
     *
     * @return EnsembleOutput containing all results
     * @throws ValidationException if the ensemble configuration is invalid
     */
    public EnsembleOutput run() {
        return run(Map.of());
    }

    /**
     * Execute the ensemble's tasks with template variable substitution.
     *
     * Variables like {topic} in task descriptions and expected outputs are
     * replaced with values from the inputs map before execution.
     *
     * @param inputs map of variable names to replacement values
     * @return EnsembleOutput containing all results
     * @throws ValidationException if the ensemble configuration is invalid
     */
    public EnsembleOutput run(Map<String, String> inputs) {
        String ensembleId = UUID.randomUUID().toString();
        MDC.put("ensemble.id", ensembleId);

        try {
            log.info(
                    "Ensemble run started | Workflow: {} | Tasks: {} | Agents: {}",
                    workflow,
                    tasks.size(),
                    agents.size());
            log.debug("Input variables: {}", inputs);

            // Step 1: Validate configuration
            new EnsembleValidator(this).validate();

            // Step 2: Resolve template variables in task descriptions and expected outputs
            List<Task> resolvedTasks = resolveTasks(inputs);

            // Step 3: Create memory context for this run (no-op when memory is not configured)
            MemoryContext memoryContext = memory != null ? MemoryContext.from(memory) : MemoryContext.disabled();

            if (memoryContext.isActive()) {
                log.info(
                        "Memory enabled | shortTerm={} longTerm={} entityMemory={}",
                        memoryContext.hasShortTerm(),
                        memoryContext.hasLongTerm(),
                        memoryContext.hasEntityMemory());
            }

            // Step 4: Build execution context -- bundles memory, verbosity, and listeners
            ExecutionContext executionContext =
                    ExecutionContext.of(memoryContext, verbose, listeners != null ? listeners : List.of());

            // Step 5: Select and execute WorkflowExecutor
            WorkflowExecutor executor = selectExecutor();
            EnsembleOutput output = executor.execute(resolvedTasks, executionContext);

            log.info(
                    "Ensemble run completed | Duration: {} | Tasks: {} | Tool calls: {}",
                    output.getTotalDuration(),
                    output.getTaskOutputs().size(),
                    output.getTotalToolCalls());

            return output;

        } catch (ValidationException e) {
            log.warn("Ensemble validation failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Ensemble run failed", e);
            throw e;
        } finally {
            MDC.remove("ensemble.id");
        }
    }

    /**
     * Resolve template variables in task descriptions and expected outputs.
     *
     * Two-pass approach: first resolve descriptions/expectedOutputs and build an
     * original-to-resolved mapping, then rewrite context lists so they reference the
     * resolved Task instances. Without the second pass, context lookups in
     * SequentialWorkflowExecutor would fail when a task with a template variable is
     * also referenced by another task's context list (value equality would not match
     * the original and resolved copies).
     */
    private List<Task> resolveTasks(Map<String, String> inputs) {
        // Pass 1: resolve description and expectedOutput; build original -> resolved map.
        // Use identity-based map because Task uses value equality and two pre-resolution
        // tasks with different descriptions must be treated as distinct keys.
        IdentityHashMap<Task, Task> originalToResolved = new IdentityHashMap<>();
        for (Task task : tasks) {
            Task resolved = task.toBuilder()
                    .description(TemplateResolver.resolve(task.getDescription(), inputs))
                    .expectedOutput(TemplateResolver.resolve(task.getExpectedOutput(), inputs))
                    .build();
            originalToResolved.put(task, resolved);
        }

        // Pass 2: rewrite context lists to reference resolved instances.
        // IMPORTANT: update originalToResolved as each final task is created so that
        // later tasks in this pass (e.g., task D depending on task B) pick up the
        // fully-rewritten version (with correct context) rather than the pass-1 draft.
        // Without this update, a diamond A->B->D / A->C->D would produce D with stale
        // context references not present in the result list, breaking DAG lookup.
        List<Task> result = new ArrayList<>(tasks.size());
        for (Task original : tasks) {
            Task resolvedBase = originalToResolved.get(original);
            List<Task> originalContext = original.getContext();
            if (originalContext.isEmpty()) {
                result.add(resolvedBase);
                // resolvedBase == originalToResolved.get(original) already; no update needed
            } else {
                List<Task> resolvedContext = new ArrayList<>(originalContext.size());
                for (Task ctxTask : originalContext) {
                    resolvedContext.add(originalToResolved.getOrDefault(ctxTask, ctxTask));
                }
                Task finalTask =
                        resolvedBase.toBuilder().context(resolvedContext).build();
                // Update the mapping so subsequent tasks in this pass see the final version
                originalToResolved.put(original, finalTask);
                result.add(finalTask);
            }
        }
        return result;
    }

    private WorkflowExecutor selectExecutor() {
        return switch (workflow) {
            case SEQUENTIAL -> new SequentialWorkflowExecutor(agents, maxDelegationDepth);
            case HIERARCHICAL -> new HierarchicalWorkflowExecutor(
                    resolveManagerLlm(), agents, managerMaxIterations, maxDelegationDepth);
            case PARALLEL -> new ParallelWorkflowExecutor(agents, maxDelegationDepth, parallelErrorStrategy);
        };
    }

    private ChatModel resolveManagerLlm() {
        return managerLlm != null ? managerLlm : agents.get(0).getLlm();
    }

    // ========================
    // Custom builder methods (lambda convenience for event listeners)
    // ========================

    /**
     * Extends the Lombok-generated builder with lambda convenience methods for registering
     * event listeners without implementing the full {@link EnsembleListener} interface.
     *
     * Each method wraps the provided {@link Consumer} in an anonymous {@link EnsembleListener}
     * and delegates to {@code listener(EnsembleListener)}, which is generated by Lombok's
     * {@code @Singular} annotation. Multiple calls accumulate; none overwrite each other.
     */
    public static class EnsembleBuilder {

        /**
         * Register a lambda that is called immediately before each task starts.
         *
         * @param handler consumer receiving a {@link TaskStartEvent}
         * @return this builder
         */
        public EnsembleBuilder onTaskStart(Consumer<TaskStartEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onTaskStart(TaskStartEvent event) {
                    handler.accept(event);
                }
            });
        }

        /**
         * Register a lambda that is called immediately after each task completes successfully.
         *
         * @param handler consumer receiving a {@link TaskCompleteEvent}
         * @return this builder
         */
        public EnsembleBuilder onTaskComplete(Consumer<TaskCompleteEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onTaskComplete(TaskCompleteEvent event) {
                    handler.accept(event);
                }
            });
        }

        /**
         * Register a lambda that is called when a task fails, before the exception propagates.
         *
         * @param handler consumer receiving a {@link TaskFailedEvent}
         * @return this builder
         */
        public EnsembleBuilder onTaskFailed(Consumer<TaskFailedEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onTaskFailed(TaskFailedEvent event) {
                    handler.accept(event);
                }
            });
        }

        /**
         * Register a lambda that is called after each tool execution in the ReAct loop.
         *
         * @param handler consumer receiving a {@link ToolCallEvent}
         * @return this builder
         */
        public EnsembleBuilder onToolCall(Consumer<ToolCallEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onToolCall(ToolCallEvent event) {
                    handler.accept(event);
                }
            });
        }
    }
}
