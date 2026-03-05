package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.metrics.MemoryOperationCounts;
import net.agentensemble.tools.calculator.CalculatorTool;
import net.agentensemble.tools.datetime.DateTimeTool;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.trace.CapturedMessage;
import net.agentensemble.trace.ExecutionTrace;
import net.agentensemble.trace.LlmInteraction;
import net.agentensemble.trace.TaskTrace;
import net.agentensemble.trace.ToolCallTrace;

/**
 * Demonstrates CaptureMode for deep execution data collection.
 *
 * <p>This example runs the same agent at each of the three capture levels and prints
 * what additional data becomes available at each level:
 *
 * <ul>
 *   <li>{@code OFF} -- base trace: prompts, tool args/results, timing, token counts
 *   <li>{@code STANDARD} -- adds full LLM message history per ReAct iteration
 *   <li>{@code FULL} -- adds enriched tool I/O (parsedInput maps) and writes a JSON
 *       trace file to {@code traces/}
 * </ul>
 *
 * <p>CaptureMode can also be activated without any code change:
 * <pre>
 * java -Dagentensemble.captureMode=FULL -jar my-app.jar
 * AGENTENSEMBLE_CAPTURE_MODE=STANDARD java -jar my-app.jar
 * </pre>
 *
 * Prerequisites:
 * - Set the OPENAI_API_KEY environment variable.
 *
 * Run with:
 *   ./gradlew :agentensemble-examples:runCaptureMode
 */
public class CaptureModeExample {

    static final String TASK_DESCRIPTION = "Calculate the number of days between 2024-01-15 and 2025-03-01, "
            + "then compute 15% of 42750 (representing the daily rate times the days).";

    static final String EXPECTED_OUTPUT = "The number of days and the calculated amount.";

    public static void main(String[] args) {
        var chatModel = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        System.out.println("=".repeat(60));
        System.out.println("CaptureMode Example");
        System.out.println("=".repeat(60));

        // ========================
        // LEVEL 1: CaptureMode.OFF (default)
        // ========================
        System.out.println("\n--- CaptureMode.OFF (default) ---");
        EnsembleOutput offOutput = runEnsemble(chatModel, CaptureMode.OFF);
        printOffTrace(offOutput);

        // ========================
        // LEVEL 2: CaptureMode.STANDARD
        // ========================
        System.out.println("\n--- CaptureMode.STANDARD ---");
        EnsembleOutput standardOutput = runEnsemble(chatModel, CaptureMode.STANDARD);
        printStandardTrace(standardOutput);

        // ========================
        // LEVEL 3: CaptureMode.FULL
        // ========================
        System.out.println("\n--- CaptureMode.FULL ---");
        EnsembleOutput fullOutput = runEnsemble(chatModel, CaptureMode.FULL);
        printFullTrace(fullOutput);
    }

    private static EnsembleOutput runEnsemble(dev.langchain4j.model.chat.ChatModel chatModel, CaptureMode captureMode) {
        var analyst = Agent.builder()
                .role("Quantitative Analyst")
                .goal("Perform precise calculations using available tools")
                .tools(List.of(new CalculatorTool(), new DateTimeTool()))
                .llm(chatModel)
                .maxIterations(10)
                .build();

        var task = Task.builder()
                .description(TASK_DESCRIPTION)
                .expectedOutput(EXPECTED_OUTPUT)
                .agent(analyst)
                .build();

        return Ensemble.builder()
                .agent(analyst)
                .task(task)
                .captureMode(captureMode)
                .build()
                .run();
    }

    private static void printOffTrace(EnsembleOutput output) {
        ExecutionTrace trace = output.getTrace();
        System.out.println("CaptureMode on trace: " + trace.getCaptureMode());
        System.out.println("Schema version:       " + trace.getSchemaVersion());

        for (TaskTrace taskTrace : trace.getTaskTraces()) {
            System.out.println("Task:                 " + taskTrace.getTaskDescription());
            System.out.println(
                    "LLM interactions:     " + taskTrace.getLlmInteractions().size());
            System.out.println("Total tool calls:     " + taskTrace.getMetrics().getToolCallCount());
            System.out.println("Total tokens:         " + taskTrace.getMetrics().getTotalTokens());

            for (LlmInteraction i : taskTrace.getLlmInteractions()) {
                System.out.printf(
                        "  Iteration %d: [%s] messages=%d (always empty at OFF)%n",
                        i.getIterationIndex(),
                        i.getResponseType(),
                        i.getMessages().size());
                for (ToolCallTrace tc : i.getToolCalls()) {
                    System.out.printf(
                            "    Tool: %-20s result=%-30s parsedInput=%s%n",
                            tc.getToolName(), truncate(tc.getResult(), 30), tc.getParsedInput()); // null at OFF
                }
            }
        }
    }

    private static void printStandardTrace(EnsembleOutput output) {
        ExecutionTrace trace = output.getTrace();
        System.out.println("CaptureMode on trace: " + trace.getCaptureMode());

        for (TaskTrace taskTrace : trace.getTaskTraces()) {
            MemoryOperationCounts memOps = taskTrace.getMetrics().getMemoryOperations();
            System.out.println("Memory ops (STANDARD wires these):");
            System.out.println("  STM writes:     " + memOps.getShortTermEntriesWritten());
            System.out.println("  LTM stores:     " + memOps.getLongTermStores());
            System.out.println("  LTM retrievals: " + memOps.getLongTermRetrievals());

            for (LlmInteraction i : taskTrace.getLlmInteractions()) {
                System.out.printf(
                        "  Iteration %d [%s] -- message history (%d messages):%n",
                        i.getIterationIndex(),
                        i.getResponseType(),
                        i.getMessages().size());
                for (CapturedMessage msg : i.getMessages()) {
                    String contentPreview = msg.getContent() != null
                            ? truncate(msg.getContent(), 70)
                            : "(tool calls: " + msg.getToolCalls().size() + ")";
                    System.out.printf("    [%-9s] %s%n", msg.getRole(), contentPreview);
                }
            }
        }
    }

    private static void printFullTrace(EnsembleOutput output) {
        ExecutionTrace trace = output.getTrace();
        System.out.println("CaptureMode on trace: " + trace.getCaptureMode());
        System.out.println("(Trace auto-written to ./traces/ when no explicit traceExporter is set)");

        for (TaskTrace taskTrace : trace.getTaskTraces()) {
            for (LlmInteraction i : taskTrace.getLlmInteractions()) {
                for (ToolCallTrace tc : i.getToolCalls()) {
                    System.out.printf("  Tool: %-20s%n", tc.getToolName());
                    System.out.printf("    arguments (raw):    %s%n", tc.getArguments());
                    System.out.printf("    arguments (parsed): %s%n", tc.getParsedInput()); // non-null at FULL
                    System.out.printf("    result:             %s%n", truncate(tc.getResult(), 50));
                    System.out.printf("    outcome:            %s%n", tc.getOutcome());
                }
            }
        }

        // Output comparison
        System.out.println("\nFinal answer: " + truncate(output.getRaw(), 100));
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "null";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
