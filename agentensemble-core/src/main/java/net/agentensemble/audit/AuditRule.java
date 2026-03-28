package net.agentensemble.audit;

import java.time.Duration;
import java.util.List;

/**
 * A rule that escalates audit level based on conditions.
 */
public final class AuditRule {
    private final String condition;
    private final AuditRuleType ruleType;
    private final AuditLevel targetLevel;
    private final List<String> targetEnsembles;
    private final Duration duration;

    private AuditRule(
            String condition,
            AuditRuleType ruleType,
            AuditLevel targetLevel,
            List<String> targetEnsembles,
            Duration duration) {
        this.condition = condition;
        this.ruleType = ruleType;
        this.targetLevel = targetLevel;
        this.targetEnsembles = targetEnsembles != null ? List.copyOf(targetEnsembles) : List.of("*");
        this.duration = duration;
    }

    public String condition() {
        return condition;
    }

    public AuditRuleType ruleType() {
        return ruleType;
    }

    public AuditLevel targetLevel() {
        return targetLevel;
    }

    public List<String> targetEnsembles() {
        return targetEnsembles;
    }

    public Duration duration() {
        return duration;
    }

    public boolean appliesTo(String ensembleId) {
        return targetEnsembles.contains("*") || targetEnsembles.contains(ensembleId);
    }

    public boolean matches(String event) {
        return condition != null && condition.equals(event);
    }

    public static AuditRuleBuilder when(String condition) {
        return new AuditRuleBuilder(condition, AuditRuleType.EVENT);
    }

    public static AuditRuleBuilder schedule(String cronExpression) {
        return new AuditRuleBuilder(cronExpression, AuditRuleType.SCHEDULE);
    }

    public enum AuditRuleType {
        METRIC,
        EVENT,
        SCHEDULE,
        HUMAN_TRIGGERED
    }

    public static final class AuditRuleBuilder {
        private final String condition;
        private final AuditRuleType ruleType;
        private AuditLevel targetLevel;
        private List<String> targetEnsembles;
        private Duration duration;

        AuditRuleBuilder(String condition, AuditRuleType ruleType) {
            this.condition = condition;
            this.ruleType = ruleType;
        }

        public AuditRuleBuilder escalateTo(AuditLevel level) {
            this.targetLevel = level;
            return this;
        }

        public AuditRuleBuilder on(String... ensembles) {
            this.targetEnsembles = List.of(ensembles);
            return this;
        }

        public AuditRuleBuilder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public AuditRule build() {
            if (targetLevel == null) throw new IllegalStateException("targetLevel must be set via escalateTo()");
            return new AuditRule(condition, ruleType, targetLevel, targetEnsembles, duration);
        }
    }
}
