package net.agentensemble.tool;

/**
 * Controls how a {@link ToolPipeline} behaves when an intermediate step fails.
 *
 * <p>A step is considered failed when its {@link ToolResult#isSuccess()} returns {@code false},
 * or when its {@link AgentTool#execute(String)} throws an exception (which
 * {@link AbstractAgentTool} automatically converts to a {@link ToolResult#failure(String)}).
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
