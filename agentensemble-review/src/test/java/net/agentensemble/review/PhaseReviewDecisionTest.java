package net.agentensemble.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PhaseReviewDecision}: factories, toText(), parse(), and record validation.
 */
class PhaseReviewDecisionTest {

    // ========================
    // Static factories
    // ========================

    @Test
    void approve_returnsApproveInstance() {
        assertThat(PhaseReviewDecision.approve()).isInstanceOf(PhaseReviewDecision.Approve.class);
    }

    @Test
    void approve_returnsNewInstanceEachCall() {
        // After removing the static singleton, each call returns a fresh Approve instance.
        // Identity comparison must not be used to test equality; value equality is sufficient.
        PhaseReviewDecision a1 = PhaseReviewDecision.approve();
        PhaseReviewDecision a2 = PhaseReviewDecision.approve();
        assertThat(a1).isNotSameAs(a2);
        assertThat(a1).isEqualTo(a2); // record equality
    }

    @Test
    void approve_concurrentAccess_neverDeadlocks() throws Exception {
        // Regression test for ClassInitializationDeadlock: concurrent access to
        // approve() and parse() must complete without deadlock.
        int threads = 20;
        var latch = new java.util.concurrent.CountDownLatch(threads);
        var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        if (idx % 2 == 0) {
                            PhaseReviewDecision.approve();
                        } else {
                            PhaseReviewDecision.parse("APPROVE");
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(errors).isEmpty();
    }

    @Test
    void retry_createsRetryWithFeedback() {
        PhaseReviewDecision.Retry decision = PhaseReviewDecision.retry("Need more depth");
        assertThat(decision.feedback()).isEqualTo("Need more depth");
    }

    @Test
    void retry_nullFeedback_throwsNPE() {
        assertThatNullPointerException()
                .isThrownBy(() -> PhaseReviewDecision.retry(null))
                .withMessageContaining("feedback must not be null");
    }

    @Test
    void retryPredecessor_createsPredecessorRetryWithNameAndFeedback() {
        PhaseReviewDecision.RetryPredecessor decision =
                PhaseReviewDecision.retryPredecessor("research", "Expand on topic X");
        assertThat(decision.phaseName()).isEqualTo("research");
        assertThat(decision.feedback()).isEqualTo("Expand on topic X");
    }

    @Test
    void retryPredecessor_nullPhaseName_throwsNPE() {
        assertThatNullPointerException()
                .isThrownBy(() -> PhaseReviewDecision.retryPredecessor(null, "feedback"))
                .withMessageContaining("phaseName must not be null");
    }

    @Test
    void retryPredecessor_nullFeedback_throwsNPE() {
        assertThatNullPointerException()
                .isThrownBy(() -> PhaseReviewDecision.retryPredecessor("research", null))
                .withMessageContaining("feedback must not be null");
    }

    @Test
    void reject_createsRejectWithReason() {
        PhaseReviewDecision.Reject decision = PhaseReviewDecision.reject("Fundamentally flawed");
        assertThat(decision.reason()).isEqualTo("Fundamentally flawed");
    }

    @Test
    void reject_nullReason_throwsNPE() {
        assertThatNullPointerException()
                .isThrownBy(() -> PhaseReviewDecision.reject(null))
                .withMessageContaining("reason must not be null");
    }

    // ========================
    // toText()
    // ========================

    @Test
    void toText_approve_returnsAPPROVE() {
        assertThat(PhaseReviewDecision.approve().toText()).isEqualTo("APPROVE");
    }

    @Test
    void toText_retry_prefixesFeedbackWithRETRY() {
        assertThat(PhaseReviewDecision.retry("Need more sources").toText()).isEqualTo("RETRY: Need more sources");
    }

    @Test
    void toText_retryPredecessor_formatsWithPhaseNameAndFeedback() {
        assertThat(PhaseReviewDecision.retryPredecessor("research", "More depth")
                        .toText())
                .isEqualTo("RETRY_PREDECESSOR research: More depth");
    }

    @Test
    void toText_reject_prefixesReasonWithREJECT() {
        assertThat(PhaseReviewDecision.reject("Unusable output").toText()).isEqualTo("REJECT: Unusable output");
    }

    // ========================
    // parse() -- happy paths
    // ========================

    @Test
    void parse_APPROVE_returnsApprove() {
        assertThat(PhaseReviewDecision.parse("APPROVE")).isInstanceOf(PhaseReviewDecision.Approve.class);
    }

    @Test
    void parse_approve_caseInsensitive() {
        assertThat(PhaseReviewDecision.parse("approve")).isInstanceOf(PhaseReviewDecision.Approve.class);
        assertThat(PhaseReviewDecision.parse("Approve")).isInstanceOf(PhaseReviewDecision.Approve.class);
    }

    @Test
    void parse_RETRY_withFeedback() {
        PhaseReviewDecision result = PhaseReviewDecision.parse("RETRY: need more depth");
        assertThat(result).isInstanceOf(PhaseReviewDecision.Retry.class);
        assertThat(((PhaseReviewDecision.Retry) result).feedback()).isEqualTo("need more depth");
    }

    @Test
    void parse_retry_caseInsensitive() {
        PhaseReviewDecision result = PhaseReviewDecision.parse("retry: fix the grammar");
        assertThat(result).isInstanceOf(PhaseReviewDecision.Retry.class);
        assertThat(((PhaseReviewDecision.Retry) result).feedback()).isEqualTo("fix the grammar");
    }

    @Test
    void parse_RETRY_withoutColon_returnsRetryWithEmptyFeedback() {
        PhaseReviewDecision result = PhaseReviewDecision.parse("RETRY");
        assertThat(result).isInstanceOf(PhaseReviewDecision.Retry.class);
        assertThat(((PhaseReviewDecision.Retry) result).feedback()).isEqualTo("");
    }

    @Test
    void parse_RETRY_PREDECESSOR_withPhaseNameAndFeedback() {
        PhaseReviewDecision result = PhaseReviewDecision.parse("RETRY_PREDECESSOR research: need more sources");
        assertThat(result).isInstanceOf(PhaseReviewDecision.RetryPredecessor.class);
        PhaseReviewDecision.RetryPredecessor rp = (PhaseReviewDecision.RetryPredecessor) result;
        assertThat(rp.phaseName()).isEqualTo("research");
        assertThat(rp.feedback()).isEqualTo("need more sources");
    }

    @Test
    void parse_RETRY_PREDECESSOR_withoutColon_treatsRestAsPhaseName() {
        PhaseReviewDecision result = PhaseReviewDecision.parse("RETRY_PREDECESSOR research");
        assertThat(result).isInstanceOf(PhaseReviewDecision.RetryPredecessor.class);
        PhaseReviewDecision.RetryPredecessor rp = (PhaseReviewDecision.RetryPredecessor) result;
        assertThat(rp.phaseName()).isEqualTo("research");
        assertThat(rp.feedback()).isEqualTo("");
    }

    @Test
    void parse_REJECT_withReason() {
        PhaseReviewDecision result = PhaseReviewDecision.parse("REJECT: data is corrupted");
        assertThat(result).isInstanceOf(PhaseReviewDecision.Reject.class);
        assertThat(((PhaseReviewDecision.Reject) result).reason()).isEqualTo("data is corrupted");
    }

    @Test
    void parse_REJECT_withoutColon_returnsRejectWithEmptyReason() {
        PhaseReviewDecision result = PhaseReviewDecision.parse("REJECT");
        assertThat(result).isInstanceOf(PhaseReviewDecision.Reject.class);
        assertThat(((PhaseReviewDecision.Reject) result).reason()).isEqualTo("");
    }

    // ========================
    // parse() -- edge cases
    // ========================

    @Test
    void parse_null_returnsApprove() {
        assertThat(PhaseReviewDecision.parse(null)).isInstanceOf(PhaseReviewDecision.Approve.class);
    }

    @Test
    void parse_blank_returnsApprove() {
        assertThat(PhaseReviewDecision.parse("   ")).isInstanceOf(PhaseReviewDecision.Approve.class);
    }

    @Test
    void parse_empty_returnsApprove() {
        assertThat(PhaseReviewDecision.parse("")).isInstanceOf(PhaseReviewDecision.Approve.class);
    }

    @Test
    void parse_unrecognisedText_returnsApprove() {
        assertThat(PhaseReviewDecision.parse("looks good to me")).isInstanceOf(PhaseReviewDecision.Approve.class);
    }

    @Test
    void parse_leadingAndTrailingWhitespace_isTrimmed() {
        PhaseReviewDecision result = PhaseReviewDecision.parse("  RETRY: fix indentation  ");
        assertThat(result).isInstanceOf(PhaseReviewDecision.Retry.class);
        assertThat(((PhaseReviewDecision.Retry) result).feedback()).isEqualTo("fix indentation");
    }

    @Test
    void parse_feedbackWithColons_preservesAllColons() {
        // The colon split is only on the FIRST colon, so feedback can contain more colons.
        PhaseReviewDecision result = PhaseReviewDecision.parse("RETRY: issue: too short");
        assertThat(result).isInstanceOf(PhaseReviewDecision.Retry.class);
        assertThat(((PhaseReviewDecision.Retry) result).feedback()).isEqualTo("issue: too short");
    }

    // ========================
    // Round-trip: toText() -> parse()
    // ========================

    @Test
    void roundTrip_approve() {
        PhaseReviewDecision original = PhaseReviewDecision.approve();
        PhaseReviewDecision parsed = PhaseReviewDecision.parse(original.toText());
        assertThat(parsed).isInstanceOf(PhaseReviewDecision.Approve.class);
    }

    @Test
    void roundTrip_retry() {
        PhaseReviewDecision original = PhaseReviewDecision.retry("Expand section 3");
        PhaseReviewDecision parsed = PhaseReviewDecision.parse(original.toText());
        assertThat(parsed).isInstanceOf(PhaseReviewDecision.Retry.class);
        assertThat(((PhaseReviewDecision.Retry) parsed).feedback()).isEqualTo("Expand section 3");
    }

    @Test
    void roundTrip_retryPredecessor() {
        PhaseReviewDecision original = PhaseReviewDecision.retryPredecessor("research", "Add citations");
        PhaseReviewDecision parsed = PhaseReviewDecision.parse(original.toText());
        assertThat(parsed).isInstanceOf(PhaseReviewDecision.RetryPredecessor.class);
        PhaseReviewDecision.RetryPredecessor rp = (PhaseReviewDecision.RetryPredecessor) parsed;
        assertThat(rp.phaseName()).isEqualTo("research");
        assertThat(rp.feedback()).isEqualTo("Add citations");
    }

    @Test
    void roundTrip_reject() {
        PhaseReviewDecision original = PhaseReviewDecision.reject("Invalid data");
        PhaseReviewDecision parsed = PhaseReviewDecision.parse(original.toText());
        assertThat(parsed).isInstanceOf(PhaseReviewDecision.Reject.class);
        assertThat(((PhaseReviewDecision.Reject) parsed).reason()).isEqualTo("Invalid data");
    }
}
