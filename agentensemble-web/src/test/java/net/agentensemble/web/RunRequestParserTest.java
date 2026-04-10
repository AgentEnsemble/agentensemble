package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import java.util.Map;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.execution.RunOptions;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RunRequestParser}: Level 1 and Level 2 configuration building.
 */
class RunRequestParserTest {

    private RunRequestParser parser;
    private Ensemble template;

    // Catalog-configured parser for Level 2 tests
    private RunRequestParser parserWithCatalogs;
    private ToolCatalog toolCatalog;
    private ModelCatalog modelCatalog;

    // Named task and unnamed task for override matching tests
    private Task namedTask;
    private Task unnamedTask;
    private AgentTool webSearchTool;
    private AgentTool calculatorTool;
    private ChatModel mockModel;

    @BeforeEach
    void setUp() {
        parser = new RunRequestParser(null, null);
        template = mock(Ensemble.class);

        // Build real tools for catalog
        webSearchTool = new AgentTool() {
            @Override
            public String name() {
                return "web_search";
            }

            @Override
            public String description() {
                return "Search the web";
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.success("results");
            }
        };

        calculatorTool = new AgentTool() {
            @Override
            public String name() {
                return "calculator";
            }

            @Override
            public String description() {
                return "Math operations";
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.success("42");
            }
        };

        mockModel = mock(ChatModel.class);

        toolCatalog = ToolCatalog.builder()
                .tool("web_search", webSearchTool)
                .tool("calculator", calculatorTool)
                .build();

        modelCatalog = ModelCatalog.builder().model("sonnet", mockModel).build();

        parserWithCatalogs = new RunRequestParser(toolCatalog, modelCatalog);

        // Named task: has name field set
        namedTask = Task.builder()
                .name("researcher")
                .description("Research AI trends in enterprise software")
                .expectedOutput("A detailed report")
                .build();

        // Unnamed task: no name, matched by description prefix
        unnamedTask = Task.builder()
                .description("Summarize the key findings from research")
                .expectedOutput("An executive summary")
                .build();
    }

    // ========================
    // buildFromTemplate -- null guards
    // ========================

    @Test
    void buildFromTemplate_nullTemplate_throws() {
        assertThatThrownBy(() -> parser.buildFromTemplate(null, Map.of(), RunOptions.DEFAULT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("template ensemble must not be null");
    }

    // ========================
    // buildFromTemplate -- happy paths
    // ========================

    @Test
    void buildFromTemplate_withInputs_returnsConfigurationWithCopiedInputs() {
        Map<String, String> inputs = Map.of("topic", "AI safety", "year", "2025");
        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, inputs, null);

        assertThat(config.template()).isSameAs(template);
        assertThat(config.inputs()).containsEntry("topic", "AI safety").containsEntry("year", "2025");
        assertThat(config.options()).isSameAs(RunOptions.DEFAULT);
    }

    @Test
    void buildFromTemplate_nullInputs_defaultsToEmpty() {
        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, null, null);
        assertThat(config.inputs()).isEmpty();
    }

    @Test
    void buildFromTemplate_emptyInputs_returnsEmptyInputs() {
        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, Map.of(), null);
        assertThat(config.inputs()).isEmpty();
    }

    @Test
    void buildFromTemplate_withOptions_preservesOptions() {
        RunOptions options = RunOptions.builder().maxToolOutputLength(5000).build();
        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, Map.of(), options);
        assertThat(config.options()).isSameAs(options);
    }

    @Test
    void buildFromTemplate_nullOptions_defaultsToRunOptionsDefault() {
        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, Map.of(), null);
        assertThat(config.options()).isSameAs(RunOptions.DEFAULT);
    }

    @Test
    void buildFromTemplate_inputsAreCopied_mutatingOriginalDoesNotAffectConfig() {
        Map<String, String> mutableInputs = new java.util.HashMap<>();
        mutableInputs.put("topic", "AI");

        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, mutableInputs, null);
        mutableInputs.put("year", "2025"); // mutate after building

        assertThat(config.inputs()).doesNotContainKey("year");
    }

    // ========================
    // Catalog accessors
    // ========================

    @Test
    void getToolCatalog_returnsNullWhenNotConfigured() {
        assertThat(parser.getToolCatalog()).isNull();
    }

    @Test
    void getModelCatalog_returnsNullWhenNotConfigured() {
        assertThat(parser.getModelCatalog()).isNull();
    }

    @Test
    void withCatalogs_returnsConfiguredCatalogs() {
        ToolCatalog tc = ToolCatalog.builder().build();
        ModelCatalog mc = ModelCatalog.builder().build();
        RunRequestParser parserWithCatalogs = new RunRequestParser(tc, mc);

        assertThat(parserWithCatalogs.getToolCatalog()).isSameAs(tc);
        assertThat(parserWithCatalogs.getModelCatalog()).isSameAs(mc);
    }

    // ========================
    // buildFromTemplate -- overrideTasks is null (Level 1 backward compat)
    // ========================

    @Test
    void buildFromTemplate_overrideTasks_isNull() {
        RunRequestParser.RunConfiguration config = parser.buildFromTemplate(template, Map.of(), null);
        assertThat(config.overrideTasks()).isNull();
    }

    // ========================
    // Level 2: buildFromTemplateWithOverrides -- null/empty overrides
    // ========================

    @Test
    void buildFromTemplateWithOverrides_nullTemplate_throws() {
        assertThatThrownBy(() -> parserWithCatalogs.buildFromTemplateWithOverrides(null, Map.of(), null, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("template ensemble must not be null");
    }

    @Test
    void buildFromTemplateWithOverrides_emptyOverrides_returnsSameTaskList() {
        when(template.getTasks()).thenReturn(List.of(namedTask, unnamedTask));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromTemplateWithOverrides(template, Map.of("topic", "AI"), null, Map.of());

        assertThat(config.overrideTasks()).hasSize(2);
        assertThat(config.overrideTasks().get(0).getDescription()).isEqualTo(namedTask.getDescription());
        assertThat(config.overrideTasks().get(1).getDescription()).isEqualTo(unnamedTask.getDescription());
    }

    @Test
    void buildFromTemplateWithOverrides_nullOverrides_returnsSameTaskList() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromTemplateWithOverrides(template, Map.of(), null, null);

        assertThat(config.overrideTasks()).hasSize(1);
        assertThat(config.overrideTasks().get(0).getDescription()).isEqualTo(namedTask.getDescription());
    }

    @Test
    void buildFromTemplateWithOverrides_preservesInputsAndOptions() {
        when(template.getTasks()).thenReturn(List.of(namedTask));
        RunOptions options = RunOptions.builder().maxToolOutputLength(3000).build();

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromTemplateWithOverrides(template, Map.of("topic", "AI"), options, Map.of());

        assertThat(config.inputs()).containsEntry("topic", "AI");
        assertThat(config.options()).isSameAs(options);
        assertThat(config.template()).isSameAs(template);
    }

    // ========================
    // Level 2: override matching -- by name
    // ========================

    @Test
    void buildFromTemplateWithOverrides_matchByExactName_updatesDescription() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        Map<String, Map<String, Object>> overrides =
                Map.of("researcher", Map.of("description", "Research EU AI regulation"));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromTemplateWithOverrides(template, Map.of(), null, overrides);

        assertThat(config.overrideTasks()).hasSize(1);
        assertThat(config.overrideTasks().get(0).getDescription()).isEqualTo("Research EU AI regulation");
        assertThat(config.overrideTasks().get(0).getName()).isEqualTo("researcher");
    }

    @Test
    void buildFromTemplateWithOverrides_matchByExactName_caseInsensitive() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        Map<String, Map<String, Object>> overrides =
                Map.of("RESEARCHER", Map.of("description", "Research EU AI regulation"));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromTemplateWithOverrides(template, Map.of(), null, overrides);

        assertThat(config.overrideTasks().get(0).getDescription()).isEqualTo("Research EU AI regulation");
    }

    // ========================
    // Level 2: override matching -- by description prefix
    // ========================

    @Test
    void buildFromTemplateWithOverrides_matchByDescriptionPrefix_updatesDescription() {
        when(template.getTasks()).thenReturn(List.of(unnamedTask));

        // Override key is the description prefix (first 50 chars)
        String prefix = unnamedTask
                .getDescription()
                .substring(0, Math.min(50, unnamedTask.getDescription().length()));
        Map<String, Map<String, Object>> overrides = Map.of(prefix, Map.of("description", "Write a concise summary"));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromTemplateWithOverrides(template, Map.of(), null, overrides);

        assertThat(config.overrideTasks().get(0).getDescription()).isEqualTo("Write a concise summary");
    }

    @Test
    void buildFromTemplateWithOverrides_matchByDescriptionPrefix_caseInsensitive() {
        when(template.getTasks()).thenReturn(List.of(unnamedTask));

        String prefix = unnamedTask
                .getDescription()
                .substring(0, Math.min(50, unnamedTask.getDescription().length()))
                .toUpperCase();
        Map<String, Map<String, Object>> overrides = Map.of(prefix, Map.of("expectedOutput", "A brief summary"));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromTemplateWithOverrides(template, Map.of(), null, overrides);

        assertThat(config.overrideTasks().get(0).getExpectedOutput()).isEqualTo("A brief summary");
    }

    // ========================
    // Level 2: unknown override key
    // ========================

    @Test
    void buildFromTemplateWithOverrides_unknownOverrideKey_throws() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        Map<String, Map<String, Object>> overrides = Map.of("nonexistent-task", Map.of("description", "Updated"));

        assertThatThrownBy(() -> parserWithCatalogs.buildFromTemplateWithOverrides(template, Map.of(), null, overrides))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent-task");
    }

    // ========================
    // Level 2: override fields -- description and expectedOutput
    // ========================

    @Test
    void buildFromTemplateWithOverrides_overrideDescription_replaced() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template,
                Map.of(),
                null,
                Map.of("researcher", Map.of("description", "Research {topic} focusing on EU regulation")));

        assertThat(config.overrideTasks().get(0).getDescription())
                .isEqualTo("Research {topic} focusing on EU regulation");
    }

    @Test
    void buildFromTemplateWithOverrides_overrideExpectedOutput_replaced() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template,
                Map.of(),
                null,
                Map.of("researcher", Map.of("expectedOutput", "A regulatory analysis report")));

        assertThat(config.overrideTasks().get(0).getExpectedOutput()).isEqualTo("A regulatory analysis report");
        // Original description unchanged
        assertThat(config.overrideTasks().get(0).getDescription()).isEqualTo(namedTask.getDescription());
    }

    // ========================
    // Level 2: override fields -- maxIterations
    // ========================

    @Test
    void buildFromTemplateWithOverrides_overrideMaxIterations_replaced() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("researcher", Map.of("maxIterations", 15)));

        assertThat(config.overrideTasks().get(0).getMaxIterations()).isEqualTo(15);
    }

    @Test
    void buildFromTemplateWithOverrides_overrideMaxIterations_fromString() {
        // maxIterations may arrive as a String from JSON deserialization
        when(template.getTasks()).thenReturn(List.of(namedTask));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("researcher", Map.of("maxIterations", "20")));

        assertThat(config.overrideTasks().get(0).getMaxIterations()).isEqualTo(20);
    }

    // ========================
    // Level 2: override fields -- model
    // ========================

    @Test
    void buildFromTemplateWithOverrides_overrideModel_resolvedFromCatalog() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("researcher", Map.of("model", "sonnet")));

        assertThat(config.overrideTasks().get(0).getChatLanguageModel()).isSameAs(mockModel);
    }

    @Test
    void buildFromTemplateWithOverrides_overrideModel_unknownAlias_throws() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        assertThatThrownBy(() -> parserWithCatalogs.buildFromTemplateWithOverrides(
                        template, Map.of(), null, Map.of("researcher", Map.of("model", "unknown-model"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-model");
    }

    @Test
    void buildFromTemplateWithOverrides_overrideModel_noCatalog_throws() {
        // Parser without model catalog cannot resolve model aliases
        when(template.getTasks()).thenReturn(List.of(namedTask));

        assertThatThrownBy(() -> parser.buildFromTemplateWithOverrides(
                        template, Map.of(), null, Map.of("researcher", Map.of("model", "sonnet"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ModelCatalog");
    }

    // ========================
    // Level 2: override fields -- additionalContext (appended to description)
    // ========================

    @Test
    void buildFromTemplateWithOverrides_overrideAdditionalContext_appendedToDescription() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template,
                Map.of(),
                null,
                Map.of("researcher", Map.of("additionalContext", "The EU AI Act passed in 2024.")));

        String description = config.overrideTasks().get(0).getDescription();
        assertThat(description).startsWith(namedTask.getDescription());
        assertThat(description).contains("The EU AI Act passed in 2024.");
    }

    // ========================
    // Level 2: override fields -- tools.add and tools.remove
    // ========================

    @Test
    void buildFromTemplateWithOverrides_toolsAdd_appendsToolToTaskList() {
        Task taskWithCalculator = Task.builder()
                .name("analyst")
                .description("Analyse data using tools")
                .expectedOutput("Analysis result")
                .tools(List.of(calculatorTool))
                .build();
        when(template.getTasks()).thenReturn(List.of(taskWithCalculator));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("analyst", Map.of("tools", Map.of("add", List.of("web_search")))));

        List<Object> tools = config.overrideTasks().get(0).getTools();
        assertThat(tools).hasSize(2);
        assertThat(tools).contains(calculatorTool, webSearchTool);
    }

    @Test
    void buildFromTemplateWithOverrides_toolsRemove_removesToolFromTaskList() {
        Task taskWithBothTools = Task.builder()
                .name("analyst")
                .description("Analyse data")
                .expectedOutput("Analysis result")
                .tools(List.of(calculatorTool, webSearchTool))
                .build();
        when(template.getTasks()).thenReturn(List.of(taskWithBothTools));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("analyst", Map.of("tools", Map.of("remove", List.of("calculator")))));

        List<Object> tools = config.overrideTasks().get(0).getTools();
        assertThat(tools).hasSize(1);
        assertThat(tools).contains(webSearchTool);
        assertThat(tools).doesNotContain(calculatorTool);
    }

    @Test
    void buildFromTemplateWithOverrides_toolsAdd_unknownTool_throws() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        assertThatThrownBy(() -> parserWithCatalogs.buildFromTemplateWithOverrides(
                        template,
                        Map.of(),
                        null,
                        Map.of("researcher", Map.of("tools", Map.of("add", List.of("nonexistent_tool"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent_tool");
    }

    @Test
    void buildFromTemplateWithOverrides_toolsAdd_noCatalog_throws() {
        // Parser without tool catalog cannot resolve tool names
        Task aTask = Task.builder()
                .name("researcher")
                .description("Research")
                .expectedOutput("Report")
                .build();
        when(template.getTasks()).thenReturn(List.of(aTask));

        assertThatThrownBy(() -> parser.buildFromTemplateWithOverrides(
                        template,
                        Map.of(),
                        null,
                        Map.of("researcher", Map.of("tools", Map.of("add", List.of("web_search"))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ToolCatalog");
    }

    // ========================
    // Level 2: combined overrides on same task
    // ========================

    @Test
    void buildFromTemplateWithOverrides_combinedOverrides_allApplied() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template,
                Map.of(),
                null,
                Map.of(
                        "researcher",
                        Map.of(
                                "description", "Research {topic} focusing on EU regulation",
                                "expectedOutput", "A regulatory analysis report",
                                "maxIterations", 15,
                                "model", "sonnet")));

        Task overridden = config.overrideTasks().get(0);
        assertThat(overridden.getDescription()).isEqualTo("Research {topic} focusing on EU regulation");
        assertThat(overridden.getExpectedOutput()).isEqualTo("A regulatory analysis report");
        assertThat(overridden.getMaxIterations()).isEqualTo(15);
        assertThat(overridden.getChatLanguageModel()).isSameAs(mockModel);
    }

    // ========================
    // Level 2: multiple tasks, only some overridden
    // ========================

    @Test
    void buildFromTemplateWithOverrides_multipleTasksOneOverridden_onlyMatchingTaskUpdated() {
        when(template.getTasks()).thenReturn(List.of(namedTask, unnamedTask));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("researcher", Map.of("description", "Research EU regulation")));

        assertThat(config.overrideTasks()).hasSize(2);
        // Named task updated
        assertThat(config.overrideTasks().get(0).getDescription()).isEqualTo("Research EU regulation");
        // Unnamed task unchanged
        assertThat(config.overrideTasks().get(1).getDescription()).isEqualTo(unnamedTask.getDescription());
    }

    // ========================
    // Level 2: original tasks not mutated
    // ========================

    @Test
    void buildFromTemplateWithOverrides_originalTasksNotMutated() {
        when(template.getTasks()).thenReturn(List.of(namedTask));
        String originalDescription = namedTask.getDescription();

        parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("researcher", Map.of("description", "New description")));

        assertThat(namedTask.getDescription()).isEqualTo(originalDescription);
    }

    // ========================
    // Level 3: buildFromDynamicTasks
    // ========================

    @Test
    void buildFromDynamicTasks_nullTemplate_throws() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "writer",
                "description", "Write a report",
                "expectedOutput", "A report"));

        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(null, taskDefs, Map.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("template ensemble must not be null");
    }

    @Test
    void buildFromDynamicTasks_nullTaskDefs_throws() {
        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, null, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tasks");
    }

    @Test
    void buildFromDynamicTasks_emptyTaskDefs_throws() {
        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, List.of(), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tasks");
    }

    @Test
    void buildFromDynamicTasks_singleTask_buildsCorrectTask() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "researcher",
                "description", "Research {topic} trends",
                "expectedOutput", "A detailed report"));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of("topic", "AI"), null);

        assertThat(config.template()).isSameAs(template);
        assertThat(config.inputs()).containsEntry("topic", "AI");
        assertThat(config.overrideTasks()).hasSize(1);

        Task task = config.overrideTasks().get(0);
        assertThat(task.getName()).isEqualTo("researcher");
        assertThat(task.getDescription()).isEqualTo("Research {topic} trends");
        assertThat(task.getExpectedOutput()).isEqualTo("A detailed report");
    }

    @Test
    void buildFromDynamicTasks_taskWithTools_toolsResolvedFromCatalog() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "researcher",
                "description", "Research AI",
                "expectedOutput", "Report",
                "tools", List.of("web_search", "calculator")));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null);

        Task task = config.overrideTasks().get(0);
        assertThat(task.getTools()).hasSize(2);
        assertThat(task.getTools()).contains(webSearchTool, calculatorTool);
    }

    @Test
    void buildFromDynamicTasks_taskWithModel_modelResolvedFromCatalog() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "researcher",
                "description", "Research AI",
                "expectedOutput", "Report",
                "model", "sonnet"));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null);

        assertThat(config.overrideTasks().get(0).getChatLanguageModel()).isSameAs(mockModel);
    }

    @Test
    void buildFromDynamicTasks_taskWithMaxIterations_applied() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "researcher",
                "description", "Research AI",
                "expectedOutput", "Report",
                "maxIterations", 20));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null);

        assertThat(config.overrideTasks().get(0).getMaxIterations()).isEqualTo(20);
    }

    @Test
    void buildFromDynamicTasks_contextByName_resolvesCorrectTask() {
        // writer depends on researcher via $researcher
        List<Map<String, Object>> taskDefs = List.of(
                Map.of(
                        "name", "researcher",
                        "description", "Research AI trends",
                        "expectedOutput", "Research findings"),
                Map.of(
                        "name", "writer",
                        "description", "Write executive brief",
                        "expectedOutput", "Executive brief",
                        "context", List.of("$researcher")));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null);

        assertThat(config.overrideTasks()).hasSize(2);
        Task researcher = config.overrideTasks().get(0);
        Task writer = config.overrideTasks().get(1);

        assertThat(writer.getContext()).hasSize(1);
        assertThat(writer.getContext().get(0)).isSameAs(researcher);
    }

    @Test
    void buildFromDynamicTasks_contextByIndex_resolvesCorrectTask() {
        // writer depends on researcher via $0 (index 0)
        List<Map<String, Object>> taskDefs = List.of(
                Map.of(
                        "name", "researcher",
                        "description", "Research AI trends",
                        "expectedOutput", "Research findings"),
                Map.of(
                        "name", "writer",
                        "description", "Write report",
                        "expectedOutput", "Report",
                        "context", List.of("$0")));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null);

        Task researcher = config.overrideTasks().get(0);
        Task writer = config.overrideTasks().get(1);

        assertThat(writer.getContext()).hasSize(1);
        assertThat(writer.getContext().get(0)).isSameAs(researcher);
    }

    @Test
    void buildFromDynamicTasks_circularDependency_throws() {
        // A depends on B, B depends on A -- circular
        List<Map<String, Object>> taskDefs = List.of(
                Map.of(
                        "name", "taskA",
                        "description", "Task A",
                        "expectedOutput", "Output A",
                        "context", List.of("$taskB")),
                Map.of(
                        "name", "taskB",
                        "description", "Task B",
                        "expectedOutput", "Output B",
                        "context", List.of("$taskA")));

        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("circular");
    }

    @Test
    void buildFromDynamicTasks_unknownContextName_throws() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "writer",
                "description", "Write report",
                "expectedOutput", "Report",
                "context", List.of("$nonexistent")));

        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void buildFromDynamicTasks_outOfBoundsContextIndex_throws() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "writer",
                "description", "Write report",
                "expectedOutput", "Report",
                "context", List.of("$99")));

        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$99");
    }

    @Test
    void buildFromDynamicTasks_unknownTool_throws() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "researcher",
                "description", "Research AI",
                "expectedOutput", "Report",
                "tools", List.of("nonexistent_tool")));

        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent_tool");
    }

    @Test
    void buildFromDynamicTasks_unknownModel_throws() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "researcher",
                "description", "Research AI",
                "expectedOutput", "Report",
                "model", "unknown-model"));

        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-model");
    }

    @Test
    void buildFromDynamicTasks_outputSchema_injectedIntoExpectedOutput() {
        // outputSchema as a map representing JSON Schema
        Map<String, Object> schema =
                Map.of("type", "object", "properties", Map.of("competitors", Map.of("type", "array")));

        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "researcher",
                "description", "Research competitors",
                "expectedOutput", "A structured analysis",
                "outputSchema", schema));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null);

        Task task = config.overrideTasks().get(0);
        // outputSchema should be reflected in the expectedOutput (schema injected)
        assertThat(task.getExpectedOutput()).contains("A structured analysis");
        assertThat(task.getExpectedOutput()).contains("competitors");
    }

    @Test
    void buildFromDynamicTasks_missingDescription_throws() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "researcher",
                "expectedOutput", "A report"
                // no "description"
                ));

        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void buildFromDynamicTasks_preservesInputsAndOptions() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "researcher",
                "description", "Research {topic}",
                "expectedOutput", "Report"));
        RunOptions options = RunOptions.builder().maxToolOutputLength(5000).build();

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of("topic", "AI"), options);

        assertThat(config.inputs()).containsEntry("topic", "AI");
        assertThat(config.options()).isSameAs(options);
    }

    @Test
    void buildFromDynamicTasks_multipleTasksNoContext_allBuilt() {
        List<Map<String, Object>> taskDefs = List.of(
                Map.of("name", "task1", "description", "Do task 1", "expectedOutput", "Output 1"),
                Map.of("name", "task2", "description", "Do task 2", "expectedOutput", "Output 2"),
                Map.of("name", "task3", "description", "Do task 3", "expectedOutput", "Output 3"));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null);

        assertThat(config.overrideTasks()).hasSize(3);
        assertThat(config.overrideTasks().get(0).getName()).isEqualTo("task1");
        assertThat(config.overrideTasks().get(1).getName()).isEqualTo("task2");
        assertThat(config.overrideTasks().get(2).getName()).isEqualTo("task3");
    }

    @Test
    void buildFromDynamicTasks_taskWithAdditionalContext_appendedToDescription() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "researcher",
                "description", "Research AI",
                "expectedOutput", "Report",
                "additionalContext", "Focus on EU regulation."));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null);

        Task task = config.overrideTasks().get(0);
        assertThat(task.getDescription()).startsWith("Research AI");
        assertThat(task.getDescription()).contains("Focus on EU regulation.");
    }

    @Test
    void buildFromDynamicTasks_taskWithoutExpectedOutput_usesDefault() {
        // When expectedOutput is omitted, Task.DEFAULT_EXPECTED_OUTPUT is used
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "researcher",
                "description", "Research AI"));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null);

        assertThat(config.overrideTasks().get(0).getExpectedOutput())
                .isEqualTo(net.agentensemble.Task.DEFAULT_EXPECTED_OUTPUT);
    }

    // ========================
    // Level 2: maxIterations with numeric types
    // ========================

    @Test
    void buildFromTemplateWithOverrides_overrideMaxIterations_fromLongValue() {
        // Ensure toInt() handles Long correctly (occurs when JSON integer is large)
        when(template.getTasks()).thenReturn(List.of(namedTask));

        // We pass the value via the override map using Long.valueOf to simulate what
        // might arrive from custom deserialization paths
        java.util.Map<String, Object> overrideFields = new java.util.LinkedHashMap<>();
        overrideFields.put("maxIterations", Long.valueOf(25));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("researcher", overrideFields));

        assertThat(config.overrideTasks().get(0).getMaxIterations()).isEqualTo(25);
    }

    // ========================
    // Level 2: unknown override fields silently ignored
    // ========================

    @Test
    void buildFromTemplateWithOverrides_unknownField_silentlyIgnored() {
        when(template.getTasks()).thenReturn(List.of(namedTask));

        // "unknownField" is not a recognized override key; it should be ignored
        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("researcher", Map.of("unknownField", "some-value")));

        // Task is unchanged except for the known fields; no exception thrown
        assertThat(config.overrideTasks().get(0).getDescription()).isEqualTo(namedTask.getDescription());
    }

    // ========================
    // Opportunistic coverage: RunOptions parsing via null options
    // ========================

    @Test
    void buildFromDynamicTasks_nullOptions_usesDefault() {
        List<Map<String, Object>> taskDefs = List.of(Map.of(
                "name", "t1",
                "description", "Do something",
                "expectedOutput", "Output"));

        RunRequestParser.RunConfiguration config =
                parserWithCatalogs.buildFromDynamicTasks(template, taskDefs, Map.of(), null);

        assertThat(config.options()).isSameAs(net.agentensemble.execution.RunOptions.DEFAULT);
    }

    // ========================
    // Level 3: type validation for expectedOutput field
    // ========================

    @Test
    void buildFromDynamicTasks_invalidExpectedOutputType_throwsIllegalArgumentException() {
        // expectedOutput must be a String; passing an integer should throw with a useful message
        java.util.Map<String, Object> def = new java.util.LinkedHashMap<>();
        def.put("description", "Some task");
        def.put("expectedOutput", 42); // integer, not a string

        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, List.of(def), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedOutput")
                .hasMessageContaining("non-null string");
    }

    @Test
    void buildFromDynamicTasks_nullExpectedOutputValue_throwsIllegalArgumentException() {
        java.util.Map<String, Object> def = new java.util.LinkedHashMap<>();
        def.put("description", "Some task");
        def.put("expectedOutput", null);

        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, List.of(def), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedOutput");
    }

    // ========================
    // Level 3: type validation for tool names
    // ========================

    @Test
    void buildFromDynamicTasks_invalidToolNameType_throwsIllegalArgumentException() {
        // Tool names must be non-blank strings; passing an integer should throw
        java.util.Map<String, Object> def = new java.util.LinkedHashMap<>();
        def.put("description", "Some task");
        def.put("tools", java.util.List.of(123)); // integer, not a string

        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, List.of(def), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tool names must be non-blank strings");
    }

    // ========================
    // Level 3: type validation for context field
    // ========================

    @Test
    void buildFromDynamicTasks_invalidContextType_throwsIllegalArgumentException() {
        // 'context' must be an array; passing a string should throw
        java.util.Map<String, Object> def = new java.util.LinkedHashMap<>();
        def.put("description", "Some task");
        def.put("context", "not-an-array");

        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, List.of(def), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context")
                .hasMessageContaining("array");
    }

    @Test
    void buildFromDynamicTasks_contextWithNonStringEntry_throwsIllegalArgumentException() {
        // Context entries must be strings ($name or $N); passing an integer should throw
        java.util.Map<String, Object> def = new java.util.LinkedHashMap<>();
        def.put("description", "Some task");
        def.put("context", java.util.List.of(42)); // integer, not a string

        assertThatThrownBy(() -> parserWithCatalogs.buildFromDynamicTasks(template, List.of(def), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context")
                .hasMessageContaining("strings");
    }

    // ========================
    // Level 2: additionalContext + description deterministic ordering
    // ========================

    @Test
    void buildFromTemplateWithOverrides_descriptionAndAdditionalContext_descriptionFirst() {
        // When both 'description' and 'additionalContext' are provided, the result must be:
        // [overridden description] + newline + [additionalContext]
        // This must hold regardless of Map iteration order.
        when(template.getTasks()).thenReturn(List.of(namedTask));

        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("description", "New description");
        fields.put("additionalContext", "Extra context");

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("researcher", fields));

        String desc = config.overrideTasks().get(0).getDescription();
        assertThat(desc).startsWith("New description");
        assertThat(desc).endsWith("Extra context");
        assertThat(desc).contains("\n\n");
    }

    @Test
    void buildFromTemplateWithOverrides_additionalContextOnly_appendsToOriginal() {
        // When only 'additionalContext' is provided (no 'description' override), the result
        // must be [original description] + newline + [additionalContext].
        when(template.getTasks()).thenReturn(List.of(namedTask));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("researcher", Map.of("additionalContext", "Appended context")));

        String desc = config.overrideTasks().get(0).getDescription();
        assertThat(desc).startsWith(namedTask.getDescription());
        assertThat(desc).endsWith("Appended context");
    }

    // ========================
    // Level 2: tool removal by name (not reference equality)
    // ========================

    @Test
    void buildFromTemplateWithOverrides_removeToolByName_removesFromTaskToolList() {
        // The tool on the task and the catalog tool share the same name but are different
        // instances. Name-based removal must succeed.
        net.agentensemble.tool.AgentTool catalogTool =
                toolCatalog.find("calculator").orElseThrow();
        // Build a task whose tools list contains a DIFFERENT instance with the same name,
        // simulating a tool that was constructed outside the catalog.
        net.agentensemble.tool.AgentTool externalInstance = new net.agentensemble.tool.AgentTool() {
            @Override
            public String name() {
                return "calculator";
            }

            @Override
            public String description() {
                return "A calculator (external)";
            }

            @Override
            public net.agentensemble.tool.ToolResult execute(String input) {
                return net.agentensemble.tool.ToolResult.success("0");
            }
        };

        net.agentensemble.Task taskWithTool = net.agentensemble.Task.builder()
                .name("researcher")
                .description("Research task")
                .expectedOutput("Output")
                .tools(java.util.List.of(externalInstance))
                .build();
        when(template.getTasks()).thenReturn(java.util.List.of(taskWithTool));

        java.util.Map<String, Object> toolsOverride = new java.util.LinkedHashMap<>();
        toolsOverride.put("remove", java.util.List.of("calculator"));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("researcher", Map.of("tools", toolsOverride)));

        // Tool was removed even though the instance was different from the catalog instance
        assertThat(config.overrideTasks().get(0).getTools()).isEmpty();
    }

    @Test
    void buildFromTemplateWithOverrides_addToolDeduplicated_doesNotAddDuplicate() {
        // Adding a tool that is already present (by name) must not result in duplicates.
        net.agentensemble.tool.AgentTool calcTool =
                toolCatalog.find("calculator").orElseThrow();

        net.agentensemble.Task taskWithTool = net.agentensemble.Task.builder()
                .name("researcher")
                .description("Research task")
                .expectedOutput("Output")
                .tools(java.util.List.of(calcTool))
                .build();
        when(template.getTasks()).thenReturn(java.util.List.of(taskWithTool));

        java.util.Map<String, Object> toolsOverride = new java.util.LinkedHashMap<>();
        toolsOverride.put("add", java.util.List.of("calculator"));

        RunRequestParser.RunConfiguration config = parserWithCatalogs.buildFromTemplateWithOverrides(
                template, Map.of(), null, Map.of("researcher", Map.of("tools", toolsOverride)));

        // Should still have exactly one tool (no duplicate added)
        assertThat(config.overrideTasks().get(0).getTools()).hasSize(1);
    }
}
