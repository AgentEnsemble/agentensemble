# .clinerules/workflows/code-scan-workflow.md
# Code Scan Workflow (Deep Review)

## Purpose

Perform comprehensive, AI-powered code review of a specific module or package directory to
identify logic bugs, thread safety issues, test coverage gaps, API design problems, and
observability issues. This workflow provides proactive code quality assurance beyond what
Spotless, Error Prone, and JaCoCo can detect automatically.

## When to Use

**Proactive audits:**
- Before major releases or milestone versions
- After onboarding new contributors
- When refactoring an existing module
- Before introducing a new module to the project
- When adding a significant new feature to an existing module

**Reactive audits:**
- After a bug report or unexpected behavior is found
- When a pattern of related failures emerges
- Before merging a large or architecturally significant PR

## Input Parameters

```
scan-code <path>

Java module examples:
  scan-code agentensemble-core/src/main/java/net/agentensemble/agent
  scan-code agentensemble-core/src/main/java/net/agentensemble/delegation
  scan-code agentensemble-memory/src/main/java/net/agentensemble/memory
  scan-code agentensemble-review/src/main/java/net/agentensemble/review
  scan-code agentensemble-tools/http/src/main/java/net/agentensemble/tool/http
  scan-code agentensemble-tools/process/src/main/java/net/agentensemble/tool/process
  scan-code agentensemble-metrics-micrometer/src/main/java/net/agentensemble/metrics
  scan-code agentensemble-devtools/src/main/java/net/agentensemble/devtools

TypeScript module example:
  scan-code agentensemble-viz/src/components
  scan-code agentensemble-viz/src/utils
```

---

## Execution Steps

### Step 1: Directory Discovery and Mapping

**Production Code Discovery:**

```bash
# Java module
find <path> -type f -name "*.java"

# TypeScript/React module
find <path> -type f \( -name "*.ts" -o -name "*.tsx" \)
```

**Determine the owning Gradle module** from the path prefix, for example:
- `agentensemble-core/src/main/java/...` -> module `:agentensemble-core`
- `agentensemble-tools/http/src/main/java/...` -> module `:agentensemble-tools:http`
- `agentensemble-memory/src/main/java/...` -> module `:agentensemble-memory`

**Test File Mapping:**

Map each production file to its corresponding test file:

**Java modules:**
```
agentensemble-core/src/main/java/net/agentensemble/agent/AgentExecutor.java
  ->
agentensemble-core/src/test/java/net/agentensemble/agent/AgentExecutorTest.java

agentensemble-tools/http/src/main/java/net/agentensemble/tool/http/HttpTool.java
  ->
agentensemble-tools/http/src/test/java/net/agentensemble/tool/http/HttpToolTest.java

agentensemble-memory/src/main/java/net/agentensemble/memory/MemoryStore.java
  ->
agentensemble-memory/src/test/java/net/agentensemble/memory/MemoryStoreTest.java
```

**TypeScript/React (agentensemble-viz):**
```
agentensemble-viz/src/utils/parser.ts
  ->
agentensemble-viz/src/__tests__/parser.test.ts

agentensemble-viz/src/components/graph/TaskNode.tsx
  ->
agentensemble-viz/src/__tests__/TaskNode.test.tsx  (or similar)
```

**Track missing test files.** These are high-priority findings.

---

### Step 2: Deep Analysis (File-by-File)

For each file, perform comprehensive analysis across 6 categories:

1. **Thread Safety and Concurrency**
2. **Logic Bugs and Edge Cases**
3. **Error Handling and Logging**
4. **Test Coverage Gaps**
5. **API Design and Public Surface**
6. **Code Quality and Maintainability**

For the `agentensemble-viz` TypeScript module, replace categories 1 and 5 with:
- **Frontend: Logic Bugs and Stale State**
- **Frontend: Rendering and UX correctness**

**Analysis Process:**
- Read each production file
- Map to test file (note if missing)
- Run through all relevant category checklists below
- Document findings with severity + file:line

---

#### Category 1: Thread Safety and Concurrency

The core execution engine runs agents concurrently via `ExecutorService`. Any shared state,
mutable fields, or external resources require careful scrutiny.

- [ ] **Shared mutable state**: Instance fields mutated across concurrent calls
  ```java
  // BAD: mutable field on a shared service instance
  public class AgentExecutor {
      private List<String> results = new ArrayList<>(); // NOT thread-safe

  // GOOD: build per-execution state locally
  public class AgentExecutor {
      public ExecutionResult execute(Agent agent, Task task, EnsembleContext ctx) {
          List<String> results = new ArrayList<>(); // local to this call
  ```

- [ ] **Executor lifecycle**: `ExecutorService` instances not shut down properly
  ```java
  // BAD
  ExecutorService exec = Executors.newFixedThreadPool(4);
  exec.invokeAll(tasks);
  // exec never shut down -- leaks threads

  // GOOD
  ExecutorService exec = Executors.newFixedThreadPool(4);
  try {
      exec.invokeAll(tasks);
  } finally {
      exec.shutdown();
  }
  ```

- [ ] **Immutability violations**: Domain objects (`Agent`, `Task`, `Ensemble`) should be
  effectively immutable after construction. Check for setters or mutable collections being
  returned from public getters.

- [ ] **Race conditions in callbacks**: `EnsembleListener` implementations invoked from
  multiple threads without synchronization.

- [ ] **Thread-unsafe collections**: `ArrayList`, `HashMap`, `HashSet` used in shared context.
  Use `CopyOnWriteArrayList`, `ConcurrentHashMap`, or `Collections.unmodifiableX` where appropriate.

- [ ] **LangChain4j resource leaks**: `ChatLanguageModel` and streaming handlers not closed
  when the call completes or fails.

**Severity: High to Critical** depending on whether the bug can cause data corruption, lost
results, or deadlocks at runtime.

---

#### Category 2: Logic Bugs and Edge Cases

- [ ] **Null returns from LLM**: LLM response parsed as null or empty -- not handled
  ```java
  // BAD
  String response = model.generate(prompt);
  return parser.parse(response); // NPE if response is null

  // GOOD
  String response = model.generate(prompt);
  if (response == null || response.isBlank()) {
      throw new AgentExecutionException("LLM returned empty response for agent: " + agent.getName());
  }
  return parser.parse(response);
  ```

- [ ] **Optional misuse**: `Optional.get()` called without `isPresent()` / `orElseThrow()`
  ```java
  // BAD
  Agent lead = ensemble.getLeadAgent().get();

  // GOOD
  Agent lead = ensemble.getLeadAgent()
      .orElseThrow(() -> new EnsembleConfigurationException("No lead agent configured"));
  ```

- [ ] **Tool result handling**: Tool returns error string vs. throws exception -- calling code
  must handle both consistently.

- [ ] **Delegation cycles**: Agent A delegates to Agent B which delegates back to A -- no
  cycle detection guard.

- [ ] **Off-by-one in retry logic**: `maxRetries = 3` but code loops `i <= maxRetries`
  (4 attempts).

- [ ] **Task dependency ordering**: Tasks with explicit dependencies executed before their
  dependencies complete.

- [ ] **Empty tool list**: Agent configured with no tools attempts a tool call -- should fail
  with a clear message, not a NullPointerException.

- [ ] **Template variable substitution**: Missing variables in `{{variable}}` placeholders
  silently left as literal text instead of failing early.

**Severity: High** for logic bugs in core execution paths.

---

#### Category 3: Error Handling and Logging

- [ ] **Swallowed exceptions**: Empty or log-only `catch` blocks that allow silent failures
  ```java
  // BAD
  try {
      tool.execute(input);
  } catch (Exception e) {
      log.warn("Tool failed"); // drops root cause, continues silently
  }

  // GOOD
  try {
      tool.execute(input);
  } catch (ToolExecutionException e) {
      log.error("Tool execution failed: tool={} input={}", tool.getName(), input, e);
      throw e; // or wrap in AgentExecutionException
  }
  ```

- [ ] **Missing context in log messages**: Logs that do not include agent name, task name,
  tool name, or relevant IDs make debugging nearly impossible.
  ```java
  // BAD
  log.error("Execution failed", e);

  // GOOD
  log.error("Agent execution failed: agent={} task={} attempt={}/{}",
      agent.getName(), task.getName(), attempt, maxRetries, e);
  ```

- [ ] **Wrong log levels**: Retryable failures logged as `ERROR`; unexpected failures logged
  as `WARN`.

- [ ] **Exception wrapping loses cause**: Wrapping exceptions without passing the original as
  the cause loses the stack trace.
  ```java
  // BAD
  throw new AgentExecutionException("Failed"); // loses original cause

  // GOOD
  throw new AgentExecutionException("Failed: " + e.getMessage(), e);
  ```

- [ ] **Error messages lack actionability**: Exceptions thrown with generic messages like
  "invalid configuration" instead of specifying what is invalid and how to fix it.

**Severity: Medium** (High if the gap prevents production diagnosis).

---

#### Category 4: Test Coverage Gaps

Reference: `.clinerules/testing-tdd.md`

**For each production file, verify:**

- [ ] Test file exists
- [ ] Happy path covered
- [ ] Failure/exception paths covered
- [ ] Boundary conditions covered (null input, empty list, maxRetries = 0, etc.)
- [ ] Integration-level test exists if the class interacts with LangChain4j models,
  other modules, or external I/O

**Common missing test scenarios for this codebase:**

**Core / agent execution:**
- [ ] LLM returns null or blank response
- [ ] LLM throws exception on first call but succeeds on retry
- [ ] Tool throws exception during execution
- [ ] Agent has no tools configured
- [ ] Delegation target agent does not exist in the ensemble
- [ ] Template variable missing from inputs map

**Memory module:**
- [ ] Store and retrieve across multiple runs
- [ ] Memory key collision / overwrite behavior
- [ ] Empty memory state on first run

**Tools:**
- [ ] Tool called with null or empty input
- [ ] Tool called with oversized input
- [ ] Tool encounters external failure (network error, file not found)
- [ ] Tool output exceeds expected format

**Metrics:**
- [ ] Metric recorded on success
- [ ] Metric recorded on failure with correct tag/reason
- [ ] No metric calls when module not on classpath (optional registration)

**Review / guardrails:**
- [ ] Guardrail passes valid output
- [ ] Guardrail rejects invalid output with descriptive reason
- [ ] Guardrail failure triggers correct ensemble-level behavior

**TypeScript (viz) specific:**
- [ ] Parser handles malformed JSON trace file
- [ ] Parser handles empty trace (no events)
- [ ] Graph layout handles disconnected nodes
- [ ] Component renders loading state
- [ ] Component renders error state
- [ ] Component renders empty state (no trace loaded)

**Test Quality Issues to Flag:**
- [ ] Tests asserting on implementation details (verify internal method call order vs. behavior)
- [ ] Tests using real LLM API calls (all LLM calls must be mocked)
- [ ] Tests that depend on ordering of a `Map` or `Set`
- [ ] Tests with no assertions (passes vacuously)

**Severity: Medium** for missing tests. **High** if a public API method has zero tests.

---

#### Category 5: API Design and Public Surface

This is a library -- the public API is a contract with external callers. Mistakes here require
a breaking change to fix.

- [ ] **Unnecessary public visibility**: Classes or methods that should be package-private or
  internal are exposed as `public`.
  ```java
  // BAD: internal helper leaked as public
  public class AgentPromptBuilder { // used only within agent package

  // GOOD
  class AgentPromptBuilder { // package-private
  ```

- [ ] **Mutable objects returned from public API**: Returning a live internal collection
  instead of an unmodifiable view allows callers to corrupt internal state.
  ```java
  // BAD
  public List<Agent> getAgents() { return this.agents; }

  // GOOD
  public List<Agent> getAgents() { return Collections.unmodifiableList(this.agents); }
  ```

- [ ] **Missing Javadoc on public types and methods**: Any public class, interface, or
  method without a Javadoc comment is incomplete. This includes parameter descriptions
  (`@param`), return value (`@return`), and exceptions (`@throws`).

- [ ] **Builder missing validation**: Builder `build()` methods that do not validate required
  fields will produce broken objects that fail at runtime with obscure errors.
  ```java
  // BAD
  public Agent build() { return new Agent(name, role, model); } // name could be null

  // GOOD
  public Agent build() {
      if (name == null || name.isBlank()) {
          throw new IllegalStateException("Agent name must not be null or blank");
      }
      Objects.requireNonNull(model, "ChatLanguageModel must not be null");
      return new Agent(name, role, model);
  }
  ```

- [ ] **Checked vs. unchecked exception choice**: Public API should throw unchecked exceptions
  (`AgentExecutionException`, `EnsembleConfigurationException`) unless the caller is
  genuinely expected to recover. Do not leak LangChain4j internal exceptions through the
  public surface.

- [ ] **SPI contracts**: Tool implementations (`ToolSpec` / `@Tool` methods) must clearly
  document parameter types, return type, and error behavior so integrators can implement
  them correctly.

- [ ] **Cross-module leakage**: A module should not expose transitive dependency types
  (e.g., LangChain4j internal types) on its public API surface unless intentional.

**Severity: High** for breaking API issues. **Medium** for documentation gaps.

---

#### Category 6: Code Quality and Maintainability

- [ ] **Magic numbers and strings**: Literal values not extracted to named constants
  ```java
  // BAD
  if (retryCount > 3) { ... }
  if (response.length() > 8192) { ... }

  // GOOD
  private static final int DEFAULT_MAX_RETRIES = 3;
  private static final int MAX_RESPONSE_LENGTH = 8192;
  ```

- [ ] **DRY violations**: Identical logic in two or more places; extract to a shared utility
  or base class.

- [ ] **Cyclomatic complexity**: Methods with too many branches (> 10) are difficult to test
  and maintain. Split into smaller, focused methods.

- [ ] **TODOs without issue references**: `// TODO: improve this` should be
  `// TODO(#123): improve this` -- linking to a tracked GitHub issue.

- [ ] **Dead code**: Unused methods, fields, imports, or classes. Error Prone catches many
  of these, but some slip through (e.g., methods that are public and thus not flagged).

- [ ] **Spotless violations**: Any file that would fail `./gradlew spotlessCheck` is a
  finding (though this should be caught by the pre-commit hook).

- [ ] **Error Prone suppressions**: Any `@SuppressWarnings` annotation should have a
  justification comment explaining why the suppression is safe.

**Severity: Low** unless the issue blocks readability or safe extension.

---

#### Supplemental: TypeScript / React (agentensemble-viz only)

Apply these checks when scanning `agentensemble-viz/src/`:

**Logic and State:**
- [ ] **Stale closures**: `useEffect` / `useCallback` / `useMemo` missing dependencies
- [ ] **Missing cleanup**: `useEffect` with async ops or timers not returning a cleanup function
  (causes state updates on unmounted components)
- [ ] **Reducer action exhaustiveness**: `useReducer` switch missing cases for defined actions
- [ ] **Unhandled promise rejections**: `async` calls in event handlers without `try/catch`
- [ ] **Race condition on file load**: Multiple files loaded in rapid succession -- last write
  wins but UI may show stale data

**Rendering:**
- [ ] **Missing loading state**: No loading indicator while trace file is being parsed
- [ ] **Missing error state**: No error message when parsing fails or file is malformed
- [ ] **Missing empty state**: No message when no trace is loaded

**Security:**
- [ ] **XSS via trace data**: Agent names, task names, or tool output rendered via
  `dangerouslySetInnerHTML` without sanitization (trace files are developer-supplied, but
  still worth flagging)
- [ ] **Unsafe external links**: URLs extracted from trace data used in `href` without validation

**Test coverage:**
- [ ] Parser tested with valid, malformed, and empty JSON inputs
- [ ] Graph layout utility tested for disconnected graphs, single-node graphs, cycles
- [ ] Components tested for loading / error / empty states with Vitest + Testing Library
- [ ] Tests use `userEvent` and accessible roles/labels, not CSS class selectors

**Build:**
- Verify with: `cd agentensemble-viz && npm run typecheck && npm test`

**Severity: Medium** for state bugs and missing states. **High** if graph rendering is
completely broken for a valid input shape.

---

### Step 3: Generate Findings Report

Create a structured Markdown report:

```markdown
# Code Scan Report: [path]

**Date:** [timestamp]
**Scanned By:** Cline AI Code Scanner
**Module:** [gradle module or agentensemble-viz]

## Summary

- **Files Scanned:** X
- **Test Files Found:** Y (Z missing)
- **Total Issues:** N

### Issues by Severity
- Critical: A
- High: B
- Medium: C
- Low: D

### Issues by Category
- Thread Safety / Concurrency: X
- Logic Bugs: Y
- Error Handling / Logging: Z
- Test Gaps: W
- API Design: V
- Code Quality: U

---

## Critical Issues (Fix Immediately)

### Missing cycle detection in DelegationContext

**Severity:** Critical
**Category:** Logic Bug
**File:** `agentensemble-core/src/main/java/net/agentensemble/delegation/DelegationContext.java:72`

**Risk:** Agent A delegates to Agent B which delegates to Agent A, causing a stack overflow
at runtime.

**Current Code:**
```java
public void delegate(DelegationRequest request) {
    Agent target = ensembleContext.findAgent(request.getTargetAgentName());
    target.execute(request.getTask());
}
```

**Fix:** Track delegation chain in context and throw before entering a cycle:
```java
public void delegate(DelegationRequest request) {
    if (delegationChain.contains(request.getTargetAgentName())) {
        throw new DelegationCycleException(
            "Delegation cycle detected: " + delegationChain + " -> " + request.getTargetAgentName()
        );
    }
    delegationChain.add(request.getTargetAgentName());
    Agent target = ensembleContext.findAgent(request.getTargetAgentName());
    target.execute(request.getTask());
}
```

**Effort:** S (2-4 hours including tests)

---

## High Priority Issues (Fix Soon)

[Same format]

---

## Medium Priority Issues (Address When Possible)

[Same format]

---

## Low Priority Issues (Nice to Have)

[Same format]

---

## Test Coverage Analysis

### Files Without Tests
1. `agentensemble-core/.../agent/AgentPromptBuilder.java` -- no test file found
2. `agentensemble-tools/http/.../HttpTool.java` -- no test file found

### Undertested Files
1. `AgentExecutor.java` -- has tests, but missing:
   - LLM returns null response
   - Tool throws exception on second retry

### Test Quality Issues
1. `EnsembleTest.java` -- makes real LangChain4j API calls (must be mocked)

---

## Observability Gaps

### Missing Log Context
1. `AgentExecutor.execute()` -- failure log does not include agent name or task name

### Missing Metrics
1. `MemoryStore.retrieve()` -- no counter for cache hit vs. miss
2. `HttpTool.execute()` -- no latency timer or failure counter

---

## Recommendations

1. **Immediate Actions** (Critical + High):
   - Add cycle detection to DelegationContext (#GH-XXX)
   - Add null check for LLM response in AgentExecutor (#GH-XXX)

2. **Next Sprint:**
   - Improve error handling context in agent failure logs
   - Add missing integration tests for HttpTool

3. **Technical Debt:**
   - Extract magic retry constant to named field in AgentExecutor
   - Add Javadoc to all public methods in agentensemble-memory

---

## Appendix: Files Analyzed

1. AgentExecutor.java
2. AgentPromptBuilder.java
3. ToolResolver.java
...
```

Save report to: `memory-bank/code-reviews/scan-[module-name]-[YYYY-MM-DD].md`

---

### Step 4: Create GitHub Issues

For **Critical** and **High Priority** findings, create GitHub Issues:

```bash
gh issue create \
  --title "[Code Scan] Missing cycle detection in DelegationContext" \
  --body "$(cat <<'BODY'
**Severity:** Critical
**Category:** Logic Bug
**Module:** agentensemble-core
**Location:** src/main/java/net/agentensemble/delegation/DelegationContext.java:72

**Risk:** Agent A delegates to Agent B which delegates back to Agent A, causing an
infinite recursion / stack overflow at runtime with no recoverable error.

---

## Implementation Steps

### Step 1: Add cycle detection field to DelegationContext
**File:** agentensemble-core/src/main/java/net/agentensemble/delegation/DelegationContext.java

**Action:** Add a Set to track the delegation chain and check before each delegation.

**Complete Code:**
\`\`\`java
// Inside DelegationContext constructor or builder:
private final Set<String> delegationChain = new LinkedHashSet<>();

public void delegate(DelegationRequest request) {
    String targetName = request.getTargetAgentName();
    if (delegationChain.contains(targetName)) {
        throw new DelegationCycleException(
            "Delegation cycle detected. Chain: " + delegationChain + " -> " + targetName
        );
    }
    delegationChain.add(targetName);
    try {
        Agent target = ensembleContext.findAgent(targetName);
        target.execute(request.getTask());
    } finally {
        delegationChain.remove(targetName);
    }
}
\`\`\`

### Step 2: Add DelegationCycleException
**File:** agentensemble-core/src/main/java/net/agentensemble/delegation/DelegationCycleException.java

\`\`\`java
package net.agentensemble.delegation;

public class DelegationCycleException extends RuntimeException {
    public DelegationCycleException(String message) {
        super(message);
    }
}
\`\`\`

### Step 3: Add tests
**File:** agentensemble-core/src/test/java/net/agentensemble/delegation/DelegationContextTest.java

\`\`\`java
@Test
void delegateShouldThrowWhenCycleDetected() {
    // Arrange: set up context where agentA delegates to agentB which delegates to agentA
    // Act + Assert: expect DelegationCycleException
}

@Test
void delegateShouldSucceedForLinearChain() {
    // Arrange: agentA -> agentB -> agentC (no cycle)
    // Act + Assert: no exception thrown, result is returned
}
\`\`\`

### Step 4: Verify
\`\`\`bash
./gradlew :agentensemble-core:test --tests DelegationContextTest --continue
\`\`\`

**Expected:**
\`\`\`
DelegationContextTest > delegateShouldThrowWhenCycleDetected PASSED
DelegationContextTest > delegateShouldSucceedForLinearChain PASSED
BUILD SUCCESSFUL
\`\`\`

---

## Verification Checklist
- [ ] DelegationCycleException class compiles
- [ ] DelegationContext compiles
- [ ] New unit tests pass
- [ ] All existing delegation tests still pass
- [ ] ./gradlew :agentensemble-core:build --continue passes
BODY
)" \
  --label "bug" \
  --label "high-priority" \
  --label "code-scan"
```

**Issue Template Fields:**

| Field | Value |
|-------|-------|
| **Title prefix** | `[Code Scan]` |
| **Labels** | `code-scan` + severity (`critical`, `high`) + category (`bug`, `test-gap`, etc.) |
| **Effort Estimate** | Include in body: xs / s / m / l / xl |
| **Description** | Must follow Copy-Paste Ready format (see below) |

**Effort Estimation:**
- **XS** (< 1 hour): Rename, config change, add constant, single-line fix
- **S** (1-4 hours): Simple fix, localized change, add unit tests
- **M** (1-2 days): Moderate complexity, multiple files
- **L** (3-5 days): Complex change, architectural impact, many tests
- **XL** (> 1 week): Major refactor, breaking API change

---

## Issue Quality Standards (Copy-Paste Ready Format)

**CRITICAL RULE:** Every issue description must be **copy-paste executable**. A developer
should be able to implement the fix by following the issue step-by-step without reading the
source code.

### Required Issue Structure

```markdown
**Severity:** [Critical | High | Medium | Low]
**Category:** [Thread Safety | Logic Bug | Error Handling | Test Gap | API Design | Code Quality]
**Module:** [e.g., agentensemble-core, agentensemble-tools/http]
**Location:** [file path]:[line range]

**Risk:** [impact description -- what goes wrong at runtime or for users of the library]

**Root Cause:** [technical explanation of why the bug exists]

---

## Implementation Steps

### Step 1: [Action Title]
**File:** `path/to/File.java`
**Method/Section:** [specific location]
**Lines:** [start-end]

**Action:** [What to do -- be specific]

**Complete Code:**
```java
// FULL implementation -- no abbreviations like "..."
// Include ALL necessary imports
// Include COMPLETE method bodies
public void completeMethod() {
    // Every line of code needed
}
```

### Step 2: Add / Update Tests
**File:** `path/to/test/FileTest.java`

**Complete Test Code:**
```java
// Entire new test class or full test methods -- ready to copy-paste
@Test
void behaviorUnderCondition() {
    // Arrange
    // Act
    // Assert
}
```

### Step 3: Verify Changes
**Commands:**
```bash
# For the owning module:
./gradlew :module-name:build --continue

# Or for a specific test class:
./gradlew :module-name:test --tests ClassName --no-daemon
```

**Expected Output:**
```
ClassName > test1 PASSED
ClassName > test2 PASSED
BUILD SUCCESSFUL
```

---

## Verification Checklist
- [ ] Code compiles without errors
- [ ] All new tests pass
- [ ] All existing tests in the module still pass
- [ ] ./gradlew :module:build --continue passes (spotless + errorprone + jacoco)
- [ ] Javadoc added for any new public types or methods
```

### Examples: Bad vs. Good Issues

**BAD Issue (too vague):**
```
Title: Fix null handling in AgentExecutor

AgentExecutor doesn't handle null responses from the model.
Fix: add a null check.
Effort: S
```
Problems: no file path, no line numbers, no code, no test, no commands to verify.

---

**GOOD Issue (copy-paste ready):**
```
Title: [Code Scan] AgentExecutor throws NPE on blank LLM response

**Severity:** High
**Category:** Logic Bug
**Module:** agentensemble-core
**Location:** agentensemble-core/src/main/java/net/agentensemble/agent/AgentExecutor.java:87-91

**Risk:** High -- blank or null LLM response causes NullPointerException with no useful error
message, making it impossible to diagnose which agent or task was responsible.

**Root Cause:** The return value of `model.generate(prompt)` is passed directly to
`structuredOutputHandler.parse()` without a null/blank guard. LangChain4j can return null
or blank when the model context window is exhausted or the model refuses to respond.

---

## Implementation Steps

### Step 1: Add null/blank guard in AgentExecutor
**File:** `agentensemble-core/src/main/java/net/agentensemble/agent/AgentExecutor.java`
**Method:** `executeWithRetry`
**Lines:** 87-91

**Action:** Add guard immediately after `model.generate(prompt)`:

**Complete replacement block:**
```java
String rawResponse = model.generate(prompt);
if (rawResponse == null || rawResponse.isBlank()) {
    throw new AgentExecutionException(
        String.format(
            "LLM returned empty response: agent=%s task=%s attempt=%d/%d",
            agent.getName(), task.getName(), attempt, maxRetries
        )
    );
}
return structuredOutputHandler.parse(rawResponse, task.getExpectedOutputType());
```

### Step 2: Add tests
**File:** `agentensemble-core/src/test/java/net/agentensemble/agent/AgentExecutorTest.java`

**Add these two test methods:**
```java
@Test
void executeShouldThrowWhenModelReturnsNull() {
    // Arrange
    ChatLanguageModel model = mock(ChatLanguageModel.class);
    when(model.generate(anyString())).thenReturn(null);
    Agent agent = Agent.builder().name("tester").model(model).build();
    Task task = Task.builder().description("do something").build();
    AgentExecutor executor = new AgentExecutor();

    // Act + Assert
    AgentExecutionException ex = assertThrows(
        AgentExecutionException.class,
        () -> executor.execute(agent, task, new EnsembleContext())
    );
    assertThat(ex.getMessage()).contains("empty response").contains("tester");
}

@Test
void executeShouldThrowWhenModelReturnsBlank() {
    ChatLanguageModel model = mock(ChatLanguageModel.class);
    when(model.generate(anyString())).thenReturn("   ");
    Agent agent = Agent.builder().name("tester").model(model).build();
    Task task = Task.builder().description("do something").build();
    AgentExecutor executor = new AgentExecutor();

    AgentExecutionException ex = assertThrows(
        AgentExecutionException.class,
        () -> executor.execute(agent, task, new EnsembleContext())
    );
    assertThat(ex.getMessage()).contains("empty response");
}
```

### Step 3: Verify
```bash
./gradlew :agentensemble-core:test --tests AgentExecutorTest --no-daemon
./gradlew :agentensemble-core:build --continue
```

**Expected output:**
```
AgentExecutorTest > executeShouldThrowWhenModelReturnsNull PASSED
AgentExecutorTest > executeShouldThrowWhenModelReturnsBlank PASSED
BUILD SUCCESSFUL
```

---

## Verification Checklist
- [ ] AgentExecutor compiles
- [ ] Both new tests pass
- [ ] All existing AgentExecutorTest cases still pass
- [ ] ./gradlew :agentensemble-core:build --continue passes
- [ ] Exception message includes agent name and task name
```

---

### Step 5: Update Memory Bank

After the scan is complete:

1. **Add entry to `memory-bank/progress.md`:**
   ```markdown
   ### Technical Debt from Code Scan ([date])
   - Scanned: [path]
   - Created issues: #NNN through #NNN (X total)
   - Critical issues: N
   - High issues: N
   - Report: memory-bank/code-reviews/scan-[module]-[date].md
   ```

2. **Update `memory-bank/activeContext.md`** if the scan reveals systemic patterns
   (e.g., consistent missing error context across all modules, shared thread safety
   anti-pattern)

3. **Update `memory-bank/systemPatterns.md`** if new architectural anti-patterns are
   identified that should guide future development

---

## Severity Classification Guide

**Critical** (Immediate fix required):
- Thread safety bugs that can cause data corruption or lost results at runtime
- Logic bugs that can cause silent wrong output (results appear to succeed but are wrong)
- Stack overflows or infinite loops in execution paths
- Public API that leaks internal state mutably

**High** (Fix soon):
- Logic bugs in non-critical paths
- Missing error context that blocks production diagnosis
- No tests at all for a public class
- Builder that accepts null required fields silently

**Medium** (Address when possible):
- Missing tests for edge cases (class has some tests, not complete)
- Missing log context for non-critical paths
- Missing metrics for observable operations
- Javadoc absent from public API methods
- Cross-module coupling that should be loosened

**Low** (Nice to have):
- Magic numbers / strings
- Dead code
- Complexity that is not yet a maintenance problem
- Code style observations beyond what Spotless enforces

---

## Example Usage Sessions

### Example 1: Delegation Module Audit

```
User: "scan-code agentensemble-core/src/main/java/net/agentensemble/delegation"

Files discovered: 4
  DelegationContext.java
  DelegationPriority.java
  DelegationRequest.java
  AgentDelegationTool.java

Tests: 3/4 found (AgentDelegationTool missing tests)

Analysis complete.

Critical (1):
  Missing cycle detection in DelegationContext.delegate()

High (2):
  AgentDelegationTool has no tests
  DelegationContext.delegate() swallows NotFoundException from findAgent

Medium (2):
  No log context (agent name) in delegation failure path
  DelegationRequest.builder() does not validate targetAgentName

Low (1):
  Magic number: timeout value 30 not extracted to constant

Report: memory-bank/code-reviews/scan-delegation-2026-03-05.md
GitHub Issues: #NNN through #NNN
```

### Example 2: Tool Module Audit

```
User: "scan-code agentensemble-tools/http/src/main/java/net/agentensemble/tool/http"

Files discovered: 3
  HttpTool.java
  HttpRequest.java
  HttpResponse.java

Tests: 2/3 found (HttpResponse missing dedicated tests -- covered inline)

Analysis complete.

Critical (0)

High (1):
  HttpTool.execute() does not set a connection timeout -- can block indefinitely

Medium (3):
  No metrics for request latency or failure rate
  Empty catch on IOException swallows network errors
  Missing Javadoc on HttpTool public methods

Low (1):
  Magic number: default timeout 5000ms should be named constant

Report: memory-bank/code-reviews/scan-http-tool-2026-03-05.md
GitHub Issues: #NNN through #NNN
```

---

## Build Verification Reference

**Run full build for a Java module** (includes Spotless, Error Prone, JaCoCo, tests):
```bash
./gradlew :agentensemble-core:build --continue
./gradlew :agentensemble-tools:http:build --continue
./gradlew :agentensemble-memory:build --continue
```

**Run a specific test class:**
```bash
./gradlew :agentensemble-core:test --tests AgentExecutorTest --no-daemon
```

**Run all tests across all modules:**
```bash
./gradlew build --continue
```

**TypeScript / viz module:**
```bash
cd agentensemble-viz
npm run typecheck
npm test
```

---

## Best Practices

1. **Scan one directory at a time** -- do not try to scan the entire codebase in one pass
2. **Fix before scanning more** -- avoid accumulating hundreds of open issues
3. **Rotate through modules** -- core, memory, review, metrics, each tool, viz
4. **Before releases** -- scan modules changed since the last release tag
5. **After incidents** -- scan the execution path involved in the failure
6. **Batch similar issues** -- one issue for "add Javadoc to all public methods in X module"
   is better than ten individual issues

---

## Definition of Done

A code scan is complete when:
- [ ] All files in the target directory have been analyzed
- [ ] Test coverage has been mapped (missing files tracked)
- [ ] Report generated and saved to `memory-bank/code-reviews/`
- [ ] GitHub Issues created for all Critical and High findings
- [ ] Memory bank updated (`progress.md` at minimum)
- [ ] User has reviewed the report and issue summaries
- [ ] Every issue description is copy-paste executable:
  - Complete code blocks (no `...` abbreviations)
  - Exact file paths and line numbers
  - Full test method bodies ready to paste
  - Exact `./gradlew` commands with expected output
  - Verification checklist with concrete items
