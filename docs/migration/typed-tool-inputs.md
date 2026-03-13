# Migration: Typed Tool Inputs

This document describes the typed tool input system introduced alongside the existing
string-based `AgentTool` API. This is a **fully additive, backward-compatible change** --
no existing code needs to change.

---

## What Changed

A new opt-in extension allows tools to declare a Java record as their input type:

- `@ToolInput` -- optional annotation on input record classes
- `@ToolParam` -- annotation on record components (description, required)
- `TypedAgentTool<T>` -- interface extending `AgentTool`
- `AbstractTypedAgentTool<T>` -- base class extending `AbstractAgentTool`

The framework generates typed JSON Schema for the LLM and handles deserialization automatically.

---

## What Did NOT Change

- `AgentTool` interface is **unchanged**
- `AbstractAgentTool` is **unchanged**
- `LangChain4jToolAdapter` falls back to the original single-`"input"` schema for any tool that does not implement `TypedAgentTool`
- Existing tool implementations compile and run identically
- No configuration changes required

---

## Built-in Tool Input Format Changes

Five built-in tools were migrated from the legacy string-based format to typed records.
If you are constructing inputs for these tools programmatically (e.g., in integration tests
or when calling `execute(String)` directly), update the input format:

| Tool | Old format | New format |
|------|-----------|-----------|
| `FileReadTool` | `"report.txt"` | `{"path": "report.txt"}` |
| `FileWriteTool` | `{"path": "out.txt", "content": "..."}` | `{"path": "out.txt", "content": "..."}` (unchanged) |
| `JsonParserTool` | `"user.name\n{\"user\": ...}"` | `{"jsonPath": "user.name", "json": "{\"user\": ...}"}` |
| `WebSearchTool` | `"Java 21 virtual threads"` | `{"query": "Java 21 virtual threads"}` |
| `WebScraperTool` | `"https://example.com"` | `{"url": "https://example.com"}` |

Note: For `FileWriteTool`, the JSON format was already in use (the migration only removed the
manual Jackson parsing inside the tool). For LLM use, the tool schema now exposes `path` and
`content` as individual typed parameters rather than a single `input` string — so LLM prompts
do not need to instruct the model to format the input as JSON.

---

## Migrating Your Own Tools (Optional)

Migrating a custom tool to the typed system is entirely optional. If you choose to migrate:

**Before:**

```java
public class MyTool extends AbstractAgentTool {
    @Override
    public String name() { return "my_tool"; }

    @Override
    public String description() {
        return "Does something. Input: JSON with 'fieldA' and 'fieldB'.";
    }

    @Override
    protected ToolResult doExecute(String input) {
        JsonNode node = MAPPER.readTree(input);
        String fieldA = node.get("fieldA").asText();
        String fieldB = node.get("fieldB").asText();
        // ...
    }
}
```

**After:**

```java
@ToolInput
public record MyToolInput(
    @ToolParam(description = "Description of field A") String fieldA,
    @ToolParam(description = "Description of field B") String fieldB
) {}

public class MyTool extends AbstractTypedAgentTool<MyToolInput> {
    @Override
    public String name() { return "my_tool"; }

    @Override
    public String description() {
        return "Does something."; // Parameters self-document via schema
    }

    @Override
    public Class<MyToolInput> inputType() { return MyToolInput.class; }

    @Override
    public ToolResult execute(MyToolInput input) {
        // input.fieldA() and input.fieldB() are already typed
        // ...
    }
}
```

See [Typed Tool Inputs guide](../guides/tools.md) for full documentation.
