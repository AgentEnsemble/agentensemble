package net.agentensemble.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class GuardrailResultTest {

    @Test
    void success_isSuccess() {
        GuardrailResult result = GuardrailResult.success();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void success_messageIsEmpty() {
        GuardrailResult result = GuardrailResult.success();
        assertThat(result.getMessage()).isEmpty();
    }

    @Test
    void failure_isNotSuccess() {
        GuardrailResult result = GuardrailResult.failure("blocked: contains PII");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void failure_carriesMessage() {
        GuardrailResult result = GuardrailResult.failure("blocked: contains PII");
        assertThat(result.getMessage()).isEqualTo("blocked: contains PII");
    }

    @Test
    void failure_withNullMessage_throws() {
        assertThatNullPointerException().isThrownBy(() -> GuardrailResult.failure(null));
    }

    @Test
    void distinctSuccessInstances_areEqual() {
        GuardrailResult a = GuardrailResult.success();
        GuardrailResult b = GuardrailResult.success();
        assertThat(a.isSuccess()).isEqualTo(b.isSuccess());
        assertThat(a.getMessage()).isEqualTo(b.getMessage());
    }
}
