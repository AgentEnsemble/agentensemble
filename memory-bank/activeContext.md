# Active Context

## Current Focus

Branch `feature/rate-limiting` is open for Issue #59: Rate Limiting at Task/Agent/Ensemble levels.

**Implementation complete and ready for PR:**

### What was built

- `net.agentensemble.ratelimit.RateLimit` -- immutable value object. Factory methods:
  `of(int, Duration)`, `perMinute(int)`, `perSecond(int)`.
- `net.agentensemble.ratelimit.RateLimitedChatModel` -- decorator implementing `ChatModel`.
  Token-bucket algorithm using `ReentrantLock + Condition`. Thread-safe. Configurable
  `waitTimeout` (default 30s). `of(delegate, rateLimit)` / `of(delegate, rateLimit, timeout)`.
- `net.agentensemble.ratelimit.RateLimitTimeoutException` -- thrown when wait exceeds timeout.
  Extends `AgentEnsembleException`. Carries `rateLimit` and `waitTimeout`.

### Builder conveniences (three levels)

- **Ensemble level**: `Ensemble.builder().rateLimit(RateLimit.perMinute(60))` -- wraps
  `chatLanguageModel` once per `run()` call; all synthesized agents sharing the ensemble
  model share one token bucket.
- **Task level**: `Task.builder().rateLimit(RateLimit.perMinute(30))`
  - If task also has `chatLanguageModel`: model is wrapped at `build()` time; `rateLimit`
    is `null` on the resulting Task (consumed).
  - If task has no `chatLanguageModel`: `rateLimit` stored on Task; applied in
    `Ensemble.resolveAgents()` to the inherited ensemble model (separate bucket per task).
- **Agent level**: `Agent.builder().rateLimit(RateLimit.perSecond(2))` -- wraps `llm`
  at `build()` time.

### Shared-bucket pattern

```java
var sharedModel = RateLimitedChatModel.of(openAiModel, RateLimit.perMinute(60));
var researcher = Agent.builder().llm(sharedModel).build();
var writer = Agent.builder().llm(sharedModel).build();
```

### Tests added

- `RateLimitTest` (11 tests): value object factory methods, validation, equals, nanosPerToken
- `RateLimitTimeoutExceptionTest` (5 tests): hierarchy, message, getters
- `RateLimitedChatModelTest` (14 tests): delegation, rate enforcement, timeout, concurrent access
- `AgentTest` (4 new tests): rateLimit builder wraps llm, default null, already-wrapped model
- `TaskTest` (5 new tests): rateLimit+chatModel wraps at build time, rateLimit without model stored, default null, toBuilder preserves
- `EnsembleTest` (4 new tests): rateLimit stored on ensemble, can be combined with chatLanguageModel
- `RateLimitIntegrationTest` (9 tests): sequential delay, parallel shared bucket, no-rate-limit baseline, task/agent/ensemble level functional, shared explicit model, timeout propagation

### Documentation updated

- `docs/guides/rate-limiting.md` -- new guide (core concepts, per-level examples, shared buckets, wait timeout, thread safety)
- `mkdocs.yml` -- Rate Limiting added to Guides nav
- `docs/reference/ensemble-configuration.md` -- `rateLimit` field added to builder table
- `docs/reference/exceptions.md` -- `RateLimitTimeoutException` added to hierarchy and with section
- `docs/design/08-error-handling.md` -- exception hierarchy, section, summary table, flow diagram updated

### Build status

All 9 integration tests pass. Full `agentensemble-core` build + javadoc: **BUILD SUCCESSFUL**.

## Key Design Decisions (Issue #59 / Rate Limiting)

- **No external dependencies**: token-bucket implemented with `java.util.concurrent.locks`
  (~100 lines), avoiding Guava/Bucket4j/Resilience4j dependency bloat
- **Requests, not tokens**: rate limiting is request-count-based (API calls), not
  token-count-based (avoids tokenizer dependency per provider)
- **Three levels of convenience**: ensemble (task-first, shared bucket) / task / agent,
  with explicit `RateLimitedChatModel.of()` for advanced shared-bucket control
- **Task.rateLimit semantics**: consumed at build time when chatLanguageModel is set;
  preserved on Task when no chatLanguageModel (Ensemble applies it to the inherited model)
- **Ensemble.rateLimit semantics**: wraps `chatLanguageModel` once per `run()` call,
  not at builder `build()` time; ensures fresh bucket per run

## Important Patterns and Preferences

### Rate Limiting (Issue #59, v0.8.0)
- `RateLimit.perMinute(60)` / `perSecond(2)` / `of(N, Duration)`
- `RateLimitedChatModel.of(model, rateLimit)` -- manual decorator for shared buckets
- Builder shortcut: `.rateLimit()` on Ensemble, Task, Agent builders
- `RateLimitTimeoutException extends AgentEnsembleException`
- Package: `net.agentensemble.ratelimit`

### agentensemble-web module (v2.1.0)
- `WebDashboard.onPort(port)` -- zero-config; `WebDashboard.builder()` for full config
- `Ensemble.builder().webDashboard(dashboard)` -- single call wires listener + review
  handler + lifecycle hooks
- `EnsembleDashboard` interface: `streamingListener()`, `reviewHandler()`,
  `start()`, `stop()`, `isRunning()`, `onEnsembleStarted(...)`, `onEnsembleCompleted(...)`

### v2 Task-First API (Issues #104/#105)
- Task.of(description) -- zero-ceremony, default expectedOutput, no agent required
- Ensemble.run(model, tasks...) -- static factory, single-line ensemble execution
- Ensemble.builder().chatLanguageModel(model) -- ensemble-level LLM for synthesis

### v2.0.0 EnsembleOutput API (Issue #111)
- `output.isComplete()` -- true only when all tasks ran to completion
- `output.getExitReason()` -- COMPLETED, USER_EXIT_EARLY, TIMEOUT, ERROR
- `output.getOutput(researchTask)` -- identity-based lookup

## Next Steps

- Open PR for `feature/rate-limiting` (Issue #59)
- Issue #60 (retry with backoff) -- next related resilience feature
