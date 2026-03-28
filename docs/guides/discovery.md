# Discovery

AgentEnsemble v4.0 introduces dynamic capability discovery on the ensemble network.
Ensembles advertise their shared tasks and tools with optional tags, and other ensembles
can discover providers at runtime without hardcoding names or URLs.

---

## Quick Start

```java
// Kitchen shares a tool with tags
Ensemble kitchen = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))
    .shareTool("check-inventory", inventoryTool, "food", "stock")
    .shareTask("prepare-meal", mealTask, "food", "cooking")
    .webDashboard(WebDashboard.builder().port(7329).build())
    .build();

kitchen.start(7329);

// Room service discovers tools dynamically
NetworkTool inventoryCheck = NetworkTool.discover("check-inventory", registry);
```

---

## Tag Support

Tags classify shared capabilities for filtered discovery. Pass tags as additional
arguments to `shareTask()` and `shareTool()`:

```java
Ensemble kitchen = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))
    .shareTask("prepare-meal", mealTask, "food", "cooking")
    .shareTool("check-inventory", inventoryTool, "food", "stock")
    .shareTool("dietary-check", allergyTool, "food", "safety")
    .build();
```

Tags are included in the `SharedCapabilityInfo` sent during the HelloMessage handshake
and indexed by the `CapabilityRegistry` for fast lookup.

---

## NetworkTool.discover()

Find any tool provider on the network by capability name:

```java
// Discover a tool -- no need to know which ensemble provides it
NetworkTool tool = NetworkTool.discover("check-inventory", clientRegistry);
```

`discover()` queries the `CapabilityRegistry` for the first ensemble that provides
the named tool and returns a `NetworkTool` bound to that provider. Throws
`IllegalStateException` if no provider is found.

---

## NetworkToolCatalog

`NetworkToolCatalog` is a `DynamicToolProvider` that resolves network tools at task
execution time. Place it into `Task.builder().tools()` alongside regular tools.

### All tools

```java
// Make every tool on the network available to the agent
Task task = Task.builder()
    .description("Handle guest request")
    .tools(NetworkToolCatalog.all(clientRegistry))
    .build();
```

### Filtered by tag

```java
// Only food-related tools
Task task = Task.builder()
    .description("Handle room service order")
    .tools(NetworkToolCatalog.tagged("food", clientRegistry))
    .build();
```

### How it works

On each task execution, the catalog's `resolve()` method queries the `CapabilityRegistry`
for all `TOOL`-type capabilities (optionally filtered by tag) and returns a fresh list
of `NetworkTool` instances. New ensembles that come online between executions are
immediately discoverable.

---

## DynamicToolProvider

`NetworkToolCatalog` implements the `DynamicToolProvider` interface from the core module:

```java
public interface DynamicToolProvider {
    List<AgentTool> resolve();
}
```

The framework's `ToolResolver` expands `DynamicToolProvider` instances at execution time
(not at build time). This means the set of available tools can change between executions
as the network topology evolves.

You can implement `DynamicToolProvider` for custom discovery strategies:

```java
public class MyCustomCatalog implements DynamicToolProvider {
    @Override
    public List<AgentTool> resolve() {
        // Return tools based on custom logic
    }
}
```

---

## CapabilityRegistry

The `CapabilityRegistry` maintains a thread-safe index of all shared capabilities
discovered across the network. It is populated automatically during the WebSocket
connection handshake.

### Automatic registration

When an ensemble connects to another ensemble, the server sends a `HelloMessage`
containing its `sharedCapabilities`. The client-side registry processes this list and
builds inverted indices for fast lookup by name and tag.

### Lookup methods

```java
CapabilityRegistry registry = clientRegistry.getCapabilityRegistry();

// Find any provider of a capability
Optional<String> provider = registry.findProvider("check-inventory");

// Find all providers
List<String> providers = registry.findAllProviders("check-inventory");

// Find capabilities by tag
List<SharedCapabilityInfo> foodCaps = registry.findByTag("food");

// List all capabilities on the network
List<SharedCapabilityInfo> all = registry.all();

// Total capability count
int total = registry.size();
```

### Unregistration

When an ensemble disconnects, its capabilities are removed from the registry:

```java
registry.unregister("kitchen");
```

---

## Wire Protocol

Discovery uses two message types on the wire:

### `capability_query`

Sent by a client to discover providers of a named capability:

```json
{
  "type": "capability_query",
  "capabilityName": "check-inventory",
  "tag": "food"
}
```

### `capability_response`

Returned by the server with matching providers:

```json
{
  "type": "capability_response",
  "capabilities": [
    {
      "name": "check-inventory",
      "description": "Check current inventory levels",
      "type": "TOOL",
      "tags": ["food", "stock"]
    }
  ]
}
```

The `HelloMessage` handshake provides the initial capability set. The query/response
protocol enables on-demand discovery after connection.

---

## Related

- [Cross-Ensemble Delegation](cross-ensemble-delegation.md)
- [Discovery Catalog Example](../examples/discovery-catalog.md)
