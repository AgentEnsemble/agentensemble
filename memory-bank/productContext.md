# Product Context

## Why This Project Exists

The multi-agent AI orchestration pattern is gaining traction but Java developers lack a clean, idiomatic framework for it. Existing solutions are primarily in Python. AgentEnsemble fills this gap by providing a Java-native framework built on LangChain4j.

## Problems It Solves

1. **No Java multi-agent framework**: Java developers who want to orchestrate multiple AI agents have to build from scratch or use Python.
2. **Boilerplate**: Setting up agent prompts, tool integration, task chaining, and error handling for multi-agent workflows requires significant boilerplate.
3. **LLM lock-in**: Many quick implementations are tied to a single LLM provider. AgentEnsemble is provider-agnostic via LangChain4j.

## How It Should Work

Users define:
- **Agents** with distinct roles, goals, and capabilities (tools)
- **Tasks** with clear descriptions and expected outputs
- **An Ensemble** that groups agents and tasks with a workflow strategy

Then call `ensemble.run()` (optionally with input variables) and receive structured output.

## User Experience Goals

1. **5-minute quickstart**: A developer should be able to define 2 agents, 2 tasks, and run an ensemble within 5 minutes of reading the README.
2. **Readable code**: The builder API should read like a natural description of what the ensemble does.
3. **Clear errors**: When something goes wrong, the error message should tell the user exactly what to fix.
4. **Observable**: SLF4J logging with MDC provides production-grade observability out of the box.
5. **Testable**: Every component can be unit tested with mocked LLMs.
