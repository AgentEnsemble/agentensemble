# Human Directives and Control Plane Directives

AgentEnsemble v3.0.0 supports **human directives** that inject non-blocking guidance into
an ensemble's context at runtime, and **control plane directives** that modify ensemble
behavior (e.g., switching LLM models).

---

## Human Directives

Human directives are text-based guidance injected into agent prompts for future task
executions. They do not interrupt in-flight tasks.

### Sending a Directive (Dashboard)

Connected dashboard users can send directives via the directive panel. The directive
is stored in the ensemble's `DirectiveStore` and injected into agent prompts as an
`## Active Directives` section.

### Sending a Directive (Wire Protocol)

```json
{
  "type": "directive",
  "to": "room-service",
  "from": "manager:human",
  "content": "Guest in 801 is VIP, prioritize all their requests"
}
```

### Directive Expiration (TTL)

Directives can include an optional TTL. When the TTL expires, the directive is
automatically removed from the store and no longer injected into prompts.

```json
{
  "type": "directive",
  "to": "kitchen",
  "from": "manager:human",
  "content": "Use premium ingredients for VIP orders",
  "ttl": "PT4H"
}
```

### How Directives Appear in Prompts

Active directives are injected as a section in the agent's user prompt:

```
## Active Directives
The following directives from human operators are currently active. Factor them
into your reasoning:

---
[manager:human | 2026-03-28T10:30:00Z] Guest in 801 is VIP, prioritize all their requests
---
```

---

## Control Plane Directives

Control plane directives modify ensemble behavior at runtime without restarting.

### SET_MODEL_TIER

Switches the ensemble between its primary and fallback LLM models:

```java
Ensemble.builder()
    .chatLanguageModel(gpt4)         // primary
    .fallbackModel(gpt4Mini)         // cheaper fallback
    .build();
```

Wire protocol message:
```json
{
  "type": "directive",
  "to": "kitchen",
  "from": "cost-policy:automated",
  "action": "SET_MODEL_TIER",
  "value": "FALLBACK"
}
```

The switch applies to new tasks only; in-flight tasks continue with their current model.

### APPLY_PROFILE

Applies a named operational profile:

```json
{
  "type": "directive",
  "to": "*",
  "action": "APPLY_PROFILE",
  "value": "weekend-mode"
}
```

### Auto-Directive Rules

Rules that automatically fire directives based on execution metrics:

```java
Ensemble.builder()
    .chatLanguageModel(gpt4)
    .fallbackModel(gpt4Mini)
    .autoDirectiveRule(new AutoDirectiveRule(
        "cost-ceiling",
        metrics -> metrics.getLlmTokensOut() > 100_000,
        new Directive(UUID.randomUUID().toString(), "cost-policy:automated",
            null, "SET_MODEL_TIER", "FALLBACK", Instant.now(), null)))
    .build();
```

### Custom Directive Handlers

Register custom handlers for application-specific actions:

```java
ensemble.getDirectiveDispatcher().registerHandler("SCALE_UP", (directive, ens) -> {
    int replicas = Integer.parseInt(directive.value());
    kubernetesClient.scale(ens.getName(), replicas);
});
```
