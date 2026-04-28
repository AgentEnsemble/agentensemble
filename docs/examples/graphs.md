# Graphs Examples

Two runnable examples demonstrate the `Graph` state-machine workflow construct using
deterministic handler tasks (no LLM required, completely offline). The same patterns
work with AI-backed tasks — replace the handlers with descriptions and provide a
`chatLanguageModel(...)` on the ensemble.

## Tool router with back-edges

The headline LangGraph-on-the-JVM pattern: an `analyze` state inspects input and
routes to one of two tool states; each tool returns to `analyze`; eventually
`analyze` terminates. Demonstrates conditional edges, unconditional fallback,
back-edges, and the per-step `onGraphStateCompleted` callback.

```bash
./gradlew :agentensemble-examples:runGraphRouter
```

Source: [`GraphRouterExample.java`](https://github.com/your-org/agentensemble/blob/main/agentensemble-examples/src/main/java/net/agentensemble/examples/GraphRouterExample.java).

Sample output (truncated):

```
Step 1/20: analyze → toolA
Step 2/20: toolA → analyze
Step 3/20: analyze → toolB
Step 4/20: toolB → analyze
Step 5/20: analyze → toolA
Step 6/20: toolA → analyze
Step 7/20: analyze → __END__
Termination: terminal
Total steps: 7
Path: [analyze, toolA, analyze, toolB, analyze, toolA, analyze]
```

## Selective feedback (the LangGraph killer pattern)

A quality-gated publishing pipeline: `research → write → critique → publish`,
where `critique` can route back to `write` on `REJECT` without re-running
`research`. Demonstrates the pattern `Loop` cannot cleanly express — back-edges
that target a specific upstream state rather than re-iterating the whole body.

```bash
./gradlew :agentensemble-examples:runGraphRetryWithFallback
```

Source: [`GraphRetryWithFallbackExample.java`](https://github.com/your-org/agentensemble/blob/main/agentensemble-examples/src/main/java/net/agentensemble/examples/GraphRetryWithFallbackExample.java).

Notice in the output that `research` runs exactly once even though `write` runs
three times — the back-edge from `critique` targets `write` specifically, not
`research`.

## See also

- [Graphs guide](../guides/graphs.md) — full reference for routing, validation,
  termination, state revisits, and visualisation.
- [Loops examples](loops.md) — bounded iteration patterns; useful for the simpler
  reflection / retry-until-valid use cases that don't need state-machine routing.
