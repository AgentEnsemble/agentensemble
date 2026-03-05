# Example: Hierarchical Team

A hierarchical workflow where a Manager agent coordinates a team of specialists. The manager receives the task list, decides which worker handles each task, and synthesizes a final result.

---

## What It Does

A product analysis team where a Manager coordinates:
- **Market Researcher** -- analyses market trends and competitive landscape
- **Financial Analyst** -- reviews financial metrics and projections
- **Report Writer** -- synthesises findings into an executive report

The Manager decides task assignment and ordering at run time based on agent roles and goals.

---

## Full Code

```java
import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.*;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.Workflow;

import java.util.Map;

public class HierarchicalTeamExample {

    public static void main(String[] args) {
        String company = args.length > 0 ? args[0] : "Acme Corp";

        var fastModel = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .build();

        var powerfulModel = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o")  // stronger model for the manager
            .build();

        // Worker agents
        var marketResearcher = Agent.builder()
            .role("Market Research Analyst")
            .goal("Analyse market trends, competitive dynamics, and growth opportunities")
            .background("You specialise in technology sector market research. " +
                        "You provide concise, evidence-based insights.")
            .llm(fastModel)
            .build();

        var financialAnalyst = Agent.builder()
            .role("Financial Analyst")
            .goal("Analyse financial performance, metrics, and investment implications")
            .background("You are a CFA charter holder with 10 years of equity research experience. " +
                        "You focus on publicly available financial data.")
            .llm(fastModel)
            .build();

        var reportWriter = Agent.builder()
            .role("Executive Report Writer")
            .goal("Transform complex analysis into clear, compelling executive reports")
            .background("You write board-level reports. You use clear language, structured sections, " +
                        "and evidence-based conclusions.")
            .llm(fastModel)
            .build();

        // Tasks -- the manager will decide which agent handles each
        var marketTask = Task.builder()
            .description("Analyse {company}'s current market position and competitive landscape")
            .expectedOutput("A market analysis covering: market share, top three competitors, " +
                            "key differentiators, and two to three growth opportunities")
            .agent(marketResearcher)
            .build();

        var financialTask = Task.builder()
            .description("Review {company}'s financial health and key performance indicators")
            .expectedOutput("A financial summary covering: revenue trend (last 3 years), " +
                            "profitability metrics, balance sheet strength, and investment thesis")
            .agent(financialAnalyst)
            .build();

        var reportTask = Task.builder()
            .description("Write an executive investment brief for {company} based on market and financial analysis")
            .expectedOutput("A 600-word executive brief with: company overview, market position, " +
                            "financial highlights, risks, and investment recommendation")
            .agent(reportWriter)
            .build();

        // Hierarchical ensemble -- manager coordinates the team
        EnsembleOutput output = Ensemble.builder()
            .agent(marketResearcher)
            .agent(financialAnalyst)
            .agent(reportWriter)
            .task(marketTask)
            .task(financialTask)
            .task(reportTask)
            .workflow(Workflow.HIERARCHICAL)
            .managerLlm(powerfulModel)
            .managerMaxIterations(15)
            .build()
            .run(Map.of("company", company));

        // The manager's synthesised output
        System.out.println("EXECUTIVE BRIEF: " + company);
        System.out.println("=".repeat(60));
        System.out.println(output.getRaw());
        System.out.println();

        // Individual worker outputs
        System.out.println("WORKER OUTPUTS");
        System.out.println("-".repeat(40));
        for (int i = 0; i < output.getTaskOutputs().size() - 1; i++) {
            TaskOutput task = output.getTaskOutputs().get(i);
            System.out.printf("[%s]%n%s%n%n", task.getAgentRole(), task.getRaw());
        }

        System.out.printf("Total duration: %s%n", output.getTotalDuration());
    }
}
```

---

## Running the Example

```bash
git clone https://github.com/AgentEnsemble/agentensemble.git
cd agentensemble
export OPENAI_API_KEY=your-api-key

# Default company (Acme Corp)
./gradlew :agentensemble-examples:runHierarchicalTeam

# Analyse a different company
./gradlew :agentensemble-examples:runHierarchicalTeam --args="Acme Corp"
```

---

## How the Manager Behaves

The Manager receives a prompt describing each worker agent and their capabilities, plus the full task list. It then:

1. Calls `delegateTask("Market Research Analyst", "Analyse Acme Corp's current market position...")`
2. Receives the market analysis as the tool result
3. Calls `delegateTask("Financial Analyst", "Review Acme Corp's financial health...")`
4. Receives the financial analysis
5. Calls `delegateTask("Executive Report Writer", "Write an executive brief based on: [market and financial analyses]")`
6. Receives the executive brief
7. Synthesises a final response incorporating all worker outputs

---

## Notes on Manager LLM Choice

The manager makes routing and synthesis decisions. Using a more capable model (GPT-4o, Claude 3.5 Sonnet) for the manager and a faster model (GPT-4o-mini) for workers is often a cost-effective strategy.

---

## Checking the Output Structure

In hierarchical workflow, `output.getTaskOutputs()` contains:
1. Worker outputs in delegation order
2. The manager's final synthesis (last element)

```java
List<TaskOutput> outputs = output.getTaskOutputs();
TaskOutput managerOutput = outputs.getLast();
System.out.println("Manager: " + managerOutput.getAgentRole()); // "Manager"

List<TaskOutput> workerOutputs = outputs.subList(0, outputs.size() - 1);
workerOutputs.forEach(w -> System.out.println("Worker: " + w.getAgentRole()));
```

---

## Custom Manager Prompts

The example uses a custom `ManagerPromptStrategy` to inject an investment-focused constraint into the Manager's system prompt. You can extend or replace the default prompt logic without modifying the framework:

```java
ManagerPromptStrategy investmentStrategy = new ManagerPromptStrategy() {
    @Override
    public String buildSystemPrompt(ManagerPromptContext ctx) {
        return DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(ctx)
            + "\n\nFocus on investment-relevant insights. "
            + "Always ask the Financial Analyst to complete their analysis before "
            + "the Report Writer begins synthesising.";
    }
    @Override
    public String buildUserPrompt(ManagerPromptContext ctx) {
        return DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(ctx);
    }
};

EnsembleOutput output = Ensemble.builder()
    ...
    .workflow(Workflow.HIERARCHICAL)
    .managerPromptStrategy(investmentStrategy)
    .build()
    .run();
```

`ManagerPromptContext` exposes `agents()` (all worker agents), `tasks()` (the tasks to orchestrate), `previousOutputs()`, and `workflowDescription()`. See the [Workflows Guide](../guides/workflows.md#customizing-the-manager-prompt) for the full field reference.

---

## Delegation Policy Hooks

Register `DelegationPolicy` instances to enforce business rules before the manager can
delegate to a worker. Policies run after the built-in guards and before any worker executes.

```java
// Reject any delegation when required context is missing
DelegationPolicy requireCompany = (request, ctx) -> {
    if (request.getTaskDescription().contains("UNKNOWN_COMPANY")) {
        return DelegationPolicyResult.reject("company must be specified");
    }
    return DelegationPolicyResult.allow();
};

// Restrict which agents the Manager can delegate financial analysis to
DelegationPolicy analystOnly = (request, ctx) -> {
    if (request.getTaskDescription().toLowerCase().contains("financial")
            && !"Financial Analyst".equals(request.getAgentRole())) {
        return DelegationPolicyResult.reject("financial tasks must go to the Financial Analyst");
    }
    return DelegationPolicyResult.allow();
};

EnsembleOutput output = Ensemble.builder()
    .agent(marketResearcher)
    .agent(financialAnalyst)
    .agent(reportWriter)
    .task(marketTask)
    .task(financialTask)
    .task(reportTask)
    .workflow(Workflow.HIERARCHICAL)
    .managerLlm(powerfulModel)
    .delegationPolicy(requireCompany)
    .delegationPolicy(analystOnly)
    .onDelegationStarted(event ->
        System.out.printf("[DELEGATION] Manager -> %s [%s]%n",
            event.workerRole(), event.delegationId()))
    .onDelegationCompleted(event ->
        System.out.printf("[DONE] %s in %s%n",
            event.workerRole(), event.duration()))
    .onDelegationFailed(event ->
        System.out.printf("[REJECTED] %s: %s%n",
            event.workerRole(), event.failureReason()))
    .build()
    .run(Map.of("company", company));
```

Policy rejections produce a `FAILURE` `DelegationResponse` and the worker is never invoked.
The manager receives the rejection reason as the tool result and can adapt its plan.
See the [Delegation Guide](../guides/delegation.md#delegation-policy-hooks) for the full
policy evaluation reference.

---

## With Hierarchical Constraints

`HierarchicalConstraints` adds deterministic guardrails to the delegation graph without
removing the LLM's control over which worker handles each task.

```java
HierarchicalConstraints constraints = HierarchicalConstraints.builder()
    .requiredWorker("Financial Analyst")    // must be called at least once
    .allowedWorker("Financial Analyst")     // only these two workers may be delegated to
    .allowedWorker("Risk Analyst")
    .maxCallsPerWorker("Risk Analyst", 2)   // Risk Analyst may be called at most twice
    .globalMaxDelegations(5)                // at most 5 total delegations
    .requiredStage(List.of("Financial Analyst")) // stage 0: Financial Analyst goes first
    .requiredStage(List.of("Risk Analyst"))      // stage 1: Risk Analyst only after Financial
    .build();

EnsembleOutput output = Ensemble.builder()
    .agent(financialAnalyst)
    .agent(riskAnalyst)
    .task(analysisTask)
    .task(riskTask)
    .workflow(Workflow.HIERARCHICAL)
    .managerLlm(powerfulModel)
    .hierarchicalConstraints(constraints)
    .build()
    .run(Map.of("company", company));
```

Pre-delegation violations are returned as error messages to the Manager so it can adapt:

```
[REJECTED] Risk Analyst: Worker 'Risk Analyst' cannot be delegated to yet:
           stage 0 worker 'Financial Analyst' has not yet completed.
```

If a required worker is never called, `ConstraintViolationException` is thrown after the
Manager finishes:

```java
try {
    ensemble.run(Map.of("company", company));
} catch (ConstraintViolationException e) {
    for (String v : e.getViolations()) {
        System.err.println("Constraint violated: " + v);
    }
    // Inspect partial work that did complete
    for (TaskOutput completed : e.getCompletedTaskOutputs()) {
        System.out.println("Completed: " + completed.getAgentRole());
    }
}
```

See the [Delegation Guide](../guides/delegation.md#hierarchical-constraints) for the full
`HierarchicalConstraints` reference.
