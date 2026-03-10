# AgentEnsemble Documentation

AgentEnsemble is an open-source Java 21 framework for orchestrating teams of AI agents that collaborate to accomplish complex tasks. Built on [LangChain4j](https://github.com/langchain4j/langchain4j), it is LLM-agnostic and integrates with OpenAI, Anthropic, Ollama, Azure OpenAI, Amazon Bedrock, Google Vertex AI, and any other provider LangChain4j supports.

---

## Why AgentEnsemble?

### AgentEnsemble vs hand-rolled LangChain4j orchestration

LangChain4j gives you excellent building blocks. But stitching multiple agents together yourself means writing the same boilerplate every time: prompt assembly, context threading, error recovery, retry logic, and delegation plumbing. AgentEnsemble is that layer, already built and battle-tested.

- **Three lines instead of hundreds** — A working multi-agent pipeline runs with a single `Ensemble.run(model, task1, task2, task3)` call. Sequential, hierarchical, parallel, and MapReduce strategies come built-in.
- **Workflow strategies that compose** — SEQUENTIAL, HIERARCHICAL (manager delegates to workers), PARALLEL (DAG-based concurrent execution via virtual threads), and MapReduce for large-context workloads. Switching between them is one enum value.
- **Production concerns handled for you** — Memory across runs, review gates for human-in-the-loop approval, input/output guardrails, structured output with automatic retry, delegation guards and lifecycle events. None of this has to be invented from scratch.
- **Full observability out of the box** — Every run produces token counts, LLM latency, tool timing, and a complete execution trace. Export to JSON, stream to a live browser dashboard, or push to Micrometer. Zero configuration required.

### Why JVM teams need a production-minded agent framework

Python agent frameworks are not designed for Java engineering constraints. AgentEnsemble is written in Java 21, distributed as standard Maven/Gradle artifacts, and fits directly into the toolchains, testing practices, and deployment pipelines that JVM teams already use.

- **Idiomatic Java 21** — Fluent builders, records for structured output, sealed interfaces, and Java virtual threads for concurrent execution. No reflection tricks, no annotation processors, no runtime surprises.
- **Gradle and Maven with a BOM** — Add the BOM and pull the modules you need. Versions align automatically. The same dependency management your team uses for every other library.
- **Plugs into your existing stack** — Micrometer metrics integrate with Prometheus and Grafana. SLF4J logging works with Logback and Log4j2. The live dashboard is a plain embedded WebSocket server — no Docker, no npm, no sidecar process.
- **Type-safe from input to output** — Declare `outputType(MyRecord.class)` on a task and receive a fully typed, schema-validated Java object. Parse failures trigger automatic correction prompts before any exception is thrown.

### Why AgentEnsemble instead of Python-first agent frameworks

Frameworks like LangChain and CrewAI are excellent in their ecosystem. Bringing them into a Java service means a Python runtime, an HTTP sidecar or subprocess, serialization overhead, and two languages to test and deploy. AgentEnsemble runs on the same JVM as your service.

- **No Python runtime or interop tax** — Deploy as a library JAR. No subprocess management, no inter-process serialization, no latency from crossing a process boundary on every agent call.
- **LLM-agnostic via LangChain4j** — OpenAI, Anthropic, Ollama, Azure OpenAI, Amazon Bedrock, Google Vertex AI — and any provider LangChain4j adds in the future. Switching providers is a one-line change.
- **Feature parity with Python frameworks** — Sequential, hierarchical, and parallel workflows. MapReduce for large workloads. Multi-level memory. Tool pipelines. Human-in-the-loop review gates. Delegation with guards. Structured typed output. All in Java.
- **One language to test and deploy** — Unit tests with JUnit, integration tests with your existing test containers, CI with the same Gradle tasks. No Python virtualenv to maintain, no separate test suite to keep in sync.

---

## Getting Started

| Page | Description |
|---|---|
| [Installation](getting-started/installation.md) | Add the dependency to your project |
| [Quickstart](getting-started/quickstart.md) | Build your first ensemble in five minutes |
| [Core Concepts](getting-started/concepts.md) | Agents, Tasks, Ensembles, Workflows, Memory, Delegation |

---

## Guides

| Page | Description |
|---|---|
| [Agents](guides/agents.md) | Create and configure agents |
| [Tasks](guides/tasks.md) | Define tasks, expected outputs, and context |
| [Workflows](guides/workflows.md) | Sequential, hierarchical, and parallel execution strategies |
| [Tools](guides/tools.md) | Extend agents with custom tools |
| [Memory](guides/memory.md) | Short-term, long-term, and entity memory |
| [Delegation](guides/delegation.md) | Agent-to-agent delegation |
| [Error Handling](guides/error-handling.md) | Exceptions, partial results, recovery patterns |
| [Callbacks](guides/callbacks.md) | Task and tool lifecycle event listeners |
| [Logging](guides/logging.md) | SLF4J logging, MDC keys, and configuration |
| [Template Variables](guides/template-variables.md) | Dynamic task descriptions |

---

## Reference

| Page | Description |
|---|---|
| [Agent Configuration](reference/agent-configuration.md) | All agent fields and defaults |
| [Task Configuration](reference/task-configuration.md) | All task fields and defaults |
| [Ensemble Configuration](reference/ensemble-configuration.md) | All ensemble fields and defaults |
| [Memory Configuration](reference/memory-configuration.md) | EnsembleMemory and memory type reference |
| [Exceptions](reference/exceptions.md) | Full exception hierarchy |

---

## Examples

| Page | Description |
|---|---|
| [Research and Writer](examples/research-writer.md) | Sequential two-agent research and writing pipeline |
| [Hierarchical Team](examples/hierarchical-team.md) | Manager-led team with automatic task delegation |
| [Parallel Workflow](examples/parallel-workflow.md) | Concurrent independent tasks with a dependency graph |
| [Memory Across Runs](examples/memory-across-runs.md) | Long-term memory persisted across ensemble runs |
| [Structured Output](examples/structured-output.md) | Typed JSON output parsed into Java records |
| [Callbacks](examples/callbacks.md) | Observing task and tool lifecycle events |

---

## Design Documentation

The `docs/design/` directory contains internal architecture specifications for contributors:

- [01 - Overview](design/01-overview.md)
- [02 - Architecture](design/02-architecture.md)
- [03 - Domain Model](design/03-domain-model.md)
- [04 - Execution Engine](design/04-execution-engine.md)
- [05 - Prompt Templates](design/05-prompt-templates.md)
- [06 - Tool System](design/06-tool-system.md)
- [07 - Template Resolver](design/07-template-resolver.md)
- [08 - Error Handling](design/08-error-handling.md)
- [09 - Logging](design/09-logging.md)
- [10 - Concurrency](design/10-concurrency.md)
- [11 - Configuration Reference](design/11-configuration.md)
- [12 - Testing Strategy](design/12-testing-strategy.md)
- [13 - Future Roadmap](design/13-future-roadmap.md)

---

## Releases

| Version | Features |
|---|---|
| [v0.4.0](https://github.com/AgentEnsemble/agentensemble/releases/tag/v0.4.0) | Agent delegation |
| [v0.3.0](https://github.com/AgentEnsemble/agentensemble/releases/tag/v0.3.0) | Memory system |
| [v0.2.0](https://github.com/AgentEnsemble/agentensemble/releases/tag/v0.2.0) | Hierarchical workflow |
| [v0.1.0](https://github.com/AgentEnsemble/agentensemble/releases/tag/v0.1.0) | Core framework |
