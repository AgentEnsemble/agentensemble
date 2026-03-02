# 12 - Testing Strategy

This document specifies the test plan, test cases, mocking approach, and testing conventions.

## Test Framework and Dependencies

| Dependency | Purpose |
|---|---|
| JUnit 5 (Jupiter) | Test framework |
| AssertJ | Fluent assertions |
| Mockito | Mocking `ChatLanguageModel`, tools, etc. |
| SLF4J Simple or Logback Test | Logging during tests |

## Test Conventions

- **Test class naming**: `{ClassName}Test.java` for unit tests, `{Feature}IntegrationTest.java` for integration tests
- **Test method naming**: `testMethodName_scenario_expectedBehavior` or descriptive `@DisplayName`
- **Structure**: Arrange / Act / Assert pattern
- **No test order dependencies**: Each test is independent and self-contained
- **No real network calls**: All LLM interactions are mocked
- **Deterministic**: No reliance on time, randomness, or external state

## Test Directory Structure

```
agentensemble-core/src/test/java/io/agentensemble/
  AgentTest.java                              # Agent builder validation
  TaskTest.java                               # Task builder validation
  EnsembleTest.java                           # Ensemble validation (run() preconditions)
  agent/
    AgentExecutorTest.java                    # Agent execution with mocked LLM
    AgentPromptBuilderTest.java               # Prompt construction
  workflow/
    SequentialWorkflowExecutorTest.java       # Sequential execution logic
  tool/
    LangChain4jToolAdapterTest.java           # Tool adaptation
    ToolResultTest.java                       # ToolResult factory methods
  config/
    TemplateResolverTest.java                 # Template variable substitution
  integration/
    SequentialEnsembleIntegrationTest.java    # End-to-end sequential ensemble
    ToolUseIntegrationTest.java               # Agent with tools, mocked LLM
    ErrorHandlingIntegrationTest.java         # Error scenarios end-to-end
```

## Mocking Strategy

### Mocking the LLM

LangChain4j's `ChatLanguageModel` is mocked using Mockito:

```java
ChatLanguageModel mockLlm = mock(ChatLanguageModel.class);

// For simple text response (no tools):
when(mockLlm.generate(anyList()))
    .thenReturn(Response.from(AiMessage.from("Mocked response text")));

// For tool call response:
when(mockLlm.generate(anyList(), anyList()))
    .thenReturn(Response.from(AiMessage.from(
        ToolExecutionRequest.builder()
            .name("web_search")
            .arguments("{\"input\": \"AI trends 2026\"}")
            .build())))
    .thenReturn(Response.from(AiMessage.from("Final answer after tool use")));
```

### Mocking Tools

For `AgentTool` implementations:

```java
AgentTool mockTool = mock(AgentTool.class);
when(mockTool.name()).thenReturn("web_search");
when(mockTool.description()).thenReturn("Search the web");
when(mockTool.execute(anyString())).thenReturn(ToolResult.success("Search results..."));
```

## Unit Test Cases

### AgentTest.java

| Test | Description |
|---|---|
| `testBuild_withAllFields_succeeds` | Build with every field set, verify all values |
| `testBuild_withMinimalFields_succeeds` | Build with only role + goal + llm |
| `testBuild_withNullRole_throwsValidation` | Null role throws `ValidationException` |
| `testBuild_withBlankRole_throwsValidation` | Blank/empty role throws `ValidationException` |
| `testBuild_withNullGoal_throwsValidation` | Null goal throws `ValidationException` |
| `testBuild_withBlankGoal_throwsValidation` | Blank goal throws `ValidationException` |
| `testBuild_withNullLlm_throwsValidation` | Null LLM throws `ValidationException` |
| `testBuild_withZeroMaxIterations_throwsValidation` | maxIterations=0 throws |
| `testBuild_withNegativeMaxIterations_throwsValidation` | maxIterations=-1 throws |
| `testBuild_withInvalidToolObject_throwsValidation` | Object that is not AgentTool and has no @Tool methods |
| `testBuild_withAgentToolAndAnnotatedTool_succeeds` | Mixed tool types in same list |
| `testDefaultValues_areCorrect` | Verify verbose=false, maxIterations=25, etc. |
| `testToBuilder_createsModifiedCopy` | Modify one field via toBuilder, others unchanged |
| `testToolsList_isImmutable` | Verify tools list cannot be modified after build |

### TaskTest.java

| Test | Description |
|---|---|
| `testBuild_withAllFields_succeeds` | Build with every field set |
| `testBuild_withMinimalFields_succeeds` | Build with only description + expectedOutput + agent |
| `testBuild_withNullDescription_throwsValidation` | Null description throws |
| `testBuild_withBlankDescription_throwsValidation` | Blank description throws |
| `testBuild_withNullExpectedOutput_throwsValidation` | Null expectedOutput throws |
| `testBuild_withBlankExpectedOutput_throwsValidation` | Blank expectedOutput throws |
| `testBuild_withNullAgent_throwsValidation` | Null agent throws |
| `testBuild_withSelfReference_throwsValidation` | Task in its own context list |
| `testBuild_withEmptyContext_succeeds` | Default empty context list |
| `testContextList_isImmutable` | Verify context list cannot be modified after build |

### EnsembleTest.java

Tests call `run()` which triggers validation before execution.

| Test | Description |
|---|---|
| `testRun_withEmptyTasks_throwsValidation` | No tasks throws |
| `testRun_withEmptyAgents_throwsValidation` | No agents throws |
| `testRun_withUnregisteredAgent_throwsValidation` | Task references agent not in ensemble |
| `testRun_withCircularContext_throwsValidation` | Tasks A->B->A circular reference |
| `testRun_withContextOrderViolation_throwsValidation` | Task references context task appearing later |
| `testRun_withUnusedAgent_logsWarning` | Agent in list but no task uses it |
| `testRun_withValidConfig_executesSuccessfully` | Happy path with mocked LLM |
| `testRun_withInputs_resolvedTemplates` | Template variables are substituted |
| `testRun_calledMultipleTimes_independent` | Each run is independent |

### TemplateResolverTest.java

| Test | Description |
|---|---|
| `testResolve_simpleVariable` | `{topic}` -> value |
| `testResolve_multipleVariables` | `{a} and {b}` -> values |
| `testResolve_missingVariable_throwsAll` | Reports ALL missing, not just first |
| `testResolve_escapedBraces_returnsLiteral` | `{{var}}` -> `{var}` |
| `testResolve_emptyValue_replacesWithEmpty` | `{x}` with value="" -> "" |
| `testResolve_nullValue_replacesWithEmpty` | `{x}` with value=null -> "" |
| `testResolve_noVariables_returnsUnchanged` | No placeholders, returns as-is |
| `testResolve_nullTemplate_returnsNull` | null input -> null output |
| `testResolve_blankTemplate_returnsBlank` | "" input -> "" output |
| `testResolve_nullInputs_treatedAsEmpty` | null map treated as empty |
| `testResolve_sameVariableMultipleTimes` | `{a}{a}` -> "XX" |
| `testResolve_underscoreInName` | `{under_score}` is valid |
| `testResolve_extraInputsIgnored` | Unused keys in map are ignored |

### AgentPromptBuilderTest.java

| Test | Description |
|---|---|
| `testBuildSystemPrompt_withAllFields` | Role + background + goal + responseFormat |
| `testBuildSystemPrompt_withoutBackground` | Background=null, omitted |
| `testBuildSystemPrompt_withEmptyBackground` | Background="", omitted |
| `testBuildSystemPrompt_withoutResponseFormat` | responseFormat="", omitted |
| `testBuildSystemPrompt_minimalAgent` | Only role + goal |
| `testBuildUserPrompt_withoutContext` | No context, just task + expected output |
| `testBuildUserPrompt_withSingleContext` | One prior task output |
| `testBuildUserPrompt_withMultipleContexts` | Multiple prior outputs, order preserved |
| `testBuildUserPrompt_contextOrderPreserved` | Outputs render in context list order |
| `testBuildUserPrompt_emptyContextOutput` | Context with empty raw text, headers still shown |

### LangChain4jToolAdapterTest.java

| Test | Description |
|---|---|
| `testAdapt_createsCorrectSpec` | ToolSpecification name, description, parameters match |
| `testAdapt_execution_delegatesToAgentTool` | Calls execute(), returns output |
| `testAdapt_executionError_returnsErrorString` | ToolResult.failure -> "Error: ..." |
| `testAdapt_executionThrows_returnsErrorString` | execute() throws -> caught, returns error |
| `testAdapt_nullResult_returnsEmpty` | execute() returns null -> "" |

### ToolResultTest.java

| Test | Description |
|---|---|
| `testSuccess_withOutput` | success=true, output set, errorMessage=null |
| `testSuccess_withNullOutput` | Null coerced to empty string |
| `testFailure_withMessage` | success=false, errorMessage set, output="" |

## Integration Test Cases

### SequentialEnsembleIntegrationTest.java

| Test | Description |
|---|---|
| `testTwoTaskEnsemble_contextPassedCorrectly` | Mock LLM returns "Research X", verify second task's prompt contains "Research X" |
| `testSingleTaskEnsemble_executesSuccessfully` | One task, one agent, verify output |
| `testThreeTaskChain_allContextFlows` | Task 3 depends on Task 1 and 2, verify both in context |
| `testTemplateSubstitution_variablesResolvedInPrompts` | {topic} resolved before execution, verify LLM receives resolved text |
| `testEnsembleOutput_containsAllTaskOutputs` | Verify EnsembleOutput has correct taskOutputs list |
| `testEnsembleOutput_rawIsLastTaskOutput` | raw field equals the final task's output |
| `testEnsembleOutput_durationsArePositive` | Duration fields are > 0 |

### ToolUseIntegrationTest.java

| Test | Description |
|---|---|
| `testAgentWithTool_toolCalledAndResultUsed` | LLM calls tool, tool returns result, LLM uses result in final answer |
| `testAgentWithMultipleToolCalls_allExecuted` | LLM calls tool A then tool B then produces answer |
| `testAgentWithToolError_errorFedBackToLlm` | Tool throws, error message sent to LLM, LLM produces answer |
| `testAgentWithToolFailureResult_errorFedBack` | Tool returns ToolResult.failure(), error sent to LLM |
| `testMaxIterations_sendsStopMessages` | LLM always calls tools, verify stop messages sent |
| `testMaxIterations_throwsAfterThreeStopMessages` | After 3 stops, MaxIterationsExceededException thrown |
| `testNoTools_directLlmCall` | Agent with no tools, verify single generate() call |
| `testMixedTools_bothTypesWork` | Agent with AgentTool + @Tool annotated, both called |

### ErrorHandlingIntegrationTest.java

| Test | Description |
|---|---|
| `testLlmThrows_wrappedInTaskExecutionException` | LLM throws RuntimeException, wrapped properly |
| `testSecondTaskFails_firstOutputPreserved` | Two tasks, second fails, first output in exception |
| `testMissingTemplateVariable_throwsBeforeExecution` | PromptTemplateException, LLM never called |
| `testValidationFailure_noTasksExecuted` | ValidationException, no LLM interaction |
| `testTaskExecutionException_hasCorrectContext` | Exception fields (taskDescription, agentRole, completedOutputs) are correct |

## Test Execution

Tests are run via Gradle:

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "io.agentensemble.AgentTest"

# Run specific test method
./gradlew test --tests "io.agentensemble.AgentTest.testBuild_withNullRole_throwsValidation"

# Run integration tests only
./gradlew test --tests "io.agentensemble.integration.*"
```

## Coverage Expectations

- All public methods of domain objects: 100% branch coverage
- All validation paths: tested with both valid and invalid inputs
- All exception types: at least one test that triggers each
- Template resolver: all edge cases from the matrix in 07-template-resolver.md
- Prompt builder: all conditional sections (with/without background, context, etc.)
- Agent executor: tool loop, no-tool path, error handling, max iterations
- Workflow executor: context passing, error propagation, output assembly
