# Typed Tool Inputs

This example demonstrates `AbstractTypedAgentTool<T>`, which lets you declare a Java record
as the tool's input type. The framework generates a typed JSON Schema for the LLM,
deserializes the JSON arguments, and validates required fields automatically.

---

## Custom Typed Tool: Address Lookup

```java
@ToolInput(description = "Parameters for looking up location details")
public record AddressLookupInput(
    @ToolParam(description = "Street address, city, or place name to look up") String address,
    @ToolParam(description = "Maximum number of results to return", required = false) Integer maxResults,
    @ToolParam(description = "Country code to restrict results (e.g. 'US', 'DE')", required = false) String countryCode
) {}

public final class AddressLookupTool extends AbstractTypedAgentTool<AddressLookupInput> {

    private final GeocodingClient geocoder;

    public AddressLookupTool(GeocodingClient geocoder) {
        this.geocoder = geocoder;
    }

    @Override
    public String name() { return "address_lookup"; }

    @Override
    public String description() {
        return "Looks up geographic details for a street address or place name.";
    }

    @Override
    public Class<AddressLookupInput> inputType() { return AddressLookupInput.class; }

    @Override
    public ToolResult execute(AddressLookupInput input) {
        int limit = (input.maxResults() != null) ? input.maxResults() : 5;
        List<Location> results = geocoder.lookup(input.address(), input.countryCode(), limit);

        if (results.isEmpty()) {
            return ToolResult.failure("No results found for: " + input.address());
        }

        String output = results.stream()
            .map(loc -> loc.lat() + ", " + loc.lon() + " -- " + loc.displayName())
            .collect(Collectors.joining("\n"));

        return ToolResult.success(output, results);
    }
}
```

### What the LLM Sees

```json
{
  "name": "address_lookup",
  "description": "Looks up geographic details for a street address or place name.",
  "parameters": {
    "address":     { "type": "string",  "description": "Street address, city, or place name to look up" },
    "maxResults":  { "type": "integer", "description": "Maximum number of results to return" },
    "countryCode": { "type": "string",  "description": "Country code to restrict results (e.g. 'US', 'DE')" }
  },
  "required": ["address"]
}
```

---

## Using Enum Parameters

```java
public enum SortOrder { ASCENDING, DESCENDING }

@ToolInput(description = "Search parameters")
public record ProductSearchInput(
    @ToolParam(description = "Search query") String query,
    @ToolParam(description = "Category to filter by", required = false) String category,
    @ToolParam(description = "Sort order for results", required = false) SortOrder sortOrder,
    @ToolParam(description = "Maximum price in USD", required = false) Double maxPrice
) {}

public final class ProductSearchTool extends AbstractTypedAgentTool<ProductSearchInput> {

    @Override
    public String name() { return "product_search"; }

    @Override
    public String description() { return "Searches the product catalog."; }

    @Override
    public Class<ProductSearchInput> inputType() { return ProductSearchInput.class; }

    @Override
    public ToolResult execute(ProductSearchInput input) {
        // All fields are typed -- no parsing, no null-safe casting
        List<Product> results = catalog.search(
            input.query(),
            input.category(),
            input.sortOrder() != null ? input.sortOrder() : SortOrder.ASCENDING,
            input.maxPrice()
        );
        return ToolResult.success(formatResults(results));
    }
}
```

The LLM receives enum values as a constrained list:

```json
"sortOrder": { "type": "string", "enum": ["ASCENDING", "DESCENDING"] }
```

---

## Comparing Typed vs. String-Based

The same tool written both ways:

### String-based (legacy)

```java
public class FileWriteTool extends AbstractAgentTool {

    @Override
    public String name() { return "file_write"; }

    @Override
    public String description() {
        // Description must explain the input format
        return "Writes content to a file. Input: a JSON object with 'path' "
             + "(relative file path) and 'content' (text to write) fields. "
             + "Example: {\"path\": \"output.txt\", \"content\": \"Hello!\"}";
    }

    @Override
    protected ToolResult doExecute(String input) {
        // Manual JSON parsing
        JsonNode node = OBJECT_MAPPER.readTree(input.trim());
        JsonNode pathNode = node.get("path");
        JsonNode contentNode = node.get("content");
        if (pathNode == null || pathNode.isNull() || pathNode.asText().isBlank()) {
            return ToolResult.failure("Missing required field 'path'");
        }
        if (contentNode == null || contentNode.isNull()) {
            return ToolResult.failure("Missing required field 'content'");
        }
        String path = pathNode.asText().trim();
        String content = contentNode.asText();
        // ... write logic
    }
}
```

### Typed (modern)

```java
@ToolInput(description = "File write parameters")
public record FileWriteInput(
    @ToolParam(description = "Relative file path") String path,
    @ToolParam(description = "Text content to write") String content
) {}

public class FileWriteTool extends AbstractTypedAgentTool<FileWriteInput> {

    @Override
    public String name() { return "file_write"; }

    @Override
    public String description() {
        // Description focuses on what the tool does -- parameters self-document
        return "Writes content to a file within a sandboxed directory.";
    }

    @Override
    public Class<FileWriteInput> inputType() { return FileWriteInput.class; }

    @Override
    public ToolResult execute(FileWriteInput input) {
        // No parsing needed -- framework handles it
        // ... write logic using input.path() and input.content()
    }
}
```

---

## When to Use Each Style

### Use `AbstractTypedAgentTool<T>` when:

- The tool takes multiple parameters
- Named parameters with types and descriptions improve LLM accuracy
- Consistent validation and clear error messages matter

### Keep `AbstractAgentTool` (string-based) when:

- The input is a single, natural expression or command -- a math formula, a date command, a raw payload to forward
- Wrapping in a one-field record would not improve clarity for tool authors or the LLM

See also: [CalculatorTool](../../agentensemble-tools/calculator) and [DateTimeTool](../../agentensemble-tools/datetime) as intentional examples of the string-based style.

---

## Running the Example

See `TypedToolsExample` in `agentensemble-examples` for a complete runnable demonstration.

```shell
./gradlew :agentensemble-examples:typed-tools
```
