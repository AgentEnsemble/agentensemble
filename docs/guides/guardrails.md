# Guardrails

Guardrails are pluggable validation hooks that run before and after agent execution, giving you control over what enters and exits each task. They let you enforce content policies, safety constraints, length limits, or any custom validation rule without modifying agent prompts or task logic.

---

## Quick Start

```java
// Block tasks whose description contains certain keywords
InputGuardrail noSensitiveDataGuardrail = input -> {
    if (input.taskDescription().contains("SSN") || input.taskDescription().contains("password")) {
        return GuardrailResult.failure("Task description contains sensitive data");
    }
    return GuardrailResult.success();
};

// Enforce a maximum response length
OutputGuardrail lengthGuardrail = output -> {
    if (output.rawResponse().length() > 5000) {
        return GuardrailResult.failure("Response exceeds maximum length of 5000 characters");
    }
    return GuardrailResult.success();
};

var task = Task.builder()
    .description("Write an executive summary")
    .expectedOutput("A concise summary")
    .agent(writer)
    .inputGuardrails(List.of(noSensitiveDataGuardrail))
    .outputGuardrails(List.of(lengthGuardrail))
    .build();
```

---

## Input Guardrails

Input guardrails run **before the LLM call is made**. If any guardrail returns a failure, execution stops immediately and `GuardrailViolationException` is thrown -- the agent's LLM is never contacted.

Implement `InputGuardrail` as a functional interface:

```java
@FunctionalInterface
public interface InputGuardrail {
    GuardrailResult validate(GuardrailInput input);
}
```

The `GuardrailInput` record carries everything needed to make a decision:

| Field | Type | Description |
|-------|------|-------------|
| `taskDescription()` | `String` | The task description |
| `expectedOutput()` | `String` | The expected output specification |
| `contextOutputs()` | `List<TaskOutput>` | Outputs from prior context tasks (immutable) |
| `agentRole()` | `String` | The role of the agent about to execute |

### Example: Keyword filter

```java
InputGuardrail piiGuardrail = input -> {
    String desc = input.taskDescription().toLowerCase();
    if (desc.contains("ssn") || desc.contains("credit card") || desc.contains("passport")) {
        return GuardrailResult.failure(
            "Task description may contain personally identifiable information");
    }
    return GuardrailResult.success();
};
```

### Example: Agent role check

```java
InputGuardrail roleGuardrail = input -> {
    if ("Untrusted Agent".equals(input.agentRole())) {
        return GuardrailResult.failure("Untrusted agents are not permitted on this task");
    }
    return GuardrailResult.success();
};
```

---

## Output Guardrails

Output guardrails run **after the agent produces a response**. When `task.outputType` is set, output guardrails run after structured output parsing completes -- the parsed Java object is available via `parsedOutput()`.

Implement `OutputGuardrail` as a functional interface:

```java
@FunctionalInterface
public interface OutputGuardrail {
    GuardrailResult validate(GuardrailOutput output);
}
```

The `GuardrailOutput` record carries the response for inspection:

| Field | Type | Description |
|-------|------|-------------|
| `rawResponse()` | `String` | The full text produced by the agent |
| `parsedOutput()` | `Object` | The parsed Java object (null if no `outputType` set) |
| `taskDescription()` | `String` | The task description |
| `agentRole()` | `String` | The role of the agent that produced the output |

### Example: Length limit

```java
OutputGuardrail lengthGuardrail = output -> {
    int maxChars = 3000;
    if (output.rawResponse().length() > maxChars) {
        return GuardrailResult.failure(
            "Response is " + output.rawResponse().length() +
            " chars, exceeds limit of " + maxChars);
    }
    return GuardrailResult.success();
};
```

### Example: Required keyword check

```java
OutputGuardrail conclusionGuardrail = output -> {
    if (!output.rawResponse().toLowerCase().contains("conclusion")) {
        return GuardrailResult.failure(
            "Response must include a conclusion section");
    }
    return GuardrailResult.success();
};
```

### Example: Typed output validation

```java
record ResearchReport(String title, List<String> findings, String conclusion) {}

OutputGuardrail findingsGuardrail = output -> {
    if (output.parsedOutput() instanceof ResearchReport report) {
        if (report.findings() == null || report.findings().isEmpty()) {
            return GuardrailResult.failure("Report must include at least one finding");
        }
    }
    return GuardrailResult.success();
};
```

---

## GuardrailResult

Guardrails communicate pass/fail via `GuardrailResult`:

```java
// Pass
return GuardrailResult.success();

// Fail with a descriptive reason
return GuardrailResult.failure("Reason: response contains prohibited content");
```

The failure reason is included verbatim in the `GuardrailViolationException` message.

---

## Multiple Guardrails

You can configure multiple guardrails per task. They are evaluated **in order** -- the first failure stops evaluation and throws immediately. Subsequent guardrails in the list are not called.

```java
var task = Task.builder()
    .description("Write an article")
    .expectedOutput("An article")
    .agent(writer)
    .inputGuardrails(List.of(piiGuardrail, roleGuardrail, domainGuardrail))
    .outputGuardrails(List.of(lengthGuardrail, conclusionGuardrail, toxicityGuardrail))
    .build();
```

To collect all failures rather than stop at the first, compose them into a single guardrail that aggregates results:

```java
InputGuardrail compositeGuardrail = input -> {
    List<String> failures = new ArrayList<>();
    for (InputGuardrail g : List.of(piiGuardrail, roleGuardrail)) {
        GuardrailResult r = g.validate(input);
        if (!r.isSuccess()) {
            failures.add(r.getMessage());
        }
    }
    return failures.isEmpty()
        ? GuardrailResult.success()
        : GuardrailResult.failure(String.join("; ", failures));
};
```

---

## Exception Handling

When a guardrail fails, `GuardrailViolationException` is thrown. It propagates through the workflow executor and is wrapped in `TaskExecutionException` (the same pattern as other task failures).

```java
try {
    ensemble.run();
} catch (TaskExecutionException ex) {
    if (ex.getCause() instanceof GuardrailViolationException gve) {
        System.out.println("Guardrail type: " + gve.getGuardrailType()); // INPUT or OUTPUT
        System.out.println("Violation: " + gve.getViolationMessage());
        System.out.println("Task: " + gve.getTaskDescription());
        System.out.println("Agent: " + gve.getAgentRole());
    }
}
```

`GuardrailViolationException` fields:

| Field | Type | Description |
|-------|------|-------------|
| `getGuardrailType()` | `GuardrailType` | `INPUT` or `OUTPUT` |
| `getViolationMessage()` | `String` | The failure reason from `GuardrailResult.failure(reason)` |
| `getTaskDescription()` | `String` | The task that was blocked |
| `getAgentRole()` | `String` | The agent assigned to the task |

---

## Guardrails and Callbacks

When a guardrail blocks a task, the `TaskFailedEvent` callback fires before the exception propagates. The `cause` field of `TaskFailedEvent` will be the `GuardrailViolationException`.

```java
Ensemble.builder()
    .agent(writer)
    .task(guardedTask)
    .onTaskFailed(event -> {
        if (event.cause() instanceof GuardrailViolationException gve) {
            metrics.incrementCounter("guardrail.violation." + gve.getGuardrailType());
        }
    })
    .build()
    .run();
```

---

## Guardrails and Structured Output

When a task uses `outputType`, the execution order is:

1. Input guardrails run (before LLM)
2. LLM executes and produces raw text
3. Structured output parsing (JSON extraction + deserialization)
4. Output guardrails run (with both `rawResponse()` and `parsedOutput()` available)

This means output guardrails can inspect the typed object directly:

```java
OutputGuardrail typedGuardrail = output -> {
    if (output.parsedOutput() instanceof Report r && r.title() == null) {
        return GuardrailResult.failure("Report title must not be null");
    }
    return GuardrailResult.success();
};
```

---

## Thread Safety

`InputGuardrail` and `OutputGuardrail` are functional interfaces -- their implementations must be thread-safe when used with `Workflow.PARALLEL`, as multiple tasks may run concurrently and invoke guardrails on separate threads. Stateless guardrails (lambdas with no shared mutable state) are inherently thread-safe.

---

## Reference

- [Task Configuration](../reference/task-configuration.md) -- `inputGuardrails` and `outputGuardrails` fields
- [Error Handling](error-handling.md) -- exception hierarchy
- [Exceptions Reference](../reference/exceptions.md) -- `GuardrailViolationException`
