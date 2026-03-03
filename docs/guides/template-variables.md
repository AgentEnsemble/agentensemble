# Template Variables

Task descriptions and expected outputs support `{variable}` placeholder substitution. Variables are resolved at run time by calling `ensemble.run(Map<String, String> inputs)`.

---

## Basic Usage

Use curly braces to define a placeholder:

```java
Task task = Task.builder()
    .description("Research the latest developments in {topic} for the {audience} audience")
    .expectedOutput("A 400-word summary of {topic} suitable for {audience}")
    .agent(researcher)
    .build();
```

Pass values at run time:

```java
EnsembleOutput output = ensemble.run(Map.of(
    "topic", "quantum computing",
    "audience", "software engineers"
));
```

The resolved description becomes:
```
Research the latest developments in quantum computing for the software engineers audience
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

When no variables are needed, call `run()` without arguments:

```java
EnsembleOutput output = ensemble.run();
// equivalent to ensemble.run(Map.of())
```

---

## Missing Variables

If a task description contains a placeholder that is not in the inputs map, a `PromptTemplateException` is thrown before any LLM calls:

```java
// Task has {topic} and {year} placeholders
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

With `inputs = Map.of("varName", "userId")`, the resolved description is:
```
Write a Java method that parses {variable} from a string. Variable name: userId
```

---

## Sharing Variables Across Tasks

All tasks in the ensemble are resolved with the same inputs map. Any variable defined in inputs is available in all task descriptions and expected outputs:

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

// Both tasks receive the same variables
ensemble.run(Map.of("topic", "AI agents"));
```

---

## Dynamic Task Creation

Because template resolution happens at run time, you can create task templates once and reuse them across multiple runs:

```java
// Create tasks once
var researchTask = Task.builder()
    .description("Research {topic}")
    .expectedOutput("A summary of {topic}")
    .agent(researcher)
    .build();

var writeTask = Task.builder()
    .description("Write about {topic}")
    .expectedOutput("A blog post about {topic}")
    .agent(writer)
    .context(List.of(researchTask))
    .build();

Ensemble ensemble = Ensemble.builder()
    .agent(researcher).agent(writer)
    .task(researchTask).task(writeTask)
    .build();

// Run multiple times with different inputs
ensemble.run(Map.of("topic", "AI agents"));
ensemble.run(Map.of("topic", "quantum computing"));
ensemble.run(Map.of("topic", "blockchain"));
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
ensemble.run(Map.of("topic", ""));   // substitutes empty string
```
