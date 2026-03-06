package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.tool.PipelineErrorStrategy;
import net.agentensemble.tool.ToolPipeline;
import net.agentensemble.tools.calculator.CalculatorTool;
import net.agentensemble.tools.json.JsonParserTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates ToolPipeline: chaining multiple tools into a single compound tool that
 * the LLM calls once, with all steps executing without LLM round-trips between them.
 *
 * This example builds two pipelines:
 *
 * Pipeline 1 -- "extract_and_calculate":
 *   Step 1: JsonParserTool  -- extracts a numeric value from a JSON payload
 *   Step 2: CalculatorTool  -- applies a 10% markup formula to the extracted number
 *   Adapter: reshapes the extracted number into a formula string for CalculatorTool
 *
 * Pipeline 2 -- "json_multi_extract":
 *   Step 1: JsonParserTool  -- extracts the first field
 *   Step 2: JsonParserTool  -- extracts a nested field from the result of step 1
 *   Demonstrates chaining the same tool type multiple times
 *
 * The LLM calls each pipeline exactly once. Both steps inside each pipeline run
 * without any LLM inference between them -- reducing token cost and latency compared
 * to asking the LLM to call two separate tools sequentially via the ReAct loop.
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:runToolPipeline
 */
public class ToolPipelineExample {

    private static final Logger log = LoggerFactory.getLogger(ToolPipelineExample.class);

    // Sample data used as the tool call input from the LLM
    static final String PRODUCT_JSON =
            "{\"product\": {\"name\": \"Widget Pro\", \"base_price\": 149.99, \"category\": \"hardware\"}}";

    public static void main(String[] args) throws Exception {
        log.info("Starting ToolPipeline example");

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY environment variable is not set. " + "Please set it to your OpenAI API key.");
        }

        var model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .build();

        // ========================
        // Pipeline 1: JSON extraction -> arithmetic
        //
        // The LLM calls this pipeline with a JSON payload.
        // Step 1 (JsonParserTool) extracts the base_price field.
        // The adapter reshapes "149.99" into "149.99 * 1.1" for the calculator.
        // Step 2 (CalculatorTool) evaluates "149.99 * 1.1" -> "164.989".
        //
        // The LLM receives one tool result: "164.989"
        // No LLM inference occurs between the two steps.
        // ========================
        ToolPipeline extractAndCalculate = ToolPipeline.builder()
                .name("extract_and_calculate")
                .description("Given a JSON payload with a 'product.base_price' field, extracts the price "
                        + "and returns the price with a 10% markup applied. "
                        + "Input: a JSON string containing a product object.")
                .step(new JsonParserTool())
                .adapter(result -> result.getOutput() + " * 1.1")
                .step(new CalculatorTool())
                .errorStrategy(PipelineErrorStrategy.FAIL_FAST)
                .build();

        // ========================
        // Pipeline 2: chained JSON extraction
        //
        // Step 1: extracts the whole "product" object from the outer JSON.
        // Step 2: extracts the "name" field from the product object.
        // Demonstrates chaining the same tool type and how string piping works.
        // ========================
        ToolPipeline chainedExtract = ToolPipeline.builder()
                .name("extract_product_name")
                .description("Given a JSON payload with a nested 'product' object, extracts the product name. "
                        + "Input: a JSON string containing a product object.")
                .step(new JsonParserTool())
                .adapter(result -> "name\n" + result.getOutput())
                .step(new JsonParserTool())
                .errorStrategy(PipelineErrorStrategy.FAIL_FAST)
                .build();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("PIPELINE DEMO");
        System.out.println("=".repeat(60));
        System.out.println("Input JSON: " + PRODUCT_JSON);
        System.out.println();

        // ========================
        // Task using the extract_and_calculate pipeline
        // ========================
        var priceTask = Task.builder()
                .description("Use the extract_and_calculate tool to compute the retail price (base price + 10% "
                        + "markup) from the following product JSON:\n\n"
                        + "product.base_price\n"
                        + PRODUCT_JSON
                        + "\n\nReport the retail price.")
                .expectedOutput("The retail price for Widget Pro with a 10% markup applied.")
                .tools(List.of(extractAndCalculate))
                .build();

        EnsembleOutput priceOutput = Ensemble.builder()
                .chatLanguageModel(model)
                .task(priceTask)
                .build()
                .run();

        System.out.println("--- extract_and_calculate pipeline result ---");
        System.out.println(priceOutput.getRaw());
        System.out.printf(
                "Tool calls: %d | Duration: %s%n%n", priceOutput.getTotalToolCalls(), priceOutput.getTotalDuration());

        // ========================
        // Task using the chained extract pipeline
        // ========================
        var nameTask = Task.builder()
                .description("Use the extract_product_name tool to extract the product name from this JSON:\n\n"
                        + "product\n"
                        + PRODUCT_JSON
                        + "\n\nReport the product name.")
                .expectedOutput("The name of the product extracted from the JSON.")
                .tools(List.of(chainedExtract))
                .build();

        EnsembleOutput nameOutput = Ensemble.builder()
                .chatLanguageModel(model)
                .task(nameTask)
                .build()
                .run();

        System.out.println("--- extract_product_name pipeline result ---");
        System.out.println(nameOutput.getRaw());
        System.out.printf(
                "Tool calls: %d | Duration: %s%n%n", nameOutput.getTotalToolCalls(), nameOutput.getTotalDuration());

        // ========================
        // Show pipeline structure
        // ========================
        System.out.println("--- Pipeline structure ---");
        System.out.println("Pipeline: " + extractAndCalculate.name());
        System.out.println("  Error strategy: " + extractAndCalculate.getErrorStrategy());
        System.out.println("  Steps (" + extractAndCalculate.getSteps().size() + "):");
        for (int i = 0; i < extractAndCalculate.getSteps().size(); i++) {
            System.out.printf(
                    "    [%d] %s%n",
                    i + 1, extractAndCalculate.getSteps().get(i).name());
        }
    }
}
