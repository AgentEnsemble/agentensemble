# Discovery & Network Tool Catalog

This example demonstrates capability-based discovery: tagging shared tasks and tools,
finding providers without knowing their ensemble name, and resolving all available
network tools dynamically at execution time.

---

## Tagging Shared Capabilities

When an ensemble shares a task or tool, attach tags for category-based discovery.

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;

Ensemble kitchen = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))
    .shareTask("prepare-meal", Task.builder()
        .description("Prepare a meal as specified by the guest")
        .expectedOutput("Confirmation with preparation details")
        .build(), "food", "kitchen")
    .shareTool("check-inventory", inventoryTool, "food")
    .shareTool("clean-kitchen", cleaningTool, "cleaning")
    .webDashboard(dashboard)
    .build();

kitchen.start(7329);
```

Tags are string varargs -- add as many as needed. They are advertised to the network
alongside the capability name and type.

---

## Discovering a Tool by Name

Use `NetworkTool.discover()` to find any provider of a named tool without specifying
which ensemble hosts it.

```java
import net.agentensemble.network.NetworkConfig;
import net.agentensemble.network.NetworkClientRegistry;
import net.agentensemble.network.NetworkTool;

NetworkConfig config = NetworkConfig.builder()
    .ensemble("kitchen", "ws://kitchen:7329/ws")
    .ensemble("maintenance", "ws://maintenance:7330/ws")
    .build();

NetworkClientRegistry registry = new NetworkClientRegistry(config);

// Find whichever ensemble provides "check-inventory"
NetworkTool inventoryCheck = NetworkTool.discover("check-inventory", registry);

EnsembleOutput result = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Check if we have wagyu beef in stock")
        .tools(inventoryCheck)
        .build())
    .build()
    .run();

registry.close();
```

`discover()` searches the capability registry and returns a `NetworkTool` bound to the
first provider found. If no provider exists, it throws `IllegalStateException`.

---

## All Network Tools in a Task

`NetworkToolCatalog.all()` is a `DynamicToolProvider` that resolves every TOOL capability
on the network at execution time. New ensembles that come online between task runs are
picked up automatically.

```java
import net.agentensemble.network.NetworkToolCatalog;

EnsembleOutput result = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("The guest in room 403 needs extra towels and a club sandwich")
        .tools(NetworkToolCatalog.all(registry))
        .build())
    .build()
    .run();
```

The agent sees every shared tool across all ensembles and picks the right ones.

---

## Filtered by Tag

`NetworkToolCatalog.tagged()` resolves only tools matching a specific tag.

```java
EnsembleOutput result = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Prepare a dinner menu for tonight's event")
        .tools(NetworkToolCatalog.tagged("food", registry))
        .build())
    .build()
    .run();
```

Only tools tagged `"food"` are visible to the agent -- `check-inventory` appears,
but `clean-kitchen` (tagged `"cleaning"`) does not.

---

## Dynamic Appearance

Because `NetworkToolCatalog` resolves tools fresh on every task execution, new ensembles
are immediately available without restarts.

```java
NetworkToolCatalog catalog = NetworkToolCatalog.all(registry);

// First run -- only kitchen tools are available
Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("List all available tools")
        .tools(catalog)
        .build())
    .build()
    .run();

// A maintenance ensemble comes online and registers capabilities
// (no code change needed on the consumer side)

// Second run -- kitchen AND maintenance tools are available
Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("List all available tools")
        .tools(catalog)
        .build())
    .build()
    .run();
```

The same `catalog` instance picks up the newly registered maintenance tools on
the second run. Similarly, if an ensemble goes offline and unregisters, its tools
disappear from subsequent resolutions.

---

## Related

- [Cross-Ensemble Delegation Example](cross-ensemble-delegation.md)
- [Shared Memory Consistency Example](shared-memory-consistency.md)
- [Federation Example](federation.md)
