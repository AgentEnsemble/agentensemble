# 01 - Overview

## What is AgentEnsemble?

**AgentEnsemble** is an open-source Java 21 framework for orchestrating teams of AI agents that collaborate to accomplish complex tasks. It provides a clean, idiomatic Java API for defining agents with distinct roles, assigning them tasks, and running them together as an ensemble.

Built on [LangChain4j](https://github.com/langchain4j/langchain4j) for LLM integration, AgentEnsemble supports any LLM provider that LangChain4j supports (OpenAI, Anthropic, Ollama, Azure OpenAI, Amazon Bedrock, Google Vertex AI, and more).

## Core Concepts

| Concept | Description |
|---|---|
| **Agent** | An AI entity with a role, goal, background, and optional tools |
| **Task** | A unit of work assigned to an agent, with a description and expected output |
| **Ensemble** | A group of agents working together on a sequence of tasks |
| **Tool** | A capability an agent can invoke during task execution (e.g., search, calculate) |
| **Workflow** | How tasks are executed (sequential, hierarchical, parallel) |

## Goals

- **Clean, idiomatic Java API** using builders, immutable value objects, and standard patterns
- **LLM-agnostic** via LangChain4j -- users bring their preferred model provider
- **Easy to extend** with custom tools, workflows, and prompt strategies
- **Production-ready** with proper error handling, structured logging, input validation, and testability
- **Minimal dependency footprint** -- only what is necessary

## Non-Goals (Phase 1)

- GUI or web interface
- Built-in tool library (search, web scrape, etc.) -- users bring their own tools
- Persistent memory across ensemble runs
- Agent-to-agent delegation
- Streaming output
- Async/parallel task execution

## Inspiration

AgentEnsemble is inspired by the multi-agent orchestration pattern where agents with distinct roles collaborate on complex workflows. The core abstractions (Agent, Task, Ensemble, Workflow, Tool) are common patterns in the AI agent ecosystem.

## License

MIT License. See [LICENSE](https://github.com/AgentEnsemble/agentensemble/blob/main/LICENSE).
