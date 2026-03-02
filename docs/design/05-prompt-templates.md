# 05 - Prompt Templates

This document specifies the exact prompts constructed by `AgentPromptBuilder` and sent to the LLM.

## System Prompt

Built by `AgentPromptBuilder.buildSystemPrompt(Agent agent)`.

### Template

```
You are {agent.role}.
{agent.background -- only present if non-null and non-blank}

Your personal goal is: {agent.goal}

You must produce a final answer that satisfies the expected output described in the task.
Focus on quality and accuracy. Do not add unnecessary preamble or postscript to your final answer.
{agent.responseFormat -- only present if non-blank, prefixed with "Output format instructions: "}
```

### Example: Full Agent (with background and responseFormat)

```
You are Senior Research Analyst.
You are a veteran technology researcher with 20 years of experience
analyzing emerging trends. You have a sharp eye for separating hype
from substance.

Your personal goal is: Uncover cutting-edge developments in AI and
provide actionable insights.

You must produce a final answer that satisfies the expected output described in the task.
Focus on quality and accuracy. Do not add unnecessary preamble or postscript to your final answer.
Output format instructions: Structure your findings as a numbered list with brief explanations.
```

### Example: Minimal Agent (no background, no responseFormat)

```
You are Content Writer.

Your personal goal is: Write engaging, well-researched articles about technology.

You must produce a final answer that satisfies the expected output described in the task.
Focus on quality and accuracy. Do not add unnecessary preamble or postscript to your final answer.
```

### Construction Rules

1. First line is always `"You are {role}."` with a period.
2. If `background` is non-null and non-blank: add a blank line, then the background text.
3. Always add a blank line, then `"Your personal goal is: {goal}"`.
4. Always add a blank line, then the standard instruction paragraph.
5. If `responseFormat` is non-blank: add `"Output format instructions: {responseFormat}"` on the next line.
6. No trailing whitespace. No trailing newlines.

---

## User Prompt

Built by `AgentPromptBuilder.buildUserPrompt(Task task, List<TaskOutput> contextOutputs)`.

### Template: Without Context

```
## Task
{task.description}

## Expected Output
{task.expectedOutput}
```

### Template: With Context

```
## Context from Previous Tasks
The following results from previous tasks may be relevant:

---
### {contextOutput1.agentRole}: {contextOutput1.taskDescription}
{contextOutput1.raw}
---
### {contextOutput2.agentRole}: {contextOutput2.taskDescription}
{contextOutput2.raw}
---

## Task
{task.description}

## Expected Output
{task.expectedOutput}
```

### Construction Rules

1. If `contextOutputs` is non-empty:
   - Start with `"## Context from Previous Tasks"` header
   - Add explanation line
   - Add blank line
   - For each context output, add a `---` separator, then `### {agentRole}: {taskDescription}`, then the raw output, then another `---`
   - Add blank line before the Task section
2. Always include `"## Task"` followed by the task description
3. Always include `"## Expected Output"` followed by the expected output
4. Context outputs are rendered in the order they appear in the `context` list

### Example: With Two Context Tasks

```
## Context from Previous Tasks
The following results from previous tasks may be relevant:

---
### Senior Research Analyst: Research the latest AI agent frameworks
AI agent frameworks have seen rapid growth in 2026. Key developments include
multi-agent orchestration patterns, improved tool use capabilities, and
tighter integration with enterprise systems...
---
### Data Analyst: Analyze market adoption data for AI agents
According to recent surveys, 67% of enterprises are either evaluating or
actively deploying AI agent systems. The market is projected to reach
$15B by 2028...
---

## Task
Write a comprehensive blog post about the state of AI agents in 2026.

## Expected Output
A well-structured 1500-word blog post with an introduction, 3-4 main sections
covering key trends, market data, and predictions, and a conclusion.
```

---

## Design Decisions

### Why Context Before Task

Context is placed BEFORE the task description so the LLM reads background information first, then focuses on its specific assignment. This follows the principle of providing context before instructions.

### Why No Tool Instructions in Our Prompts

LangChain4j automatically injects tool-use instructions when tools are provided to the `generate()` call. We do not add our own tool instructions to avoid conflicts or duplication with LangChain4j's prompt engineering.

### Why Explicit Expected Output Section

The `expectedOutput` is rendered as a separate, clearly labeled section rather than embedded in the task description. This gives the LLM explicit success criteria and makes it more likely to produce output matching the user's expectations.

---

## Edge Cases

| Scenario | Behavior |
|---|---|
| `background` is null | Omit background line entirely (no blank line left behind) |
| `background` is empty string | Treated as blank, omitted |
| `responseFormat` is empty string | Omit the format line entirely |
| Context list is empty | Omit the entire Context section; prompt starts with `## Task` |
| Context output exceeds 10,000 chars | Log `WARN: Context from task '{description}' is {length} characters. Consider breaking into smaller tasks.` No truncation (user responsibility). |
| Context output is empty string | Rendered as an empty block (headers still shown for traceability) |
