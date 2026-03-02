package net.agentensemble.memory;

import net.agentensemble.exception.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class EnsembleMemoryTest {

    // ========================
    // shortTerm only
    // ========================

    @Test
    void testBuilder_shortTermOnly_builds() {
        EnsembleMemory memory = EnsembleMemory.builder()
                .shortTerm(true)
                .build();

        assertThat(memory.isShortTerm()).isTrue();
        assertThat(memory.getLongTerm()).isNull();
        assertThat(memory.getEntityMemory()).isNull();
    }

    // ========================
    // longTerm only
    // ========================

    @Test
    void testBuilder_longTermOnly_builds() {
        LongTermMemory ltm = mock(LongTermMemory.class);

        EnsembleMemory memory = EnsembleMemory.builder()
                .longTerm(ltm)
                .build();

        assertThat(memory.getLongTerm()).isSameAs(ltm);
        assertThat(memory.isShortTerm()).isFalse();
    }

    // ========================
    // entityMemory only
    // ========================

    @Test
    void testBuilder_entityMemoryOnly_builds() {
        EntityMemory em = new InMemoryEntityMemory();

        EnsembleMemory memory = EnsembleMemory.builder()
                .entityMemory(em)
                .build();

        assertThat(memory.getEntityMemory()).isSameAs(em);
    }

    // ========================
    // all three types
    // ========================

    @Test
    void testBuilder_allThreeTypes_builds() {
        LongTermMemory ltm = mock(LongTermMemory.class);
        EntityMemory em = new InMemoryEntityMemory();

        EnsembleMemory memory = EnsembleMemory.builder()
                .shortTerm(true)
                .longTerm(ltm)
                .entityMemory(em)
                .build();

        assertThat(memory.isShortTerm()).isTrue();
        assertThat(memory.getLongTerm()).isSameAs(ltm);
        assertThat(memory.getEntityMemory()).isSameAs(em);
    }

    // ========================
    // defaults
    // ========================

    @Test
    void testBuilder_defaults_shortTermFalseAndMaxResults5() {
        LongTermMemory ltm = mock(LongTermMemory.class);

        EnsembleMemory memory = EnsembleMemory.builder()
                .longTerm(ltm)
                .build();

        assertThat(memory.isShortTerm()).isFalse();
        assertThat(memory.getLongTermMaxResults()).isEqualTo(5);
    }

    @Test
    void testBuilder_customMaxResults_setsCorrectly() {
        LongTermMemory ltm = mock(LongTermMemory.class);

        EnsembleMemory memory = EnsembleMemory.builder()
                .longTerm(ltm)
                .longTermMaxResults(10)
                .build();

        assertThat(memory.getLongTermMaxResults()).isEqualTo(10);
    }

    // ========================
    // Validation
    // ========================

    @Test
    void testBuilder_noMemoryTypeEnabled_throwsValidationException() {
        assertThatThrownBy(() -> EnsembleMemory.builder().build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one memory type");
    }

    @Test
    void testBuilder_shortTermFalseAndNullLtmAndNullEntity_throwsValidationException() {
        assertThatThrownBy(() -> EnsembleMemory.builder()
                .shortTerm(false)
                .build())
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void testBuilder_longTermMaxResultsZero_throwsValidationException() {
        LongTermMemory ltm = mock(LongTermMemory.class);

        assertThatThrownBy(() -> EnsembleMemory.builder()
                .longTerm(ltm)
                .longTermMaxResults(0)
                .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("longTermMaxResults");
    }

    @Test
    void testBuilder_longTermMaxResultsNegative_throwsValidationException() {
        LongTermMemory ltm = mock(LongTermMemory.class);

        assertThatThrownBy(() -> EnsembleMemory.builder()
                .longTerm(ltm)
                .longTermMaxResults(-1)
                .build())
                .isInstanceOf(ValidationException.class);
    }
}
