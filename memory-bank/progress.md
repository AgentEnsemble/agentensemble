# Progress

## What Works

### Core Framework (agentensemble-core)
- Sequential, parallel (dependency-based DAG), and hierarchical workflows
- Agent execution with ReAct-style tool-calling loop
- Multi-tool parallel execution via Java 21 virtual threads (single-turn parallelism)
- Configurable tool executor and metrics backend on `Ensemble.builder()`
- `AbstractAgentTool` base class with template method, auto-metrics, structured logging, exception safety
- `ToolContext` (Logger + ToolMetrics + Executor) injected into `AbstractAgentTool` instances
- `ToolMetrics` interface + `NoOpToolMetrics` default
- `ToolContextInjector` friend-bridge for cross-package injection
- Enhanced `ToolResult` with optional `structuredOutput` field
- Enhanced `ToolCallEvent` with `structuredResult` and `(toolName, agentRole)` tagging
- Short-term, long-term, and entity memory (EnsembleMemory)
- Agent delegation (peer-to-peer with depth limiting)
- Input/output guardrails
- Structured output parsing with retry logic
- Template variable substitution in task descriptions
- Callback/listener system (onTaskStart/Complete/Failed/onToolCall)
- Verbose logging mode
- MDC propagation for structured logging in parallel workflows

### Built-in Tool Library
Per-tool Gradle modules under `agentensemble-tools/`:

| Module | Package | Status |
|--------|---------|--------|
| `agentensemble-tools-calculator` | `net.agentensemble.tools.calculator` | Done |
| `agentensemble-tools-datetime` | `net.agentensemble.tools.datetime` | Done |
| `agentensemble-tools-json-parser` | `net.agentensemble.tools.json` | Done |
| `agentensemble-tools-file-read` | `net.agentensemble.tools.io` | Done |
| `agentensemble-tools-file-write` | `net.agentensemble.tools.io` | Done |
| `agentensemble-tools-web-search` | `net.agentensemble.tools.web.search` | Done |
| `agentensemble-tools-web-scraper` | `net.agentensemble.tools.web.scraper` | Done |
| `agentensemble-tools-process` | `net.agentensemble.tools.process` | Done |
| `agentensemble-tools-http` | `net.agentensemble.tools.http` | Done |
| `agentensemble-tools-bom` | (platform) | Done |

All tools extend `AbstractAgentTool` with automatic metrics, logging, exception handling.

### Metrics Integration
- `agentensemble-metrics-micrometer` with `MicrometerToolMetrics`
- `agentensemble.tool.executions` counter + `agentensemble.tool.duration` timer
- Tagged by `(tool_name, agent_role, outcome)`

### Build Infrastructure
- `buildSrc/agentensemble.tool-conventions` precompiled convention plugin
- `pluginManagement` in `settings.gradle.kts` for plugin version alignment
- JaCoCo coverage enforced: LINE >= 90%, BRANCH >= 75% across all modules
- Spotless + ErrorProne enabled on all modules

### Documentation
- Complete API docs for all new framework classes
- `guides/tools.md` (AbstractAgentTool, ToolContext, virtual threads)
- `guides/built-in-tools.md` (per-module installation, all 9 tools)
- `guides/remote-tools.md` (ProcessAgentTool, HttpAgentTool, subprocess protocol)
- `guides/metrics.md` (ToolMetrics, MicrometerToolMetrics, Prometheus)
- `getting-started/installation.md` (per-tool deps, BOM)

## What's Left to Build

### Near-term (post v1.0.0 merge)
- MCP (Model Context Protocol) integration (`McpAgentTool`)
- GraalVM polyglot tool support
- Tool output validation / schema enforcement

### Longer-term
- See `design/13-future-roadmap.md` for the full roadmap

## Current Status

**Branch:** `feature/60-built-in-tool-library`
**PR:** #72 (open, targeting `main`)
**Issues:** Closes #60 and #73

All tests pass:
- `agentensemble-core` -- check passes (90%+ coverage)
- All 9 `agentensemble-tools-*` modules -- check passes (90%+ coverage)
- `agentensemble-metrics-micrometer` -- check passes
- `agentensemble-examples` -- compiles successfully

## Known Issues

None at this time.

## Evolution of Project Decisions

- **Async**: Decided NOT to add `AsyncAgentTool` interface. Java 21 virtual threads make
  blocking cheap; the framework parallelizes multi-tool turns using `CompletableFuture`
  internally. Tool authors write plain synchronous code.
- **Module layout**: Per-tool modules (not per-category) chosen from the start to avoid
  breaking changes later and to give users precise dependency control.
- **Metrics tagging**: Both `tool_name` and `agent_role` tags required after design discussion
  -- same tool used by different agents should be distinguishable in metrics.
