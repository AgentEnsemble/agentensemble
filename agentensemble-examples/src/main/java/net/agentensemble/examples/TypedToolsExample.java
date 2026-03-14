package net.agentensemble.examples;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import java.util.Map;
import java.util.regex.Pattern;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.AbstractTypedAgentTool;
import net.agentensemble.tool.LangChain4jToolAdapter;
import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;
import net.agentensemble.tool.ToolResult;

/**
 * Demonstrates the typed tool input system introduced in AgentEnsemble.
 *
 * <p>This example does not require an API key -- it shows how to define typed tools,
 * what schema the LLM receives, and how to invoke tools directly for verification.
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew :agentensemble-examples:runTypedTools
 * </pre>
 */
public final class TypedToolsExample {

    // ========================
    // Pattern 1: Typed tool with multiple parameters
    // ========================

    @ToolInput(description = "Parameters for a product search")
    public record ProductSearchInput(
            @ToolParam(description = "Search query for the product name or description") String query,
            @ToolParam(description = "Product category to filter results", required = false) String category,
            @ToolParam(description = "Maximum price in USD", required = false) Double maxPrice,
            @ToolParam(description = "Maximum number of results to return", required = false) Integer limit) {}

    static final class ProductSearchTool extends AbstractTypedAgentTool<ProductSearchInput> {

        @Override
        public String name() {
            return "product_search";
        }

        @Override
        public String description() {
            return "Searches the product catalog and returns matching items.";
        }

        @Override
        public Class<ProductSearchInput> inputType() {
            return ProductSearchInput.class;
        }

        @Override
        public ToolResult execute(ProductSearchInput input) {
            int resultLimit = (input.limit() != null) ? input.limit() : 10;
            String categoryFilter = (input.category() != null) ? " in " + input.category() : "";
            String priceFilter = (input.maxPrice() != null) ? " under $" + input.maxPrice() : "";
            String summary = String.format(
                    "Found %d results for '%s'%s%s",
                    Math.min(resultLimit, 42), input.query(), categoryFilter, priceFilter);
            return ToolResult.success(summary);
        }
    }

    // ========================
    // Pattern 2: Typed tool with enum parameter
    // ========================

    public enum SortOrder {
        ASCENDING,
        DESCENDING
    }

    @ToolInput(description = "Parameters for sorting a list")
    public record SortInput(
            @ToolParam(description = "Comma-separated list of items to sort") String items,
            @ToolParam(description = "Sort order") SortOrder order) {}

    static final class SortTool extends AbstractTypedAgentTool<SortInput> {

        private static final Pattern COMMA = Pattern.compile(",");

        @Override
        public String name() {
            return "sort_items";
        }

        @Override
        public String description() {
            return "Sorts a list of items alphabetically.";
        }

        @Override
        public Class<SortInput> inputType() {
            return SortInput.class;
        }

        @Override
        public ToolResult execute(SortInput input) {
            String[] items = COMMA.split(input.items());
            java.util.Arrays.sort(items, String.CASE_INSENSITIVE_ORDER);
            if (input.order() == SortOrder.DESCENDING) {
                // reverse
                for (int i = 0, j = items.length - 1; i < j; i++, j--) {
                    String tmp = items[i];
                    items[i] = items[j];
                    items[j] = tmp;
                }
            }
            return ToolResult.success(String.join(", ", items));
        }
    }

    // ========================
    // Pattern 3: Legacy string-based tool (intentional)
    // ========================

    /**
     * CalculatorTool-style: the input IS a natural expression string.
     * A typed record would not improve this tool.
     */
    static final class MathTool extends AbstractAgentTool {

        @Override
        public String name() {
            return "math";
        }

        @Override
        public String description() {
            return "Evaluates a simple arithmetic expression. " + "Input: a math expression such as '2 + 3 * 4'.";
        }

        @Override
        protected ToolResult doExecute(String input) {
            // In a real implementation this would evaluate the expression.
            // For this demo, just return a placeholder.
            return ToolResult.success("Result of [" + input.trim() + "] = 42");
        }
    }

    // ========================
    // Main: show schemas and execute tools
    // ========================

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("AgentEnsemble: Typed Tool Input System Demo");
        System.out.println("=".repeat(70));

        // --- Typed tool: ProductSearchTool ---
        ProductSearchTool searchTool = new ProductSearchTool();
        ToolSpecification searchSpec = LangChain4jToolAdapter.toSpecification(searchTool);

        System.out.println("\n--- Typed Tool: ProductSearchTool ---");
        System.out.println("Name       : " + searchSpec.name());
        System.out.println("Description: " + searchSpec.description());
        System.out.println("Schema (what the LLM sees):");
        printSchema(searchSpec.parameters());

        // Execute with all parameters
        ToolResult r1 = LangChain4jToolAdapter.executeForResult(
                searchTool,
                "{\"query\":\"wireless headphones\",\"category\":\"Electronics\"," + "\"maxPrice\":150.0,\"limit\":5}");
        System.out.println("\nExecution (all params): " + r1.getOutput());

        // Execute with required parameter only
        ToolResult r2 = LangChain4jToolAdapter.executeForResult(searchTool, "{\"query\":\"Java books\"}");
        System.out.println("Execution (query only): " + r2.getOutput());

        // Missing required parameter
        ToolResult r3 = LangChain4jToolAdapter.executeForResult(searchTool, "{}");
        System.out.println("Missing required 'query': failure=" + !r3.isSuccess() + " | msg=" + r3.getErrorMessage());

        // --- Typed tool: SortTool ---
        SortTool sortTool = new SortTool();
        ToolSpecification sortSpec = LangChain4jToolAdapter.toSpecification(sortTool);

        System.out.println("\n--- Typed Tool: SortTool (with enum parameter) ---");
        System.out.println("Schema (what the LLM sees):");
        printSchema(sortSpec.parameters());

        ToolResult r4 = LangChain4jToolAdapter.executeForResult(
                sortTool, "{\"items\":\"Banana, Apple, Cherry, Date\",\"order\":\"ASCENDING\"}");
        System.out.println("\nExecution ASCENDING: " + r4.getOutput());

        ToolResult r5 = LangChain4jToolAdapter.executeForResult(
                sortTool, "{\"items\":\"Banana, Apple, Cherry, Date\",\"order\":\"DESCENDING\"}");
        System.out.println("Execution DESCENDING: " + r5.getOutput());

        // --- Legacy tool: MathTool ---
        MathTool mathTool = new MathTool();
        ToolSpecification mathSpec = LangChain4jToolAdapter.toSpecification(mathTool);

        System.out.println("\n--- Legacy Tool: MathTool (single string input) ---");
        System.out.println("Schema (what the LLM sees):");
        printSchema(mathSpec.parameters());

        ToolResult r6 = LangChain4jToolAdapter.executeForResult(mathTool, "{\"input\":\"2 + 3 * 4\"}");
        System.out.println("\nExecution: " + r6.getOutput());

        // --- Summary ---
        System.out.println("\n" + "=".repeat(70));
        System.out.println("Summary");
        System.out.println("=".repeat(70));
        System.out.println("ProductSearchTool (typed): LLM sees query/category/maxPrice/limit as typed parameters");
        System.out.println("SortTool (typed):          LLM sees items/order with enum [ASCENDING, DESCENDING]");
        System.out.println("MathTool (legacy):         LLM sees a single generic 'input' string parameter");
        System.out.println();
        System.out.println("Typed tools make the LLM's job easier -- no format instructions in description,");
        System.out.println("no parsing code in the tool, consistent validation with clear error messages.");
        System.out.println();
        System.out.println("Legacy string tools still work exactly as before -- backward compatible.");
    }

    /** Prints a readable summary of a JSON schema for demonstration purposes. */
    private static void printSchema(JsonObjectSchema schema) {
        System.out.println("  required: " + schema.required());
        for (Map.Entry<String, JsonSchemaElement> entry : schema.properties().entrySet()) {
            String type = entry.getValue()
                    .getClass()
                    .getSimpleName()
                    .replace("Json", "")
                    .replace("Schema", "");
            String desc = descriptionOf(entry.getValue());
            String descStr = (desc != null && !desc.isEmpty()) ? " -- " + desc : "";
            System.out.printf("  %-15s : %s%s%n", entry.getKey(), type.toLowerCase(), descStr);
        }
    }

    private static String descriptionOf(JsonSchemaElement element) {
        try {
            return (String) element.getClass().getMethod("description").invoke(element);
        } catch (Exception e) {
            return null;
        }
    }
}
