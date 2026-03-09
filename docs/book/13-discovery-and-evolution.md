# Chapter 13: Discovery and Evolution

## Finding What You Need

In a small hotel, everyone knows everyone. Room service knows the kitchen is down the hall.
Maintenance knows procurement is on the second floor. The organizational chart is small
enough to memorize.

In a hotel chain with dozens of properties, you cannot memorize every department in every
hotel. You need a directory. And in a dynamic environment -- where new departments open,
old ones close, and capabilities shift with seasonal demand -- you need a directory that
updates itself.

The Ensemble Network provides three levels of capability discovery, from static to fully
dynamic.

## Level 1: Static Configuration

The simplest approach. You know your ensembles at deployment time and configure connections
explicitly:

```java
NetworkTask.from("kitchen", "prepare-meal")
NetworkTool.from("kitchen", "check-inventory")
NetworkTask.from("procurement", "purchase-parts")
```

The ensemble names resolve via K8s DNS (`kitchen.hotel-downtown.svc.cluster.local`). No
discovery protocol needed. This is sufficient for most single-realm deployments where the
organizational structure is known and stable.

## Level 2: Capability Registration

When ensembles start, they publish their shared capabilities via the capability handshake
protocol. Other ensembles can query the network for providers of a specific capability:

```java
// "Who provides check-inventory?"
NetworkTool.discover("check-inventory")
```

The framework resolves the query at call time: it looks up which ensemble registered the
"check-inventory" shared tool and routes the request there.

Capability registration happens automatically when an ensemble starts (via the
`ensemble_register` wire protocol message). De-registration happens when the ensemble
shuts down or when its K8s readiness probe fails.

This level is useful when:
- Multiple ensembles might provide the same capability (load-balancing across providers)
- The specific provider does not matter to the caller (any kitchen will do)
- Ensembles come and go dynamically (autoscaling, rolling deployments)

## Level 3: Dynamic Tool Catalog

The most powerful form of discovery. An ensemble's available tools are not fixed at build
time -- they are discovered from the network at execution time:

```java
Task.builder()
    .description("Handle any guest request")
    .tools(
        guestDatabaseTool,                  // fixed local tool
        NetworkToolCatalog.all(),            // all tools on the network
        NetworkToolCatalog.tagged("food"))   // tools tagged "food"
    .build();
```

`NetworkToolCatalog.all()` queries the network registry at task execution time (not
at build time) and returns tool descriptions for all shared tools and tasks across all
registered ensembles. The agent's LLM sees them as available tools and decides which to
call based on the task at hand.

This means: **a new ensemble comes online and its tools are immediately available to every
agent on the network**, without restarting or reconfiguring anything.

### Tags

Shared capabilities can be tagged for filtered discovery:

```java
.shareTask("prepare-meal", Task.builder()
    .description("Prepare a meal as specified")
    .build(),
    Tags.of("food", "kitchen"))

.shareTool("check-inventory", inventoryTool,
    Tags.of("food", "inventory"))
```

Tags allow callers to scope discovery. `NetworkToolCatalog.tagged("food")` returns only
food-related capabilities. This prevents an agent from being overwhelmed with irrelevant
tools from every ensemble on the network.

## Why Natural Language Contracts Eliminate Schema Versioning

In a traditional microservice architecture, API evolution is a major engineering challenge.
Changing a field name in a JSON response breaks all clients. Adding a required field breaks
all clients. Teams spend significant effort on API versioning, backward compatibility,
deprecation policies, and migration guides.

The Ensemble Network sidesteps this entirely.

The contract between ensembles is natural language. When maintenance asks procurement to
"order a replacement valve," the contract is not a typed schema -- it is a description of
what is needed and what the response should contain. The LLM on each side interprets the
natural language.

If procurement changes how it phrases its output -- maybe it used to say "Delivery expected
Thursday" and now says "Estimated arrival: Thursday PM" -- maintenance's agent interprets
the new phrasing without any code change. The LLM is the compatibility layer.

### When Does This Break?

Natural language contracts have limits:

**Semantic changes**: If "purchase-parts" changes from "buy parts" to "request quotes for
parts" (different behavior, same name), callers expecting purchased parts will be confused.
The convention is to use a new task name for semantically different behavior. "Purchase-
parts" and "request-quotes" are different tasks.

**Structured output changes**: If a caller expects a `PurchaseOrder.class` structured
output and procurement changes the field names, Jackson deserialization may fail. The
mitigation is `@JsonIgnoreProperties(ignoreUnknown = true)` for forward compatibility,
and treating structured output as optional (the natural language output is always
available).

**Capability removal**: If procurement stops offering "purchase-parts" entirely, callers
get a "capability not found" error. This is handled by the error handling mechanisms in
Chapter 9 (fallback, circuit breaker).

For the vast majority of changes -- rewording, adding detail, changing internal process,
improving output quality -- natural language contracts are completely transparent. This is
a genuine advantage of AI-native systems over traditional typed-API systems.

## Evolution Without Coordination

The most powerful consequence of natural language contracts and dynamic discovery is that
ensembles can evolve independently.

The kitchen can:
- Change its internal process (switch from sequential to parallel meal preparation)
- Upgrade its LLM (from GPT-4 to Claude)
- Add new shared tools (expose a "daily-specials" tool)
- Improve its output quality (more detailed preparation notes)

None of these changes require any modification to room service, maintenance, or any other
ensemble. The kitchen's callers send the same natural language requests and interpret the
natural language responses. Internal changes are invisible. New capabilities appear
automatically via the capability registration protocol.

This is the microservice independence property -- services evolve independently as long as
they respect their contracts -- but with a much weaker contract requirement. Natural
language is a far more flexible contract than a typed JSON schema.

The hotel does not shut down for renovations of the kitchen. The kitchen renovates while
continuing to serve meals. The rest of the hotel does not notice.
