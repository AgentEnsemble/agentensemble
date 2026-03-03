# Example: Parallel Workflow

This example demonstrates `Workflow.PARALLEL` with a research pipeline where independent tasks run concurrently and dependent tasks wait for their prerequisites.

## Scenario

A competitive intelligence pipeline with four tasks:

1. **Market Research** -- gather competitor landscape data (no dependencies)
2. **Financial Analysis** -- pull financial metrics for competitors (no dependencies)
3. **SWOT Synthesis** -- synthesize findings from both above (depends on 1 and 2)
4. **Executive Summary** -- write the final report (depends on 3)

Tasks 1 and 2 are independent and run in parallel. Task 3 waits for both. Task 4 waits for 3.

## Code

```java
import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.Workflow;

import java.util.List;

public class ParallelCompetitiveIntelligenceExample {

    public static void main(String[] args) {

        var model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        // ---- Agents ----

        var marketResearcher = Agent.builder()
                .role("Market Research Analyst")
                .goal("Gather comprehensive data on the competitive landscape")
                .background("You specialize in identifying market trends and competitor positioning.")
                .llm(model)
                .build();

        var financialAnalyst = Agent.builder()
                .role("Financial Analyst")
                .goal("Analyse financial metrics and performance indicators for competitors")
                .background("You specialize in financial statement analysis and KPI benchmarking.")
                .llm(model)
                .build();

        var strategist = Agent.builder()
                .role("Strategy Consultant")
                .goal("Synthesize market and financial findings into strategic insights")
                .background("You produce SWOT analyses and strategic recommendations.")
                .llm(model)
                .build();

        var writer = Agent.builder()
                .role("Executive Writer")
                .goal("Write concise, clear executive summaries for senior leadership")
                .llm(model)
                .build();

        // ---- Tasks ----

        // Tasks 1 and 2 have no context dependencies -- they run in PARALLEL
        var marketTask = Task.builder()
                .description("Research the competitive landscape for {company} in the {industry} industry. "
                        + "Identify the top 5 competitors, their market positioning, and key differentiators.")
                .expectedOutput("A structured competitor analysis covering market share, positioning, "
                        + "strengths, and recent strategic moves for each competitor.")
                .agent(marketResearcher)
                .build();

        var financialTask = Task.builder()
                .description("Analyse the financial performance of the top competitors in the "
                        + "{industry} industry. Focus on revenue growth, margins, and R&D spend.")
                .expectedOutput("A financial comparison table and narrative highlighting which "
                        + "competitors are gaining or losing financial ground.")
                .agent(financialAnalyst)
                .build();

        // Task 3 depends on BOTH market and financial tasks
        var swotTask = Task.builder()
                .description("Using the market research and financial analysis provided as context, "
                        + "produce a SWOT analysis for {company} relative to its key competitors.")
                .expectedOutput("A complete SWOT analysis (Strengths, Weaknesses, Opportunities, "
                        + "Threats) with supporting evidence from the market and financial data.")
                .agent(strategist)
                .context(List.of(marketTask, financialTask))  // waits for both
                .build();

        // Task 4 depends on the SWOT synthesis
        var summaryTask = Task.builder()
                .description("Write a one-page executive summary of the competitive position of "
                        + "{company} based on the SWOT analysis provided in context.")
                .expectedOutput("A concise, well-structured executive summary suitable for "
                        + "presentation to the board of directors.")
                .agent(writer)
                .context(List.of(swotTask))  // waits for SWOT
                .build();

        // ---- Ensemble ----

        EnsembleOutput output = Ensemble.builder()
                .agent(marketResearcher)
                .agent(financialAnalyst)
                .agent(strategist)
                .agent(writer)
                .task(marketTask)
                .task(financialTask)
                .task(swotTask)
                .task(summaryTask)
                .workflow(Workflow.PARALLEL)
                .build()
                .run(java.util.Map.of(
                        "company", "Acme Corp",
                        "industry", "enterprise software"));

        // ---- Results ----

        System.out.println("=== Executive Summary ===");
        System.out.println(output.getRaw());

        System.out.println("\n=== All Task Outputs ===");
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            System.out.printf("[%s] %s%n", taskOutput.getAgentRole(),
                    taskOutput.getTaskDescription());
        }

        System.out.printf("%nCompleted in %s | %d total tool calls%n",
                output.getTotalDuration(), output.getTotalToolCalls());
    }
}
```

## Execution Timeline

```
Time -->

[Market Research] ----+
                       +--> [SWOT Synthesis] --> [Executive Summary]
[Financial Analysis]--+
```

Both the Market Research task and Financial Analysis task start at the same time (no dependencies). The SWOT Synthesis task starts as soon as both complete. The Executive Summary starts immediately after SWOT Synthesis finishes.

## Key Points

**1. No explicit parallelism annotations**: The parallelism is derived from the `context` declarations. Declaring no context on `marketTask` and `financialTask` means they have no prerequisites and can run immediately.

**2. Task list order does not matter**: For `PARALLEL` workflow, the order of `.task(...)` calls in the builder is irrelevant to execution. The dependency graph drives scheduling. This is different from `SEQUENTIAL`, where tasks must be listed in dependency order.

**3. Context outputs are injected automatically**: When `swotTask` executes, it receives the outputs from `marketTask` and `financialTask` injected into its user prompt under a "Context from prior tasks" section.

**4. Output ordering**: `output.getTaskOutputs()` returns outputs in topological completion order -- guaranteed to respect all dependency constraints. The first items are always the root tasks (in the order they completed), and dependent tasks always appear after all their dependencies.

**5. Error handling**: If `marketTask` throws an exception, `swotTask` and `summaryTask` are skipped. `financialTask` (which is independent) still runs to completion. Add `.parallelErrorStrategy(ParallelErrorStrategy.CONTINUE_ON_ERROR)` to capture partial results in this scenario.

## Output Structure

`output.getRaw()` returns the text from `summaryTask` (the last task in topological order).

`output.getTaskOutputs()` contains outputs in this order:
1. Either `marketTask` or `financialTask` output (whichever finished first)
2. The other of `marketTask` / `financialTask`
3. `swotTask` output
4. `summaryTask` output

## Related Examples

- [Research Writer (Sequential)](research-writer.md) -- a linear pipeline with `SEQUENTIAL` workflow
- [Hierarchical Team](hierarchical-team.md) -- manager-driven task assignment
- [Memory Across Runs](memory-across-runs.md) -- cross-run persistence with long-term memory
