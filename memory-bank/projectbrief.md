# Project Brief: AgentEnsemble

## Overview

AgentEnsemble is an open-source Java 21 framework for orchestrating teams of AI agents that collaborate to accomplish complex tasks. It is hosted at github.com/AgentEnsemble/agentensemble under the MIT license.

## Core Requirements

1. **Multi-agent orchestration**: Define agents with roles, goals, backgrounds, and tools. Group them into an ensemble that executes tasks in a defined workflow.
2. **LLM-agnostic**: Built on LangChain4j, supporting any LLM provider (OpenAI, Anthropic, Ollama, etc.).
3. **Clean Java API**: Builders, immutable value objects, standard Java patterns.
4. **Extensible**: Custom tools, custom LLM providers, future custom workflows.
5. **Production-ready**: Proper error handling, structured logging (SLF4J), input validation, testability.

## Core Concepts

| Concept | Description |
|---|---|
| Agent | An AI entity with a role, goal, background, and optional tools |
| Task | A unit of work assigned to an agent |
| Ensemble | A group of agents working together on tasks |
| Tool | A capability an agent can invoke |
| Workflow | How tasks are executed (sequential, hierarchical, parallel) |

## Tech Stack

- Java 21
- Gradle with Kotlin DSL
- LangChain4j (core LLM integration)
- Lombok (builders, value objects)
- Jackson (JSON)
- SLF4J (logging)
- JUnit 5 + AssertJ + Mockito (testing)

## Important Constraints

- No references to competing frameworks by name (trademark/copyright)
- Minimal dependency footprint
- MIT license
- No emoji or unicode in code/docs

## Scope

### Phase 1 (Current)
- Agent, Task, Ensemble, Workflow domain model
- Sequential workflow execution
- Tool system (framework interface + LangChain4j @Tool support)
- Template variable substitution
- Full exception hierarchy
- Structured logging with MDC
- Unit and integration tests
- Example application

### Future Phases
- Hierarchical workflow (Phase 2)
- Memory system (Phase 3)
- Agent delegation (Phase 4)
- Parallel workflow (Phase 5)
- Structured output (Phase 6)
- Callbacks, streaming, guardrails, built-in tools (Phase 7)
