package net.agentensemble.trace.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.trace.CapturedMessage;
import net.agentensemble.trace.LlmInteraction;
import net.agentensemble.trace.LlmResponseType;
import net.agentensemble.trace.TaskTrace;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TaskTraceAccumulator verifying CaptureMode-gated behavior:
 * - OFF: messages list is always empty
 * - STANDARD+: messages list is populated when setCurrentMessages() is called
 * - getCaptureMode() returns the constructor-supplied mode
 */
class TaskTraceAccumulatorCaptureModeTest {

    private static final Instant STARTED_AT = Instant.parse("2026-03-05T09:00:00Z");

    private List<CapturedMessage> sampleMessages() {
        return CapturedMessage.fromAll(
                List.of(new SystemMessage("You are a researcher."), UserMessage.from("Research AI frameworks.")));
    }

    // ========================
    // Default constructor (OFF)
    // ========================

    @Test
    void defaultConstructor_captureModeIsOff() {
        TaskTraceAccumulator acc = new TaskTraceAccumulator("Agent", "Task", "Expected", STARTED_AT);
        assertThat(acc.getCaptureMode()).isEqualTo(CaptureMode.OFF);
    }

    // ========================
    // CaptureMode.OFF -- messages never captured
    // ========================

    @Test
    void off_setCurrentMessages_isIgnored_messagesEmptyInTrace() {
        TaskTraceAccumulator acc = new TaskTraceAccumulator("Agent", "Task", "Expected", STARTED_AT, CaptureMode.OFF);

        acc.beginLlmCall(STARTED_AT);
        acc.endLlmCall(STARTED_AT.plusSeconds(1), null);
        acc.setCurrentMessages(sampleMessages()); // should be ignored
        acc.finalizeIteration(LlmResponseType.FINAL_ANSWER, "answer");

        TaskTrace trace = acc.buildTrace("answer", null, STARTED_AT.plusSeconds(2), null);
        LlmInteraction interaction = trace.getLlmInteractions().get(0);

        assertThat(interaction.getMessages()).isEmpty();
    }

    // ========================
    // CaptureMode.STANDARD -- messages captured
    // ========================

    @Test
    void standard_setCurrentMessages_messagesIncludedInTrace() {
        TaskTraceAccumulator acc =
                new TaskTraceAccumulator("Agent", "Task", "Expected", STARTED_AT, CaptureMode.STANDARD);

        acc.beginLlmCall(STARTED_AT);
        acc.endLlmCall(STARTED_AT.plusSeconds(1), null);
        acc.setCurrentMessages(sampleMessages());
        acc.finalizeIteration(LlmResponseType.FINAL_ANSWER, "answer");

        TaskTrace trace = acc.buildTrace("answer", null, STARTED_AT.plusSeconds(2), null);
        LlmInteraction interaction = trace.getLlmInteractions().get(0);

        assertThat(interaction.getMessages()).hasSize(2);
        assertThat(interaction.getMessages().get(0).getRole()).isEqualTo("system");
        assertThat(interaction.getMessages().get(1).getRole()).isEqualTo("user");
    }

    @Test
    void standard_messagesConsumedAfterFinalizeIteration_nextIterationStartsClean() {
        TaskTraceAccumulator acc =
                new TaskTraceAccumulator("Agent", "Task", "Expected", STARTED_AT, CaptureMode.STANDARD);

        // First iteration with messages
        acc.beginLlmCall(STARTED_AT);
        acc.endLlmCall(STARTED_AT.plusSeconds(1), null);
        acc.setCurrentMessages(sampleMessages());
        acc.finalizeIteration(LlmResponseType.TOOL_CALLS, null);

        // Second iteration without setting messages
        acc.beginLlmCall(STARTED_AT.plusSeconds(2));
        acc.endLlmCall(STARTED_AT.plusSeconds(3), null);
        // Note: no setCurrentMessages() call for this iteration
        acc.finalizeIteration(LlmResponseType.FINAL_ANSWER, "answer");

        TaskTrace trace = acc.buildTrace("answer", null, STARTED_AT.plusSeconds(4), null);

        assertThat(trace.getLlmInteractions().get(0).getMessages()).hasSize(2);
        assertThat(trace.getLlmInteractions().get(1).getMessages()).isEmpty();
    }

    @Test
    void standard_setCurrentMessages_withNullMessages_isIgnored() {
        TaskTraceAccumulator acc =
                new TaskTraceAccumulator("Agent", "Task", "Expected", STARTED_AT, CaptureMode.STANDARD);

        acc.beginLlmCall(STARTED_AT);
        acc.endLlmCall(STARTED_AT.plusSeconds(1), null);
        acc.setCurrentMessages(null);
        acc.finalizeIteration(LlmResponseType.FINAL_ANSWER, "answer");

        TaskTrace trace = acc.buildTrace("answer", null, STARTED_AT.plusSeconds(2), null);

        assertThat(trace.getLlmInteractions().get(0).getMessages()).isEmpty();
    }

    @Test
    void standard_setCurrentMessages_withEmptyList_isIgnored() {
        TaskTraceAccumulator acc =
                new TaskTraceAccumulator("Agent", "Task", "Expected", STARTED_AT, CaptureMode.STANDARD);

        acc.beginLlmCall(STARTED_AT);
        acc.endLlmCall(STARTED_AT.plusSeconds(1), null);
        acc.setCurrentMessages(List.of());
        acc.finalizeIteration(LlmResponseType.FINAL_ANSWER, "answer");

        TaskTrace trace = acc.buildTrace("answer", null, STARTED_AT.plusSeconds(2), null);

        assertThat(trace.getLlmInteractions().get(0).getMessages()).isEmpty();
    }

    // ========================
    // CaptureMode.FULL -- also captures messages (superset of STANDARD)
    // ========================

    @Test
    void full_setCurrentMessages_messagesIncludedInTrace() {
        TaskTraceAccumulator acc = new TaskTraceAccumulator("Agent", "Task", "Expected", STARTED_AT, CaptureMode.FULL);

        acc.beginLlmCall(STARTED_AT);
        acc.endLlmCall(STARTED_AT.plusSeconds(1), null);
        acc.setCurrentMessages(sampleMessages());
        acc.finalizeIteration(LlmResponseType.FINAL_ANSWER, "answer");

        TaskTrace trace = acc.buildTrace("answer", null, STARTED_AT.plusSeconds(2), null);

        assertThat(trace.getLlmInteractions().get(0).getMessages()).hasSize(2);
    }

    // ========================
    // Memory operation counters (independent of CaptureMode -- always available)
    // ========================

    @Test
    void memoryCounters_incrementCorrectly_inMetrics() {
        TaskTraceAccumulator acc =
                new TaskTraceAccumulator("Agent", "Task", "Expected", STARTED_AT, CaptureMode.STANDARD);

        acc.incrementStmWrite();
        acc.incrementStmWrite();
        acc.incrementLtmStore();
        acc.incrementLtmRetrieval(Duration.ofMillis(10));
        acc.incrementLtmRetrieval(Duration.ofMillis(20));
        acc.incrementEntityLookup(Duration.ofMillis(5));

        var metrics = acc.buildMetrics(null);
        var memOps = metrics.getMemoryOperations();

        assertThat(memOps.getShortTermEntriesWritten()).isEqualTo(2);
        assertThat(memOps.getLongTermStores()).isEqualTo(1);
        assertThat(memOps.getLongTermRetrievals()).isEqualTo(2);
        assertThat(memOps.getEntityLookups()).isEqualTo(1);
        assertThat(metrics.getMemoryRetrievalTime()).isEqualTo(Duration.ofMillis(35)); // 10 + 20 + 5
    }
}
