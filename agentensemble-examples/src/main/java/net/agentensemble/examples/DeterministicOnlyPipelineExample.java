package net.agentensemble.examples;

import java.util.List;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates deterministic-only orchestration: multi-step pipelines that run entirely
 * without any AI or LLM call.
 *
 * AgentEnsemble is not exclusively an AI orchestrator. The same DAG execution, parallel
 * phases, callbacks, guardrails, metrics, and review gates that coordinate AI agents can
 * coordinate purely deterministic Java functions -- REST API calls, data transformations,
 * file operations, or any business logic.
 *
 * This example covers three patterns:
 *
 * Pattern 1 -- Sequential pipeline with data passing:
 *   A three-step ETL pipeline where the output of each step flows into the next via
 *   context(). No ChatModel required.
 *
 * Pattern 2 -- Parallel fan-out:
 *   Three independent tasks that execute concurrently, each calling a different
 *   simulated service. Results are merged by a downstream aggregation task.
 *
 * Pattern 3 -- Phase-based deterministic workstreams:
 *   An ingest-transform-publish pipeline expressed as named phases with a dependency DAG.
 *   Per-phase outputs are accessible by name on the EnsembleOutput.
 *
 * Run with:
 *   ./gradlew :agentensemble-examples:runDeterministicOnlyPipeline
 */
public class DeterministicOnlyPipelineExample {

    private static final Logger log = LoggerFactory.getLogger(DeterministicOnlyPipelineExample.class);

    public static void main(String[] args) {
        System.out.println("=== Pattern 1: Sequential ETL pipeline with data passing ===");
        runSequentialEtl();

        System.out.println();
        System.out.println("=== Pattern 2: Parallel fan-out and merge ===");
        runParallelFanOut();

        System.out.println();
        System.out.println("=== Pattern 3: Phase-based pipeline ===");
        runPhasePipeline();
    }

    // ========================
    // Pattern 1: Sequential ETL pipeline
    // ========================

    private static void runSequentialEtl() {
        // Simulates:  fetch -> parse -> transform -> report
        //
        // Each downstream step reads from the previous step's output via
        // ctx.contextOutputs().get(0).getRaw()

        Task fetchTask = Task.builder()
                .description("Fetch order records from the orders service")
                .expectedOutput("Raw JSON order list")
                .handler(ctx -> {
                    // Simulated HTTP response from an orders API
                    String json = "[{\"id\":\"ORD-001\",\"total\":49.99,\"status\":\"shipped\"},"
                            + "{\"id\":\"ORD-002\",\"total\":129.50,\"status\":\"pending\"},"
                            + "{\"id\":\"ORD-003\",\"total\":19.00,\"status\":\"shipped\"}]";
                    if (log.isInfoEnabled()) {
                        log.info("Fetched {} characters of order data", json.length());
                    }
                    return ToolResult.success(json);
                })
                .build();

        Task parseTask = Task.builder()
                .description("Parse orders and extract pending order IDs")
                .expectedOutput("Comma-separated list of pending order IDs")
                .context(List.of(fetchTask))
                .handler(ctx -> {
                    String json = ctx.contextOutputs().get(0).getRaw();
                    // Naive parse -- in production use a proper JSON library
                    long pendingCount = java.util.Arrays.stream(json.split("\"status\":\"pending\""))
                                    .count()
                            - 1;
                    String result = "pending_orders=" + pendingCount;
                    log.info("Parsed orders: {}", result);
                    return ToolResult.success(result);
                })
                .build();

        Task transformTask = Task.builder()
                .description("Enrich pending order count with SLA label")
                .expectedOutput("Enriched status report")
                .context(List.of(parseTask))
                .handler(ctx -> {
                    String parsed = ctx.contextOutputs().get(0).getRaw();
                    int count = Integer.parseInt(parsed.split("=")[1]);
                    String sla = count > 5 ? "BREACH" : count > 2 ? "WARNING" : "OK";
                    String report = parsed + ",sla=" + sla;
                    log.info("Transformed: {}", report);
                    return ToolResult.success(report);
                })
                .build();

        Task reportTask = Task.builder()
                .description("Write status report to output stream")
                .expectedOutput("Confirmation that report was written")
                .context(List.of(transformTask))
                .handler(ctx -> {
                    String report = ctx.contextOutputs().get(0).getRaw();
                    System.out.println("  Status report: " + report);
                    return ToolResult.success("Report written: " + report);
                })
                .build();

        // Ensemble.run(Task...) -- no ChatModel required
        EnsembleOutput output = Ensemble.run(fetchTask, parseTask, transformTask, reportTask);

        System.out.println("  Final output: " + output.getRaw());
        System.out.println("  Tasks executed: " + output.getTaskOutputs().size());
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            System.out.printf("    [%s] %s%n", taskOutput.getAgentRole(), taskOutput.getTaskDescription());
        }
    }

    // ========================
    // Pattern 2: Parallel fan-out and merge
    // ========================

    private static void runParallelFanOut() {
        // Fetch from three independent services concurrently, then merge.
        // Context dependencies on all three services cause PARALLEL to be inferred.

        Task inventoryTask = Task.builder()
                .description("Fetch inventory level for product SKU-42")
                .expectedOutput("Inventory count")
                .handler(ctx -> {
                    // Simulated inventory service response
                    simulateLatency(30);
                    return ToolResult.success("inventory=850");
                })
                .build();

        Task pricingTask = Task.builder()
                .description("Fetch current price for product SKU-42")
                .expectedOutput("Price in USD")
                .handler(ctx -> {
                    simulateLatency(20);
                    return ToolResult.success("price=24.99");
                })
                .build();

        Task reviewsTask = Task.builder()
                .description("Fetch average review score for product SKU-42")
                .expectedOutput("Average score out of 5")
                .handler(ctx -> {
                    simulateLatency(25);
                    return ToolResult.success("score=4.3");
                })
                .build();

        Task mergeTask = Task.builder()
                .description("Merge inventory, pricing, and review data into a product card")
                .expectedOutput("Complete product card string")
                .context(List.of(inventoryTask, pricingTask, reviewsTask))
                .handler(ctx -> {
                    String inv = ctx.contextOutputs().get(0).getRaw();
                    String price = ctx.contextOutputs().get(1).getRaw();
                    String reviews = ctx.contextOutputs().get(2).getRaw();
                    String card = "SKU-42 | " + price + " | " + inv + " | " + reviews;
                    log.info("Merged product card: {}", card);
                    return ToolResult.success(card);
                })
                .build();

        // Context dependency on three tasks causes PARALLEL workflow to be inferred.
        // inventoryTask, pricingTask, reviewsTask all run concurrently.
        long start = System.currentTimeMillis();
        EnsembleOutput output = Ensemble.builder()
                .task(inventoryTask)
                .task(pricingTask)
                .task(reviewsTask)
                .task(mergeTask)
                .onTaskComplete(e -> log.info("Completed: {}", e.taskDescription()))
                .build()
                .run();
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("  Product card: " + output.getRaw());
        System.out.printf("  Elapsed: %dms (3 parallel service calls + merge)%n", elapsed);
    }

    // ========================
    // Pattern 3: Phase-based pipeline
    // ========================

    private static void runPhasePipeline() {
        // Three named phases: ingest -> transform -> publish
        // The transform phase has a cross-phase context dependency on the ingest task.

        Task ingestTask = Task.builder()
                .description("Ingest daily transaction log from storage")
                .expectedOutput("Raw transaction data")
                .handler(ctx -> {
                    String data = "txn:1001,amount:150.00;txn:1002,amount:75.50;txn:1003,amount:320.00";
                    if (log.isInfoEnabled()) {
                        log.info("Ingested transaction log: {} bytes", data.length());
                    }
                    return ToolResult.success(data);
                })
                .build();

        Phase ingestPhase = Phase.of("ingest", ingestTask);

        Task aggregateTask = Task.builder()
                .description("Aggregate transaction totals")
                .expectedOutput("Total transaction amount")
                .context(List.of(ingestTask))
                .handler(ctx -> {
                    String data = ctx.contextOutputs().get(0).getRaw();
                    double total = java.util.Arrays.stream(data.split(";"))
                            .mapToDouble(txn -> {
                                String[] parts = txn.split(",amount:");
                                return parts.length == 2 ? Double.parseDouble(parts[1]) : 0.0;
                            })
                            .sum();
                    String result = String.format("total=%.2f,count=3", total);
                    log.info("Aggregated: {}", result);
                    return ToolResult.success(result);
                })
                .build();

        Task validateTask = Task.builder()
                .description("Validate aggregated totals are within expected range")
                .expectedOutput("Validation result")
                .handler(ctx -> ToolResult.success("validation=PASSED"))
                .build();

        Phase transformPhase = Phase.builder()
                .name("transform")
                .task(aggregateTask)
                .task(validateTask)
                .after(ingestPhase)
                .build();

        Task publishTask = Task.builder()
                .description("Publish daily summary report")
                .expectedOutput("Publication confirmation")
                .context(List.of(aggregateTask))
                .handler(ctx -> {
                    String summary = ctx.contextOutputs().get(0).getRaw();
                    System.out.println("  Publishing report: " + summary);
                    return ToolResult.success("published: " + summary);
                })
                .build();

        Phase publishPhase = Phase.builder()
                .name("publish")
                .task(publishTask)
                .after(transformPhase)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .phase(ingestPhase)
                .phase(transformPhase)
                .phase(publishPhase)
                .build()
                .run();

        System.out.println("  Final output:  " + output.getRaw());
        System.out.println("  Phases completed: " + output.getPhaseOutputs().size());
        output.getPhaseOutputs()
                .forEach((phaseName, taskOutputs) ->
                        System.out.printf("    Phase '%s': %d task(s)%n", phaseName, taskOutputs.size()));
    }

    private static void simulateLatency(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
