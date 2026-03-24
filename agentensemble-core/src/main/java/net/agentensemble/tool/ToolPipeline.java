package net.agentensemble.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A composite {@link AgentTool} that chains multiple tools together in a linear pipeline,
 * analogous to a Unix pipe ({@code tool_a | tool_b | tool_c}).
 *
 * <h2>How It Works</h2>
 *
 * <p>When the LLM calls the pipeline, all steps execute sequentially inside a single
 * {@link #execute(String)} call -- no LLM round-trips occur between steps. The output of
 * each step becomes the input to the next step. The pipeline exposes a single
 * {@link dev.langchain4j.agent.tool.ToolSpecification} to the LLM, so the LLM treats the
 * entire chain as one atomic tool call.
 *
 * <p>This reduces token cost and latency for deterministic, data-transformation-style
 * pipelines where no LLM reasoning is needed between steps.
 *
 * <h2>Data Handoff Between Steps</h2>
 *
 * <p>By default, {@link ToolResult#getOutput()} (a plain {@code String}) from step N is
 * passed verbatim as the {@code input} to step N+1. When you need to reshape the output
 * before it reaches the next step -- for example, to extract a specific field or reformat
 * a JSON string -- attach an adapter after the source step using
 * the builder's {@link Builder#adapter(java.util.function.Function)} method.
 *
 * <h2>Using TypedAgentTool Steps</h2>
 *
 * <p>Pipeline steps that extend {@link AbstractTypedAgentTool} expect their input to be a
 * JSON object whose keys match the record component names. When composing a pipeline where
 * step N+1 is a {@link TypedAgentTool}, you must ensure step N's output (or an attached
 * adapter's result) is valid JSON that matches step N+1's input record type. For example:
 *
 * <pre>
 * ToolPipeline pipeline = ToolPipeline.builder()
 *     .name("fetch_and_save")
 *     .description("Fetch a URL and save the response body to a file")
 *     .step(new HttpAgentTool("fetcher"))
 *     .adapter(result -> {
 *         String body = result.getOutput()
 *                 .replace("\\", "\\\\")
 *                 .replace("\"", "\\\"")
 *                 .replace("\n", "\\n")
 *                 .replace("\r", "\\r");
 *         return "{\"path\": \"/tmp/out.txt\", \"content\": \"" + body + "\"}";
 *     })
 *     .step(FileWriteTool.of(Path.of("/tmp")))
 *     .build();
 * </pre>
 *
 * <p>The pipeline itself always presents a legacy single-{@code "input"} string parameter
 * to the LLM. If the first step is a {@link TypedAgentTool}, document the expected JSON
 * format in the pipeline's description so the LLM formats its input accordingly.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * // Simple pipeline (auto-generated name and description)
 * ToolPipeline pipeline = ToolPipeline.of(
 *     new WebSearchTool(provider),
 *     new JsonParserTool(),
 *     FileWriteTool.of(outputPath)
 * );
 *
 * // Builder with explicit name, description, adapters, and error strategy
 * ToolPipeline pipeline = ToolPipeline.builder()
 *     .name("search_and_save")
 *     .description("Search for information, extract the top result, and save it to disk")
 *     .step(new WebSearchTool(provider))
 *     .adapter(result -> "results[0]\n" + result.getOutput())  // reshape for JsonParserTool
 *     .step(new JsonParserTool())
 *     .step(FileWriteTool.of(outputPath))
 *     .errorStrategy(PipelineErrorStrategy.FAIL_FAST)
 *     .build();
 *
 * // Register as a single tool on an agent (v2: on Task)
 * var task = Task.builder()
 *     .description("Research and save AI trends")
 *     .tools(List.of(pipeline))
 *     .build();
 * </pre>
 *
 * <h2>Error Handling</h2>
 *
 * <p>The default {@link PipelineErrorStrategy#FAIL_FAST} strategy stops the pipeline and
 * returns the failed step's {@link ToolResult} immediately. Use
 * {@link PipelineErrorStrategy#CONTINUE_ON_FAILURE} to let the pipeline always run to
 * completion, passing the failed step's error message as input to the next step.
 *
 * <h2>Metrics and Logging</h2>
 *
 * <p>Each step that extends {@link AbstractAgentTool} has its own metrics automatically
 * recorded (timing, success/failure counters). The pipeline itself also records aggregate
 * timing and a single success/failure counter via the inherited
 * {@link AbstractAgentTool#execute(String)} instrumentation.
 *
 * <h2>Approval Gates</h2>
 *
 * <p>Steps that extend {@link AbstractAgentTool} and call {@link AbstractAgentTool#requestApproval}
 * will pause for human review mid-pipeline. The {@link ToolContext} (including the review
 * handler) is propagated from the pipeline to all nested {@code AbstractAgentTool} steps
 * automatically when the framework injects context via {@link ToolContextInjector}.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Instances are immutable after construction. Steps are executed sequentially and are not
 * called concurrently within a single pipeline execution, but the pipeline itself may be called
 * concurrently from multiple virtual threads in a parallel agent executor turn.
 * Each step must be individually thread-safe.
 */
public final class ToolPipeline extends AbstractAgentTool {

    private final String name;
    private final String description;
    private final List<PipelineStep> steps;
    private final PipelineErrorStrategy errorStrategy;

    private ToolPipeline(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.steps = Collections.unmodifiableList(new ArrayList<>(builder.steps));
        this.errorStrategy = builder.errorStrategy;
    }

    /**
     * Propagate the injected {@link ToolContext} to all nested steps that extend
     * {@link AbstractAgentTool}, so each step receives the same metrics backend,
     * executor, logger, and review handler as the pipeline itself.
     */
    @Override
    void setContext(ToolContext toolContext) {
        super.setContext(toolContext);
        for (PipelineStep step : steps) {
            if (step.tool() instanceof AbstractAgentTool abstractStep) {
                abstractStep.setContext(toolContext);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return name;
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        return description;
    }

    /**
     * Execute all pipeline steps sequentially. The output of each step (transformed by
     * any attached adapter) becomes the input to the next step.
     *
     * <p>If a step fails and the error strategy is {@link PipelineErrorStrategy#FAIL_FAST},
     * the pipeline returns that step's failure result immediately and skips all subsequent
     * steps. If the strategy is {@link PipelineErrorStrategy#CONTINUE_ON_FAILURE}, the error
     * message is forwarded as the next step's input and the pipeline continues to completion.
     *
     * <p>Returns {@link ToolResult#success(String) success(input)} when the pipeline has no
     * steps (pass-through).
     *
     * @param input the initial input string from the LLM tool call
     * @return the result of the last executed step, or a pass-through success if no steps
     */
    @Override
    protected ToolResult doExecute(String input) {
        if (steps.isEmpty()) {
            return ToolResult.success(input != null ? input : "");
        }

        String currentInput = input != null ? input : "";
        ToolResult lastResult = null;

        for (int i = 0; i < steps.size(); i++) {
            PipelineStep step = steps.get(i);
            if (log().isDebugEnabled()) {
                log().debug(
                                "Pipeline '{}': executing step [{}/{}] '{}'",
                                name,
                                i + 1,
                                steps.size(),
                                step.tool().name());
            }

            try {
                lastResult = step.tool().execute(currentInput);
                // Normalize null result from misbehaving steps (consistent with
                // AbstractAgentTool.execute() and LangChain4jToolAdapter behaviour).
                if (lastResult == null) {
                    lastResult = ToolResult.success("");
                }
            } catch (Exception e) {
                // Catch exceptions from plain AgentTool steps (AbstractAgentTool catches its own).
                // Re-throw control-flow signals so the framework can handle them correctly.
                if (e instanceof net.agentensemble.exception.ExitEarlyException
                        || e instanceof net.agentensemble.exception.ToolConfigurationException) {
                    throw e;
                }
                String exceptionMessage = e.getMessage();
                if (log().isWarnEnabled()) {
                    log().warn(
                                    "Pipeline '{}': step '{}' threw exception: {}",
                                    name,
                                    step.tool().name(),
                                    exceptionMessage != null ? exceptionMessage : e.toString(),
                                    e);
                }
                // Pass null to ToolResult.failure() when the exception has no message; its
                // built-in defaulting produces a meaningful error string.
                String failureMessage = (exceptionMessage != null && !exceptionMessage.isEmpty())
                        ? "Step '" + step.tool().name() + "' threw: " + exceptionMessage
                        : null;
                lastResult = ToolResult.failure(failureMessage);
            }

            if (!lastResult.isSuccess()) {
                if (log().isDebugEnabled()) {
                    log().debug(
                                    "Pipeline '{}': step '{}' failed: {}",
                                    name,
                                    step.tool().name(),
                                    lastResult.getErrorMessage());
                }
                if (errorStrategy == PipelineErrorStrategy.FAIL_FAST) {
                    return lastResult;
                }
                // CONTINUE_ON_FAILURE: forward the error message as the next step's input
                currentInput = lastResult.getErrorMessage() != null ? lastResult.getErrorMessage() : "";
            } else if (i < steps.size() - 1) {
                // Adapt the successful result before passing it to the next step
                currentInput = step.adaptOutput(lastResult);
            }
        }

        return lastResult;
    }

    /**
     * Returns the ordered list of tools that make up this pipeline's steps.
     * The returned list is unmodifiable.
     *
     * @return the pipeline steps in execution order
     */
    public List<AgentTool> getSteps() {
        return steps.stream().map(PipelineStep::tool).collect(Collectors.toUnmodifiableList());
    }

    /**
     * Returns the error strategy configured on this pipeline.
     *
     * @return the error strategy; never null
     */
    public PipelineErrorStrategy getErrorStrategy() {
        return errorStrategy;
    }

    // ========================
    // Factory methods
    // ========================

    /**
     * Create a pipeline from one or more tools with an auto-generated name and description.
     *
     * <p>The pipeline name is the step names joined with {@code "_then_"}, and the description
     * is {@code "Pipeline: step1 -> step2 -> ..."}. Use {@link Builder} for full control over
     * name, description, adapters, and error strategy.
     *
     * <p>The error strategy defaults to {@link PipelineErrorStrategy#FAIL_FAST}.
     *
     * @param first the first (and optionally only) step in the pipeline; must not be null
     * @param rest  additional steps, in order; may be empty; must not contain null elements
     * @return a new ToolPipeline
     */
    public static ToolPipeline of(AgentTool first, AgentTool... rest) {
        if (first == null) {
            throw new IllegalArgumentException("first step must not be null");
        }
        List<AgentTool> allSteps = new ArrayList<>();
        allSteps.add(first);
        if (rest != null) {
            for (AgentTool step : rest) {
                if (step == null) {
                    throw new IllegalArgumentException("pipeline steps must not contain null elements");
                }
                allSteps.add(step);
            }
        }

        String autoName = allSteps.stream().map(AgentTool::name).collect(Collectors.joining("_then_"));
        String autoDesc = "Pipeline: " + allSteps.stream().map(AgentTool::name).collect(Collectors.joining(" -> "));

        Builder b = builder().name(autoName).description(autoDesc);
        allSteps.forEach(b::step);
        return b.build();
    }

    /**
     * Create a pipeline with an explicit name and description.
     *
     * <p>The error strategy defaults to {@link PipelineErrorStrategy#FAIL_FAST}. Use
     * {@link Builder} when you need adapters between steps or a different error strategy.
     *
     * @param name        the tool name exposed to the LLM; must not be blank
     * @param description the tool description shown to the LLM; must not be blank
     * @param first       the first step; must not be null
     * @param rest        the remaining steps, in order; must not contain null elements
     * @return a new ToolPipeline
     */
    public static ToolPipeline of(String name, String description, AgentTool first, AgentTool... rest) {
        if (first == null) {
            throw new IllegalArgumentException("first step must not be null");
        }
        Builder b = builder().name(name).description(description).step(first);
        if (rest != null) {
            Arrays.stream(rest).forEach(b::step);
        }
        return b.build();
    }

    /**
     * Create a new {@link Builder} for full control over the pipeline configuration.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ========================
    // Builder
    // ========================

    /**
     * Builder for {@link ToolPipeline}.
     *
     * <p>Call {@link #step(AgentTool)} to add each step in order. Optionally follow a step
     * with {@link #adapter(Function)} to attach an output transformer that reshapes the step's
     * result before it is passed to the next step.
     *
     * <pre>
     * ToolPipeline pipeline = ToolPipeline.builder()
     *     .name("extract_and_calculate")
     *     .description("Extract a numeric field from JSON and evaluate a formula on it")
     *     .step(new JsonParserTool())
     *     .adapter(result -> result.getOutput() + " * 1.1")   // append formula for CalculatorTool
     *     .step(new CalculatorTool())
     *     .errorStrategy(PipelineErrorStrategy.FAIL_FAST)
     *     .build();
     * </pre>
     */
    public static final class Builder {

        private String name;
        private String description;
        private final List<PipelineStep> steps = new ArrayList<>();
        private PipelineErrorStrategy errorStrategy = PipelineErrorStrategy.FAIL_FAST;

        private Builder() {}

        /**
         * Set the tool name exposed to the LLM.
         *
         * @param name the tool name; must not be blank
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Set the description shown to the LLM when it selects this tool.
         *
         * @param description the tool description; must not be blank
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Add a step to the pipeline.
         *
         * <p>Steps execute in the order they are added. Each step's output becomes the next
         * step's input (unless an {@link #adapter(Function) adapter} is attached).
         *
         * @param tool the tool to add as a pipeline step; must not be null
         * @return this builder
         */
        public Builder step(AgentTool tool) {
            if (tool == null) {
                throw new IllegalArgumentException("step tool must not be null");
            }
            steps.add(new PipelineStep(tool, null));
            return this;
        }

        /**
         * Attach an output adapter to the most recently added step.
         *
         * <p>The adapter receives the most recent step's {@link ToolResult} and returns the
         * {@code String} that will be passed as input to the next step. If the step failed and
         * the pipeline strategy is {@link PipelineErrorStrategy#CONTINUE_ON_FAILURE}, the
         * adapter is NOT called -- the error message is forwarded directly.
         *
         * <p>Must be called immediately after a {@link #step(AgentTool)} call. Calling this
         * method without a preceding step throws {@link IllegalStateException}.
         *
         * <p>Example -- reshape a JSON search result to provide a path expression for
         * {@code JsonParserTool}:
         *
         * <pre>
         * .step(new WebSearchTool(provider))
         * .adapter(result -> "results[0].title\n" + result.getOutput())
         * .step(new JsonParserTool())
         * </pre>
         *
         * @param adapter a function that transforms the preceding step's result into the next
         *                step's input string; must not be null
         * @return this builder
         * @throws IllegalStateException if called before any {@link #step(AgentTool)} call
         */
        public Builder adapter(Function<ToolResult, String> adapter) {
            if (steps.isEmpty()) {
                throw new IllegalStateException("adapter() must be called after a step()");
            }
            if (adapter == null) {
                throw new IllegalArgumentException("adapter must not be null");
            }
            // Replace the most recent step with the same tool but the provided adapter
            PipelineStep last = steps.remove(steps.size() - 1);
            steps.add(new PipelineStep(last.tool(), adapter));
            return this;
        }

        /**
         * Set the error strategy for this pipeline.
         *
         * <p>Defaults to {@link PipelineErrorStrategy#FAIL_FAST} when not set.
         *
         * @param errorStrategy the strategy to use; must not be null
         * @return this builder
         */
        public Builder errorStrategy(PipelineErrorStrategy errorStrategy) {
            if (errorStrategy == null) {
                throw new IllegalArgumentException("errorStrategy must not be null");
            }
            this.errorStrategy = errorStrategy;
            return this;
        }

        /**
         * Build the {@link ToolPipeline}.
         *
         * @return a new ToolPipeline
         * @throws IllegalArgumentException if name or description is blank
         */
        public ToolPipeline build() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("ToolPipeline name must not be blank");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("ToolPipeline description must not be blank");
            }
            return new ToolPipeline(this);
        }
    }

    // ========================
    // Internal: step record
    // ========================

    /**
     * Internal record pairing a pipeline step tool with an optional output adapter.
     * The adapter transforms the step's ToolResult into the String input for the next step.
     */
    private record PipelineStep(AgentTool tool, Function<ToolResult, String> adapter) {

        /**
         * Apply the adapter (if present) to transform the result, or return
         * {@link ToolResult#getOutput()} as-is when no adapter is configured.
         *
         * <p>Guarantees a non-null {@code String} output: any {@code null} produced by the
         * adapter is normalized to {@code ""} so that downstream steps always receive a
         * non-null input string. {@link ToolResult#getOutput()} itself is guaranteed non-null
         * by the {@code ToolResult} contract; the null check here is retained for defensive
         * purposes in case a misbehaving step produces a non-standard result.
         */
        String adaptOutput(ToolResult result) {
            String output;
            if (adapter != null) {
                output = adapter.apply(result);
            } else {
                output = result.getOutput();
            }
            return output != null ? output : "";
        }
    }
}
