package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Phase;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for deterministic-only ensembles -- ensembles where every task has a
 * {@link net.agentensemble.task.TaskHandler} and no {@link dev.langchain4j.model.chat.ChatModel}
 * is required anywhere.
 *
 * <p>These tests prove that AgentEnsemble works as a general-purpose task orchestrator for
 * non-AI pipelines: ETL, API chaining, data transformation, multi-step workflows.
 */
class DeterministicOnlyEnsembleIntegrationTest {

    // ========================
    // Sequential: basic pipeline
    // ========================

    @Test
    void sequential_allHandlerTasks_noModel_runs() {
        AtomicBoolean step1Ran = new AtomicBoolean(false);
        AtomicBoolean step2Ran = new AtomicBoolean(false);

        Task step1 = Task.builder()
                .description("Fetch raw data")
                .expectedOutput("Raw data string")
                .handler(ctx -> {
                    step1Ran.set(true);
                    return ToolResult.success("raw-data");
                })
                .build();

        Task step2 = Task.builder()
                .description("Process raw data")
                .expectedOutput("Processed data")
                .handler(ctx -> {
                    step2Ran.set(true);
                    return ToolResult.success("processed-data");
                })
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(step1)
                .task(step2)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();

        assertThat(step1Ran).isTrue();
        assertThat(step2Ran).isTrue();
        assertThat(output.getRaw()).isEqualTo("processed-data");
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    // ========================
    // Data passing: output of one task feeds into the next
    // ========================

    @Test
    void sequential_contextPassingBetweenHandlers_downstreamTaskReceivesPriorOutput() {
        Task fetchTask = Task.builder()
                .description("Fetch product data from API")
                .expectedOutput("JSON product data")
                .handler(ctx -> ToolResult.success("{\"name\":\"Widget\",\"price\":9.99}"))
                .build();

        Task transformTask = Task.builder()
                .description("Transform product data into display format")
                .expectedOutput("Formatted product line")
                .context(List.of(fetchTask))
                .handler(ctx -> {
                    assertThat(ctx.contextOutputs()).hasSize(1);
                    String apiData = ctx.contextOutputs().get(0).getRaw();
                    assertThat(apiData).isEqualTo("{\"name\":\"Widget\",\"price\":9.99}");
                    return ToolResult.success("Product: Widget @ $9.99");
                })
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(fetchTask)
                .task(transformTask)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Product: Widget @ $9.99");
        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs().get(0).getRaw()).isEqualTo("{\"name\":\"Widget\",\"price\":9.99}");
        assertThat(output.getTaskOutputs().get(1).getRaw()).isEqualTo("Product: Widget @ $9.99");
    }

    @Test
    void sequential_threeStepPipeline_eachStepSeesAllPriorOutputs() {
        Task step1 = Task.builder()
                .description("Step 1: fetch")
                .expectedOutput("Fetched value")
                .handler(ctx -> ToolResult.success("fetched"))
                .build();

        Task step2 = Task.builder()
                .description("Step 2: parse")
                .expectedOutput("Parsed value")
                .context(List.of(step1))
                .handler(ctx -> {
                    String prior = ctx.contextOutputs().get(0).getRaw();
                    return ToolResult.success("parsed:" + prior);
                })
                .build();

        Task step3 = Task.builder()
                .description("Step 3: format")
                .expectedOutput("Final output")
                .context(List.of(step2))
                .handler(ctx -> {
                    String prior = ctx.contextOutputs().get(0).getRaw();
                    return ToolResult.success("final:" + prior);
                })
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(step1)
                .task(step2)
                .task(step3)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("final:parsed:fetched");
    }

    // ========================
    // Parallel: independent deterministic tasks
    // ========================

    @Test
    void parallel_allHandlerTasks_noModel_runsAllTasks() {
        AtomicBoolean taskARan = new AtomicBoolean(false);
        AtomicBoolean taskBRan = new AtomicBoolean(false);

        Task taskA = Task.builder()
                .description("Fetch from service A")
                .expectedOutput("Service A data")
                .handler(ctx -> {
                    taskARan.set(true);
                    return ToolResult.success("data-from-A");
                })
                .build();

        Task taskB = Task.builder()
                .description("Fetch from service B")
                .expectedOutput("Service B data")
                .handler(ctx -> {
                    taskBRan.set(true);
                    return ToolResult.success("data-from-B");
                })
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(taskA)
                .task(taskB)
                .workflow(Workflow.PARALLEL)
                .build()
                .run();

        assertThat(taskARan).isTrue();
        assertThat(taskBRan).isTrue();
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    @Test
    void parallel_withContextDependency_inferred_downstreamReceivesPriorOutput() {
        Task upstream = Task.builder()
                .description("Compute base value")
                .expectedOutput("Base value")
                .handler(ctx -> ToolResult.success("base-42"))
                .build();

        Task downstream = Task.builder()
                .description("Apply transformation to base value")
                .expectedOutput("Transformed value")
                .context(List.of(upstream))
                .handler(ctx -> {
                    String base = ctx.contextOutputs().get(0).getRaw();
                    return ToolResult.success("transformed:" + base);
                })
                .build();

        // Context dependency between ensemble tasks -> workflow inferred as PARALLEL
        EnsembleOutput output =
                Ensemble.builder().task(upstream).task(downstream).build().run();

        assertThat(output.getRaw()).isEqualTo("transformed:base-42");
    }

    // ========================
    // Phase-based: deterministic workstreams
    // ========================

    @Test
    void phases_allHandlerTasks_noModel_runsAllPhases() {
        AtomicBoolean phase1Ran = new AtomicBoolean(false);
        AtomicBoolean phase2Ran = new AtomicBoolean(false);

        Task ingestTask = Task.builder()
                .description("Ingest data from source")
                .expectedOutput("Raw ingested data")
                .handler(ctx -> {
                    phase1Ran.set(true);
                    return ToolResult.success("ingested-data");
                })
                .build();

        Phase ingestPhase = Phase.of("ingest", ingestTask);

        Task processTask = Task.builder()
                .description("Process ingested data")
                .expectedOutput("Processed result")
                .handler(ctx -> {
                    phase2Ran.set(true);
                    return ToolResult.success("processed-result");
                })
                .build();

        Phase processPhase = Phase.builder()
                .name("process")
                .task(processTask)
                .after(ingestPhase)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .phase(ingestPhase)
                .phase(processPhase)
                .build()
                .run();

        assertThat(phase1Ran).isTrue();
        assertThat(phase2Ran).isTrue();
        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getPhaseOutputs()).containsKey("ingest");
        assertThat(output.getPhaseOutputs()).containsKey("process");
    }

    @Test
    void phases_crossPhaseContextPassing_downstreamPhaseReceivesPriorPhaseOutput() {
        Task fetchTask = Task.builder()
                .description("Fetch configuration from remote")
                .expectedOutput("Config JSON")
                .handler(ctx -> ToolResult.success("{\"env\":\"production\"}"))
                .build();

        Phase fetchPhase = Phase.of("fetch-config", fetchTask);

        Task deployTask = Task.builder()
                .description("Deploy using fetched configuration")
                .expectedOutput("Deployment result")
                .context(List.of(fetchTask))
                .handler(ctx -> {
                    assertThat(ctx.contextOutputs()).hasSize(1);
                    String config = ctx.contextOutputs().get(0).getRaw();
                    assertThat(config).isEqualTo("{\"env\":\"production\"}");
                    return ToolResult.success("deployed-to-production");
                })
                .build();

        Phase deployPhase = Phase.builder()
                .name("deploy")
                .task(deployTask)
                .after(fetchPhase)
                .build();

        EnsembleOutput output =
                Ensemble.builder().phase(fetchPhase).phase(deployPhase).build().run();

        assertThat(output.getRaw()).isEqualTo("deployed-to-production");
        assertThat(output.getPhaseOutputs().get("fetch-config")).hasSize(1);
        assertThat(output.getPhaseOutputs().get("deploy")).hasSize(1);
    }

    // ========================
    // Ensemble.run(Task...) static factory -- no-model overload
    // ========================

    @Test
    void runFactory_deterministicTasks_noModelRequired() {
        Task step1 = Task.builder()
                .description("Compute hash")
                .expectedOutput("Hash value")
                .handler(ctx -> ToolResult.success("abc123"))
                .build();

        Task step2 = Task.builder()
                .description("Store hash")
                .expectedOutput("Storage confirmation")
                .handler(ctx -> ToolResult.success("stored"))
                .build();

        EnsembleOutput output = Ensemble.run(step1, step2);

        assertThat(output.getRaw()).isEqualTo("stored");
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    @Test
    void runFactory_singleDeterministicTask_succeeds() {
        Task task = Task.builder()
                .description("Transform input to uppercase")
                .expectedOutput("Uppercase string")
                .handler(ctx -> ToolResult.success("HELLO WORLD"))
                .build();

        EnsembleOutput output = Ensemble.run(task);

        assertThat(output.getRaw()).isEqualTo("HELLO WORLD");
    }

    @Test
    void runFactory_taskWithoutHandler_throwsIllegalArgumentException() {
        Task nonHandlerTask = Task.builder()
                .description("This task has no handler and no LLM")
                .expectedOutput("Some output")
                .build();

        assertThatThrownBy(() -> Ensemble.run(nonHandlerTask))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handler")
                .hasMessageContaining("chatLanguageModel");
    }

    @Test
    void runFactory_nullTasks_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> Ensemble.run((Task[]) null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runFactory_emptyTasks_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> Ensemble.run(new Task[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runFactory_nullTaskElement_throwsIllegalArgumentException() {
        Task validTask = Task.builder()
                .description("Valid handler task")
                .expectedOutput("Output")
                .handler(ctx -> ToolResult.success("done"))
                .build();

        assertThatThrownBy(() -> Ensemble.run(validTask, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tasks[1]");
    }

    // ========================
    // Callbacks fire on deterministic-only ensembles
    // ========================

    @Test
    void sequential_callbacks_fireForHandlerTasks() {
        AtomicBoolean taskStartFired = new AtomicBoolean(false);
        AtomicBoolean taskCompleteFired = new AtomicBoolean(false);

        Task task = Task.builder()
                .description("Callback test task")
                .expectedOutput("Output")
                .handler(ctx -> ToolResult.success("done"))
                .build();

        Ensemble.builder()
                .task(task)
                .workflow(Workflow.SEQUENTIAL)
                .onTaskStart(e -> taskStartFired.set(true))
                .onTaskComplete(e -> taskCompleteFired.set(true))
                .build()
                .run();

        assertThat(taskStartFired).isTrue();
        assertThat(taskCompleteFired).isTrue();
    }

    // ========================
    // Handler returning failure propagates as exception
    // ========================

    @Test
    void sequential_handlerReturnsFailure_throwsTaskExecutionException() {
        Task failingTask = Task.builder()
                .description("Task that fails")
                .expectedOutput("Should not complete")
                .handler(ctx -> ToolResult.failure("Something went wrong"))
                .build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .task(failingTask)
                        .workflow(Workflow.SEQUENTIAL)
                        .build()
                        .run())
                .isInstanceOf(net.agentensemble.exception.TaskExecutionException.class);
    }

    // ========================
    // Validation: mixed tasks (handler + non-handler) without a model fails cleanly
    // ========================

    @Test
    void validation_handlerTaskMixedWithNonHandlerTaskAndNoModel_throwsValidation() {
        Task handlerTask = Task.builder()
                .description("Handler task")
                .expectedOutput("Output A")
                .handler(ctx -> ToolResult.success("done"))
                .build();

        Task nonHandlerTask = Task.builder()
                .description("Non-handler task without any LLM source")
                .expectedOutput("Output B")
                .build(); // no handler, no agent, no LLM

        assertThatThrownBy(() -> Ensemble.builder()
                        .task(handlerTask)
                        .task(nonHandlerTask)
                        .workflow(Workflow.SEQUENTIAL)
                        .build()
                        .run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("LLM");
    }
}
