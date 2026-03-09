# Rate Limiting

Rate limiting caps the number of LLM API requests per time window. It is especially important
in parallel workflows where multiple agents share the same API key and could exceed provider
quotas simultaneously.

---

## Core Concepts

Rate limiting uses a **token-bucket algorithm**: a token is added to the bucket at a fixed
interval (e.g. every 500 ms for 2 req/sec). Each LLM request consumes one token. When no
token is available, the calling thread blocks until one is ready or a wait timeout expires.

`RateLimit` describes the bucket refill rate. `RateLimitedChatModel` is a decorator that
wraps any `ChatModel` with a bucket.

---

## RateLimit

```java
// Factory methods
RateLimit.of(60, Duration.ofMinutes(1))   // 60 requests per minute
RateLimit.perMinute(60)                   // convenience alias
RateLimit.perSecond(2)                    // 2 requests per second
```

---

## Applying Rate Limits

### Ensemble level (task-first, shared bucket)

The most common usage. All synthesized agents that inherit the ensemble model share one bucket.

```java
EnsembleOutput result = Ensemble.builder()
    .chatLanguageModel(openAiModel)
    .rateLimit(RateLimit.perMinute(60))   // wraps chatLanguageModel at run time
    .task(Task.of("Research AI trends"))
    .task(Task.of("Write a summary report"))
    .build()
    .run();
```

All tasks that inherit the ensemble model share the same token bucket, which is created once
per `ensemble.run()` call. Tasks with their own `chatLanguageModel` or `rateLimit` are not
affected.

### Task level

Apply a rate limit to a specific task's LLM. Two sub-cases:

**Task has its own `chatLanguageModel`** -- the model is wrapped at build time:

```java
Task task = Task.builder()
    .description("Research AI trends")
    .expectedOutput("A report")
    .chatLanguageModel(openAiModel)
    .rateLimit(RateLimit.perMinute(30))   // wraps chatLanguageModel
    .build();
```

**Task inherits the ensemble model** -- the rate limit is stored on the task and applied
to the inherited model (creating a separate bucket from the ensemble-level limit):

```java
Task task = Task.builder()
    .description("Research AI trends")
    .expectedOutput("A report")
    .rateLimit(RateLimit.perMinute(30))   // applied when ensemble assigns a model
    .build();
```

### Agent level

For explicit agents, use `Agent.builder().rateLimit()`:

```java
Agent researcher = Agent.builder()
    .role("Researcher")
    .goal("Find the latest AI developments")
    .llm(openAiModel)
    .rateLimit(RateLimit.perMinute(60))   // wraps llm at build time
    .build();
```

---

## Shared vs separate buckets

Understanding which pattern gives you a shared bucket vs an independent one is key to
getting the behaviour you expect.

### Ensemble `.rateLimit()` -- shared across all tasks

`Ensemble.builder().rateLimit(limit)` creates **one** `RateLimitedChatModel` once per
`run()` call and gives it to every synthesized agent that inherits the ensemble model.
All those agents share the same token bucket.

```java
// One bucket: all tasks compete for the same 60 req/min allowance
Ensemble.builder()
    .chatLanguageModel(openAiModel)
    .rateLimit(RateLimit.perMinute(60))  // shared bucket for the whole ensemble
    .task(Task.of("Research AI trends"))
    .task(Task.of("Analyse the findings"))
    .task(Task.of("Write an executive summary"))
    .build()
    .run();
```

This is the right choice when you have one API key and want to enforce a global request
cap across an entire run.

### Task or Agent `.rateLimit()` -- independent bucket per task/agent

Each `.rateLimit()` on a `Task` or `Agent` builder creates a **new, separate** token
bucket for that task or agent. Two tasks both configured with `perMinute(30)` each get
their own 30 req/min allowance -- they do not share.

```java
// TWO independent buckets: task1 has 30 req/min, task2 has 30 req/min (separate)
var task1 = Task.builder()
    .description("Research AI trends")
    .chatLanguageModel(openAiModel)
    .rateLimit(RateLimit.perMinute(30))   // bucket A
    .build();

var task2 = Task.builder()
    .description("Write a summary")
    .chatLanguageModel(openAiModel)
    .rateLimit(RateLimit.perMinute(30))   // bucket B (independent from A)
    .build();
```

Use this when different tasks or agents have different quotas (e.g. a fast model with a
higher cap and a slow model with a lower one).

### Explicit shared instance -- share across selected tasks/agents

To share one bucket across a subset of tasks or agents, create one
`RateLimitedChatModel` instance and pass it explicitly wherever you want it:

```java
// One bucket shared by researcher and writer; analyst has its own
var shared = RateLimitedChatModel.of(openAiModel, RateLimit.perMinute(60));
var separate = RateLimitedChatModel.of(openAiModel, RateLimit.perMinute(20));

var researcher = Agent.builder().role("Researcher").goal("Research").llm(shared).build();
var writer = Agent.builder().role("Writer").goal("Write").llm(shared).build();
var analyst = Agent.builder().role("Analyst").goal("Analyse").llm(separate).build();
```

This works for explicit agents. For task-first (agentless) tasks, pass the shared model
as `chatLanguageModel`:

```java
var shared = RateLimitedChatModel.of(openAiModel, RateLimit.perMinute(60));

var task1 = Task.builder()
    .description("Research")
    .chatLanguageModel(shared)   // shares bucket with task2
    .build();
var task2 = Task.builder()
    .description("Write")
    .chatLanguageModel(shared)   // same bucket
    .build();
```

### Summary

| Approach | Bucket sharing |
|---|---|
| `Ensemble.builder().rateLimit()` | Shared across all synthesized agents that use the ensemble model |
| `Task.builder().rateLimit()` | Independent per task |
| `Agent.builder().rateLimit()` | Independent per agent |
| `RateLimitedChatModel.of(model, limit)` passed to multiple agents/tasks | Shared (same object instance = same bucket) |

---

## Wait Timeout

By default, threads wait up to **30 seconds** for a token. If no token is available within
the timeout, `RateLimitTimeoutException` is thrown.

Customise the timeout with the three-argument factory:

```java
var model = RateLimitedChatModel.of(
    openAiModel,
    RateLimit.perMinute(60),
    Duration.ofSeconds(60)    // wait up to 60 seconds before timing out
);
```

When timeout is exceeded, `RateLimitTimeoutException` propagates up as a
`TaskExecutionException`. Handle it or increase the timeout.

---

## Thread Safety

`RateLimitedChatModel` is thread-safe. Multiple threads (parallel workflow virtual threads)
can call `chat()` concurrently and correctly share the token bucket via `ReentrantLock`.

---

## No Extra Dependencies

The token-bucket implementation uses only `java.util.concurrent.locks`. No third-party
rate-limiting library is required.

---

## See Also

- [Ensemble Configuration](../reference/ensemble-configuration.md) -- `rateLimit` builder field
- [Exceptions](../reference/exceptions.md) -- `RateLimitTimeoutException`
- [Workflows](workflows.md) -- parallel workflow concurrency
