# 23. Typed Tool Input System

## Overview

AgentEnsemble 1.x introduced `AgentTool`, which accepts and returns plain `String` values. While simple, this leads to *stringly-typed* interfaces: each tool must parse its input manually, the LLM receives an opaque single-parameter schema, and validation errors are tool-specific and inconsistent.

The typed tool input system (introduced alongside the existing string-based API) addresses these problems with **zero breaking changes** by introducing `TypedAgentTool<T>` — an opt-in extension that lets tool authors declare a Java record as the tool's input type. The framework handles schema generation, JSON deserialization, and required-field validation automatically.

---

## Problem Statement

With the legacy string-based API, every tool that accepts multiple parameters must:

1. Embed format instructions in the tool description ("Input: a JSON object with `url` and `content` fields")
2. Parse the input string manually (Jackson, regex, delimiter splitting, etc.)
3. Validate each field individually with ad-hoc error messages
4. Maintain the parsing/validation code as the parameters evolve

The LLM receives a schema with a single `"input": string` parameter, with no per-field type information or descriptions. The LLM must infer the expected format from the prose description — a fragile contract.

---

## Design

### New Types

```
net.agentensemble.tool
  @ToolInput            -- annotation on input record classes
  @ToolParam            -- annotation on record components
  TypedAgentTool<T>     -- interface extending AgentTool
  AbstractTypedAgentTool<T>  -- base class extending AbstractAgentTool
  ToolSchemaGenerator   -- record introspection -> JsonObjectSchema
  ToolInputDeserializer -- JSON string -> record instance
```

### TypedAgentTool\<T\> Interface

```java
public interface TypedAgentTool<T> extends AgentTool {
    Class<T> inputType();
    ToolResult execute(T input);
}
```

Extends `AgentTool`. The additional methods are:

- `inputType()` — returns the Class of the input record `T`, used for schema generation and deserialization.
- `execute(T)` — the typed business-logic method, called after deserialization.

The `AgentTool.execute(String)` method is satisfied by `AbstractAgentTool.execute(String)` (final, provides metrics), which calls `AbstractTypedAgentTool.doExecute(String)`, which deserializes and delegates to `execute(T)`.

### AbstractTypedAgentTool\<T\>

```java
public abstract class AbstractTypedAgentTool<T>
        extends AbstractAgentTool implements TypedAgentTool<T> {

    @Override
    protected final ToolResult doExecute(String argumentsJson) {
        T typedInput = ToolInputDeserializer.deserialize(argumentsJson, inputType());
        return execute(typedInput);
    }
}
```

Provides the String-to-T bridge. `doExecute` is `final` to preserve the deserialization contract. Subclasses implement `execute(T)`.

### Execution Flow

```
LLM JSON args -> LangChain4jToolAdapter.executeForResult()
              -> (TypedAgentTool path) tool.execute(fullJson)
              -> AbstractAgentTool.execute(String) [final, metrics]
              -> AbstractTypedAgentTool.doExecute(String)
              -> ToolInputDeserializer.deserialize(json, T.class)
              -> tool.execute(T)
```

### Schema Generation

`ToolSchemaGenerator.generateSchema(Class<T>)` introspects a record class via `Class.getRecordComponents()` and maps Java types to LangChain4j JSON Schema elements:

| Java Type | JSON Schema Type |
|-----------|-----------------|
| `String` | `string` |
| `int`, `Integer`, `long`, `Long`, `short`, `Short`, `byte`, `Byte` | `integer` |
| `double`, `Double`, `float`, `Float`, `BigDecimal`, `Number` | `number` |
| `boolean`, `Boolean` | `boolean` |
| Enum subclasses | `enum` (with values from `Enum.values()`) |
| `List<T>`, `Collection<T>`, `T[]` | `array` (items typed if T is known) |
| `Map<K,V>` and other objects | `object` (open schema) |

Components without `@ToolParam` or annotated with `@ToolParam(required = true)` appear in the schema's `required` array. Components annotated with `@ToolParam(required = false)` are optional.

### Deserialization and Validation

`ToolInputDeserializer.deserialize(String json, Class<T>)`:

1. Parses the JSON string using Jackson with `FAIL_ON_UNKNOWN_PROPERTIES = false` (extra fields from the LLM are silently ignored).
2. Validates that all required fields are present and non-null. Missing required fields produce a clear `IllegalArgumentException` listing all absent parameter names.
3. Deserializes via `ObjectMapper.treeToValue(root, T.class)`. Jackson 2.17+ has native record support.
4. Returns the typed record instance.

Deserialization failures are `IllegalArgumentException`s, which `AbstractAgentTool.execute()` catches and converts to `ToolResult.failure(message)`. The LLM receives a clear error message and can retry with correct parameters.

### Adapter Changes

`LangChain4jToolAdapter.toSpecification(AgentTool)` branches on whether the tool is a `TypedAgentTool`:

- **Typed**: calls `ToolSchemaGenerator.generateSchema(tool.inputType())` — produces multi-parameter schema.
- **Legacy**: produces the original single `"input": string` schema (unchanged behavior).

`LangChain4jToolAdapter.executeForResult(AgentTool, String argumentsJson)` also branches:

- **Typed**: passes the full `argumentsJson` to `tool.execute(String)` — all parameters are at top level.
- **Legacy**: extracts just the `"input"` key value from `argumentsJson` (unchanged behavior).

---

## Usage

### Defining a Typed Tool

```java
@ToolInput(description = "Parameters for writing a file")
public record FileWriteInput(
    @ToolParam(description = "Relative file path within the sandbox directory") String path,
    @ToolParam(description = "Text content to write to the file") String content
) {}

public final class FileWriteTool extends AbstractTypedAgentTool<FileWriteInput> {

    private final Path baseDir;

    @Override public String name() { return "file_write"; }

    @Override
    public String description() {
        return "Writes content to a file within a sandboxed directory.";
    }

    @Override
    public Class<FileWriteInput> inputType() { return FileWriteInput.class; }

    @Override
    public ToolResult execute(FileWriteInput input) {
        // input.path() and input.content() are already typed -- no parsing needed
        Path target = baseDir.resolve(input.path());
        Files.writeString(target, input.content());
        return ToolResult.success("Written: " + input.path());
    }
}
```

### What the LLM Receives

**Before (legacy):**
```json
{
  "name": "file_write",
  "description": "Writes content to a file. Input: JSON with 'path' and 'content' fields...",
  "parameters": { "input": { "type": "string" } }
}
```

**After (typed):**
```json
{
  "name": "file_write",
  "description": "Writes content to a file within a sandboxed directory.",
  "parameters": {
    "path":    { "type": "string", "description": "Relative file path within the sandbox directory" },
    "content": { "type": "string", "description": "Text content to write to the file" }
  },
  "required": ["path", "content"]
}
```

---

## When to Use Each Style

### Use `AbstractTypedAgentTool<T>` when:

- The tool accepts multiple distinct parameters
- Parameter names, types, and descriptions should be visible to the LLM in the schema
- Consistent validation and clear error messages matter
- The input cannot be naturally expressed as a single domain-specific string

### Keep `AbstractAgentTool` (legacy) when:

- The input is a single, natural domain-specific string — a math expression, a date command, a command-line invocation, a payload to forward to a remote endpoint
- Wrapping in a one-field record would add boilerplate without improving clarity for tool authors or the LLM

**Examples of intentional legacy tools in this codebase:**

- `CalculatorTool` — input is a math expression such as `"2 + 3 * 4"`. There is exactly one meaningful parameter.
- `DateTimeTool` — input is a command such as `"now in America/New_York"` or `"2024-01-01 + 5 days"`. The command language is a compact DSL.
- `HttpAgentTool` — input is the payload string forwarded to a configured remote endpoint. The URL and method are configured at construction time, not passed as parameters.
- `ProcessAgentTool` — input is the string sent to a subprocess via stdin. The command is configured at construction time.

---

## Backward Compatibility

- `AgentTool` interface is **unchanged**. The `execute(String)` contract is preserved.
- `AbstractAgentTool` is **unchanged**. Existing tools compile and run without modification.
- `LangChain4jToolAdapter` falls back to the original single-`"input"` schema for any tool that does not implement `TypedAgentTool`.
- External consumers' custom tools require zero changes.
- `ToolPipeline` works with typed steps: if a step is a `TypedAgentTool`, its `doExecute(String)` bridge deserializes the input. When chaining typed steps, use adapters (`.adapter(result -> ...)`) to convert the previous step's string output to the expected JSON format.

---

## Built-in Tools Migration Summary

| Tool | Style | Input Type |
|------|-------|-----------|
| `FileReadTool` | `AbstractTypedAgentTool` | `FileReadInput(path)` |
| `FileWriteTool` | `AbstractTypedAgentTool` | `FileWriteInput(path, content)` |
| `JsonParserTool` | `AbstractTypedAgentTool` | `JsonParserInput(jsonPath, json)` |
| `WebSearchTool` | `AbstractTypedAgentTool` | `WebSearchInput(query)` |
| `WebScraperTool` | `AbstractTypedAgentTool` | `WebScraperInput(url)` |
| `CalculatorTool` | `AbstractAgentTool` (legacy) | `String` expression |
| `DateTimeTool` | `AbstractAgentTool` (legacy) | `String` command |
| `HttpAgentTool` | `AbstractAgentTool` (legacy) | `String` payload |
| `ProcessAgentTool` | `AbstractAgentTool` (legacy) | `String` input |

---

## Key Classes

| Class | Module | Purpose |
|-------|--------|---------|
| `@ToolInput` | `agentensemble-core` | Annotation for input record types |
| `@ToolParam` | `agentensemble-core` | Annotation for record components |
| `TypedAgentTool<T>` | `agentensemble-core` | Interface for typed tools |
| `AbstractTypedAgentTool<T>` | `agentensemble-core` | Base class providing deserialization bridge |
| `ToolSchemaGenerator` | `agentensemble-core` | Record introspection -> `JsonObjectSchema` |
| `ToolInputDeserializer` | `agentensemble-core` | JSON string -> typed record |
| `LangChain4jToolAdapter` | `agentensemble-core` | Updated to detect and handle typed tools |
