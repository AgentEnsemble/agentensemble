# Template Variables

Task descriptions and expected outputs support `{variable}` placeholder substitution. Variables are resolved at run time from inputs configured on the builder or passed to `ensemble.run(Map<String, String>)`.

---

## Basic Usage

Use curly braces to define a placeholder in any task description or expected output:

```java
Task task = Task.builder()
    .description("Research the latest developments in {topic} for the {audience} audience")
    .expectedOutput("A 400-word summary of {topic} suitable for {audience}")
    .agent(researcher)
    .build();
```

Supply values on the builder with `.input("key", "value")`:

```java
EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .task(task)
    .input("topic", "quantum computing")
    .input("audience", "software engineers")
    .build()
    .run();
```

The resolved description becomes:
```
Research the latest developments in quantum computing for the software engineers audience
```

---

## Multiple Inputs

Chain `.input()` calls -- they accumulate and are all applied at run time:

```java
Ensemble.builder()
    .agent(analyst)
    .task(financialTask)
    .input("company", "Acme Corp")
    .input("quarter", "Q4")
    .input("year", "2025")
    .build()
    .run();
```

To supply a whole map at once, use `.inputs(Map<String, String>)`:

```java
Ensemble.builder()
    .agent(analyst)
    .task(financialTask)
    .inputs(Map.of("company", "Acme Corp", "quarter", "Q4", "year", "2025"))
    .build()
    .run();
```

---

## Variables in Expected Output

Template variables are resolved in both `description` and `expectedOutput`:

```java
Task task = Task.builder()
    .description("Analyse {company} financial results for Q{quarter} {year}")
    .expectedOutput("A financial analysis report for {company} Q{quarter} {year} with three key findings")
    .agent(analyst)
    .build();
```

---

## No-Variable Runs

When no variables are needed, call `run()` without any inputs configured:

```java
EnsembleOutput output = ensemble.run();
```

---

## Dynamic Runs: Overriding Inputs at Invocation Time

When you need to run the same ensemble multiple times with different variable values, keep the ensemble instance and pass values to `run(Map<String, String>)`. Run-time values are merged with any builder inputs; **run-time values win** on key conflicts:

```java
// Create tasks and ensemble once
Ensemble ensemble = Ensemble.builder()
    .agent(analyst).agent(advisor)
    .task(analysisTask).task(recommendationTask)
    .build();

// Invoke multiple times with different inputs
ensemble.run(Map.of("week", "2026-01-06"));
ensemble.run(Map.of("week", "2026-01-13"));
ensemble.run(Map.of("week", "2026-01-20"));
```

Merge example -- builder provides a default, run-time call overrides it:

```java
Ensemble ensemble = Ensemble.builder()
    .agent(researcher)
    .task(task)
    .input("audience", "developers")  // default
    .build();

// audience = "developers" (builder default)
ensemble.run(Map.of("topic", "AI agents"));

// audience = "executives" (run-time overrides builder)
ensemble.run(Map.of("topic", "AI agents", "audience", "executives"));
```

---

## Missing Variables

If a task description contains a placeholder that is not supplied by either the builder inputs or the run-time inputs, a `PromptTemplateException` is thrown before any LLM calls:

```java
// Task has {topic} and {year} placeholders
// Builder has no inputs configured
try {
    ensemble.run(Map.of("topic", "AI"));  // missing "year"
} catch (PromptTemplateException e) {
    System.err.println("Missing: " + e.getMissingVariables());
    // Missing: [year]
}
```

---

## Escaping Braces

To include a literal `{variable}` in the output without substitution, use double braces:

```java
Task task = Task.builder()
    .description("Write a Java method that parses {{variable}} from a string. Variable name: {varName}")
    .expectedOutput("A Java method with parameter name {varName}")
    .agent(coder)
    .build();
```

With `input("varName", "userId")`, the resolved description is:
```
Write a Java method that parses {variable} from a string. Variable name: userId
```

---

## Sharing Variables Across Tasks

All tasks in the ensemble are resolved with the same inputs. Any variable defined on the builder is available in all task descriptions and expected outputs:

```java
var researchTask = Task.builder()
    .description("Research {topic} in depth")
    .expectedOutput("A summary of {topic}")
    .agent(researcher)
    .build();

var writeTask = Task.builder()
    .description("Write a blog post about {topic}")
    .expectedOutput("A 700-word blog post about {topic}")
    .agent(writer)
    .context(List.of(researchTask))
    .build();

// Both tasks receive the same variable
Ensemble.builder()
    .agent(researcher).agent(writer)
    .task(researchTask).task(writeTask)
    .input("topic", "AI agents")
    .build()
    .run();
```

---

## Variable Naming

Variable names are case-sensitive and can contain letters, digits, and underscores only (no hyphens or spaces):

```
{topic}          -- valid
{company_name}   -- valid
{year2025}       -- valid
{TOPIC}          -- valid, but different from {topic}
{company-name}   -- invalid (hyphen not allowed)
{company name}   -- invalid (space not allowed)
```

---

## Null and Empty Values

Values are always strings. Passing `null` as a value is not permitted by `Map.of()`. Empty strings are allowed but will produce empty substitutions:

```java
Ensemble.builder()
    .agent(researcher)
    .task(task)
    .input("topic", "")  // substitutes empty string
    .build()
    .run();
```
