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

## Shared Buckets

To enforce a single rate limit across multiple agents that share an API key, pass the
**same `RateLimitedChatModel` instance** to all agents. All calls go through the same bucket.

```java
// Create one shared rate-limited model
var sharedModel = RateLimitedChatModel.of(openAiModel, RateLimit.perMinute(60));

// Both agents share the same token bucket
var researcher = Agent.builder()
    .role("Researcher").goal("Research").llm(sharedModel).build();
var writer = Agent.builder()
    .role("Writer").goal("Write").llm(sharedModel).build();

EnsembleOutput result = Ensemble.builder()
    .task(Task.builder().description("Research").expectedOutput("Report").agent(researcher).build())
    .task(Task.builder().description("Write").expectedOutput("Article").agent(writer).build())
    .build()
    .run();
```

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
