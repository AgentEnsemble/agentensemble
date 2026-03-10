# Active Context

## Current Focus

### v3.0.0 Ensemble Network Design (branch: docs/v3-ensemble-network-design)

Comprehensive v3.0.0 architecture designed and documented. Branch has 4 commits, NOT pushed.

**What was produced:**
- Design document: `docs/design/18-ensemble-network.md` (24 sections, 1269 lines)
- Issue breakdown: `docs/design/18-ensemble-network-issues.md` (30 issues, 4 phases)
- White paper: `docs/whitepaper/ensemble-network-architecture.md`
- Blog post: `docs/blog/ensemble-network.md`
- Book: `docs/book/` (15 chapters + 3 appendices, 19 files, ~36K words)
- Book PDF: `docs/book/ensemble-network-book.pdf` (author: Matthew Dickinson)
- Design notes: `docs/design/18-ensemble-network-design-notes.md` (conversation record)

**Core architecture decisions:**
- Peer-to-peer mesh of autonomous long-running ensembles (NOT central conductor)
- Hotel analogy: departments as ensembles, hotel runs 24/7, humans come and go
- Two sharing primitives: SharedTask (delegate a process) + SharedTool (borrow a capability)
- WorkRequest envelope with priority, deadline, delivery method, trace context
- "Bend, don't break" capacity management: accept and queue, reject only at hard limits
- Caller-side SLA: caller specifies deadline, provider reports ETA, caller decides
- Dual transport: WebSocket (real-time events) + durable queues (reliable work delivery)
- Human participation spectrum: Autonomous -> Advisory -> Notifiable -> Approvable -> Gated
- K8s-native deployment: no custom registry, framework provides health endpoints + metrics
- OTel for distributed tracing, W3C trace context mandatory in wire protocol
- Natural language contracts: LLM is the compatibility layer, no schema versioning
- Built-in chaos engineering (application-level fault injection, not infrastructure-level)
- Adaptive audit trail with dynamic rules (metric/event/schedule/human-triggered escalation)
- Per-scope shared state consistency: EVENTUAL / LOCKED / OPTIMISTIC / EXTERNAL

**10 design topics resolved:**
1. Distributed Tracing: OTel SDK + W3C trace context + backend-agnostic
2. Resilience: framework=semantics, infra=transport; expose metrics for K8s HPA
3. Versioning: no explicit versioning; LLM=compatibility layer
4. Testing: 3-tier (component/simulation/chaos), all built into framework
5. Capacity: "bend don't break"; caller-side SLA; operational profiles
6. Audit: leveled + dynamic rules; pluggable sink SPI
7. Delivery: WorkRequest envelope; 7 delivery methods; pluggable ingress; durable queues
8. Lifecycle: STARTING/READY/DRAINING/STOPPED; K8s integration
9. Shared State: per-scope configurable consistency; LockProvider SPI
10. Cost: in tracing; control plane directives for model tier switching

**Next steps:**
- Review all docs for completeness
- Push branch when ready
- Implementation begins with Phase 1 (EN-001 through EN-009)

## Previously Focused

Added a "Why AgentEnsemble?" comparison section to the landing page, README, and docs.
Three comparison cards were added to the site (`WhyAgentEnsemble.astro` component)
placed between the Hero and Features sections:
- "AgentEnsemble vs hand-rolled LangChain4j orchestration"
- "Why JVM teams need a production-minded agent framework"
- "Why AgentEnsemble instead of Python-first agent frameworks"

The same three sections (as Markdown) were added near the top of `README.md` and
`docs/index.md`. The site build was verified clean (65 pages, sitemap generated).

The content is positioned as an explicit comparison story for Java engineers evaluating
alternatives, using the exact phrasing identified as high-leverage search terms.

## Previously Focused

Branch `fix/web-dashboard-lifecycle-auto-stop` addresses a lifecycle bug where
`WebDashboard` (Javalin/Jetty) non-daemon threads prevented JVM exit after an ensemble
run completed.

**Root cause:** `EnsembleBuilder.webDashboard()` auto-started the dashboard but
`Ensemble.runWithInputs()` never called `dashboard.stop()`. Javalin/Jetty server threads
are non-daemon, so the JVM could not exit. The JVM shutdown hook registered in `WebDashboard`
can only fire when the JVM *begins* shutdown, which never happened because non-daemon threads
were still alive.

**Fixes implemented:**
- `Ensemble.runWithInputs()` now calls `dashboard.stop()` in the `finally` block after
  the run completes (normal or exceptional). Only applies when the dashboard was registered
  via `webDashboard()` (the `dashboard` field is null when wired manually).
- `EnsembleDashboard` now extends `AutoCloseable`; default `close()` delegates to `stop()`.
  This enables try-with-resources for manually-managed dashboards.
- `EnsembleBuilder.webDashboard()` Javadoc updated to document the new auto-stop (4th item).
- Design doc `docs/design/16-live-dashboard.md` section 3.4 updated to document the
  lifecycle contract and try-with-resources pattern.

**Tests added (TDD -- written failing first, then fixed):**
- `EnsembleDashboardLifecycleTest` (3 tests in agentensemble-core):
  - `webDashboard_stopsAfterSuccessfulRun` -- verifies `stop()` called after normal run
  - `webDashboard_stopsEvenWhenRunThrows` -- verifies `stop()` called even when run throws
  - `manuallyWiredDashboard_isNotAutoStopped` -- verifies manually-wired dashboard NOT stopped
- `WebDashboardTest` additions (4 tests in agentensemble-web):
  - `close_delegatesToStop` -- verifies `close()` stops the server
  - `usableWithTryWithResources` -- verifies try-with-resources works
  - `implementsAutoCloseable` -- compile-time check

**Build status:** Full `./gradlew build` including javadoc and JaCoCo: **BUILD SUCCESSFUL**.

## Previously Focused

Branch `feature/rate-limiting` is open for Issue #59. PR #173 is up to date at commit
`e91eda9` -- all 9 Copilot review comments addressed. Ready for final review and merge.

## What was built

### New package: `net.agentensemble.ratelimit`

- `RateLimit` -- immutable value object. Factory methods: `of(int, Duration)`,
  `perMinute(int)`, `perSecond(int)`. `nanosPerToken()` clamped to minimum 1 ns.
- `RateLimitedChatModel` -- decorator implementing `ChatModel`. Real token-bucket
  algorithm (starts with 1 token, refills up to capacity while idle). Thread-safe via
  `ReentrantLock + Condition`. Configurable `waitTimeout` (default 30s).
  `of(delegate, rateLimit)` / `of(delegate, rateLimit, timeout)`.
- `RateLimitTimeoutException` -- thrown when wait exceeds timeout. Message uses
  `Duration.toString()` (ISO-8601) for precision on sub-second periods.
- `RateLimitInterruptedException` -- NEW: thrown when waiting thread is interrupted;
  distinct from timeout; preserves `InterruptedException` as cause.

### Builder conveniences (three levels)

- **Ensemble level**: `Ensemble.builder().rateLimit(RateLimit.perMinute(60))` -- wraps
  `chatLanguageModel` once per `run()` call; all synthesized agents sharing the ensemble
  model share one token bucket.
- **Task level**: `Task.builder().rateLimit(RateLimit.perMinute(30))`
  - If task also has `chatLanguageModel`: model is wrapped at `build()` time; `rateLimit`
    is `null` on the resulting Task (consumed).
  - If task has no `chatLanguageModel`: `rateLimit` stored on Task; applied in
    `Ensemble.resolveAgents()` to the **raw** ensemble model (not the already-wrapped
    effective model, to prevent nested rate-limiting).
- **Agent level**: `Agent.builder().rateLimit(RateLimit.perSecond(2))` -- wraps `llm`
  at `build()` time.

### Shared-bucket pattern

```java
var sharedModel = RateLimitedChatModel.of(openAiModel, RateLimit.perMinute(60));
var researcher = Agent.builder().llm(sharedModel).build();
var writer = Agent.builder().llm(sharedModel).build();
```

### Validation

`EnsembleValidator` now fails fast when `rateLimit != null && chatLanguageModel == null`,
preventing silent no-op misconfiguration.

### Tests added

- `RateLimitTest` (12 tests): value object, nanosPerToken clamp
- `RateLimitTimeoutExceptionTest` (6 tests): Duration.toString precision
- `RateLimitInterruptedExceptionTest` (5 tests): new; hierarchy, cause, distinction
- `RateLimitedChatModelTest` (16 tests): interrupt handling, shared instance
- `AgentTest`, `TaskTest`, `EnsembleTest` (13 new tests): builder levels
- `RateLimitIntegrationTest` (13 tests): validator fail-fast, flaky upper bound removed

### Documentation updated

- `docs/guides/rate-limiting.md` -- new guide
- `mkdocs.yml` -- Rate Limiting added to Guides nav
- `docs/reference/ensemble-configuration.md` -- `rateLimit` field added
- `docs/reference/exceptions.md` -- both exception classes documented
- `docs/design/08-error-handling.md` -- hierarchy, section, table, flow diagram

### Build status

Full `agentensemble-core` build + javadoc: **BUILD SUCCESSFUL** (commit `e91eda9`).

## Key Design Decisions (Issue #59 / Rate Limiting)

- **No external dependencies**: token-bucket implemented with `java.util.concurrent.locks`
- **Real token-bucket, not leaky-bucket**: accumulates tokens up to capacity while idle
- **Requests, not tokens**: request-count-based (avoids tokenizer dependency per provider)
- **Three levels**: ensemble (shared) / task (independent) / agent (independent)
- **Interrupt vs timeout**: separate exception classes for clear error handling
- **Nested rate limit prevention**: `resolveAgents()` wraps raw model for task-level limits

## Important Patterns and Preferences

### Rate Limiting (Issue #59, v0.8.0)
- `RateLimit.perMinute(60)` / `perSecond(2)` / `of(N, Duration)`
- `RateLimitedChatModel.of(model, rateLimit)` -- manual decorator for shared buckets
- Builder shortcut: `.rateLimit()` on Ensemble, Task, Agent builders
- `RateLimitTimeoutException extends AgentEnsembleException`
- `RateLimitInterruptedException extends AgentEnsembleException`
- Package: `net.agentensemble.ratelimit`

### agentensemble-web module (v2.1.0)
- `WebDashboard.onPort(port)` -- zero-config; `WebDashboard.builder()` for full config
- `Ensemble.builder().webDashboard(dashboard)` -- single call wires listener + review
  handler + lifecycle hooks

### v2 Task-First API (Issues #104/#105)
- Task.of(description) -- zero-ceremony, default expectedOutput, no agent required
- Ensemble.run(model, tasks...) -- static factory, single-line ensemble execution
- Ensemble.builder().chatLanguageModel(model) -- ensemble-level LLM for synthesis

## Next Steps

- Merge PR #173 once approved
- Issue #60 (retry with backoff) -- next related resilience feature
