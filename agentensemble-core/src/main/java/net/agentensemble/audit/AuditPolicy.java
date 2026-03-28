package net.agentensemble.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * Configurable audit policy with rules for dynamic level escalation.
 */
public final class AuditPolicy {
    private final AuditLevel defaultLevel;
    private final List<AuditRule> rules;

    private AuditPolicy(AuditLevel defaultLevel, List<AuditRule> rules) {
        this.defaultLevel = defaultLevel;
        this.rules = List.copyOf(rules);
    }

    public AuditLevel defaultLevel() {
        return defaultLevel;
    }

    public List<AuditRule> rules() {
        return rules;
    }

    public AuditLevel effectiveLevel(String ensembleId, String lastEvent) {
        AuditLevel level = defaultLevel;
        for (AuditRule rule : rules) {
            if (rule.appliesTo(ensembleId) && rule.matches(lastEvent)) {
                if (rule.targetLevel().ordinal() > level.ordinal()) {
                    level = rule.targetLevel();
                }
            }
        }
        return level;
    }

    public static AuditPolicyBuilder builder() {
        return new AuditPolicyBuilder();
    }

    public static final class AuditPolicyBuilder {
        private AuditLevel defaultLevel = AuditLevel.OFF;
        private final List<AuditRule> rules = new ArrayList<>();

        public AuditPolicyBuilder defaultLevel(AuditLevel level) {
            this.defaultLevel = level;
            return this;
        }

        public AuditPolicyBuilder rule(AuditRule rule) {
            rules.add(rule);
            return this;
        }

        public AuditPolicy build() {
            return new AuditPolicy(defaultLevel, rules);
        }
    }
}
