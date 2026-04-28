# Loops Examples

Two runnable examples demonstrate the `Loop` workflow construct using deterministic
handler tasks (no LLM required, completely offline). The same patterns work with
AI-backed tasks — replace the handlers with descriptions and provide a
`chatLanguageModel(...)` on the ensemble.

## Reflection loop (writer + critic)

A writer drafts content; a critic reviews; the loop repeats until the critic
approves or hits the iteration cap. Default config: `LAST_ITERATION` output,
`ACCUMULATE` memory, `RETURN_LAST` on max iterations.

```bash
./gradlew :agentensemble-examples:runLoopReflection
```

Source: [`LoopReflectionExample.java`](https://github.com/your-org/agentensemble/blob/main/agentensemble-examples/src/main/java/net/agentensemble/examples/LoopReflectionExample.java).

What it shows:

- Multi-task loop body that repeats until a predicate fires.
- Reading the per-iteration history from `EnsembleOutput.getLoopHistory(...)`.
- `LoopOutputMode.LAST_ITERATION` (default) projecting only the final iteration's
  outputs to the rest of the ensemble.
- `MaxIterationsAction.RETURN_LAST` returning the last attempt as the final
  output if the loop never converges.

Sample output (truncated):

```
Loop 'reflection' stopped by predicate after iteration 3/5
Loop ran 3 iteration(s)
Termination reason: predicate
Final draft: Draft attempt #3 on edge AI inference.
Final verdict: APPROVED
```

## Retry-until-valid (generator + validator)

A generator produces output; a validator decides if it's acceptable; the loop
repeats until valid or aborts. Two scenarios:

- **Convergence**: the validator passes on iteration 3; the loop terminates via
  the predicate.
- **Non-convergence**: the validator never passes; `MaxLoopIterationsExceededException`
  is thrown because `onMaxIterations(THROW)` is configured.

```bash
./gradlew :agentensemble-examples:runLoopRetryUntilValid
```

Source: [`LoopRetryUntilValidExample.java`](https://github.com/your-org/agentensemble/blob/main/agentensemble-examples/src/main/java/net/agentensemble/examples/LoopRetryUntilValidExample.java).

What it shows:

- `MaxIterationsAction.THROW` for "non-convergence is a hard error" semantics.
- The contrast with the reflection example: when never-converged means broken
  output rather than degraded output.
- Body tasks declaring intra-body `context()` deps so the validator sees the
  generator's output.

## See also

- [Loops guide](../guides/loops.md) — full reference for stop conditions, output
  modes, memory modes, and validation rules.
- [Phase Review examples](phase-review.md) — bounded one-step retry within a
  phase, contrasted with the multi-iteration loop pattern.
