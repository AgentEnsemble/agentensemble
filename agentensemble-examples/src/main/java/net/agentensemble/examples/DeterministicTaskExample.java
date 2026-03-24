package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.ToolPipeline;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.tools.calculator.CalculatorTool;
import net.agentensemble.tools.json.JsonParserTool;
import net.agentensemble.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates deterministic tasks: task steps that execute without any AI or LLM call.
 *
 * This example shows three patterns:
 *
 * Pattern 1 -- Lambda handler:
 *   A task that performs a deterministic string transformation, bypassing the LLM entirely.
 *
 * Pattern 2 -- ToolPipeline as handler:
 *   A task that wraps a ToolPipeline (JsonParserTool + CalculatorTool) as a handler.
 *   The pipeline executes without any LLM round-trips -- it is called directly as a
 *   Java function, not as a tool in the ReAct loop.
 *
 * Pattern 3 -- Mixed ensemble (deterministic + AI):
 *   A sequential ensemble where the first task is deterministic (data preparation)
 *   and the second is AI-backed (analysis). The AI task receives the deterministic
 *   output as context.
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:runDeterministicTask
 */
public class DeterministicTaskExample {

    private static final Logger log = LoggerFactory.getLogger(DeterministicTaskExample.class);

    // Sample JSON representing product data from a (simulated) REST API call
    static final String PRODUCT_JSON = "{\"name\": \"Widget Pro\", \"base_price\": 149.99, \"discount\": 0.10}";

    public static void main(String[] args) throws Exception {
        log.info("=== DeterministicTask Example ===");

        // ======================================================
        // Pattern 1: Lambda handler -- no LLM, no API key needed
        // ======================================================
        runPattern1_lambdaHandler();

        // ======================================================
        // Pattern 2: ToolPipeline as handler -- no LLM needed
        // ======================================================
        runPattern2_toolPipelineHandler();

        // ======================================================
        // Pattern 3: Mixed ensemble (requires OpenAI API key)
        // ======================================================
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            runPattern3_mixedEnsemble(apiKey);
        } else {
            log.info("OPENAI_API_KEY not set -- skipping Pattern 3 (mixed ensemble)");
        }

        log.info("=== DeterministicTask Example complete ===");
    }

    // ========================
    // Pattern 1: Lambda handler
    // ========================

    static void runPattern1_lambdaHandler() {
        log.info("--- Pattern 1: Lambda handler ---");

        // Simple deterministic transformation -- no AI involved
        Task prepareData = Task.builder()
                .description("Prepare the product data for display")
                .expectedOutput("Formatted product summary")
                .handler(ctx -> {
                    // Simulate extracting and transforming product data
                    String summary = "Product: Widget Pro | Price: $149.99 | Discount: 10%";
                    log.info("Handler produced: {}", summary);
                    return ToolResult.success(summary);
                })
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(prepareData)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();

        if (log.isInfoEnabled()) {
            log.info("Pattern 1 output: {}", output.getRaw());
        }
        if (log.isInfoEnabled()) {
            log.info("Agent role: {}", output.getTaskOutputs().get(0).getAgentRole());
        }
        if (log.isInfoEnabled()) {
            log.info("Tool calls: {}", output.getTaskOutputs().get(0).getToolCallCount());
        }
    }

    // ========================
    // Pattern 2: ToolPipeline as handler
    // ========================

    static void runPattern2_toolPipelineHandler() {
        log.info("--- Pattern 2: ToolPipeline as handler ---");

        // Build a pipeline: extract the base_price from JSON, then compute discounted price
        ToolPipeline extractAndDiscount = ToolPipeline.builder()
                .name("extract_and_discount")
                .description("Extract base price from JSON and apply a 10% discount")
                .step(new JsonParserTool())
                .adapter(result -> result.getOutput() + " * 0.90") // reshape for calculator
                .step(new CalculatorTool())
                .build();

        // The pipeline runs as a deterministic task -- no LLM call at all
        Task computePrice = Task.builder()
                .description(PRODUCT_JSON + "\nbase_price") // JSON + path for JsonParserTool
                .expectedOutput("Discounted price as a number")
                .handler(extractAndDiscount)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(computePrice)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();

        if (log.isInfoEnabled()) {
            log.info("Pattern 2 discounted price: {}", output.getRaw());
        }
        if (log.isInfoEnabled()) {
            log.info("Agent role: {}", output.getTaskOutputs().get(0).getAgentRole());
        }
    }

    // ========================
    // Pattern 3: Mixed ensemble
    // ========================

    static void runPattern3_mixedEnsemble(String apiKey) {
        log.info("--- Pattern 3: Mixed ensemble (deterministic + AI) ---");

        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .build();

        // Task 1: Deterministic -- prepare and normalize the product data
        Task prepareData = Task.builder()
                .description("Prepare product data for analysis")
                .expectedOutput("Normalized product summary with key metrics")
                .handler(ctx -> {
                    // Simulate calling a REST API and normalizing the response
                    String normalized =
                            """
                            Product: Widget Pro
                            Base Price: $149.99
                            Discounted Price: $134.99 (10% off)
                            Category: Hardware
                            In Stock: Yes
                            """;
                    return ToolResult.success(normalized);
                })
                .build();

        // Task 2: AI-backed -- analyze the normalized data and produce recommendations
        Task analyzeProduct = Task.builder()
                .description("Based on the product data provided, write a 2-sentence marketing summary "
                        + "highlighting the value proposition.")
                .expectedOutput("A concise 2-sentence marketing summary")
                .chatLanguageModel(model)
                .context(List.of(prepareData))
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(prepareData)
                .task(analyzeProduct)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();

        log.info("Pattern 3 results:");
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            if (log.isInfoEnabled()) {
                log.info(
                        "  [{}] {}: {}",
                        taskOutput.getAgentRole(),
                        taskOutput.getTaskDescription(),
                        taskOutput.getRaw());
            }
        }
    }
}
