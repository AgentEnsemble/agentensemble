# AgentEnsemble Documentation

AgentEnsemble is an open-source Java 21 framework for orchestrating teams of AI agents that collaborate to accomplish complex tasks. Built on [LangChain4j](https://github.com/langchain4j/langchain4j), it is LLM-agnostic and integrates with OpenAI, Anthropic, Ollama, Azure OpenAI, Amazon Bedrock, Google Vertex AI, and any other provider LangChain4j supports.

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
| [Workflows](guides/workflows.md) | Sequential and hierarchical execution strategies |
| [Tools](guides/tools.md) | Extend agents with custom tools |
| [Memory](guides/memory.md) | Short-term, long-term, and entity memory |
| [Delegation](guides/delegation.md) | Agent-to-agent delegation |
| [Error Handling](guides/error-handling.md) | Exceptions, partial results, recovery patterns |
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
| [Memory Across Runs](examples/memory-across-runs.md) | Long-term memory persisted across ensemble runs |

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
