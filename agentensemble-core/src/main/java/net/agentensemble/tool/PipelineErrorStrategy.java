package net.agentensemble.tool;

/**
 * Controls how a {@link ToolPipeline} behaves when an intermediate step fails.
 *
 * <p>A step is considered failed when its {@link ToolResult#isSuccess()} returns {@code false}.
 * This includes:
 * <ul>
 *   <li>Steps that return {@link ToolResult#failure(String)} directly.</li>
 *   <li>Steps extending {@link AbstractAgentTool}: exceptions thrown by
 *       {@link AgentTool#execute(String)} are automatically converted to a
 *       {@link ToolResult#failure(String)} by {@code AbstractAgentTool}.</li>
 *   <li>Plain {@link AgentTool} steps (not extending {@code AbstractAgentTool}): exceptions
 *       are caught by {@link ToolPipeline} and converted to a
 *       {@link ToolResult#failure(String)} at the pipeline level, so
 *       {@link #FAIL_FAST} and {@link #CONTINUE_ON_FAILURE} apply consistently to all
 *       step types. Framework control-flow exceptions ({@code ExitEarlyException} and
 *       {@code ToolConfigurationException}) are re-thrown and not converted.</li>
 * </ul>
 *
 * @see ToolPipeline.Builder#errorStrategy(PipelineErrorStrategy)
 */
public enum PipelineErrorStrategy {

    /**
     * Stop the pipeline on the first failed step and return that step's
     * {@link ToolResult} directly.
     *
     * <p>This is the default strategy. Steps after the failed step are never executed.
     *
     * <p>Example: in a three-step pipeline {@code A -> B -> C}, if B fails, C is
     * skipped and the pipeline returns B's failure result immediately.
     */
    FAIL_FAST,

    /**
     * Continue executing subsequent steps even when an intermediate step fails.
     *
     * <p>When a step fails, its {@link ToolResult#getErrorMessage()} is used as the
     * input to the next step (or an empty string if the error message is null). The
     * final result of the pipeline is the result of the last step, regardless of
     * whether any intermediate steps failed.
     *
     * <p>Use this strategy when downstream steps can handle or recover from upstream
     * errors, or when the pipeline should always produce an output even if intermediate
     * transformations fail.
     */
    CONTINUE_ON_FAILURE
}
