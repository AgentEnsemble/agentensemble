# 07 - Template Resolver

This document specifies the template variable substitution system used to parameterize task descriptions and expected outputs.

## Purpose

Users can include `{variable}` placeholders in task descriptions and expected outputs. These are resolved at `ensemble.run(inputs)` time, allowing the same ensemble definition to be reused with different inputs.

```java
var task = Task.builder()
    .description("Research the latest developments in {topic}")
    .expectedOutput("A detailed report on {topic} covering the last {period}")
    .agent(researcher)
    .build();

// At run time:
ensemble.run(Map.of("topic", "AI agents", "period", "6 months"));
// Resolves to:
// description: "Research the latest developments in AI agents"
// expectedOutput: "A detailed report on AI agents covering the last 6 months"
```

## TemplateResolver

```java
public final class TemplateResolver {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{(\\w+)}");
    private static final Pattern ESCAPED_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    private TemplateResolver() {} // Utility class, not instantiable

    /**
     * Resolve template variables in the given string.
     *
     * Variables are denoted by {name} where name contains only word characters
     * (letters, digits, underscores). Escaped variables {{name}} are converted
     * to literal {name} without substitution.
     *
     * @param template String with {variable} placeholders. May be null.
     * @param inputs Map of variable names to replacement values. May be null (treated as empty).
     * @return Resolved string, or null if template was null
     * @throws PromptTemplateException if any unescaped variables are not found in inputs
     */
    public static String resolve(String template, Map<String, String> inputs) { ... }
}
```

## Algorithm

```
resolve(template, inputs):

1. IF template is null:
     RETURN null

2. IF template is blank (empty or whitespace only):
     RETURN template

3. effectiveInputs = (inputs != null) ? inputs : Map.of()

4. // Step 1: Protect escaped variables
   // Replace {{var}} with a sentinel that won't collide with real content
   working = ESCAPED_PATTERN.replaceAll(template, "__AGENTENSEMBLE_ESCAPED_$1__")

5. // Step 2: Find all unescaped variables
   matcher = VARIABLE_PATTERN.matcher(working)
   foundVariables = new LinkedHashSet<String>()
   WHILE matcher.find():
     foundVariables.add(matcher.group(1))

6. // Step 3: Check for missing variables
   missingVariables = foundVariables.stream()
     .filter(name -> !effectiveInputs.containsKey(name))
     .toList()

   IF missingVariables is not empty:
     throw new PromptTemplateException(
       "Missing template variables: " + missingVariables
         + ". Provide them in ensemble.run(inputs). Template: '"
         + truncate(template, 100) + "'",
       missingVariables,
       template)

7. // Step 4: Replace variables with values
   FOR EACH variableName IN foundVariables:
     value = effectiveInputs.get(variableName)
     IF value is null:
       value = ""
     working = working.replace("{" + variableName + "}", value)

8. // Step 5: Restore escaped variables as literals
   working = working.replaceAll("__AGENTENSEMBLE_ESCAPED_(\\w+)__", "{$1}")

9. RETURN working
```

## Edge Case Matrix

| Input | Expected Output | Notes |
|---|---|---|
| `template=null, inputs=any` | `null` | Null-safe pass-through |
| `template="", inputs=any` | `""` | Empty pass-through |
| `template="  ", inputs=any` | `"  "` | Whitespace-only pass-through |
| `template="no variables", inputs={}` | `"no variables"` | No variables, returned unchanged |
| `template="no variables", inputs={"a":"b"}` | `"no variables"` | Extra inputs ignored |
| `template="{topic}", inputs={"topic":"AI"}` | `"AI"` | Simple substitution |
| `template="{a} and {b}", inputs={"a":"X","b":"Y"}` | `"X and Y"` | Multiple variables |
| `template="{topic}", inputs={}` | Throws `PromptTemplateException` | Missing: `[topic]` |
| `template="{a} and {b}", inputs={"a":"X"}` | Throws `PromptTemplateException` | Missing: `[b]` (reports ALL missing) |
| `template="{a} and {b}", inputs={}` | Throws `PromptTemplateException` | Missing: `[a, b]` |
| `template="{{topic}}", inputs={}` | `"{topic}"` | Escaped braces, no substitution |
| `template="{{topic}}", inputs={"topic":"AI"}` | `"{topic}"` | Escaped even when input exists |
| `template="{topic}", inputs={"topic":""}` | `""` | Empty value is valid |
| `template="{topic}", inputs={"topic":null}` | `""` | Null value treated as empty |
| `template="{a}{a}", inputs={"a":"X"}` | `"XX"` | Same variable used multiple times |
| `template="Hello {name}!", inputs=null` | Throws `PromptTemplateException` | Null inputs treated as empty, variable missing |
| `template="{under_score}", inputs={"under_score":"ok"}` | `"ok"` | Underscores allowed in names |

## Where Templates Are Resolved

Templates are resolved in `Ensemble.run(inputs)` BEFORE tasks are passed to the WorkflowExecutor:

```java
// In Ensemble.run(inputs):
List<Task> resolvedTasks = tasks.stream()
    .map(task -> task.toBuilder()
        .description(TemplateResolver.resolve(task.description(), inputs))
        .expectedOutput(TemplateResolver.resolve(task.expectedOutput(), inputs))
        .build())
    .toList();
```

This creates new Task instances with resolved text. The original Task objects are immutable and unchanged.

## Logging

| Level | What |
|---|---|
| DEBUG | `"Resolving template ({length} chars) with {inputCount} variables"` |
| DEBUG | `"Resolved {variableCount} variables in template"` |
